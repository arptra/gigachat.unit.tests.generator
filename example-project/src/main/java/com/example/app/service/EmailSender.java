package com.example.app.service;

public class EmailSender {
    public void send(String address, String subject, String body) {
        System.out.printf("Sending email to %s: %s - %s%n", address, subject, body);
    }

    public static EmailSender systemSender() {
        return new EmailSender();
    }
}
