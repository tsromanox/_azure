package com.openfinance.eventprocessor.cache;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentCacheService {

    private final LoadingCache<String, String> enrichmentCache;
    private final Map<String, AtomicLong> accessFrequency = new ConcurrentHashMap<>();

    /**
     * Busca dados do cache (método principal)
     */
    @Cacheable(value = "enrichmentCache", key = "#key", unless = "#result == null")
    public String getEnrichmentData(String key) {
        try {
            // Registra acesso
            accessFrequency.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();

            // Busca do cache
            return enrichmentCache.get(key);

        } catch (ExecutionException e) {
            log.error("Erro ao buscar dados do cache para key: {}", key, e);
            throw new RuntimeException("Falha ao obter dados de enriquecimento", e.getCause());
        }
    }

    /**
     * Busca apenas do cache, sem carregar se não existir
     */
    public String getFromCache(String key) {
        String value = enrichmentCache.getIfPresent(key);
        if (value != null) {
            accessFrequency.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        }
        return value;
    }

    /**
     * Adiciona ou atualiza valor no cache
     */
    @CachePut(value = "enrichmentCache", key = "#key")
    public String putInCache(String key, String value) {
        enrichmentCache.put(key, value);
        return value;
    }

    /**
     * Busca assíncrona com timeout
     */
    public CompletableFuture<String> getEnrichmentDataAsync(String key) {
        return CompletableFuture.supplyAsync(() -> getEnrichmentData(key))
                .orTimeout(5, TimeUnit.SECONDS);
    }

    /**
     * Busca múltiplas chaves de uma vez
     */
    public Map<String, String> getBulk(List<String> keys) {
        try {
            return enrichmentCache.getAll(keys);
        } catch (Exception e) {
            log.error("Erro ao buscar bulk do cache", e);
            throw new RuntimeException("Falha no bulk fetch", e);
        }
    }

    /**
     * Pré-carrega o cache com dados frequentes
     */
    public void preloadCache(List<String> frequentKeys) {
        log.info("Pré-carregando cache com {} chaves", frequentKeys.size());

        int batchSize = 50;
        for (int i = 0; i < frequentKeys.size(); i += batchSize) {
            List<String> batch = frequentKeys.subList(i,
                    Math.min(i + batchSize, frequentKeys.size()));

            batch.parallelStream().forEach(key -> {
                try {
                    enrichmentCache.get(key);
                } catch (Exception e) {
                    log.warn("Falha ao pré-carregar chave: {}", key);
                }
            });
        }

        log.info("Pré-carregamento concluído");
    }

    /**
     * Invalida entrada específica do cache
     */
    @CacheEvict(value = "enrichmentCache", key = "#key")
    public void invalidate(String key) {
        enrichmentCache.invalidate(key);
        log.debug("Cache invalidado para key: {}", key);
    }

    /**
     * Limpa todo o cache
     */
    @CacheEvict(value = "enrichmentCache", allEntries = true)
    public void clearCache() {
        enrichmentCache.invalidateAll();
        accessFrequency.clear();
        log.info("Cache completamente limpo");
    }

    /**
     * Atualiza cache de forma assíncrona
     */
    public CompletableFuture<Void> refreshAsync(String key) {
        return CompletableFuture.runAsync(() -> {
            enrichmentCache.refresh(key);
            log.debug("Cache atualizado para key: {}", key);
        });
    }

    /**
     * Retorna estatísticas do cache
     */
    public CacheStatistics getCacheStatistics() {
        CacheStats stats = enrichmentCache.stats();

        // Top 10 chaves mais acessadas
        List<String> topKeys = accessFrequency.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue()
                        .reversed())
                .limit(10)
                .map(Map.Entry::getKey)
                .toList();

        return new CacheStatistics(
                enrichmentCache.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate() * 100,
                stats.evictionCount(),
                stats.loadCount(),
                stats.averageLoadPenalty() / 1_000_000, // Convert to ms
                topKeys
        );
    }

    /**
     * Monitora e loga estatísticas do cache
     */
    @Scheduled(fixedDelay = 60000)
    public void logCacheMetrics() {
        CacheStatistics stats = getCacheStatistics();

        log.info("Cache Metrics - Size: {}, Hit Rate: {:.2f}%, Hits: {}, Misses: {}, Avg Load Time: {:.2f}ms",
                stats.size(),
                stats.hitRate(),
                stats.hitCount(),
                stats.missCount(),
                stats.averageLoadTimeMs());

        if (stats.hitRate() < 70) {
            log.warn("Cache hit rate baixo: {:.2f}%. Considere ajustar TTL ou tamanho do cache",
                    stats.hitRate());
        }
    }

    /**
     * Aquece o cache baseado em padrões de acesso
     */
    @Scheduled(fixedDelay = 300000) // A cada 5 minutos
    public void warmupCache() {
        List<String> topKeys = accessFrequency.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue().reversed())
                .limit(100)
                .map(Map.Entry::getKey)
                .toList();

        if (!topKeys.isEmpty()) {
            log.debug("Aquecendo cache com {} chaves mais acessadas", topKeys.size());
            topKeys.forEach(key -> enrichmentCache.refresh(key));
        }
    }

    // Classe para estatísticas
    public record CacheStatistics(
            long size,
            long hitCount,
            long missCount,
            double hitRate,
            long evictionCount,
            long loadCount,
            double averageLoadTimeMs,
            List<String> topKeys
    ) {}
}