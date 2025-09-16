package com.openfinance.message.eventprocessor.model;

public enum ProcessingPriority {
    CRITICAL(1),
    HIGH(2),
    MEDIUM(3),
    LOW(4);

    private final int level;

    ProcessingPriority(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
