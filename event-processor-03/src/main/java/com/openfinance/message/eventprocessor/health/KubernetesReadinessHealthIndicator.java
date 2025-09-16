package com.openfinance.message.eventprocessor.health;

import com.openfinance.message.eventprocessor.external.ExternalApiClient;
import com.openfinance.message.eventprocessor.service.EventProcessorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kubernetes Readiness Health Indicator
 *
 * This health check determines if the pod is ready to receive traffic.
 * It will return DOWN during:
 * - Application warm-up period
 * - Database connection issues
 * - Event processor not running
 * - No events processed after grace period
 *
 * The readiness probe should be configured with:
 * - initialDelaySeconds: 30
 * - periodSeconds: 10
 * - failureThreshold: 3
 */
@Component("readiness")
@RequiredArgsConstructor
@Slf4j
public class KubernetesReadinessHealthIndicator implements HealthIndicator {

    private final EventProcessorService eventProcessorService;
    private final ApplicationAvailability applicationAvailability;
    private final DataSource dataSource;
    private final ExternalApiClient externalApiClient;

    // Configuration constants
    private static final long WARM_UP_TIME_SECONDS = 30;
    private static final long GRACE_PERIOD_SECONDS = 60;
    private static final long MIN_EVENTS_THRESHOLD = 10;
    private static final int DB_CONNECTION_TIMEOUT_SECONDS = 5;

    // Tracking variables
    private final Instant startupTime = Instant.now();
    private final AtomicBoolean hasProcessedEvents = new AtomicBoolean(false);
    private final AtomicLong firstEventProcessedTime = new AtomicLong(0);
    private volatile boolean warmUpComplete = false;

    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();
        Map<String, Object> details = new HashMap<>();

        try {
            // 1. Check application readiness state
            ReadinessCheck appReadiness = checkApplicationReadiness();
            details.put("applicationState", appReadiness.toMap());
            if (!appReadiness.isReady()) {
                return builder.down()
                        .withDetails(details)
                        .withDetail("reason", appReadiness.getReason())
                        .build();
            }

            // 2. Check warm-up period
            WarmUpCheck warmUpCheck = checkWarmUpPeriod();
            details.put("warmUp", warmUpCheck.toMap());
            if (!warmUpCheck.isComplete()) {
                return builder.down()
                        .withDetails(details)
                        .withDetail("reason", "Application warming up")
                        .build();
            }

            // 3. Check critical dependencies
            DependencyCheck dependencyCheck = checkCriticalDependencies();
            details.put("dependencies", dependencyCheck.toMap());
            if (!dependencyCheck.allHealthy()) {
                return builder.down()
                        .withDetails(details)
                        .withDetail("reason", "Critical dependencies not ready")
                        .build();
            }

            // 4. Check event processing capability
            ProcessingCheck processingCheck = checkEventProcessingCapability();
            details.put("processing", processingCheck.toMap());
            if (!processingCheck.isReady()) {
                return builder.down()
                        .withDetails(details)
                        .withDetail("reason", processingCheck.getReason())
                        .build();
            }

            // 5. Perform final readiness assessment
            boolean isReady = performFinalReadinessCheck(details);

            if (isReady) {
                return builder.up()
                        .withDetails(details)
                        .withDetail("ready", true)
                        .withDetail("readySince", getReadyDuration())
                        .build();
            } else {
                return builder.down()
                        .withDetails(details)
                        .withDetail("ready", false)
                        .build();
            }

        } catch (Exception e) {
            log.error("Readiness check failed with exception", e);
            return builder.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("errorType", e.getClass().getSimpleName())
                    .build();
        }
    }

    private ReadinessCheck checkApplicationReadiness() {
        ReadinessState state = applicationAvailability.getReadinessState();
        boolean isReady = state == ReadinessState.ACCEPTING_TRAFFIC;

        return new ReadinessCheck(
                isReady,
                state.name(),
                isReady ? null : "Application not accepting traffic"
        );
    }

    private WarmUpCheck checkWarmUpPeriod() {
        Duration uptime = Duration.between(startupTime, Instant.now());
        long uptimeSeconds = uptime.getSeconds();

        if (!warmUpComplete && uptimeSeconds >= WARM_UP_TIME_SECONDS) {
            warmUpComplete = true;
            log.info("Application warm-up period complete after {} seconds", uptimeSeconds);
        }

        long remainingSeconds = Math.max(0, WARM_UP_TIME_SECONDS - uptimeSeconds);

        return new WarmUpCheck(
                warmUpComplete,
                uptimeSeconds,
                remainingSeconds
        );
    }

    private DependencyCheck checkCriticalDependencies() {
        DependencyCheck check = new DependencyCheck();

        // Check database
        check.setDatabaseReady(checkDatabaseConnection());

        // Check Event Hub (via processor service)
        check.setEventHubReady(checkEventHubConnection());

        // Check external API (non-critical, but log status)
        check.setExternalApiReady(checkExternalApiConnection());

        return check;
    }

    private boolean checkDatabaseConnection() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_CONNECTION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("Database connection check failed", e);
            return false;
        }
    }

    private boolean checkEventHubConnection() {
        try {
            return eventProcessorService.isHealthy();
        } catch (Exception e) {
            log.warn("Event Hub connection check failed", e);
            return false;
        }
    }

    private boolean checkExternalApiConnection() {
        try {
            // External API is not critical for readiness
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() ->
                    externalApiClient.isHealthy()
            );
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("External API check failed or timed out", e);
            return false; // Non-critical, so we continue
        }
    }

    private ProcessingCheck checkEventProcessingCapability() {
        ProcessingCheck check = new ProcessingCheck();

        // Check if processor is running
        boolean isRunning = eventProcessorService.isHealthy();
        check.setProcessorRunning(isRunning);

        if (!isRunning) {
            check.setReady(false);
            check.setReason("Event processor not running");
            return check;
        }

        // Get processing stats
        long processedCount = eventProcessorService.getProcessedEventsCount();
        int activeProcessors = eventProcessorService.getActiveProcessorsCount();

        check.setEventsProcessed(processedCount);
        check.setActiveProcessors(activeProcessors);

        // Track first event processed
        if (processedCount > 0 && !hasProcessedEvents.get()) {
            hasProcessedEvents.set(true);
            firstEventProcessedTime.set(System.currentTimeMillis());
            log.info("First event processed after startup");
        }

        // Check if we're in grace period
        Duration uptime = Duration.between(startupTime, Instant.now());
        boolean inGracePeriod = uptime.getSeconds() <= GRACE_PERIOD_SECONDS;

        // After grace period, we should have processed some events
        if (!inGracePeriod && processedCount < MIN_EVENTS_THRESHOLD) {
            check.setReady(false);
            check.setReason(String.format(
                    "Insufficient events processed (%d) after %d seconds",
                    processedCount,
                    uptime.getSeconds()
            ));
            return check;
        }

        check.setReady(true);
        return check;
    }

    private boolean performFinalReadinessCheck(Map<String, Object> details) {
        // Add additional runtime metrics
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryUsage = (double) usedMemory / maxMemory * 100;

        Map<String, Object> runtimeMetrics = new HashMap<>();
        runtimeMetrics.put("memoryUsagePercent", String.format("%.2f%%", memoryUsage));
        runtimeMetrics.put("availableProcessors", runtime.availableProcessors());
        runtimeMetrics.put("totalMemoryMB", totalMemory / 1024 / 1024);
        runtimeMetrics.put("freeMemoryMB", freeMemory / 1024 / 1024);

        details.put("runtime", runtimeMetrics);

        // Check if memory usage is critical (>95%)
        if (memoryUsage > 95) {
            details.put("warning", "High memory usage detected");
            // Still return ready, but log warning
            log.warn("High memory usage detected: {}%", String.format("%.2f", memoryUsage));
        }

        return true;
    }

    private String getReadyDuration() {
        if (!hasProcessedEvents.get()) {
            return "Not yet ready";
        }

        long readyTimeMillis = firstEventProcessedTime.get();
        if (readyTimeMillis == 0) {
            return "Just became ready";
        }

        Duration readyDuration = Duration.ofMillis(System.currentTimeMillis() - readyTimeMillis);
        long hours = readyDuration.toHours();
        long minutes = readyDuration.toMinutesPart();
        long seconds = readyDuration.toSecondsPart();

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    // Inner classes for structured health data

    private static class ReadinessCheck {
        private final boolean ready;
        private final String state;
        private final String reason;

        public ReadinessCheck(boolean ready, String state, String reason) {
            this.ready = ready;
            this.state = state;
            this.reason = reason;
        }

        public boolean isReady() {
            return ready;
        }

        public String getReason() {
            return reason;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("ready", ready);
            map.put("state", state);
            if (reason != null) {
                map.put("reason", reason);
            }
            return map;
        }
    }

    private static class WarmUpCheck {
        private final boolean complete;
        private final long uptimeSeconds;
        private final long remainingSeconds;

        public WarmUpCheck(boolean complete, long uptimeSeconds, long remainingSeconds) {
            this.complete = complete;
            this.uptimeSeconds = uptimeSeconds;
            this.remainingSeconds = remainingSeconds;
        }

        public boolean isComplete() {
            return complete;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("complete", complete);
            map.put("uptimeSeconds", uptimeSeconds);
            if (!complete) {
                map.put("remainingSeconds", remainingSeconds);
            }
            return map;
        }
    }

    private static class DependencyCheck {
        private boolean databaseReady;
        private boolean eventHubReady;
        private boolean externalApiReady;

        public boolean allHealthy() {
            // External API is not critical for readiness
            return databaseReady && eventHubReady;
        }

        public void setDatabaseReady(boolean databaseReady) {
            this.databaseReady = databaseReady;
        }

        public void setEventHubReady(boolean eventHubReady) {
            this.eventHubReady = eventHubReady;
        }

        public void setExternalApiReady(boolean externalApiReady) {
            this.externalApiReady = externalApiReady;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("database", databaseReady ? "UP" : "DOWN");
            map.put("eventHub", eventHubReady ? "UP" : "DOWN");
            map.put("externalApi", externalApiReady ? "UP" : "DOWN (non-critical)");
            map.put("critical", allHealthy());
            return map;
        }
    }

    private static class ProcessingCheck {
        private boolean ready = false;
        private boolean processorRunning;
        private long eventsProcessed;
        private int activeProcessors;
        private String reason;

        public boolean isReady() {
            return ready;
        }

        public String getReason() {
            return reason;
        }

        public void setReady(boolean ready) {
            this.ready = ready;
        }

        public void setProcessorRunning(boolean processorRunning) {
            this.processorRunning = processorRunning;
        }

        public void setEventsProcessed(long eventsProcessed) {
            this.eventsProcessed = eventsProcessed;
        }

        public void setActiveProcessors(int activeProcessors) {
            this.activeProcessors = activeProcessors;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("ready", ready);
            map.put("processorRunning", processorRunning);
            map.put("eventsProcessed", eventsProcessed);
            map.put("activeProcessors", activeProcessors);
            if (reason != null) {
                map.put("reason", reason);
            }
            return map;
        }
    }
}