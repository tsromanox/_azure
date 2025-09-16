package com.openfinance.message.eventprocessor.model;

public enum ProcessingMode {
    STREAMING("streaming"),
    BATCH("batch");

    private final String value;

    ProcessingMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ProcessingMode fromString(String mode) {
        for (ProcessingMode pm : values()) {
            if (pm.value.equalsIgnoreCase(mode)) {
                return pm;
            }
        }
        return STREAMING; // default
    }
}
