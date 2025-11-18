package com.gigachat.unit.tests.generator.cleaner.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationFailureLogParserTest {

    @Test
    void parsesJavaFileErrorsWithLineNumbers() {
        String log = String.join(System.lineSeparator(),
                "CompileResult[success=false, messages=[Gradle wrapper detected. Running real compilation via task 'test'.], stdout=/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/example-project/src/test/java/com/example/app/model/UserTest.java:29: error: cannot find symbol",
                "        String actualEmail = user.getEmaifl();",
                "                                 ^",
                "  symbol:   method getEmaifl()",
                "  location: variable user of type User",
                "/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/example-project/src/test/java/com/example/app/feature/HiddenFeatureTest.java:37: error: cannot find symbol",
                "        boolean result = feature.actiвvate();",
                "                                ^",
                "  symbol:   method actiвvate()",
                "  location: variable feature of type HiddenFeature",
                "/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/example-project/src/test/java/com/example/app/service/FeatureToggleServiceTest.java:38: error: cannot find symbol",
                "        boolean result = service.isEnableвd(featureName);",
                "                                ^",
                "  symbol:   method isEnableвd(String)",
                "  location: variable service of type FeatureToggleService",
                "/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/example-project/src/test/java/com/example/lib/LibraryComponentTest.java:19: error: cannot find symbol",
                "        Map<String, String> configAfterLoad = component.configuratiвon();",
                "                                                       ^",
                "  symbol:   method configuratiвon()",
                "  location: variable component of type LibraryComponent",
                "4 errors",
                "",
                "FAILURE: Build failed with an exception.",
                "",
                "* What went wrong:",
                "Execution failed for task ':example-project:compileTestJava'.",
                "> Compilation failed; see the compiler error output for details.",
                "",
                "* Try:",
                "> Run with --info option to get more log output.",
                "> Run with --scan to get full insights.",
                "",
                "BUILD FAILED in 512ms, stderr=Gradle command exited with code 1]");

        CompilationFailureLogParser parser = new CompilationFailureLogParser();

        List<CompilationFailureLocation> locations = parser.parse(log);

        assertEquals(4, locations.size());
        assertTrue(locations.stream().anyMatch(loc -> loc.filePath().toString().endsWith("UserTest.java") && loc.lineNumber() == 29));
        assertTrue(locations.stream().anyMatch(loc -> loc.filePath().toString().endsWith("HiddenFeatureTest.java") && loc.lineNumber() == 37));
        assertTrue(locations.stream().anyMatch(loc -> loc.filePath().toString().endsWith("FeatureToggleServiceTest.java") && loc.lineNumber() == 38));
        assertTrue(locations.stream().anyMatch(loc -> loc.filePath().toString().endsWith("LibraryComponentTest.java") && loc.lineNumber() == 19));
    }
}
