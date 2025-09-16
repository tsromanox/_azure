package com.openfinance.message.eventprocessor.processor;

import com.openfinance.message.eventprocessor.model.TransformedEvent;

import java.util.concurrent.CompletableFuture;

public interface EventProcessor<T> {
    boolean canProcess(TransformedEvent event);
    CompletableFuture<Void> processAsync(TransformedEvent event);
    Class<T> getEventType();
    int getOrder();
}
