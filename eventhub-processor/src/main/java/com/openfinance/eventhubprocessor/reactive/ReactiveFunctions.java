package com.openfinance.eventhubprocessor.reactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Flux;

import java.util.function.Function;

@Configuration
@Profile("reactive")
public class ReactiveFunctions {
    private static final Logger log = LoggerFactory.getLogger(ReactiveFunctions.class);

    @Bean
    public Function<Flux<String>, Flux<String>> process() {
        return input -> input
                .map(v -> "processed:" + v)
                .doOnNext(v -> log.debug("Processed {}", v));
    }
}
