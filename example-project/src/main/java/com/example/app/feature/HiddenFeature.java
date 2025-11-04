package com.example.app.feature;

import com.example.app.service.AuditTrailService;
import com.example.app.service.FeatureToggleService;
import com.example.app.util.MathUtil;

public class HiddenFeature {
    private final FeatureToggleService featureToggleService;
    private final AuditTrailService auditTrailService;

    public HiddenFeature(FeatureToggleService featureToggleService, AuditTrailService auditTrailService) {
        this.featureToggleService = featureToggleService;
        this.auditTrailService = auditTrailService;
    }

    public boolean activate() {
        if (!featureToggleService.isEnabled("hidden")) {
            return false;
        }
        int calculations = MathUtil.sum(2, MathUtil.max(3, 4));
        auditTrailService.recordEvent("Activated hidden feature with value " + calculations);
        return true;
    }

    public boolean deactivate() {
        featureToggleService.disable("hidden");
        auditTrailService.recordEvent("Hidden feature disabled");
        return true;
    }

    public void recalibrate() {
        int factor = MathUtil.factorial(4);
        auditTrailService.recordEvent("Recalibrated feature with factor " + factor);
    }
}
