package com.openfinance.message.eventprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorContext {

    private PartitionContext partitionContext;
    private Throwable throwable;
    private String errorMessage;
    private int retryCount;
    private Instant occurredAt;

    public boolean isRetryable() {
        return retryCount < 3 && isRetryableException(throwable);
    }

    private boolean isRetryableException(Throwable throwable) {
        if (throwable == null) return false;

        String className = throwable.getClass().getName();
        return className.contains("Timeout") ||
                className.contains("ConnectException") ||
                className.contains("IOException") ||
                (throwable.getMessage() != null &&
                        throwable.getMessage().contains("temporarily unavailable"));
    }
}
