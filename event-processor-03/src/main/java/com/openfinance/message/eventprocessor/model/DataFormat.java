package com.openfinance.message.eventprocessor.model;

public enum DataFormat {
    JSON("application/json"),
    AVRO("application/avro"),
    PROTOBUF("application/protobuf");

    private final String contentType;

    DataFormat(String contentType) {
        this.contentType = contentType;
    }

    public String getContentType() {
        return contentType;
    }

    public static DataFormat fromString(String format) {
        try {
            return DataFormat.valueOf(format.toUpperCase());
        } catch (IllegalArgumentException e) {
            return JSON; // default
        }
    }
}