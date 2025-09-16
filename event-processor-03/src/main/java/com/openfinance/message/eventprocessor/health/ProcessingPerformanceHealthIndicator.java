package com.openfinance.message.eventprocessor.health;

import com.openfinance.message.eventprocessor.service.EventProcessorService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component("processingPerformance")
@RequiredArgsConstructor
@Slf4j
public class ProcessingPerformanceHealthIndicator implements HealthIndicator {

    private final MeterRegistry meterRegistry;
    private final EventProcessorService eventProcessorService;

    // Performance thresholds
    private static final double P95_LATENCY_THRESHOLD_MS = 1000; // 1 second
    private static final double P99_LATENCY_THRESHOLD_MS = 5000; // 5 seconds
    private static final double THROUGHPUT_THRESHOLD = 1000; // events/sec minimum

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();

        try {
            // Get processing timer metrics
            Timer processingTimer = meterRegistry.find("event.processing.duration")
                    .timer();

            if (processingTimer != null) {
                double p50 = processingTimer.percentile(0.5, TimeUnit.MILLISECONDS);
                double p95 = processingTimer.percentile(0.95, TimeUnit.MILLISECONDS);
                double p99 = processingTimer.percentile(0.99, TimeUnit.MILLISECONDS);
                double mean = processingTimer.mean(TimeUnit.MILLISECONDS);
                double max = processingTimer.max(TimeUnit.MILLISECONDS);
                long count = processingTimer.count();

                Map<String, Object> latencyDetails = new HashMap<>();
                latencyDetails.put("p50", String.format("%.2f ms", p50));
                latencyDetails.put("p95", String.format("%.2f ms", p95));
                latencyDetails.put("p99", String.format("%.2f ms", p99));
                latencyDetails.put("mean", String.format("%.2f ms", mean));
                latencyDetails.put("max", String.format("%.2f ms", max));
                latencyDetails.put("totalProcessed", count);

                details.put("latency", latencyDetails);

                // Calculate throughput
                double throughput = processingTimer.count() /
                        Math.max(1, Duration.ofNanos((long) processingTimer.totalTime(TimeUnit.NANOSECONDS)).getSeconds());
                details.put("throughput", String.format("%.2f events/sec", throughput));

                // Check performance thresholds
                if (p95 > P95_LATENCY_THRESHOLD_MS || p99 > P99_LATENCY_THRESHOLD_MS) {
                    return Health.status("DEGRADED")
                            .withDetail("message", "Latency exceeds acceptable thresholds")
                            .withDetails(details)
                            .build();
                }

                if (throughput < THROUGHPUT_THRESHOLD && count > 1000) {
                    return Health.status("DEGRADED")
                            .withDetail("message", "Throughput below minimum threshold")
                            .withDetails(details)
                            .build();
                }
            }

            // Get serialization metrics
            Map<String, Object> serializationMetrics = getSerializationMetrics();
            if (!serializationMetrics.isEmpty()) {
                details.put("serialization", serializationMetrics);
            }

            return Health.up().withDetails(details).build();

        } catch (Exception e) {
            log.error("Performance health check failed", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    private Map<String, Object> getSerializationMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        try {
            // JSON metrics
            Timer jsonTimer = meterRegistry.find("serialization.json.duration").timer();
            if (jsonTimer != null) {
                metrics.put("json", formatSerializationMetric(jsonTimer));
            }

            // Avro metrics
            Timer avroTimer = meterRegistry.find("serialization.avro.duration").timer();
            if (avroTimer != null) {
                metrics.put("avro", formatSerializationMetric(avroTimer));
            }

            // Protobuf metrics
            Timer protobufTimer = meterRegistry.find("serialization.protobuf.duration").timer();
            if (protobufTimer != null) {
                metrics.put("protobuf", formatSerializationMetric(protobufTimer));
            }

        } catch (Exception e) {
            log.debug("Failed to get serialization metrics", e);
        }

        return metrics;
    }

    private Map<String, Object> formatSerializationMetric(Timer timer) {
        Map<String, Object> metric = new HashMap<>();
        metric.put("mean", String.format("%.2f ms", timer.mean(TimeUnit.MILLISECONDS)));
        metric.put("max", String.format("%.2f ms", timer.max(TimeUnit.MILLISECONDS)));
        metric.put("count", timer.count());
        return metric;
    }
}