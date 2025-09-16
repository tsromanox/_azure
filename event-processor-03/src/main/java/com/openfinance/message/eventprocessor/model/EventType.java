package com.openfinance.message.eventprocessor.model;

public enum EventType {
    ORDER_CREATED(true, ProcessingPriority.HIGH),
    ORDER_UPDATED(false, ProcessingPriority.MEDIUM),
    ORDER_CANCELLED(true, ProcessingPriority.HIGH),
    PAYMENT_PROCESSED(true, ProcessingPriority.CRITICAL),
    INVENTORY_UPDATED(false, ProcessingPriority.LOW),
    USER_REGISTERED(true, ProcessingPriority.MEDIUM),
    USER_UPDATED(false, ProcessingPriority.LOW),
    NOTIFICATION_SENT(false, ProcessingPriority.LOW),
    SYSTEM_EVENT(false, ProcessingPriority.LOW);

    private final boolean requiresExternalCall;
    private final ProcessingPriority defaultPriority;

    EventType(boolean requiresExternalCall, ProcessingPriority defaultPriority) {
        this.requiresExternalCall = requiresExternalCall;
        this.defaultPriority = defaultPriority;
    }

    public boolean requiresExternalCall() {
        return requiresExternalCall;
    }

    public ProcessingPriority getDefaultPriority() {
        return defaultPriority;
    }
}
