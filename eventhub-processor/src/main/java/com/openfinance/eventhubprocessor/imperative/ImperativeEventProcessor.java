package com.openfinance.eventhubprocessor.imperative;

import com.azure.core.util.logging.ClientLogger;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.models.EventPosition;
import com.azure.messaging.eventhubs.EventProcessorClient;
import com.azure.messaging.eventhubs.EventProcessorClientBuilder;
import com.azure.messaging.eventhubs.models.PartitionContext;
import com.azure.messaging.eventhubs.EventBatchContext;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Profile("imperative")
public class ImperativeEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(ImperativeEventProcessor.class);

    private final String namespaceFqdn;
    private final String inputHub;
    private final String outputHub;
    private final String dlqHub;
    private final String consumerGroup;
    private final String checkpointConnectionString;
    private final String checkpointContainer;
    private final EnrichmentService enrichmentService;

    private EventProcessorClient processorClient;
    private ExecutorService vtPool;

    public ImperativeEventProcessor(
            @Value("${eventhubs.namespace-fqdn}") String namespaceFqdn,
            @Value("${eventhubs.input-hub:topic-a}") String inputHub,
            @Value("${eventhubs.output-hub:topic-b}") String outputHub,
            @Value("${eventhubs.dlq-hub:dlq}") String dlqHub,
            @Value("${spring.cloud.azure.eventhubs.consumer.group:$Default}") String consumerGroup,
            @Value("${eventhubs.checkpoint.connection-string}") String checkpointConnectionString,
            @Value("${eventhubs.checkpoint.container}") String checkpointContainer,
            EnrichmentService enrichmentService) {
        this.namespaceFqdn = namespaceFqdn;
        this.inputHub = inputHub;
        this.outputHub = outputHub;
        this.dlqHub = dlqHub;
        this.consumerGroup = consumerGroup;
        this.checkpointConnectionString = checkpointConnectionString;
        this.checkpointContainer = checkpointContainer;
        this.enrichmentService = enrichmentService;
    }

    @PostConstruct
    public void start() {
        vtPool = Executors.newVirtualThreadPerTaskExecutor();

        BlobContainerClient blobContainerClient = new BlobContainerClientBuilder()
                .connectionString(checkpointConnectionString)
                .containerName(checkpointContainer)
                .buildClient();

        BlobCheckpointStore checkpointStore = new BlobCheckpointStore(blobContainerClient);

        processorClient = new EventProcessorClientBuilder()
                .fullyQualifiedNamespace(namespaceFqdn)
                .consumerGroup(consumerGroup)
                .checkpointStore(checkpointStore)
                .credential(new DefaultAzureCredentialBuilder().build())
                .processEventBatch(this::onEvents, 100, Duration.ofSeconds(5))
                .processError(context -> log.error("EH error on partition {}", context.getPartitionContext().getPartitionId(), context.getThrowable()))
                .buildEventProcessorClient();

        processorClient.start();
        log.info("Imperative EventProcessor started for hub {} in group {}", inputHub, consumerGroup);
    }

    private void onEvents(EventBatchContext batchContext) {
        PartitionContext partition = batchContext.getPartitionContext();
        for (EventData e : batchContext.getEvents()) {
            vtPool.submit(() -> processOne(partition, e));
        }
    }

    private void processOne(PartitionContext partition, EventData event) {
        try {
            String body = event.getBodyAsString();
            String key = event.getPartitionKey() != null ? event.getPartitionKey() : "";
            String enrichment = enrichmentService.enrich(key);
            String enriched = "{" + "\"payload\":" + body + ",\"enrichment\":" + enrichment + "}";

            // publish to output hub
            var producer = new EventHubClientBuilder()
                    .credential(namespaceFqdn, outputHub, new DefaultAzureCredentialBuilder().build())
                    .buildProducerClient();
            try {
                producer.send(new com.azure.messaging.eventhubs.EventData(enriched.getBytes(StandardCharsets.UTF_8)));
            } finally {
                producer.close();
            }

            partition.updateCheckpoint(event);
        } catch (Exception ex) {
            log.error("Processing failed, sending to DLQ", ex);
            sendToDlq(event);
        }
    }

    private void sendToDlq(EventData event) {
        var producer = new EventHubClientBuilder()
                .credential(namespaceFqdn, dlqHub, new DefaultAzureCredentialBuilder().build())
                .buildProducerClient();
        try {
            producer.send(event);
        } finally {
            producer.close();
        }
    }

    @PreDestroy
    public void stop() {
        if (processorClient != null) processorClient.stop();
        if (vtPool != null) vtPool.close();
    }
}
