package com.openfinance.message.eventprocessor.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.openfinance.message.eventprocessor.model.DataFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.commons.lang3.SerializationException;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.openfinance.message.eventprocessor.model.DataFormat.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventDeserializer {

    private final ObjectMapper objectMapper;
    private final AvroSchemaRegistry avroSchemaRegistry;
    private final ProtobufTypeRegistry protobufTypeRegistry;

    public Object deserialize(byte[] data, DataFormat format) throws SerializationException {
        try {
            return switch (format) {
                case JSON -> deserializeJson(data);
                case AVRO -> deserializeAvro(data);
                case PROTOBUF -> deserializeProtobuf(data);
            };
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize data with format: " + format, e);
        }
    }

    private Object deserializeJson(byte[] data) throws IOException {
        // Performance optimized JSON deserialization
        return objectMapper.readValue(data, GenericEventData.class);
    }

    private GenericRecord deserializeAvro(byte[] data) throws IOException {
        // Schema resolution from registry
        Schema schema = avroSchemaRegistry.getLatestSchema();
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(data, null);
        return reader.read(null, decoder);
    }

    private Message deserializeProtobuf(byte[] data) throws IOException {
        // Dynamic protobuf deserialization
        return protobufTypeRegistry.parseMessage(data);
    }
}
