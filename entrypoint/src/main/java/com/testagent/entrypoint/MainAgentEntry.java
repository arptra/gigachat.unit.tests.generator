package com.testagent.entrypoint;

import com.testagent.entrypoint.config.AgentConfig;
import com.testagent.entrypoint.dto.TestClassInfo;
import com.testagent.entrypoint.pipeline.TestPipeline;
import com.testagent.entrypoint.scanner.JavaProjectScanner;
import com.testagent.entrypoint.util.ArgsParser;

import java.io.IOException;
import java.util.List;

public final class MainAgentEntry {

    private MainAgentEntry() {
    }

    public static void main(String[] args) {
        try {
            AgentConfig config = ArgsParser.parse(args);
            System.out.println("Launching TestRepairAgent with configuration:\n" + config.toYaml());

            JavaProjectScanner scanner = new JavaProjectScanner();
            List<TestClassInfo> scannedClasses = switch (config.mode()) {
                case SCAN, TEST, REPAIR -> scanner.scan(config);
                case MONITOR -> List.of();
            };

            TestPipeline pipeline = new TestPipeline(config, scannedClasses);
            pipeline.execute();
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid arguments: " + exception.getMessage());
            System.exit(1);
        } catch (IOException exception) {
            System.err.println("Failed to scan project: " + exception.getMessage());
            System.exit(2);
        }
    }
}
