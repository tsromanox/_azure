package com.openfinance.eventprocessor.config;


import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfig {

    @Value("${cache.caffeine.max-size}")
    private long maxSize;

    @Value("${cache.caffeine.expire-after-write-minutes}")
    private long expireAfterWriteMinutes;

    @Value("${cache.caffeine.record-stats}")
    private boolean recordStats;

    /**
     * Configura o Caffeine Cache Manager
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("enrichmentCache");
        cacheManager.setCaffeine(caffeineCacheBuilder());
        cacheManager.setAsyncCacheMode(true); // Habilita cache assíncrono
        return cacheManager;
    }

    /**
     * Builder do Caffeine com configurações otimizadas
     */
    private Caffeine<Object, Object> caffeineCacheBuilder() {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireAfterWriteMinutes, TimeUnit.MINUTES)
                .softValues() // Permite GC sob pressão de memória
                .removalListener((key, value, cause) -> {
                    if (cause.wasEvicted()) {
                        log.debug("Cache entry evicted - Key: {}, Cause: {}", key, cause);
                    }
                });

        if (recordStats) {
            builder.recordStats();
        }

        return builder;
    }

    /**
     * Cache específico para enriquecimento com loader
     */
    @Bean
    public LoadingCache<String, String> enrichmentCache(
            EnrichmentCacheLoader loader,
            MeterRegistry meterRegistry) {

        LoadingCache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireAfterWriteMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build(loader);

        // Registra métricas do cache
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "enrichmentCache");

        return cache;
    }

    /**
     * Monitora e loga estatísticas do cache periodicamente
     */
    @Scheduled(fixedDelay = 60000) // A cada 60 segundos
    public void logCacheStats() {
        if (recordStats && cacheManager() instanceof CaffeineCacheManager) {
            CaffeineCacheManager manager = (CaffeineCacheManager) cacheManager();
            manager.getCacheNames().forEach(cacheName -> {
                com.github.benmanes.caffeine.cache.Cache<Object, Object> cache =
                        (com.github.benmanes.caffeine.cache.Cache<Object, Object>)
                                manager.getCache(cacheName).getNativeCache();

                CacheStats stats = cache.stats();
                double hitRate = stats.hitRate() * 100;

                log.info("Cache Stats [{}] - Hit Rate: {:.2f}%, " +
                                "Hits: {}, Misses: {}, Evictions: {}, Size: {}",
                        cacheName, hitRate, stats.hitCount(),
                        stats.missCount(), stats.evictionCount(),
                        cache.estimatedSize());
            });
        }
    }
}
