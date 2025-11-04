package com.example.app.service;

import com.example.app.model.User;
import com.example.app.repository.UserRepository;
import com.example.app.util.MathUtil;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class UserService {
    private final UserRepository repository;
    private final AuditTrailService auditTrailService;
    private final NotificationService notificationService;

    public UserService(UserRepository repository,
                       AuditTrailService auditTrailService,
                       NotificationService notificationService) {
        this.repository = Objects.requireNonNull(repository);
        this.auditTrailService = Objects.requireNonNull(auditTrailService);
        this.notificationService = Objects.requireNonNull(notificationService);
    }

    public UserService() {
        this(new UserRepository(), new AuditTrailService(),
                new NotificationService(EmailSender.systemSender()));
    }

    public User createUser(String username, String email) {
        User user = new User(username, email);
        repository.save(user);
        auditTrailService.recordEvent("Created user " + username);
        notificationService.sendWelcome(user);
        return user;
    }

    public boolean disableUser(String username) {
        Optional<User> user = repository.findByUsername(username);
        user.ifPresent(value -> {
            value.deactivate();
            notificationService.sendDeactivationNotice(value);
            auditTrailService.recordEvent("Disabled user " + username);
        });
        return user.isPresent();
    }

    public User findUser(int index) {
        List<User> users = repository.findAll();
        if (index < 0 || index >= users.size()) {
            return null;
        }
        return users.get(index);
    }

    public List<String> activeUsernames() {
        return repository.findAll().stream()
                .filter(User::isActive)
                .map(User::getUsername)
                .collect(Collectors.toList());
    }

    public double averageLoginAttempts() {
        List<User> users = repository.findAll();
        if (users.isEmpty()) {
            return 0;
        }
        int total = users.stream().mapToInt(User::getLoginAttempts).sum();
        return MathUtil.average(total, users.size());
    }
}
