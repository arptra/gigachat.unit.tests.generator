package com.example.app.service;

import com.example.app.legacy.LegacyConnectionGateway;
import com.example.app.model.User;

public abstract class ParentConnectionWorkflow {
    private static final String REQUIRED_CHANNEL = "legacy-shadow-db";

    protected final AuditTrailService auditTrailService;

    protected ParentConnectionWorkflow(AuditTrailService auditTrailService) {
        this.auditTrailService = auditTrailService;
    }

    protected void openParentConnection(User user) {
        LegacyConnectionGateway.openRequiredChannel(REQUIRED_CHANNEL);
        auditTrailService.recordEvent("Parent connection opened for " + user.getUsername());
    }
}
