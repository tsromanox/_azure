package com.openfinance.message.eventprocessor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Configuração específica para RestClient e WebClient
 */
@Slf4j
@Configuration
public class HttpClientConfiguration {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
                .requestInterceptor((request, body, execution) -> {
                    // Log de request
                    log.debug("REST Request: {} {}", request.getMethod(), request.getURI());
                    return execution.execute(request, body);
                });
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .filter(ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
                    log.debug("WebClient Request: {} {}",
                            clientRequest.method(), clientRequest.url());
                    return Mono.just(clientRequest);
                }))
                .filter(ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
                    log.debug("WebClient Response: {}", clientResponse.statusCode());
                    return Mono.just(clientResponse);
                }));
    }

    @Bean
    public HttpComponentsClientHttpRequestFactory requestFactory() {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setConnectionRequestTimeout(Duration.ofSeconds(5));
        return factory;
    }
}
