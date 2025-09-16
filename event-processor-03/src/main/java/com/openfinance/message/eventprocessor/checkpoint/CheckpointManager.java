package com.openfinance.message.eventprocessor.checkpoint;

import com.openfinance.message.eventprocessor.model.PartitionContext;

public interface CheckpointManager {
    void updateCheckpoint(PartitionContext context, Long offset);
    Long getLastCheckpoint(PartitionContext context);
    void resetCheckpoint(PartitionContext context);
}
