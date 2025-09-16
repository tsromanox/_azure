package com.openfinance.message.eventprocessor.config;

import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventProcessorClient;
import com.azure.messaging.eventhubs.EventProcessorClientBuilder;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class EventHubConfiguration {

    private final EventProcessingProperties properties;

    @Value("${spring.cloud.azure.eventhubs.namespace}")
    private String namespace;

    @Value("${spring.cloud.azure.eventhubs.processor.checkpoint-store.account-name}")
    private String storageAccountName;

    @Value("${spring.cloud.azure.eventhubs.processor.checkpoint-store.container-name}")
    private String containerName;

    @Bean
    public EventHubClientBuilder eventHubClientBuilder() {
        return new EventHubClientBuilder()
                .connectionString(getConnectionString())
                .consumerGroup(properties.getConsumerGroup());
    }

    @Bean
    public EventProcessorClient eventProcessorClient(
            EventHubClientBuilder clientBuilder,
            BlobCheckpointStore checkpointStore) {

        return new EventProcessorClientBuilder()
                .connectionString(getConnectionString())
                .consumerGroup(properties.getConsumerGroup())
                .checkpointStore(checkpointStore)
                .processEvent(context -> {
                    // This will be overridden in the service
                    log.debug("Processing event from partition {}",
                            context.getPartitionContext().getPartitionId());
                })
                .processError(context -> {
                    log.error("Error processing events", context.getThrowable());
                })
                .buildEventProcessorClient();
    }

    @Bean
    public BlobCheckpointStore blobCheckpointStore() {
        BlobContainerAsyncClient blobContainerAsyncClient = new BlobContainerClientBuilder()
                .connectionString(getStorageConnectionString())
                .containerName(containerName)
                .buildAsyncClient();

        return new BlobCheckpointStore(blobContainerAsyncClient);
    }

    @Bean(name = "eventProcessingExecutor")
    public Executor eventProcessingExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private String getConnectionString() {
        // In production, use Managed Identity or Key Vault
        return String.format("Endpoint=sb://%s.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=YOUR_KEY",
                namespace);
    }

    private String getStorageConnectionString() {
        // In production, use Managed Identity or Key Vault
        return String.format("DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=YOUR_KEY;EndpointSuffix=core.windows.net",
                storageAccountName);
    }
}
