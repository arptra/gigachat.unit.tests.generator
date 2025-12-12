package com.gigachat.unit.tests.generator.analysis.deepsemantic;

import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.config.PipelineModuleConfig;
import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticAnalysis;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticStaticCall;
import com.testagent.entrypoint.pipeline.helpers.analyze.SemanticTypeMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSemanticMethodAnalyzerTest {
    private MethodSignatureRegistry registry;
    private DeepSemanticMethodAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        registry = new MethodSignatureRegistry();
        analyzer = new DeepSemanticMethodAnalyzer(null, registry);
    }

    @Test
    void shouldProduceSemanticMetadataForDomainTypes() {
        registerUserSignatures();
        registerUserDtoSignatures();
        registry.registerMethod("UserRepository", "java.util.Optional<User> findByUsername(String username)");
        registry.registerMethod("UserRepository", "User save(User user)");

        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public java.util.Optional<UserDto> save(UserDto dto) {
                    java.util.Optional<User> existing = repository.findByUsername(dto.getUsername());
                    existing.ifPresent(user -> users.removeIf(current -> current.getUsername().equalsIgnoreCase(user.getUsername())));
                    if (existing.isEmpty()) {
                        users.add(new User(dto.getUsername(), dto.getEmail()));
                        Stats.publishSave(dto.getUsername());
                    }
                    return existing.map(user -> new UserDto(user.getUsername(), user.getEmail()));
                }
                """);
        TestMethodInfo methodInfo = new TestMethodInfo(declaration.getDeclarationAsString(false, false, false),
                declaration.getType().asString(),
                declaration.getBody().map(BlockStmt::toString).orElse("{}"),
                declaration);
        TestClassInfo classInfo = new TestClassInfo("com.example.UserService",
                "com.example.UserServiceTest",
                Path.of("UserServiceTest.java"),
                List.of("import java.util.List;",
                        "import java.util.Optional;",
                        "import com.example.User;",
                        "import com.example.UserDto;",
                        "import com.example.UserRepository;",
                        "import com.example.Stats;"),
                List.of(methodInfo),
                new ClassMetadata("UserService",
                        List.of(new FieldMetadata("repository", "UserRepository", true),
                                new FieldMetadata("users", "List<User>", true))));

        MethodAnalysisResult analysis = analyzer.analyze(classInfo, methodInfo, null, PipelineModuleConfig.from(Map.of()));
        SemanticAnalysis semantic = analysis.semanticAnalysis();

        List<String> domainTypes = semantic.domainTypes();
        assertTrue(domainTypes.contains("com.example.User"));
        assertTrue(domainTypes.contains("com.example.UserDto"));
        assertTrue(domainTypes.contains("com.example.UserRepository"));
        assertTrue(domainTypes.contains("java.util.List<com.example.User>"));
        assertTrue(domainTypes.contains("java.util.Optional<com.example.User>"));
        assertTrue(domainTypes.contains("java.util.Optional<com.example.UserDto>"));
        assertTrue(domainTypes.contains("com.example.Stats"));

        List<?> userConstructors = semantic.typeConstructors().get("com.example.User");
        assertNotNull(userConstructors);
        assertFalse(userConstructors.isEmpty());
        Set<String> userMethodNames = semantic.typeMethods().get("com.example.User")
                .stream()
                .map(SemanticTypeMethod::name)
                .collect(Collectors.toSet());
        assertTrue(userMethodNames.contains("getUsername"));
        assertTrue(userMethodNames.contains("getEmail"));
        assertTrue(semantic.staticCalls()
                .stream()
                .map(SemanticStaticCall::ownerType)
                .anyMatch("com.example.Stats"::equals));
        assertTrue(semantic.typeMethods().get("java.util.List<com.example.User>")
                .stream()
                .anyMatch(method -> method.name().equals("size")));
        assertTrue(semantic.typeMethods().get("java.util.Optional<com.example.User>")
                .stream()
                .anyMatch(method -> method.name().equals("isPresent")));
    }

    @Test
    void shouldIncludeOptionalAndListHelpersForStandardTypes() {
        MethodDeclaration declaration = StaticJavaParser.parseMethodDeclaration("""
                public java.util.Optional<String> first(java.util.List<String> values) {
                    if (values.isEmpty()) {
                        return java.util.Optional.empty();
                    }
                    return java.util.Optional.of(values.get(0));
                }
                """);
        TestMethodInfo methodInfo = new TestMethodInfo(declaration.getDeclarationAsString(false, false, false),
                declaration.getType().asString(),
                declaration.getBody().map(BlockStmt::toString).orElse("{}"),
                declaration);
        TestClassInfo classInfo = new TestClassInfo("Sample",
                "SampleTest",
                Path.of("SampleTest.java"),
                List.of("import java.util.List;",
                        "import java.util.Optional;"),
                List.of(methodInfo),
                new ClassMetadata("Sample", List.of()));

        MethodAnalysisResult analysis = analyzer.analyze(classInfo, methodInfo, null, PipelineModuleConfig.from(Map.of()));
        SemanticAnalysis semantic = analysis.semanticAnalysis();

        List<String> domainTypes = semantic.domainTypes();
        assertTrue(domainTypes.stream().anyMatch(type -> type.startsWith("java.util.List")));
        assertTrue(domainTypes.stream().anyMatch(type -> type.startsWith("java.util.Optional")));
        String optionalKey = semantic.typeMethods().keySet().stream()
                .filter(key -> key.startsWith("java.util.Optional"))
                .findFirst()
                .orElse("java.util.Optional");
        String listKey = semantic.typeMethods().keySet().stream()
                .filter(key -> key.startsWith("java.util.List"))
                .findFirst()
                .orElse("java.util.List");
        assertTrue(semantic.typeMethods().get(optionalKey)
                .stream()
                .anyMatch(method -> method.name().equals("get")));
        assertTrue(semantic.typeMethods().get(listKey)
                .stream()
                .anyMatch(method -> method.name().equals("contains")));
    }

    private void registerUserSignatures() {
        ConstructorMetadata userConstructor = new ConstructorMetadata("User(String username, String email)",
                List.of(new ParameterMetadata("username", "String", List.of()),
                        new ParameterMetadata("email", "String", List.of())));
        registry.registerConstructor("User", userConstructor);
        registry.registerMethod("User", "String getUsername()");
        registry.registerMethod("User", "String getEmail()");
    }

    private void registerUserDtoSignatures() {
        ConstructorMetadata dtoConstructor = new ConstructorMetadata("UserDto(String username, String email)",
                List.of(new ParameterMetadata("username", "String", List.of()),
                        new ParameterMetadata("email", "String", List.of())));
        registry.registerConstructor("UserDto", dtoConstructor);
        registry.registerMethod("UserDto", "String getUsername()");
        registry.registerMethod("UserDto", "String getEmail()");
    }
}
