package com.openfinance.message.eventprocessor.model;

public enum ProcessingStatus {
    SUCCESS,
    FAILED,
    RETRY,
    DEAD_LETTER,
    SKIPPED,
    PARTIAL
}
