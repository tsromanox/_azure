package com.openfinance.message.eventprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionContext {

    private String partitionId;
    private String eventHubName;
    private String consumerGroup;
    private Long offset;
    private Long sequenceNumber;
    private String ownerId;

    public String getCheckpointKey() {
        return String.format("%s/%s/%s", eventHubName, consumerGroup, partitionId);
    }
}
