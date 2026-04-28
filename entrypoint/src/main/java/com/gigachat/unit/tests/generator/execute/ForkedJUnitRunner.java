package com.gigachat.unit.tests.generator.execute;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * Small forked entry point used to execute a generated test without routing through Gradle.
 */
public final class ForkedJUnitRunner {

    private ForkedJUnitRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ForkedJUnitRunner <test-class> <method-name-or-empty>");
            System.exit(2);
        }

        String testClass = args[0];
        String methodName = args[1];
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);

        LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
        if (methodName.isBlank()) {
            requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
        } else {
            requestBuilder.selectors(DiscoverySelectors.selectMethod(testClass + "#" + methodName));
        }

        LauncherDiscoveryRequest request = requestBuilder.build();
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter stdout = new PrintWriter(System.out, true);
        PrintWriter stderr = new PrintWriter(System.err, true);
        stdout.printf("GIGACHAT_TEST_SUMMARY|found=%d|succeeded=%d|failed=%d|skipped=%d%n",
                summary.getTestsFoundCount(),
                summary.getTestsSucceededCount(),
                summary.getTestsFailedCount(),
                summary.getTestsSkippedCount());
        summary.printTo(stdout);
        summary.printFailuresTo(stderr);

        System.exit(summary.getTotalFailureCount() == 0 ? 0 : 1);
    }
}
