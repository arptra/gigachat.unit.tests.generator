package com.example.app.legacy;

import com.example.app.model.User;
import com.example.app.service.AuditTrailService;
import com.example.app.service.FeatureToggleService;
import com.example.app.service.NotificationService;

public class LegacyUpgradeSession {
    private final FeatureToggleService featureToggleService;
    private final AuditTrailService auditTrailService;
    private final NotificationService notificationService;

    public LegacyUpgradeSession(FeatureToggleService featureToggleService,
                                AuditTrailService auditTrailService,
                                NotificationService notificationService) {
        this.featureToggleService = featureToggleService;
        this.auditTrailService = auditTrailService;
        this.notificationService = notificationService;
    }

    public boolean process(User user, int rawSignal) {
        int normalized = LegacyScoreRules.normalizeSignal(rawSignal, user.getLoginAttempts());
        LegacyTelemetry.emit("legacy-upgrade", "candidate:" + user.getUsername());

        if (!featureToggleService.isEnabled("legacy-upgrade")) {
            auditTrailService.recordEvent("Legacy upgrade skipped for " + user.getUsername());
            LegacyTelemetry.emit("legacy-upgrade", "skipped:" + user.getUsername());
            return false;
        }

        if (LegacyScoreRules.shouldEscalate(normalized, user.isActive())) {
            notificationService.sendWelcome(user);
            auditTrailService.recordEvent("Legacy upgrade promoted " + user.getUsername() + " with score " + normalized);
            LegacyTelemetry.emit("legacy-upgrade", "promoted:" + user.getUsername());
            return true;
        }

        notificationService.sendDeactivationNotice(user);
        auditTrailService.recordEvent("Legacy upgrade rejected " + user.getUsername() + " with score " + normalized);
        LegacyTelemetry.emit("legacy-upgrade", "rejected:" + user.getUsername());
        return false;
    }
}
