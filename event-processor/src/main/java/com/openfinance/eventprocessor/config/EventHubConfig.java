package com.openfinance.eventprocessor.config;

import com.azure.messaging.eventhubs.*;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class EventHubConfig {

    @Value("${spring.cloud.azure.eventhubs.namespace}")
    private String namespace;

    @Value("${eventhub.source.name}")
    private String sourceEventHub;

    @Value("${eventhub.destination.name}")
    private String destinationEventHub;

    @Value("${eventhub.dlq.name}")
    private String dlqEventHub;

    @Value("${spring.cloud.azure.eventhubs.processor.consumer-group}")
    private String consumerGroup;

    @Value("${spring.cloud.azure.eventhubs.processor.checkpoint-store.account-name}")
    private String storageAccountName;

    @Value("${spring.cloud.azure.eventhubs.processor.checkpoint-store.container-name}")
    private String containerName;

    /**
     * Cria o EventProcessorClient para consumir eventos
     */
    @Bean
    public EventProcessorClient eventProcessorClient(
            EventProcessorClientBuilder builder) {
        return builder.buildEventProcessorClient();
    }

    /**
     * Builder para o EventProcessorClient com configurações
     */
    @Bean
    public EventProcessorClientBuilder eventProcessorClientBuilder(
            BlobCheckpointStore checkpointStore) {

        String fullyQualifiedNamespace = namespace + ".servicebus.windows.net";

        return new EventProcessorClientBuilder()
                .credential(fullyQualifiedNamespace, sourceEventHub,
                        new DefaultAzureCredentialBuilder().build())
                .consumerGroup(consumerGroup)
                .checkpointStore(checkpointStore)
                .loadBalancingStrategy(LoadBalancingStrategy.GREEDY) // Maximiza throughput
                .trackLastEnqueuedEventProperties(true); // Para métricas de lag
    }

    /**
     * Checkpoint store usando Azure Blob Storage
     */
    @Bean
    public BlobCheckpointStore blobCheckpointStore() {
        String endpoint = String.format("https://%s.blob.core.windows.net",
                storageAccountName);

        BlobContainerAsyncClient blobClient = new BlobContainerClientBuilder()
                .endpoint(endpoint)
                .containerName(containerName)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildAsyncClient();

        return new BlobCheckpointStore(blobClient);
    }

    /**
     * Producer para enviar eventos enriquecidos
     */
    @Bean
    public EventHubProducerClient destinationProducer() {
        String fullyQualifiedNamespace = namespace + ".servicebus.windows.net";

        return new EventHubClientBuilder()
                .credential(fullyQualifiedNamespace, destinationEventHub,
                        new DefaultAzureCredentialBuilder().build())
                .buildProducerClient();
    }

    /**
     * Producer para DLQ
     */
    @Bean
    public EventHubProducerClient dlqProducer() {
        String fullyQualifiedNamespace = namespace + ".servicebus.windows.net";

        return new EventHubClientBuilder()
                .credential(fullyQualifiedNamespace, dlqEventHub,
                        new DefaultAzureCredentialBuilder().build())
                .buildProducerClient();
    }
}
