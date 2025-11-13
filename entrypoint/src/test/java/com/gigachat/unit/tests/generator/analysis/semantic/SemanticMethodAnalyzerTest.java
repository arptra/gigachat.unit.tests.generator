package com.gigachat.unit.tests.generator.analysis.semantic;

import com.gigachat.unit.tests.generator.analysis.api.ConstructorInfo;
import com.gigachat.unit.tests.generator.analysis.api.MethodAnalysisDTO;
import com.gigachat.unit.tests.generator.analysis.api.MethodInfo;
import com.gigachat.unit.tests.generator.analysis.api.StaticDependency;
import com.gigachat.unit.tests.generator.analyzer.ConstructorMetadata;
import com.gigachat.unit.tests.generator.analyzer.MethodSignatureRegistry;
import com.gigachat.unit.tests.generator.analyzer.ParameterMetadata;
import com.gigachat.unit.tests.generator.dto.TestMethodInfo;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMethodAnalyzerTest {
    @Test
    void shouldDetectConstructorsFromNewExpressions() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
        MethodAnalysisDTO analysis = analyze("public Order create(String name) { return new Order(name); }", analyzer);

        Map<String, List<ConstructorInfo>> constructors = analysis.getTypeConstructors();
        assertTrue(constructors.containsKey("Order"));
        List<ConstructorInfo> infos = constructors.get("Order");
        assertFalse(infos.isEmpty());
        assertEquals(List.of("String"), infos.get(0).getParameterTypes());
    }

    @Test
    void shouldCaptureGenericDomainTypesAndMethodUsage() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
        MethodAnalysisDTO analysis = analyze("public List<User> fetch(UserRepository repository) { List<User> users = repository.load(); return users; }",
                analyzer);

        Set<String> domainTypes = analysis.getDomainTypes();
        assertTrue(domainTypes.containsAll(Set.of("List", "User", "UserRepository")));
        Map<String, List<MethodInfo>> typeMethods = analysis.getMethods();
        assertTrue(typeMethods.containsKey("UserRepository"));
        List<MethodInfo> repositoryMethods = typeMethods.get("UserRepository");
        assertFalse(repositoryMethods.isEmpty());
        assertTrue(repositoryMethods.stream().anyMatch(info -> "load".equals(info.getMethodName())));
    }

    @Test
    void shouldResolveStaticCalls() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
        MethodAnalysisDTO analysis = analyze("public java.util.UUID build() { return java.util.UUID.randomUUID(); }", analyzer);

        List<StaticDependency> dependencies = analysis.getStaticDependencies();
        assertEquals(1, dependencies.size());
        StaticDependency dependency = dependencies.get(0);
        assertTrue(dependency.getOwner().contains("UUID"));
        assertEquals("randomUUID", dependency.getMethodName());
    }

    @Test
    void shouldPropagateParametersAndReturnTypeMetadata() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("BillingService", "void charge(PaymentRequest request)");
        registry.registerConstructor("PaymentResponse",
                new ConstructorMetadata("PaymentResponse(PaymentStatus status)",
                        List.of(new ParameterMetadata("status", "PaymentStatus", List.of()))));
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
        MethodAnalysisDTO analysis = analyze("public PaymentResponse process(BillingService service) { return null; }", analyzer);

        assertTrue(analysis.getDomainTypes().containsAll(Set.of("BillingService", "PaymentResponse")));
        assertTrue(analysis.getMethods().containsKey("BillingService"));
        assertTrue(analysis.getMethods().get("BillingService").stream()
                .anyMatch(info -> "charge".equals(info.getMethodName())));
        assertTrue(analysis.getTypeConstructors().containsKey("PaymentResponse"));
        assertNotNull(analysis.getTypeConstructors().get("PaymentResponse").stream()
                .filter(ctor -> "PaymentResponse(PaymentStatus status)".equals(ctor.getSignature()))
                .findFirst().orElse(null));
    }

    @Test
    void shouldInferLambdaTypesFromCollectionGenerics() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
        MethodAnalysisDTO analysis = analyze("public void cleanup(List<User> users) { users.removeIf(user -> user.isInactive()); }", analyzer);

        Map<String, List<MethodInfo>> methods = analysis.getMethods();
        assertTrue(methods.containsKey("User"));
        assertTrue(methods.get("User").stream().anyMatch(info -> "isInactive".equals(info.getMethodName())));
    }

    @Test
    void shouldResolveCollaboratorFieldTypes() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
        String classSource = "class Sample { private Collaborator collaborator; public void process() { collaborator.execute(); } }";
        MethodAnalysisDTO analysis = analyzeClass(classSource, "process", analyzer);

        Map<String, List<MethodInfo>> methods = analysis.getMethods();
        assertTrue(methods.containsKey("Collaborator"));
        assertTrue(methods.get("Collaborator").stream().anyMatch(info -> "execute".equals(info.getMethodName())));
    }

    @Test
    void shouldResolveListGenericsAndChainedCalls() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("User", "String getUsername()");
        registry.registerMethod("String", "boolean equalsIgnoreCase(String other)");
        registry.registerMethod("List", "boolean removeIf(Object predicate)");
        registry.registerMethod("List", "boolean add(Object element)");
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
        String classSource = """
                import java.util.List;
                class Sample {
                    private List<User> users;
                    public boolean process(User user) {
                        users.add(user);
                        return users.removeIf(existing -> existing.getUsername().equalsIgnoreCase(user.getUsername()));
                    }
                }
                """;

        MethodAnalysisDTO analysis = analyzeClass(classSource, "process", analyzer);

        assertTrue(analysis.getDomainTypes().containsAll(Set.of("List<User>", "User", "String")));

        Map<String, List<MethodInfo>> methods = analysis.getMethods();
        assertTrue(methods.containsKey("List<User>"));
        assertTrue(methods.get("List<User>").stream().anyMatch(info ->
                "removeIf".equals(info.getMethodName())
                        && info.getParameterTypes().equals(List.of("Predicate<User>"))));
        assertTrue(methods.get("List<User>").stream().anyMatch(info ->
                "add".equals(info.getMethodName())
                        && info.getParameterTypes().equals(List.of("User"))));
        assertTrue(methods.containsKey("User"));
        assertTrue(methods.get("User").stream().anyMatch(info ->
                "getUsername".equals(info.getMethodName()) && "String".equals(info.getReturnType())));
        assertTrue(methods.containsKey("String"));
        assertTrue(methods.get("String").stream().anyMatch(info ->
                "equalsIgnoreCase".equals(info.getMethodName()) && info.getParameterTypes().equals(List.of("String"))));
    }

    private MethodAnalysisDTO analyze(String methodSource, SemanticMethodAnalyzer analyzer) throws Exception {
        MethodDeclaration declaration = StaticJavaParser.parseBodyDeclaration(methodSource).asMethodDeclaration();
        String signature = declaration.getDeclarationAsString(false, false, true);
        TestMethodInfo info = new TestMethodInfo(signature,
                declaration.getType().asString(),
                declaration.toString(),
                declaration);
        return analyzer.analyze(info);
    }

    private MethodAnalysisDTO analyzeClass(String classSource, String methodName, SemanticMethodAnalyzer analyzer) throws Exception {
        CompilationUnit unit = StaticJavaParser.parse(classSource);
        MethodDeclaration declaration = unit.findFirst(MethodDeclaration.class, method -> method.getNameAsString().equals(methodName))
                .orElseThrow();
        String signature = declaration.getDeclarationAsString(false, false, true);
        TestMethodInfo info = new TestMethodInfo(signature,
                declaration.getType().asString(),
                declaration.toString(),
                declaration);
        return analyzer.analyze(info);
    }
}
