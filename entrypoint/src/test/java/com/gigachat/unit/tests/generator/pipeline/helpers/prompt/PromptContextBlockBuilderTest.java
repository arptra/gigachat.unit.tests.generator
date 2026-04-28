package com.gigachat.unit.tests.generator.pipeline.helpers.prompt;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockStrategy;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContextBlockBuilderTest {

    @Test
    void shouldExposeFallbackMockCandidatesForRequiredCollaboratorsWithoutRealPath() {
        PromptContextBlockBuilder builder = new PromptContextBlockBuilder();
        Analyze.AnalysisSummary summary = new Analyze.AnalysisSummary(
                new MockPlan(List.of(), MockStrategy.MOCKITO, List.of("repository"), List.of()),
                null,
                "",
                Map.of(),
                new Analyze.TestTargetContext("com.example.app.service.UserService", "service", true, false),
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "UserService", List.of(new ConstructorMetadata(
                                "UserService(UserRepository repository, AuditTrailService auditTrailService, NotificationService notificationService)",
                                List.of(
                                        new ParameterMetadata("repository", "UserRepository", List.of()),
                                        new ParameterMetadata("auditTrailService", "AuditTrailService", List.of()),
                                        new ParameterMetadata("notificationService", "NotificationService", List.of())
                                ))),
                        "UserRepository", List.of(new ConstructorMetadata(
                                "UserRepository()",
                                List.of()
                        )),
                        "AuditTrailService", List.of(new ConstructorMetadata(
                                "AuditTrailService()",
                                List.of()
                        )),
                        "NotificationService", List.of(new ConstructorMetadata(
                                "NotificationService(EmailSender emailSender)",
                                List.of(new ParameterMetadata("emailSender", "EmailSender", List.of()))
                        ))
                ),
                Map.of(),
                Set.of(),
                Set.of());

        Map<String, Object> policy = builder.buildSutConstructionPolicy(summary, Path.of("."));

        assertTrue(policy.containsKey("fallbackMockCandidates"));
        @SuppressWarnings("unchecked")
        List<String> fallbackCandidates = (List<String>) policy.get("fallbackMockCandidates");
        assertEquals(List.of("NotificationService"), fallbackCandidates);
    }
}
