package com.example.app.service;

import java.time.Instant;
import java.util.Objects;

public class AuditRecord {
    private final String message;
    private final Instant timestamp;

    public AuditRecord(String message, Instant timestamp) {
        this.message = Objects.requireNonNull(message, "message");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    public String message() {
        return message;
    }

    public Instant timestamp() {
        return timestamp;
    }
}
