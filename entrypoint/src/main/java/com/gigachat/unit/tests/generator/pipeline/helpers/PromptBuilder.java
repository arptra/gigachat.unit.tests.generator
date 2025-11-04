package com.gigachat.unit.tests.generator.pipeline.helpers;

import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.dto.MockPlan;
import com.gigachat.unit.tests.generator.dto.MockTarget;

import java.util.stream.Collectors;

/**
 * Combines different sources of information into a final prompt for LLM invocation.
 */
public class PromptBuilder {

    public String build(String skeletonPrompt,
                        MockPlan plan,
                        PipelineModuleConfig config,
                        String analysisJson) {
        String planSection = buildPlanSection(plan);
        String configSection = buildConfigSection(config);
        String analysisSection = normaliseJsonBlock(analysisJson);
        String skeletonSection = normaliseJsonBlock(skeletonPrompt);
        return "{\n"
                + "  \"skeleton\": " + skeletonSection + ",\n"
                + "  \"analysis\": " + analysisSection + ",\n"
                + "  \"mockPlan\": " + planSection + ",\n"
                + "  \"configuration\": " + configSection + "\n"
                + "}";
    }

    private String buildPlanSection(MockPlan plan) {
        if (plan == null) {
            return "{\"strategy\": \"NONE\", \"mocks\": []}";
        }
        String mocks = plan.targets().stream()
                .map(this::renderTarget)
                .collect(Collectors.joining(", "));
        return "{\"strategy\": \"" + plan.strategy() + "\", \"mocks\": [" + mocks + "]}";
    }

    private String renderTarget(MockTarget target) {
        if (target == null) {
            return "{}";
        }
        return "{\"type\": \"" + escape(target.qualifiedType()) + "\", "
                + "\"identifier\": \"" + escape(target.identifier()) + "\"}";
    }

    private String normaliseJsonBlock(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        return json.replace("\n", "\n  ");
    }

    private String buildConfigSection(PipelineModuleConfig config) {
        if (config == null) {
            return "{\"compile\": true, \"execute\": true, \"snapshots\": true}";
        }
        return "{\"parallelMode\": \"" + config.parallelMode() + "\", "
                + "\"compile\": " + config.compileEnabled() + ", "
                + "\"execute\": " + config.executeEnabled() + ", "
                + "\"snapshots\": " + config.snapshotsEnabled() + "}";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
