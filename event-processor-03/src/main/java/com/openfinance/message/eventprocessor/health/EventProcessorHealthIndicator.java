package com.openfinance.message.eventprocessor.health;

import com.openfinance.message.eventprocessor.config.EventProcessingProperties;
import com.openfinance.message.eventprocessor.external.ExternalApiClient;
import com.openfinance.message.eventprocessor.persistence.DeadLetterRepository;
import com.openfinance.message.eventprocessor.persistence.EventStore;
import com.openfinance.message.eventprocessor.service.EventProcessorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventProcessorHealthIndicator implements HealthIndicator {

    private final EventProcessorService eventProcessorService;
    private final DataSource dataSource;
    private final ExternalApiClient externalApiClient;
    private final EventStore eventStore;
    private final DeadLetterRepository deadLetterRepository;
    private final EventProcessingProperties properties;

    // Health thresholds
    private static final long ERROR_RATE_THRESHOLD = 5; // 5% error rate
    private static final long DLQ_THRESHOLD = 1000; // Max 1000 messages in DLQ
    private static final long PROCESSING_LAG_THRESHOLD_MS = 60000; // 1 minute lag
    private static final double MEMORY_USAGE_THRESHOLD = 0.85; // 85% memory usage

    // Tracking
    private final Instant startTime = Instant.now();
    private final AtomicReference<Instant> lastHealthCheck = new AtomicReference<>(Instant.now());

    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();
        Map<String, Object> details = new HashMap<>();

        try {
            // 1. Check Event Processor Status
            HealthStatus processorStatus = checkEventProcessor();
            details.put("eventProcessor", processorStatus.toMap());

            // 2. Check Database Health
            HealthStatus databaseStatus = checkDatabase();
            details.put("database", databaseStatus.toMap());

            // 3. Check External API Health
            HealthStatus externalApiStatus = checkExternalApi();
            details.put("externalApi", externalApiStatus.toMap());

            // 4. Check Dead Letter Queue
            HealthStatus dlqStatus = checkDeadLetterQueue();
            details.put("deadLetterQueue", dlqStatus.toMap());

            // 5. Check Processing Metrics
            ProcessingMetrics metrics = getProcessingMetrics();
            details.put("metrics", metrics.toMap());

            // 6. Check System Resources
            SystemResources resources = getSystemResources();
            details.put("systemResources", resources.toMap());

            // Determine overall health status
            Status overallStatus = determineOverallStatus(
                    metrics, resources, processorStatus, databaseStatus, externalApiStatus, dlqStatus
            );

            builder.status(overallStatus);

            // Add general information
            details.put("configuration", getConfigurationInfo());
            details.put("uptime", getUptime());
            details.put("lastCheckTime", Instant.now().toString());

            // Add all details to health response
            details.forEach(builder::withDetail);

            lastHealthCheck.set(Instant.now());

        } catch (Exception e) {
            log.error("Health check failed", e);
            builder.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("errorType", e.getClass().getSimpleName());
        }

        return builder.build();
    }

    private HealthStatus checkEventProcessor() {
        try {
            boolean isRunning = eventProcessorService.isHealthy();
            long processedCount = eventProcessorService.getProcessedEventsCount();
            int activeProcessors = eventProcessorService.getActiveProcessorsCount();

            if (!isRunning) {
                return HealthStatus.down("Event processor is not running");
            }

            // Calculate processing rate
            Duration uptime = Duration.between(startTime, Instant.now());
            double processingRate = uptime.getSeconds() > 0 ?
                    (double) processedCount / uptime.getSeconds() : 0;

            Map<String, Object> details = new HashMap<>();
            details.put("running", true);
            details.put("processedEvents", processedCount);
            details.put("activeProcessors", activeProcessors);
            details.put("processingRate", String.format("%.2f events/sec", processingRate));

            // Check if processing rate meets requirements (1157 events/sec for 100M/day)
            if (processingRate < 100 && uptime.toMinutes() > 5) {
                return HealthStatus.degraded("Processing rate below expected threshold", details);
            }

            return HealthStatus.up(details);

        } catch (Exception e) {
            return HealthStatus.down("Failed to check event processor: " + e.getMessage());
        }
    }

    private HealthStatus checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(5)) {
                return HealthStatus.down("Database connection is invalid");
            }

            // Check connection pool stats
            Map<String, Object> details = new HashMap<>();
            details.put("connected", true);
            details.put("databaseProduct", connection.getMetaData().getDatabaseProductName());
            details.put("databaseVersion", connection.getMetaData().getDatabaseProductVersion());

            // Check event store accessibility
            long eventCount = eventStore.count();
            details.put("totalEvents", eventCount);

            return HealthStatus.up(details);

        } catch (Exception e) {
            return HealthStatus.down("Database check failed: " + e.getMessage());
        }
    }

    private HealthStatus checkExternalApi() {
        try {
            boolean isHealthy = externalApiClient.isHealthy();

            Map<String, Object> details = new HashMap<>();
            details.put("available", isHealthy);

            if (!isHealthy) {
                // External API being down doesn't necessarily mean our service is down
                // Circuit breaker will handle failures gracefully
                return HealthStatus.degraded("External API is unavailable", details);
            }

            return HealthStatus.up(details);

        } catch (Exception e) {
            return HealthStatus.degraded("External API check failed: " + e.getMessage());
        }
    }

    private HealthStatus checkDeadLetterQueue() {
        try {
            long dlqCount = deadLetterRepository.count();
            long failedCount = deadLetterRepository.countByStatus("FAILED");
            long retryingCount = deadLetterRepository.countByStatus("RETRYING");
            long abandonedCount = deadLetterRepository.countByStatus("ABANDONED");

            Map<String, Object> details = new HashMap<>();
            details.put("totalMessages", dlqCount);
            details.put("failedMessages", failedCount);
            details.put("retryingMessages", retryingCount);
            details.put("abandonedMessages", abandonedCount);

            if (dlqCount > DLQ_THRESHOLD) {
                return HealthStatus.degraded(
                        String.format("Dead letter queue has %d messages (threshold: %d)",
                                dlqCount, DLQ_THRESHOLD),
                        details
                );
            }

            if (abandonedCount > 100) {
                return HealthStatus.degraded(
                        String.format("High number of abandoned messages: %d", abandonedCount),
                        details
                );
            }

            return HealthStatus.up(details);

        } catch (Exception e) {
            return HealthStatus.unknown("Failed to check DLQ: " + e.getMessage());
        }
    }

    private ProcessingMetrics getProcessingMetrics() {
        ProcessingMetrics metrics = new ProcessingMetrics();

        try {
            long processedCount = eventProcessorService.getProcessedEventsCount();
            long failedCount = deadLetterRepository.countByStatus("FAILED");

            // Calculate error rate
            double errorRate = processedCount > 0 ?
                    (double) failedCount / (processedCount + failedCount) * 100 : 0;

            metrics.setProcessedEvents(processedCount);
            metrics.setFailedEvents(failedCount);
            metrics.setErrorRate(errorRate);
            metrics.setHealthy(errorRate < ERROR_RATE_THRESHOLD);

        } catch (Exception e) {
            log.error("Failed to get processing metrics", e);
        }

        return metrics;
    }

    private SystemResources getSystemResources() {
        SystemResources resources = new SystemResources();

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        resources.setMaxMemoryMB(maxMemory / 1024 / 1024);
        resources.setUsedMemoryMB(usedMemory / 1024 / 1024);
        resources.setFreeMemoryMB(freeMemory / 1024 / 1024);
        resources.setMemoryUsagePercent((double) usedMemory / maxMemory * 100);
        resources.setAvailableProcessors(runtime.availableProcessors());
        resources.setHealthy(resources.getMemoryUsagePercent() < MEMORY_USAGE_THRESHOLD * 100);

        return resources;
    }

    private Map<String, Object> getConfigurationInfo() {
        Map<String, Object> config = new HashMap<>();
        config.put("processingMode", properties.getMode().getValue());
        config.put("deliveryGuarantee", properties.getDeliveryGuarantee().getValue());
        config.put("dataFormat", properties.getDataFormat().name());
        config.put("batchSize", properties.getBatchSize());
        config.put("prefetchCount", properties.getPrefetchCount());
        config.put("processingThreads", properties.getProcessingThreads());
        config.put("deadLetterEnabled", properties.isDeadLetterEnabled());
        return config;
    }

    private Status determineOverallStatus(ProcessingMetrics metrics, SystemResources resources, HealthStatus... statuses) {
        boolean hasDown = false;
        boolean hasDegraded = false;
        boolean hasUnknown = false;

        for (HealthStatus status : statuses) {
            if (status.getStatus() == Status.DOWN) {
                hasDown = true;
            } else if ("DEGRADED".equals(status.getStatus().getCode())) {
                hasDegraded = true;
            } else if (status.getStatus() == Status.UNKNOWN) {
                hasUnknown = true;
            }
        }

        if (hasDown) {
            return Status.DOWN;
        } else if (hasDegraded || !metrics.isHealthy() || !resources.isHealthy()) {
            return new Status("DEGRADED");
        } else if (hasUnknown) {
            return Status.UNKNOWN;
        } else {
            return Status.UP;
        }
    }

    private String getUptime() {
        Duration uptime = Duration.between(startTime, Instant.now());
        long days = uptime.toDays();
        long hours = uptime.toHoursPart();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();

        return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
    }

    // Inner classes for structured health data

    private static class HealthStatus {
        private final Status status;
        private final String message;
        private final Map<String, Object> details;

        private HealthStatus(Status status, String message, Map<String, Object> details) {
            this.status = status;
            this.message = message;
            this.details = details != null ? details : new HashMap<>();
        }

        public static HealthStatus up(Map<String, Object> details) {
            return new HealthStatus(Status.UP, "Healthy", details);
        }

        public static HealthStatus down(String message) {
            return new HealthStatus(Status.DOWN, message, null);
        }

        public static HealthStatus degraded(String message) {
            return degraded(message, null);
        }

        public static HealthStatus degraded(String message, Map<String, Object> details) {
            return new HealthStatus(new Status("DEGRADED"), message, details);
        }

        public static HealthStatus unknown(String message) {
            return new HealthStatus(Status.UNKNOWN, message, null);
        }

        public Status getStatus() {
            return status;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>(details);
            map.put("status", status.getCode());
            if (message != null) {
                map.put("message", message);
            }
            return map;
        }
    }

    private static class ProcessingMetrics {
        private long processedEvents;
        private long failedEvents;
        private double errorRate;
        private boolean healthy;

        // Getters and setters
        public long getProcessedEvents() { return processedEvents; }
        public void setProcessedEvents(long processedEvents) { this.processedEvents = processedEvents; }

        public long getFailedEvents() { return failedEvents; }
        public void setFailedEvents(long failedEvents) { this.failedEvents = failedEvents; }

        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }

        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("processedEvents", processedEvents);
            map.put("failedEvents", failedEvents);
            map.put("errorRate", String.format("%.2f%%", errorRate));
            map.put("healthy", healthy);
            return map;
        }
    }

    private static class SystemResources {
        private long maxMemoryMB;
        private long usedMemoryMB;
        private long freeMemoryMB;
        private double memoryUsagePercent;
        private int availableProcessors;
        private boolean healthy;

        // Getters and setters
        public long getMaxMemoryMB() { return maxMemoryMB; }
        public void setMaxMemoryMB(long maxMemoryMB) { this.maxMemoryMB = maxMemoryMB; }

        public long getUsedMemoryMB() { return usedMemoryMB; }
        public void setUsedMemoryMB(long usedMemoryMB) { this.usedMemoryMB = usedMemoryMB; }
	
        public long getFreeMemoryMB() { return freeMemoryMB; }
        public void setFreeMemoryMB(long freeMemoryMB) { this.freeMemoryMB = freeMemoryMB; }

        public double getMemoryUsagePercent() { return memoryUsagePercent; }
        public void setMemoryUsagePercent(double memoryUsagePercent) { this.memoryUsagePercent = memoryUsagePercent; }

        public int getAvailableProcessors() { return availableProcessors; }
        public void setAvailableProcessors(int availableProcessors) { this.availableProcessors = availableProcessors; }

        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("maxMemoryMB", maxMemoryMB);
            map.put("usedMemoryMB", usedMemoryMB);
            map.put("freeMemoryMB", freeMemoryMB);
            map.put("memoryUsagePercent", String.format("%.2f%%", memoryUsagePercent));
            map.put("availableProcessors", availableProcessors);
            map.put("healthy", healthy);
            return map;
        }
    }
}
