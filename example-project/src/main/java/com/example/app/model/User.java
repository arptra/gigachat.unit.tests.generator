package com.example.app.model;

import java.time.Instant;
import java.util.Objects;

public class User {
    private final String username;
    private final String email;
    private boolean active;
    private Instant lastLogin;
    private int loginAttempts;

    public User(String username, String email) {
        this.username = Objects.requireNonNull(username, "username");
        this.email = Objects.requireNonNull(email, "email");
        this.active = true;
        this.lastLogin = Instant.EPOCH;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastLogin() {
        return lastLogin;
    }

    public int getLoginAttempts() {
        return loginAttempts;
    }

    public void markLoggedIn() {
        this.lastLogin = Instant.now();
        this.loginAttempts = 0;
        this.active = true;
    }

    public void incrementAttempts() {
        this.loginAttempts++;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }
}
