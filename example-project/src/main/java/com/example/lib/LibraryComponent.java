package com.example.lib;

import java.util.HashMap;
import java.util.Map;

public class LibraryComponent {
    private final Map<String, String> configuration = new HashMap<>();
    private boolean connected;

    public LibraryComponent() {
        configuration.put("mode", "default");
    }

    public void load() {
        configuration.put("loaded", Boolean.TRUE.toString());
    }

    public void reload() {
        configuration.put("reloaded", Boolean.TRUE.toString());
    }

    public void close() {
        configuration.put("closed", Boolean.TRUE.toString());
        connected = false;
    }

    public boolean status() {
        return connected;
    }

    public void connect() {
        connected = true;
    }

    public Map<String, String> configuration() {
        return Map.copyOf(configuration);
    }
}
