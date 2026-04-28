package com.example.app.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LegacyTelemetry {
    private static final List<String> EVENTS = new ArrayList<>();

    private LegacyTelemetry() {
    }

    public static void emit(String stream, String message) {
        EVENTS.add(stream + "::" + message);
    }

    public static List<String> snapshot() {
        return Collections.unmodifiableList(EVENTS);
    }

    public static void clear() {
        EVENTS.clear();
    }
}
