package com.openfinance.message.eventprocessor.transformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.message.eventprocessor.model.GenericEventData;
import com.openfinance.message.eventprocessor.model.ProcessingPriority;
import com.openfinance.message.eventprocessor.model.TransformedEvent;
import com.openfinance.message.eventprocessor.model.EventType;
import org.apache.avro.generic.GenericRecord;
import com.google.protobuf.Message;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventTransformer {

    private final ObjectMapper objectMapper;

    public static TransformedEvent transform(Object rawEvent) {
        if (rawEvent instanceof GenericEventData) {
            return transformFromJson((GenericEventData) rawEvent);
        } else if (rawEvent instanceof GenericRecord) {
            return transformFromAvro((GenericRecord) rawEvent);
        } else if (rawEvent instanceof Message) {
            return transformFromProtobuf((Message) rawEvent);
        } else {
            throw new IllegalArgumentException("Unsupported event type: " + rawEvent.getClass());
        }
    }

    private static TransformedEvent transformFromJson(GenericEventData data) {
        return TransformedEvent.builder()
                .eventId(data.getEventId() != null ? data.getEventId() : UUID.randomUUID().toString())
                .eventType(parseEventType(data.getEventType()))
                .aggregateId(data.getAggregateId())
                .version(data.getVersion())
                .timestamp(data.getTimestamp() != null ? data.getTimestamp() : Instant.now())
                .payload(convertJsonNodeToMap(data.getPayload()))
                .metadata(data.getMetadata())
                .correlationId(data.getCorrelationId())
                .causationId(data.getCausationId())
                .priority(determinePriority(data))
                .build();
    }

    private static TransformedEvent transformFromAvro(GenericRecord record) {
        Map<String, Object> payload = new HashMap<>();
        record.getSchema().getFields().forEach(field ->
                payload.put(field.name(), record.get(field.name()))
        );

        return TransformedEvent.builder()
                .eventId(getStringField(record, "eventId", UUID.randomUUID().toString()))
                .eventType(parseEventType(getStringField(record, "eventType", "SYSTEM_EVENT")))
                .aggregateId(getStringField(record, "aggregateId", null))
                .version(getLongField(record, "version", 1L))
                .timestamp(Instant.ofEpochMilli(getLongField(record, "timestamp", System.currentTimeMillis())))
                .payload(payload)
                .priority(ProcessingPriority.MEDIUM)
                .build();
    }

    private static TransformedEvent transformFromProtobuf(Message message) {
        Map<String, Object> payload = new HashMap<>();
        message.getAllFields().forEach((field, value) ->
                payload.put(field.getName(), value)
        );

        return TransformedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.SYSTEM_EVENT)
                .timestamp(Instant.now())
                .payload(payload)
                .priority(ProcessingPriority.MEDIUM)
                .build();
    }

    private static EventType parseEventType(String eventTypeStr) {
        try {
            return EventType.valueOf(eventTypeStr.toUpperCase());
        } catch (Exception e) {
            return EventType.SYSTEM_EVENT;
        }
    }

    private static ProcessingPriority determinePriority(GenericEventData data) {
        EventType eventType = parseEventType(data.getEventType());
        return eventType.getDefaultPriority();
    }

    private static Map<String, Object> convertJsonNodeToMap(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) return new HashMap<>();

        Map<String, Object> map = new HashMap<>();
        node.fields().forEachRemaining(entry ->
                map.put(entry.getKey(), entry.getValue().toString())
        );
        return map;
    }

    private static String getStringField(GenericRecord record, String fieldName, String defaultValue) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : defaultValue;
    }

    private static Long getLongField(GenericRecord record, String fieldName, Long defaultValue) {
        Object value = record.get(fieldName);
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
}