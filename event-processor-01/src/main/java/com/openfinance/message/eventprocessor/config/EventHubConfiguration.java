package com.openfinance.message.eventprocessor.config;

import com.azure.messaging.eventhubs.*;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.openfinance.message.eventprocessor.model.EnrichedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Configuration
public class EventHubConfiguration {

    @Value("${azure.eventhub.connection-string}")
    private String connectionString;

    @Value("${azure.eventhub.consumer-group}")
    private String consumerGroup;

    @Value("${azure.eventhub.input-topic}")
    private String inputTopic;

    @Value("${azure.eventhub.output-topic}")
    private String outputTopic;

    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    @Value("${azure.storage.container-name}")
    private String containerName;

    @Bean
    public EventHubConsumerAsyncClient consumerClient() {
        BlobContainerAsyncClient blobClient = new BlobContainerClientBuilder()
                .connectionString(storageConnectionString)
                .containerName(containerName)
                .buildAsyncClient();

        BlobCheckpointStore checkpointStore = new BlobCheckpointStore(blobClient);

        return new EventHubClientBuilder()
                .connectionString(connectionString, inputTopic)
                .consumerGroup(consumerGroup)
                //.checkpointStore(checkpointStore)
                .buildAsyncConsumerClient();
    }

    @Bean
    public EventHubProducerClient producerClient() {
        return new EventHubClientBuilder()
                .connectionString(connectionString, outputTopic)
                .buildProducerClient();
    }

    @Bean
    public EventHubProducerClient dlqProducerClient() {
        return new EventHubClientBuilder()
                .connectionString(connectionString, inputTopic + "-dlq")
                .buildProducerClient();
    }

    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public Cache<String, EnrichedEvent> eventCache() {
        return Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    @Bean
    public WebClient enrichmentWebClient(@Value("${enrichment.api.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }
}
