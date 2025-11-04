package com.example.app.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AuditTrailService {

    private final List<String> events = new ArrayList<>();

    public void recordEvent(String event) {
        events.add(event + "@" + Instant.now());
    }

    public int countEvents() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}
