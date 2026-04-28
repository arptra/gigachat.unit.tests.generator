package com.example.app.service;

public class CoverageGoalWorkflowService {
    private final AuditTrailService auditTrailService;

    public CoverageGoalWorkflowService(AuditTrailService auditTrailService) {
        this.auditTrailService = auditTrailService;
    }

    public CoverageGoalWorkflowService() {
        this(new AuditTrailService());
    }

    public String classifySignal(int score, boolean priorityAccount) {
        if (score >= 10 && priorityAccount) {
            auditTrailService.recordEvent("priority-signal");
            return "priority";
        }
        if (score >= 10) {
            auditTrailService.recordEvent("standard-signal");
            return "standard";
        }
        auditTrailService.recordEvent("rejected-signal");
        return "rejected";
    }
}
