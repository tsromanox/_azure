package com.openfinance.eventhubprocessor.imperative;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class EnrichmentService {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    private final WebClient webClient;
    private final Cache<String, String> cache;
    private final String enrichmentBaseUrl;

    public EnrichmentService(WebClient webClient,
                             Cache<String, String> cache,
                             @Value("${enrichment.base-url:https://example.com/api/enrich}") String enrichmentBaseUrl) {
        this.webClient = webClient;
        this.cache = cache;
        this.enrichmentBaseUrl = enrichmentBaseUrl;
    }

    public String enrich(String key) {
        String cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        try {
            String result = webClient.get()
                    .uri(enrichmentBaseUrl + "?key=" + key)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .onErrorResume(ex -> {
                        log.warn("Enrichment call failed for key {}: {}", key, ex.toString());
                        return Mono.empty();
                    })
                    .blockOptional()
                    .orElse("{}");
            cache.put(key, result);
            return result;
        } catch (Exception e) {
            log.error("Enrichment error for key {}", key, e);
            return "{}";
        }
    }
}
