package com.openfinance.message.eventprocessor.serialization;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SerializationPerformanceMetrics {

    private final Timer jsonDeserializationTimer;
    private final Timer avroDeserializationTimer;
    private final Timer protobufDeserializationTimer;

    public SerializationPerformanceMetrics(MeterRegistry meterRegistry) {
        this.jsonDeserializationTimer = Timer.builder("serialization.json.duration")
                .description("JSON deserialization time")
                .register(meterRegistry);
        this.avroDeserializationTimer = Timer.builder("serialization.avro.duration")
                .description("Avro deserialization time")
                .register(meterRegistry);
        this.protobufDeserializationTimer = Timer.builder("serialization.protobuf.duration")
                .description("Protobuf deserialization time")
                .register(meterRegistry);
    }

    public void recordDeserializationTime(DataFormat format, Duration duration) {
        switch (format) {
            case JSON -> jsonDeserializationTimer.record(duration);
            case AVRO -> avroDeserializationTimer.record(duration);
            case PROTOBUF -> protobufDeserializationTimer.record(duration);
        }
    }
}
