package com.openfinance.message.eventprocessor.model;

import com.openfinance.message.eventprocessor.checkpoint.CheckpointManager;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessEventArgs {

    private EventData data;
    private PartitionContext partitionContext;
    private CheckpointManager checkpointManager;

    public void updateCheckpoint() {
        if (checkpointManager != null) {
            checkpointManager.updateCheckpoint(partitionContext, data.getOffset());
        }
    }
}
