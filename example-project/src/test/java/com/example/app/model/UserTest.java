package com.example.app.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {

    @Test
    void shouldReturnFalseWhenDeactivated() {
        User user = new User("jane", "jane@example.com");
        user.deactivate();
        Instant before = user.getLastLogin();

        boolean loggedIn = user.markLoggedIn();

        assertFalse(loggedIn);
        assertFalse(user.isActive());
        assertTrue(user.getLastLogin().isAfter(before));
    }

    @Test
    void shouldUpdateLastLoginWhenMarkLoggedInIsCalled() {
        User user = new User("john", "john@example.com");

        boolean loggedIn = user.markLoggedIn();

        assertTrue(loggedIn);
        assertTrue(user.isActive());
        assertTrue(user.getLastLogin().isAfter(Instant.EPOCH));
        assertEquals(0, user.getLoginAttempts());
    }
}
