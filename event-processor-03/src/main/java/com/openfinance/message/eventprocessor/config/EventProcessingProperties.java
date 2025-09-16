package com.openfinance.message.eventprocessor.config;


import com.openfinance.message.eventprocessor.model.DataFormat;
import com.openfinance.message.eventprocessor.model.DeliveryGuarantee;
import com.openfinance.message.eventprocessor.model.ProcessingMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@Data
@Component
@ConfigurationProperties(prefix = "event.processing")
public class EventProcessingProperties {

    @NotNull
    private ProcessingMode mode = ProcessingMode.STREAMING;

    @NotNull
    private DeliveryGuarantee deliveryGuarantee = DeliveryGuarantee.AT_LEAST_ONCE;

    @NotNull
    private DataFormat dataFormat = DataFormat.JSON;

    @Min(1)
    @Max(10)
    private int maxRetryAttempts = 3;

    private boolean deadLetterEnabled = true;

    @Min(1)
    @Max(1000)
    private int checkpointBatchSize = 100;

    @Min(1)
    @Max(500)
    private int batchSize = 64;

    private String eventHubName;
    private String consumerGroup = "$Default";
    private String storageContainerName = "checkpoints";

    // Performance tuning
    @Min(100)
    @Max(10000)
    private int prefetchCount = 1000;

    @Min(1)
    @Max(60)
    private int maxWaitTimeSeconds = 5;

    // Thread pool configuration
    @Min(1)
    @Max(100)
    private int processingThreads = 16;

    @Min(100)
    @Max(10000)
    private int queueCapacity = 1000;
}
