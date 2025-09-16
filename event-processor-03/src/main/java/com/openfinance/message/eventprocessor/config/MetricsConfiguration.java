package com.openfinance.message.eventprocessor.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfiguration {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> meterRegistryCustomizer() {
        return registry -> {
            registry.config()
                    .meterFilter(MeterFilter.deny(id -> {
                        String uri = id.getTag("uri");
                        return uri != null && (uri.startsWith("/actuator") || uri.equals("/health"));
                    }))
                    .commonTags("application", "azure-eventhub-processor");
        };
    }
}
