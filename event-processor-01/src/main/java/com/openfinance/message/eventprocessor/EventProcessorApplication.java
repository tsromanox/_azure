package com.openfinance.message.eventprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableScheduling
@EnableCaching
public class EventProcessorApplication {
    public static void main(String[] args) {
        // Enable Virtual Threads
        System.setProperty("spring.threads.virtual.enabled", "true");
        SpringApplication.run(EventProcessorApplication.class, args);
    }
}
