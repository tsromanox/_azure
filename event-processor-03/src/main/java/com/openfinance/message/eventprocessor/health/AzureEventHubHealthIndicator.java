package com.openfinance.message.eventprocessor.health;

import com.azure.messaging.eventhubs.EventHubConsumerClient;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component("azureEventHub")
@RequiredArgsConstructor
@Slf4j
public class AzureEventHubHealthIndicator implements HealthIndicator {

    private final EventHubProducerClient producerClient;
    private final EventHubConsumerClient consumerClient;

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();

        try {
            // Check EventHub connectivity
            var eventHubProperties = producerClient.getEventHubProperties();

            details.put("eventHubName", eventHubProperties.getName());
            details.put("createdAt", eventHubProperties.getCreatedAt());
            details.put("partitionIds", eventHubProperties.getPartitionIds());

            // Check partition information
            Map<String, Object> partitionDetails = new HashMap<>();
            for (String partitionId : eventHubProperties.getPartitionIds()) {
                try {
                    var partitionProperties = producerClient.getPartitionProperties(partitionId);
                    Map<String, Object> partitionInfo = new HashMap<>();
                    partitionInfo.put("beginningSequenceNumber", partitionProperties.getBeginningSequenceNumber());
                    partitionInfo.put("lastEnqueuedSequenceNumber", partitionProperties.getLastEnqueuedSequenceNumber());
                    partitionInfo.put("lastEnqueuedOffset", partitionProperties.getLastEnqueuedOffset());
                    partitionInfo.put("isEmpty", partitionProperties.isEmpty());
                    partitionDetails.put("partition-" + partitionId, partitionInfo);
                } catch (Exception e) {
                    partitionDetails.put("partition-" + partitionId, "Unable to retrieve info");
                }
            }
            details.put("partitions", partitionDetails);

            return Health.up().withDetails(details).build();

        } catch (Exception e) {
            log.error("Azure EventHub health check failed", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("errorType", e.getClass().getSimpleName())
                    .build();
        }
    }
}
