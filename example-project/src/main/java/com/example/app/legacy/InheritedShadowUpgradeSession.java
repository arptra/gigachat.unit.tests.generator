package com.example.app.legacy;

import com.example.app.model.User;
import com.example.app.service.AuditTrailService;
import com.example.app.service.FeatureToggleService;
import com.example.app.service.NotificationService;

public class InheritedShadowUpgradeSession {
    private final FeatureToggleService featureToggleService;
    private final AuditTrailService auditTrailService;
    private final NotificationService notificationService;

    public InheritedShadowUpgradeSession(FeatureToggleService featureToggleService,
                                         AuditTrailService auditTrailService,
                                         NotificationService notificationService) {
        this.featureToggleService = featureToggleService;
        this.auditTrailService = auditTrailService;
        this.notificationService = notificationService;
    }

    public boolean upgrade(User user, int rawSignal) {
        int normalized = LegacyScoreRules.normalizeSignal(rawSignal, user.getLoginAttempts());
        LegacyTelemetry.emit("inherited-shadow", "candidate:" + user.getUsername());

        if (!featureToggleService.isEnabled("inherited-shadow")) {
            auditTrailService.recordEvent("Inherited shadow skipped for " + user.getUsername());
            return false;
        }

        if (normalized >= 10) {
            notificationService.sendWelcome(user);
            auditTrailService.recordEvent("Inherited shadow promoted " + user.getUsername() + " with score " + normalized);
            LegacyTelemetry.emit("inherited-shadow", "promoted:" + user.getUsername());
            return true;
        }

        notificationService.sendDeactivationNotice(user);
        auditTrailService.recordEvent("Inherited shadow rejected " + user.getUsername() + " with score " + normalized);
        LegacyTelemetry.emit("inherited-shadow", "rejected:" + user.getUsername());
        return false;
    }
}
