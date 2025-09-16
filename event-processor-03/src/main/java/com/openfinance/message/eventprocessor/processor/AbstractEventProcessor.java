package com.openfinance.message.eventprocessor.processor;

import com.openfinance.message.eventprocessor.model.TransformedEvent;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
public abstract class AbstractEventProcessor<T> implements EventProcessor<T> {

    protected final Executor executor;

    protected AbstractEventProcessor(Executor executor) {
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> processAsync(TransformedEvent event) {
        return CompletableFuture.runAsync(() -> {
            try {
                process(event);
            } catch (Exception e) {
                log.error("Error processing event: {}", event.getEventId(), e);
                throw new RuntimeException("Processing failed", e);
            }
        }, executor);
    }

    protected abstract void process(TransformedEvent event);

    @Override
    public int getOrder() {
        return 0; // Default order
    }
}
