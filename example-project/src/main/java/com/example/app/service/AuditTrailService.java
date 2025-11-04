package com.example.app.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AuditTrailService {
    private final Clock clock;
    private final List<AuditRecord> events = new ArrayList<>();

    public AuditTrailService() {
        this(Clock.systemUTC());
    }

    public AuditTrailService(Clock clock) {
        this.clock = clock;
    }

    public void recordEvent(String event) {
        events.add(new AuditRecord(event, Instant.now(clock)));
    }

    public List<AuditRecord> events() {
        return Collections.unmodifiableList(events);
    }

    public int countEvents() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}
