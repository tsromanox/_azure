package com.openfinance.message.eventprocessor.monitoring;

import com.azure.messaging.eventhubs.EventHubConsumerAsyncClient;
import com.openfinance.message.eventprocessor.model.EnrichedEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsCollector {

    private final MeterRegistry meterRegistry;
    private final Cache<String, EnrichedEvent> eventCache;
    private final EventHubConsumerAsyncClient consumerClient;

    @PostConstruct
    public void initializeMetrics() {
        // Cache metrics
        Gauge.builder("cache.size", eventCache, Cache::estimatedSize)
                .description("Current cache size")
                .register(meterRegistry);

        Gauge.builder("cache.hit.rate", eventCache, cache -> {
                    CacheStats stats = cache.stats();
                    return stats.requestCount() > 0 ? stats.hitRate() : 0.0;
                })
                .description("Cache hit rate")
                .register(meterRegistry);

        // JVM Virtual Thread metrics
        Gauge.builder("jvm.threads.virtual", () -> {
                    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
                    return threadBean.getThreadCount();
                })
                .description("Number of virtual threads")
                .register(meterRegistry);
    }

    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void collectEventHubMetrics() {
        try {
            consumerClient.getPartitionIds()
                    .collectList()
                    .subscribe(partitionIds -> {
                        for (String partitionId : partitionIds) {
                            consumerClient.getPartitionProperties(partitionId)
                                    .subscribe(properties -> {
                                        long lag = properties.getLastEnqueuedSequenceNumber() -
                                                getCurrentSequenceNumber(partitionId);

                                        meterRegistry.gauge("eventhub.consumer.lag",
                                                Tags.of("partition", partitionId), lag);
                                    });
                        }
                    });

        } catch (Exception e) {
            log.error("Failed to collect Event Hub metrics", e);
        }
    }

    @Scheduled(fixedRate = 60000) // Every minute
    public void reportCacheStatistics() {
        CacheStats stats = eventCache.stats();

        log.info("Cache Statistics - Size: {}, Hits: {}, Misses: {}, Hit Rate: {:.2f}%, Evictions: {}",
                eventCache.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate() * 100,
                stats.evictionCount());
    }

    private long getCurrentSequenceNumber(String partitionId) {
        // Implementation to get current processed sequence number from checkpoint
        return 0L; // Placeholder
    }
}
