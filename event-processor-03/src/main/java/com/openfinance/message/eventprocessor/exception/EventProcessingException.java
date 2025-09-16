package com.openfinance.message.eventprocessor.exception;

public class EventProcessingException extends RuntimeException {

    private final String eventId;
    private final int retryCount;

    public EventProcessingException(String message, Throwable cause) {
        super(message, cause);
        this.eventId = null;
        this.retryCount = 0;
    }

    public EventProcessingException(String message, String eventId, int retryCount) {
        super(message);
        this.eventId = eventId;
        this.retryCount = retryCount;
    }

    public EventProcessingException(String message, Throwable cause, String eventId, int retryCount) {
        super(message, cause);
        this.eventId = eventId;
        this.retryCount = retryCount;
    }

    public String getEventId() {
        return eventId;
    }

    public int getRetryCount() {
        return retryCount;
    }
}}
