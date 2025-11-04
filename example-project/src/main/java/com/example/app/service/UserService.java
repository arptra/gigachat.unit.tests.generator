package com.example.app.service;

import java.util.ArrayList;
import java.util.List;

public class UserService {

    private final List<String> users = new ArrayList<>();

    public boolean createUser(String username) {
        return users.add(username);
    }

    public String findUser(int index) {
        if (index < 0 || index >= users.size()) {
            return null;
        }
        return users.get(index);
    }

    public boolean disableUser(String username) {
        return users.remove(username);
    }
}
