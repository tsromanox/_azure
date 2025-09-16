package com.openfinance.message.eventprocessor.model;

public enum DeliveryGuarantee {
    AT_LEAST_ONCE("at-least-once"),
    EXACTLY_ONCE("exactly-once");

    private final String value;

    DeliveryGuarantee(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static DeliveryGuarantee fromString(String guarantee) {
        for (DeliveryGuarantee dg : values()) {
            if (dg.value.equalsIgnoreCase(guarantee)) {
                return dg;
            }
        }
        return AT_LEAST_ONCE; // default
    }
}
