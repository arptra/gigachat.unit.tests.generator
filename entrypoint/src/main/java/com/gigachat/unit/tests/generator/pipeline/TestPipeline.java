package com.gigachat.unit.tests.generator.pipeline;

import com.gigachat.unit.tests.generator.config.AgentConfig;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TestPipeline {

    private final AgentConfig config;
    private final List<TestClassInfo> testClasses;
    private final List<PipelineStage> stages = new ArrayList<>();

    public TestPipeline(AgentConfig config, List<TestClassInfo> testClasses) {
        this.config = Objects.requireNonNull(config, "config");
        this.testClasses = testClasses == null ? List.of() : List.copyOf(testClasses);
        stages.add(new AnalysisStage());
        stages.add(new PreparationStage());
        stages.add(new LoggingStage());
        stages.add(new FutureLLMStage());
    }

    public TestPipeline registerStage(PipelineStage stage) {
        stages.add(Objects.requireNonNull(stage, "stage"));
        return this;
    }

    public void execute() {
        Instant start = Instant.now();
        for (PipelineStage stage : stages) {
            stage.run(config, testClasses);
        }
        Instant end = Instant.now();
        System.out.println("Pipeline finished in " + Duration.between(start, end).toMillis() + " ms");
    }

    public interface PipelineStage {
        void run(AgentConfig config, List<TestClassInfo> classes);

        default String name() {
            return getClass().getSimpleName();
        }
    }

    private static final class AnalysisStage implements PipelineStage {
        @Override
        public void run(AgentConfig config, List<TestClassInfo> classes) {
            long methodCount = classes.stream()
                    .mapToLong(info -> info.methods().size())
                    .sum();
            System.out.printf("Analysis: %d classes, %d methods detected.%n", classes.size(), methodCount);
        }
    }

    private static final class PreparationStage implements PipelineStage {
        @Override
        public void run(AgentConfig config, List<TestClassInfo> classes) {
            if (classes.isEmpty()) {
                System.out.println("Preparation: no classes discovered, skipping further actions.");
            } else {
                System.out.println("Preparation: validating configuration and preparing DTO payloads.");
            }
        }
    }

    private static final class LoggingStage implements PipelineStage {
        @Override
        public void run(AgentConfig config, List<TestClassInfo> classes) {
            System.out.println("Logging: Configuration snapshot (JSON):\n" + config.toJson());
        }
    }

    private static final class FutureLLMStage implements PipelineStage {
        @Override
        public void run(AgentConfig config, List<TestClassInfo> classes) {
            if (config.gigaChat().isConfigured()) {
                System.out.println("Future LLM Step: GigaChat integration point configured.");
            } else {
                System.out.println("Future LLM Step: GigaChat client is not configured yet.");
            }
        }
    }
}
