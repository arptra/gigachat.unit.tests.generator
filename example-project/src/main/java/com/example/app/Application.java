package com.example.app;

import com.example.app.feature.HiddenFeature;
import com.example.app.model.User;
import com.example.app.repository.UserRepository;
import com.example.app.service.AuditTrailService;
import com.example.app.service.FeatureToggleService;
import com.example.app.service.NotificationService;
import com.example.app.service.UserService;
import com.example.app.service.EmailSender;
import com.example.lib.LibraryComponent;

import java.util.List;

public class Application {
    private final UserService userService;
    private final AuditTrailService auditTrailService;
    private final LibraryComponent libraryComponent;
    private final FeatureToggleService featureToggleService;

    public Application(UserService userService,
                       AuditTrailService auditTrailService,
                       LibraryComponent libraryComponent,
                       FeatureToggleService featureToggleService) {
        this.userService = userService;
        this.auditTrailService = auditTrailService;
        this.libraryComponent = libraryComponent;
        this.featureToggleService = featureToggleService;
    }

    public Application() {
        this(new UserService(new UserRepository(), new AuditTrailService(),
                        new NotificationService(EmailSender.systemSender())),
                new AuditTrailService(),
                new LibraryComponent(),
                new FeatureToggleService());
    }

    public void start() {
        libraryComponent.connect();
        libraryComponent.load();
        auditTrailService.recordEvent("Application started");
        userService.createUser("alice", "alice@example.com");
        userService.createUser("bob", "bob@example.com");
    }

    public void restart() {
        shutdown();
        libraryComponent.reload();
        start();
        auditTrailService.recordEvent("Application restarted");
    }

    public void shutdown() {
        libraryComponent.close();
        auditTrailService.recordEvent("Application shutdown");
    }

    public void deployHiddenFeature() {
        HiddenFeature hiddenFeature = new HiddenFeature(featureToggleService, auditTrailService);
        if (hiddenFeature.activate()) {
            hiddenFeature.recalibrate();
        }
    }

    public List<String> activeUsers() {
        return userService.activeUsernames();
    }

    public User fetchFirstUser() {
        return userService.findUser(0);
    }
}
