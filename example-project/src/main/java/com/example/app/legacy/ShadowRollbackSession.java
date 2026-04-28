package com.example.app.legacy;

import com.example.app.model.User;
import com.example.app.service.AuditTrailService;
import com.example.app.service.FeatureToggleService;
import com.example.app.service.NotificationService;

public class ShadowRollbackSession {
    private final FeatureToggleService featureToggleService;
    private final AuditTrailService auditTrailService;
    private final NotificationService notificationService;

    public ShadowRollbackSession(FeatureToggleService featureToggleService,
                                 AuditTrailService auditTrailService,
                                 NotificationService notificationService) {
        this.featureToggleService = featureToggleService;
        this.auditTrailService = auditTrailService;
        this.notificationService = notificationService;
    }

    public boolean rollback(User user) {
        int rebound = LegacyScoreRules.reboundFactor(user.getLoginAttempts(), user.isActive());
        LegacyTelemetry.emit("shadow-rollback", "candidate:" + user.getUsername());

        if (!featureToggleService.isEnabled("shadow-rollback")) {
            auditTrailService.recordEvent("Shadow rollback skipped for " + user.getUsername());
            return false;
        }

        auditTrailService.recordEvent("Shadow rollback rebound " + rebound + " for " + user.getUsername());
        if (rebound > 6) {
            notificationService.sendDeactivationNotice(user);
            LegacyTelemetry.emit("shadow-rollback", "notified:disable:" + user.getUsername());
            return true;
        }

        notificationService.sendWelcome(user);
        LegacyTelemetry.emit("shadow-rollback", "notified:welcome:" + user.getUsername());
        return false;
    }
}
