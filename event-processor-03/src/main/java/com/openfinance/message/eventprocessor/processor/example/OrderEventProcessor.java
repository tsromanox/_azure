package com.openfinance.message.eventprocessor.processor.example;

import com.openfinance.message.eventprocessor.model.EventType;
import com.openfinance.message.eventprocessor.model.TransformedEvent;
import com.openfinance.message.eventprocessor.processor.AbstractEventProcessor;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.Executor;

@Component
@Slf4j
public class OrderEventProcessor extends AbstractEventProcessor<TransformedEvent> {

    public OrderEventProcessor(Executor eventProcessingExecutor) {
        super(eventProcessingExecutor);
    }

    @Override
    public boolean canProcess(TransformedEvent event) {
        return event.getEventType() == EventType.ORDER_CREATED ||
                event.getEventType() == EventType.ORDER_UPDATED ||
                event.getEventType() == EventType.ORDER_CANCELLED;
    }

    @Override
    protected void process(TransformedEvent event) {
        log.info("Processing order event: {}", event.getEventId());

        switch (event.getEventType()) {
            case ORDER_CREATED -> processOrderCreated(event);
            case ORDER_UPDATED -> processOrderUpdated(event);
            case ORDER_CANCELLED -> processOrderCancelled(event);
        }
    }

    private void processOrderCreated(TransformedEvent event) {
        // Business logic for order creation
        log.info("Order created: {}", event.getAggregateId());
    }

    private void processOrderUpdated(TransformedEvent event) {
        // Business logic for order update
        log.info("Order updated: {}", event.getAggregateId());
    }

    private void processOrderCancelled(TransformedEvent event) {
        // Business logic for order cancellation
        log.info("Order cancelled: {}", event.getAggregateId());
    }

    @Override
    public Class<TransformedEvent> getEventType() {
        return TransformedEvent.class;
    }
}
