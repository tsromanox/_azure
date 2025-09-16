package com.openfinance.message.eventprocessor.serialization;

import org.apache.avro.Schema;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
@Slf4j
public class AvroSchemaRegistry {

    private final Map<String, Schema> schemaCache = new ConcurrentHashMap<>();
    private static final String DEFAULT_SCHEMA = """
        {
          "type": "record",
          "name": "GenericEvent",
          "namespace": "com.yourcompany.events",
          "fields": [
            {"name": "eventId", "type": "string"},
            {"name": "eventType", "type": "string"},
            {"name": "aggregateId", "type": "string"},
            {"name": "timestamp", "type": "long"},
            {"name": "version", "type": "long"},
            {"name": "payload", "type": "string"},
            {"name": "metadata", "type": ["null", {"type": "map", "values": "string"}], "default": null}
          ]
        }
        """;

    public AvroSchemaRegistry() {
        // Initialize with default schema
        try {
            Schema defaultSchema = new Schema.Parser().parse(DEFAULT_SCHEMA);
            schemaCache.put("default", defaultSchema);
            schemaCache.put("latest", defaultSchema);
        } catch (Exception e) {
            log.error("Failed to initialize default Avro schema", e);
        }
    }

    public Schema getLatestSchema() {
        return schemaCache.getOrDefault("latest", getDefaultSchema());
    }

    public Schema getSchema(String schemaId) {
        return schemaCache.getOrDefault(schemaId, getDefaultSchema());
    }

    public void registerSchema(String schemaId, String schemaJson) {
        try {
            Schema schema = new Schema.Parser().parse(schemaJson);
            schemaCache.put(schemaId, schema);
            log.info("Registered Avro schema with ID: {}", schemaId);
        } catch (Exception e) {
            log.error("Failed to register Avro schema", e);
        }
    }

    private Schema getDefaultSchema() {
        return schemaCache.get("default");
    }
}
