package com.openfinance.message.eventprocessor.serialization;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.InvalidProtocolBufferException;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
@Slf4j
public class ProtobufTypeRegistry {

    private final Map<String, Descriptors.Descriptor> descriptorCache = new ConcurrentHashMap<>();

    public Message parseMessage(byte[] data) throws InvalidProtocolBufferException {
        // For dynamic parsing, you would typically need to know the message type
        // This is a simplified version that assumes a default message type
        // In production, you'd extract type info from headers or envelope

        try {
            // Attempt to parse as DynamicMessage if descriptor is available
            Descriptors.Descriptor descriptor = getDefaultDescriptor();
            if (descriptor != null) {
                return DynamicMessage.parseFrom(descriptor, data);
            }

            // Fallback to a generic event proto (you'd define this in .proto files)
            return parseAsGenericEvent(data);
        } catch (Exception e) {
            throw new InvalidProtocolBufferException("Failed to parse protobuf message: " + e.getMessage());
        }
    }

    public void registerDescriptor(String typeName, Descriptors.Descriptor descriptor) {
        descriptorCache.put(typeName, descriptor);
        log.info("Registered Protobuf descriptor for type: {}", typeName);
    }

    private Descriptors.Descriptor getDefaultDescriptor() {
        return descriptorCache.get("default");
    }

    private Message parseAsGenericEvent(byte[] data) throws InvalidProtocolBufferException {
        // This would typically parse your compiled .proto classes
        // For now, returning a DynamicMessage placeholder
        throw new InvalidProtocolBufferException("Generic event parsing not implemented");
    }
}
