package com.example.app.service;

import com.example.app.model.User;

public class NotificationService {
    private final EmailSender emailSender;

    public NotificationService(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    public void sendWelcome(User user) {
        emailSender.send(user.getEmail(), "Welcome", "Welcome to the system, " + user.getUsername() + "!");
    }

    public void sendDeactivationNotice(User user) {
        emailSender.send(user.getEmail(), "Account Disabled", "Your account has been disabled.");
    }
}
