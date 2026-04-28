package com.example.app.service;

import com.example.app.legacy.InheritedShadowUpgradeSession;
import com.example.app.model.User;
import com.example.lib.LibraryComponent;

public class InheritedStaticVoidWorkflowService extends ParentConnectionWorkflow {
    private final FeatureToggleService featureToggleService;
    private final NotificationService notificationService;
    private final LibraryComponent libraryComponent;

    public InheritedStaticVoidWorkflowService(FeatureToggleService featureToggleService,
                                              AuditTrailService auditTrailService,
                                              NotificationService notificationService,
                                              LibraryComponent libraryComponent) {
        super(auditTrailService);
        this.featureToggleService = featureToggleService;
        this.notificationService = notificationService;
        this.libraryComponent = libraryComponent;
    }

    public InheritedStaticVoidWorkflowService() {
        this(new FeatureToggleService(),
                new AuditTrailService(),
                new NotificationService(EmailSender.systemSender()),
                new LibraryComponent());
    }

    public boolean executeInheritedShadowUpgrade(User user, int rawSignal) {
        openParentConnection(user);
        InheritedShadowUpgradeSession session =
                new InheritedShadowUpgradeSession(featureToggleService, auditTrailService, notificationService);
        boolean upgraded = session.upgrade(user, rawSignal);
        if (upgraded) {
            libraryComponent.reload();
            auditTrailService.recordEvent("Inherited shadow upgrade completed for " + user.getUsername());
            return true;
        }
        libraryComponent.connect();
        return false;
    }
}
