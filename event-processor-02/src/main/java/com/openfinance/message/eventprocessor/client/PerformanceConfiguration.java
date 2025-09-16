package com.openfinance.message.eventprocessor.client;

import com.azure.spring.messaging.eventhubs.core.EventHubsProcessorFactory;
import com.azure.spring.messaging.eventhubs.core.checkpoint.CheckpointConfig;
import com.azure.spring.messaging.eventhubs.core.checkpoint.CheckpointMode;
import com.azure.spring.messaging.eventhubs.core.listener.EventHubsMessageListenerContainer;
import com.azure.spring.messaging.eventhubs.core.properties.EventHubsContainerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class PerformanceConfiguration {

    @Value("${event-processor.parallel-consumers:8}")
    private int parallelConsumers;

    @Value("${event-processor.batch-size:1000}")
    private int batchSize;

    @Value("${event-processor.processing-timeout:PT30S}")
    private Duration processingTimeout;

    @Value("${event-processor.checkpoint-interval:PT10S}")
    private Duration checkpointInterval;

    /**
     * Configuração do Event Processor para At-Least-Once
     */
    @Bean("atLeastOnceEventHubsContainer")
    public EventHubsMessageListenerContainer atLeastOnceEventHubsContainer(
            EventHubsProcessorFactory processorFactory) {

        EventHubsContainerProperties containerProperties = new EventHubsContainerProperties();
        containerProperties.setEventHubName("${event-processor.event-hub-name}");
        containerProperties.setConsumerGroup("${event-processor.consumer-group:$Default}");

        // Configuração de checkpoint manual para at-least-once
        CheckpointConfig checkpointConfig = new CheckpointConfig(CheckpointMode.MANUAL);
        containerProperties.setCheckpointConfig(checkpointConfig);

        // Configurações de performance
        containerProperties.setBatchSize(batchSize);
        containerProperties.setMaxConcurrentSessions(parallelConsumers);
        containerProperties.setSessionTimeout(Duration.ofSeconds(30));
        containerProperties.setMaxReceiveWaitTime(Duration.ofSeconds(1));

        return new EventHubsMessageListenerContainer(processorFactory, containerProperties);
    }

    /**
     * Configuração do Event Processor para Exactly-Once
     */
    @Bean("exactlyOnceEventHubsContainer")
    public EventHubsMessageListenerContainer exactlyOnceEventHubsContainer(
            EventHubsProcessorFactory processorFactory) {

        EventHubsContainerProperties containerProperties = new EventHubsContainerProperties();
        containerProperties.setEventHubName("${event-processor.event-hub-name}");
        containerProperties.setConsumerGroup("${event-processor.consumer-group-exact:event-processor-exact-group}");

        // Configuração de checkpoint automático para exactly-once
        CheckpointConfig checkpointConfig = new CheckpointConfig(CheckpointMode.BATCH);
        checkpointConfig.setCheckpointInterval(checkpointInterval);
        containerProperties.setCheckpointConfig(checkpointConfig);

        // Menor paralelismo para exactly-once para garantir ordem
        containerProperties.setBatchSize(batchSize / 2);
        containerProperties.setMaxConcurrentSessions(parallelConsumers / 2);
        containerProperties.setSessionTimeout(Duration.ofSeconds(60));

        return new EventHubsMessageListenerContainer(processorFactory, containerProperties);
    }

    /**
     * Thread Pool para processamento assíncrono de eventos
     */
    @Bean("eventProcessorExecutor")
    public ThreadPoolTaskExecutor eventProcessorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;
        int maxPoolSize = Runtime.getRuntime().availableProcessors() * 4;

        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(10000);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("event-processor-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // Configurações de performance
        executor.setKeepAliveSeconds(300); // 5 minutos
        executor.setAllowCoreThreadTimeOut(true);

        executor.initialize();
        return executor;
    }

    /**
     * Thread Pool específico para chamadas de API externa
     */
    @Bean("externalApiExecutor")
    public ThreadPoolTaskExecutor externalApiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(1000);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("external-api-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    /**
     * Thread Pool para operações de banco de dados
     */
    @Bean("databaseExecutor")
    public ThreadPoolTaskExecutor databaseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("database-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(45);

        executor.initialize();
        return executor;
    }
}
