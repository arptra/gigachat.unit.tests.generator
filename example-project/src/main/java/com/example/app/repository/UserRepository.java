package com.example.app.repository;

import com.example.app.model.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class UserRepository {
    private final List<User> users = new ArrayList<>();

    public User save(User user) {
        users.removeIf(existing -> existing.getUsername().equalsIgnoreCase(user.getUsername()));
        users.add(user);
        return user;
    }

    public Optional<User> findByUsername(String username) {
        return users.stream()
                .filter(user -> user.getUsername().equalsIgnoreCase(username))
                .findFirst();
    }

    public List<User> findAll() {
        return Collections.unmodifiableList(users);
    }

    public boolean delete(String username) {
        return users.removeIf(user -> user.getUsername().equalsIgnoreCase(username));
    }
}
