package com.example.app.service;

import com.example.app.legacy.LegacyUpgradeSession;
import com.example.app.legacy.ShadowRollbackSession;
import com.example.app.model.User;
import com.example.lib.LibraryComponent;

public class LegacyWorkflowService {
    private final FeatureToggleService featureToggleService;
    private final AuditTrailService auditTrailService;
    private final NotificationService notificationService;
    private final LibraryComponent libraryComponent;

    public LegacyWorkflowService(FeatureToggleService featureToggleService,
                                 AuditTrailService auditTrailService,
                                 NotificationService notificationService,
                                 LibraryComponent libraryComponent) {
        this.featureToggleService = featureToggleService;
        this.auditTrailService = auditTrailService;
        this.notificationService = notificationService;
        this.libraryComponent = libraryComponent;
    }

    public LegacyWorkflowService() {
        this(new FeatureToggleService(),
                new AuditTrailService(),
                new NotificationService(EmailSender.systemSender()),
                new LibraryComponent());
    }

    public boolean synchronizeLegacyUpgrade(User user, int rawSignal) {
        LegacyUpgradeSession session = new LegacyUpgradeSession(featureToggleService, auditTrailService, notificationService);
        boolean upgraded = session.process(user, rawSignal);
        if (upgraded) {
            libraryComponent.reload();
            auditTrailService.recordEvent("Legacy reload completed for " + user.getUsername());
            return true;
        }
        libraryComponent.connect();
        return false;
    }

    public boolean coordinateShadowRollback(User user) {
        ShadowRollbackSession session = new ShadowRollbackSession(featureToggleService, auditTrailService, notificationService);
        boolean rolledBack = session.rollback(user);
        if (rolledBack) {
            libraryComponent.close();
            auditTrailService.recordEvent("Shadow rollback completed for " + user.getUsername());
        } else {
            libraryComponent.load();
        }
        return rolledBack;
    }
}
