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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMethodAnalyzerTest {
    @Test
    void basicOwnerResolution_directObjectCall() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("MyType", "void perform()");
        registry.registerConstructor("MyType", "MyType()");

        MethodAnalysisDTO analysis = analyze("""
                public void process() {
                    MyType obj = new MyType();
                    obj.perform();
                }
                """, registry);

        assertTrue(analysis.getDomainTypes().contains("MyType"));
        assertTrue(analysis.getTypeConstructors().containsKey("MyType"));
        assertTrue(analysis.getTypeMethods().get("MyType").stream()
                .anyMatch(info -> "perform".equals(info.getMethodName())));
    }

    @Test
    void basicOwnerResolution_fieldCall() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("WorkerService", "void doWork()");
        MethodAnalysisDTO analysis = analyzeClass("""
                class Sample {
                    private WorkerService service;
                    public void act() {
                        this.service.doWork();
                    }
                }
                """, "act", registry);

        assertTrue(analysis.getDomainTypes().contains("WorkerService"));
        assertTrue(analysis.getTypeMethods().get("WorkerService").stream()
                .anyMatch(info -> "doWork".equals(info.getMethodName())));
    }

    @Test
    void basicOwnerResolution_literalCall() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("String", "String toUpperCase()");

        MethodAnalysisDTO analysis = analyze("""
                public String upper() {
                    return "abc".toUpperCase();
                }
                """, registry);

        assertTrue(analysis.getDomainTypes().contains("String"));
        assertTrue(analysis.getTypeMethods().get("String").stream()
                .anyMatch(info -> "toUpperCase".equals(info.getMethodName())));
    }

    @Test
    void genericContainers_listAddAndUserConstructors() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registerListMetadata(registry, "User");
        registry.registerConstructor("User", "User(String username)");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.List;
                class Sample {
                    public void push(List<User> users, User user) {
                        users.add(user);
                    }
                }
                """, "push", registry);

        assertTrue(analysis.getDomainTypes().contains("List<User>"));
        assertTrue(analysis.getTypeMethods().get("List<User>").stream()
                .anyMatch(info -> "add".equals(info.getMethodName())
                        && info.getParameterTypes().equals(List.of("User"))));
        assertTrue(analysis.getTypeConstructors().containsKey("User"));
    }

    @Test
    void genericContainers_normalisesWildcards() throws Exception {
        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.List;
                class Sample {
                    public void inspect(List<? extends Number> values) {
                        values.size();
                    }
                }
                """, "inspect", new MethodSignatureRegistry());

        assertTrue(analysis.getDomainTypes().contains("List<Number>"));
        assertTrue(analysis.getDomainTypes().contains("Number"));
    }

    @Test
    void chainedCalls_resolveProfileChain() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("User", "Profile getProfile()");
        registry.registerMethod("Profile", "String getEmail()");

        MethodAnalysisDTO analysis = analyzeClass("""
                class Sample {
                    public String email(User user) {
                        return user.getProfile().getEmail();
                    }
                }
                """, "email", registry);

        Map<String, List<MethodInfo>> methods = analysis.getTypeMethods();
        assertTrue(methods.get("User").stream().anyMatch(info ->
                "getProfile".equals(info.getMethodName()) && "Profile".equals(info.getReturnType())));
        assertTrue(methods.get("Profile").stream().anyMatch(info ->
                "getEmail".equals(info.getMethodName()) && "String".equals(info.getReturnType())));
        assertTrue(analysis.getDomainTypes().containsAll(Set.of("User", "Profile", "String")));
    }

    @Test
    void chainedCalls_streamFilterProducesStreamDomainType() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("User", "boolean isActive()");
        registerListMetadata(registry, "User");
        registerStreamFilterMetadata(registry, "User");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.List;
                class Sample {
                    public long countActive(List<User> users) {
                        return users.stream().filter(u -> u.isActive()).count();
                    }
                }
                """, "countActive", registry);

        assertTrue(analysis.getDomainTypes().contains("Stream<User>"));
        assertTrue(analysis.getTypeMethods().get("Stream<User>").stream()
                .anyMatch(info -> "filter".equals(info.getMethodName())
                        && info.getParameterTypes().equals(List.of("Predicate<User>"))));
    }

    @Test
    void lambdaInference_removeIfCapturesPredicate() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("User", "boolean isInactive()");
        registerListMetadata(registry, "User");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.List;
                class Sample {
                    public boolean clean(List<User> users) {
                        return users.removeIf(user -> user.isInactive());
                    }
                }
                """, "clean", registry);

        assertTrue(analysis.getTypeMethods().get("List<User>").stream()
                .anyMatch(info -> "removeIf".equals(info.getMethodName())
                        && info.getParameterTypes().equals(List.of("Predicate<User>"))));
        assertTrue(analysis.getDomainTypes().contains("Predicate<User>"));
    }

    @Test
    void lambdaInference_mapInfersReturnType() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("User", "String getUsername()");
        registerListMetadata(registry, "User");
        registerStreamFilterMetadata(registry, "User");
        registerStreamMapMetadata(registry, "User", "String");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.List;
                import java.util.stream.Collectors;
                class Sample {
                    public List<String> usernames(List<User> users) {
                        return users.stream().map(u -> u.getUsername()).collect(Collectors.toList());
                    }
                }
                """, "usernames", registry);

        assertTrue(analysis.getDomainTypes().containsAll(Set.of("String", "Stream<String>", "Stream<User>")));
        assertTrue(analysis.getTypeMethods().get("Stream<User>").stream()
                .anyMatch(info -> "map".equals(info.getMethodName())
                        && info.getParameterTypes().equals(List.of("Function<User, String>"))));
    }

    @Test
    void staticCallDetection_handlesStandardUtilities() throws Exception {
        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.Collections;
                import java.util.List;
                class Sample {
                    public void build(List<User> users) {
                        Collections.unmodifiableList(users);
                        Boolean.TRUE.toString();
                        String.valueOf(users.size());
                    }
                }
                """, "build", new MethodSignatureRegistry());

        List<StaticDependency> dependencies = analysis.getStaticDependencies();
        assertEquals(3, dependencies.size());
        assertTrue(dependencies.stream().anyMatch(dep -> "Collections".equals(dep.getOwner())));
        assertTrue(dependencies.stream().anyMatch(dep -> "Boolean".equals(dep.getOwner())));
        assertTrue(dependencies.stream().anyMatch(dep -> "String".equals(dep.getOwner())));
    }

    @Test
    void constructors_detectedFromNewExpressions() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerConstructor("User", "User(String username)");
        MethodAnalysisDTO analysis = analyze("""
                public User create(String username) {
                    return new User(username);
                }
                """, registry);

        List<ConstructorInfo> constructors = analysis.getTypeConstructors().get("User");
        assertNotNull(constructors);
        assertTrue(constructors.stream().anyMatch(info -> info.getParameterTypes().equals(List.of("String"))));
    }

    @Test
    void constructors_inferredFromRegistryWhenNotInstantiated() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("UserRepository", "User load()");
        registry.registerConstructor("User", new ConstructorMetadata("User(String id)",
                List.of(new ParameterMetadata("id", "String", List.of()))));

        MethodAnalysisDTO analysis = analyzeClass("""
                class Sample {
                    private UserRepository repository;
                    public User find() {
                        return repository.load();
                    }
                }
                """, "find", registry);

        assertTrue(analysis.getTypeConstructors().containsKey("User"));
        assertTrue(analysis.getTypeMethods().get("UserRepository").stream()
                .anyMatch(info -> "load".equals(info.getMethodName()) && "User".equals(info.getReturnType())));
    }

    @Test
    void domainTypes_includeParametersReturnAndOwners() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("BillingService", "PaymentResponse charge(PaymentRequest request)");

        MethodAnalysisDTO analysis = analyzeClass("""
                class Sample {
                    private BillingService service;
                    public PaymentResponse process(PaymentRequest request) {
                        return service.charge(request);
                    }
                }
                """, "process", registry);

        assertTrue(analysis.getDomainTypes().containsAll(Set.of("BillingService", "PaymentRequest", "PaymentResponse")));
    }

    @Test
    void domainTypes_includeGenericsAndFields() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("OrderRepository", "java.util.List loadAll()");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.List;
                class Sample {
                    private AuditLog auditLog;
                    public List<Order> fetch(OrderRepository repository) {
                        return repository.loadAll();
                    }
                }
                """, "fetch", registry);

        assertTrue(analysis.getDomainTypes().containsAll(Set.of("AuditLog", "Order", "List<Order>", "OrderRepository")));
    }

    @Test
    void domainTypes_captureLambdaParameters() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("User", "boolean isActive()");
        registerListMetadata(registry, "User");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.List;
                class Sample {
                    public void flag(List<User> users) {
                        users.removeIf(user -> !user.isActive());
                    }
                }
                """, "flag", registry);

        assertTrue(analysis.getDomainTypes().containsAll(Set.of("User", "Predicate<User>")));
    }

    @Test
    void domainTypes_includeFieldCollaboratorsEvenIfUnused() throws Exception {
        MethodAnalysisDTO analysis = analyzeClass("""
                class Sample {
                    private ExternalClient client;
                    public void noop() {}
                }
                """, "noop", new MethodSignatureRegistry());

        assertTrue(analysis.getDomainTypes().contains("ExternalClient"));
    }

    @Test
    void forbidsUnknownEntriesEvenForNestedChains() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("Collaborator", "Step handle(User user)");
        registry.registerMethod("Step", "Step next()");
        registry.registerMethod("Step", "void finish()");

        MethodAnalysisDTO analysis = analyzeClass("""
                class Sample {
                    private Collaborator collaborator;
                    public void run(User user) {
                        collaborator.handle(user).next().finish();
                    }
                }
                """, "run", registry);

        assertTrue(analysis.getDomainTypes().stream().noneMatch(value -> value.toLowerCase().contains("unknown")));
        assertTrue(analysis.getTypeMethods().keySet().stream().noneMatch(value -> value.toLowerCase().contains("unknown")));
    }

    @Test
    void integration_complexListWorkflow() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("User", "boolean isActive()");
        registry.registerConstructor("OrderResponse", "OrderResponse(java.util.List list, String id)");
        registerListMetadata(registry, "User");
        registerStreamFilterMetadata(registry, "User");
        registry.registerMethod("Stream<User>", "java.util.List toList()");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.Collections;
                import java.util.List;
                import java.util.UUID;
                class Sample {
                    public OrderResponse generate(List<User> users) {
                        String requestId = UUID.randomUUID().toString();
                        List<User> active = users.stream().filter(User::isActive).toList();
                        return new OrderResponse(Collections.unmodifiableList(active), requestId);
                    }
                }
                """, "generate", registry);

        assertTrue(analysis.getDomainTypes().containsAll(Set.of("List<User>", "Stream<User>", "String", "OrderResponse")));
        assertTrue(analysis.getStaticDependencies().stream().anyMatch(dep -> dep.getOwner().contains("UUID")));
        assertTrue(analysis.getTypeConstructors().containsKey("OrderResponse"));
    }

    @Test
    void integration_collaboratorAndRegistryMetadata() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("UserRepository", "java.util.List fetchAll()");
        registry.registerConstructor("Report", "Report(java.util.List data)");
        registerListMetadata(registry, "User");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.List;
                class Sample {
                    private UserRepository repository;
                    public Report build() {
                        List users = repository.fetchAll();
                        return new Report(users);
                    }
                }
                """, "build", registry);

        assertTrue(analysis.getTypeMethods().get("UserRepository").stream()
                .anyMatch(info -> "fetchAll".equals(info.getMethodName())));
        assertTrue(analysis.getTypeConstructors().containsKey("Report"));
        assertTrue(analysis.getDomainTypes().contains("UserRepository"));
    }

    @Test
    void integration_mapFilterAndStaticUtilities() throws Exception {
        MethodSignatureRegistry registry = new MethodSignatureRegistry();
        registry.registerMethod("User", "String getUsername()");
        registerListMetadata(registry, "User");
        registerStreamFilterMetadata(registry, "User");
        registerStreamMapMetadata(registry, "User", "String");
        registry.registerMethod("Stream<String>", "java.util.List toList()");

        MethodAnalysisDTO analysis = analyzeClass("""
                import java.util.Collections;
                import java.util.List;
                class Sample {
                    public List<String> names(List<User> users) {
                        return Collections.unmodifiableList(
                                users.stream()
                                        .filter(u -> u.getUsername() != null)
                                        .map(User::getUsername)
                                        .toList());
                    }
                }
                """, "names", registry);

        assertTrue(analysis.getTypeMethods().get("Stream<User>").stream()
                .anyMatch(info -> "map".equals(info.getMethodName())));
        assertTrue(analysis.getStaticDependencies().stream().anyMatch(dep -> "Collections".equals(dep.getOwner())));
        assertTrue(analysis.getDomainTypes().containsAll(Set.of("List<String>", "Stream<String>", "String")));
    }

    private MethodAnalysisDTO analyze(String methodSource, MethodSignatureRegistry registry) throws Exception {
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
        MethodDeclaration declaration = StaticJavaParser.parseBodyDeclaration(methodSource).asMethodDeclaration();
        String signature = declaration.getDeclarationAsString(false, false, true);
        TestMethodInfo info = new TestMethodInfo(signature,
                declaration.getType().asString(),
                declaration.toString(),
                declaration);
        return analyzer.analyze(info);
    }

    private MethodAnalysisDTO analyzeClass(String classSource, String methodName, MethodSignatureRegistry registry) throws Exception {
        SemanticMethodAnalyzer analyzer = new SemanticMethodAnalyzer(registry);
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

    private void registerListMetadata(MethodSignatureRegistry registry, String elementType) {
        registry.registerMethod("List<" + elementType + ">", "boolean add(" + elementType + " value)");
        registry.registerMethod("List<" + elementType + ">", "boolean removeIf(Predicate<" + elementType + "> predicate)");
        registry.registerMethod("List<" + elementType + ">", "Stream<" + elementType + "> stream()");
    }

    private void registerStreamFilterMetadata(MethodSignatureRegistry registry, String elementType) {
        registry.registerMethod("Stream<" + elementType + ">", "Stream<" + elementType + "> filter(Predicate<" + elementType + "> predicate)");
        registry.registerMethod("Stream<" + elementType + ">", "long count()");
    }

    private void registerStreamMapMetadata(MethodSignatureRegistry registry, String sourceType, String targetType) {
        registry.registerMethod("Stream<" + sourceType + ">", "Stream<" + targetType + "> map(Function<" + sourceType + ", " + targetType + "> mapper)");
    }
}
