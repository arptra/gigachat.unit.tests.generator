package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCorrectionStageTest {

    @Test
    void applyRemovesRealObjectAssignmentForMockitoManagedField() {
        AutoCorrectionStage stage = new AutoCorrectionStage();
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "HiddenFeatureTest",
                "testRecalibrateRecordsCorrectEvent",
                "@Test void testRecalibrateRecordsCorrectEvent() {}",
                List.of(),
                List.of(),
                List.of(
                        "@Mock private FeatureToggleService featureToggleService;",
                        "@Mock private AuditTrailService auditTrailService;",
                        "private HiddenFeature feature;"
                ),
                List.of("""
                        @BeforeEach
                        void setUp() {
                            MockitoAnnotations.openMocks(this);
                            featureToggleService = new FeatureToggleService();
                            feature = new HiddenFeature(featureToggleService, auditTrailService);
                        }
                        """),
                """
                        package com.example.app.feature;

                        import org.junit.jupiter.api.BeforeEach;
                        import org.mockito.Mock;
                        import org.mockito.MockitoAnnotations;

                        public class HiddenFeatureTest {

                            @Mock
                            private FeatureToggleService featureToggleService;

                            @Mock
                            private AuditTrailService auditTrailService;

                            private HiddenFeature feature;

                            @BeforeEach
                            void setUp() {
                                MockitoAnnotations.openMocks(this);
                                featureToggleService = new FeatureToggleService();
                                feature = new HiddenFeature(featureToggleService, auditTrailService);
                            }

                            @Test
                            void testRecalibrateRecordsCorrectEvent() {}
                        }
                        """
        );

        GeneratedTestSnippet corrected = stage.apply(snippet);

        assertFalse(corrected.helperMethods().get(0).contains("featureToggleService = new FeatureToggleService();"));
        assertFalse(corrected.fullClassSource().contains("featureToggleService = new FeatureToggleService();"));
        assertTrue(corrected.helperMethods().get(0).contains("MockitoAnnotations.openMocks(this);"));
        assertTrue(corrected.fullClassSource().contains("feature = new HiddenFeature(featureToggleService, auditTrailService);"));
    }

    @Test
    void applyKeepsExplicitMockAssignmentForMockitoManagedField() {
        AutoCorrectionStage stage = new AutoCorrectionStage();
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "ApplicationTest",
                "testStart",
                "@Test void testStart() {}",
                List.of(),
                List.of(),
                List.of("@Mock private FeatureToggleService featureToggleService;"),
                List.of("""
                        @BeforeEach
                        void setUp() {
                            MockitoAnnotations.openMocks(this);
                            featureToggleService = mock(FeatureToggleService.class);
                        }
                        """),
                ""
        );

        GeneratedTestSnippet corrected = stage.apply(snippet);

        assertTrue(corrected.helperMethods().get(0).contains("featureToggleService = mock(FeatureToggleService.class);"));
    }

    @Test
    void applyShouldPreserveSiblingTestHelpersReturnedByGeneration() {
        AutoCorrectionStage stage = new AutoCorrectionStage();
        GeneratedTestSnippet snippet = new GeneratedTestSnippet(
                "CoverageGoalWorkflowServiceTest",
                "testPriorityClassification",
                """
                @Test
                void testPriorityClassification() {
                    assertEquals("priority", "priority");
                }
                """,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        """
                        @Test
                        void testStandardClassification() {
                            assertEquals("standard", "standard");
                        }
                        """,
                        """
                        @BeforeEach
                        void setUp() {
                            MockitoAnnotations.openMocks(this);
                        }
                        """
                ),
                """
                        package com.example.app.service;

                        import org.junit.jupiter.api.BeforeEach;
                        import org.junit.jupiter.api.Test;

                        class CoverageGoalWorkflowServiceTest {
                            @BeforeEach
                            void setUp() {
                                MockitoAnnotations.openMocks(this);
                            }

                            @Test
                            void testPriorityClassification() {
                                assertEquals("priority", "priority");
                            }

                            @Test
                            void testStandardClassification() {
                                assertEquals("standard", "standard");
                            }
                        }
                        """
        );

        GeneratedTestSnippet corrected = stage.apply(snippet);

        assertTrue(corrected.helperMethods().stream().anyMatch(helper -> helper.contains("testStandardClassification")));
    }
}
