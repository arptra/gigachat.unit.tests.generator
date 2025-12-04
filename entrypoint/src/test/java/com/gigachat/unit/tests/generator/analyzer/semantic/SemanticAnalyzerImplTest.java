package com.gigachat.unit.tests.generator.analyzer.semantic;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticAnalyzerImplTest {
    private SemanticAnalyzer analyzer;
    private TestSignatureRegistry registry;

    @BeforeEach
    void setUp() {
        analyzer = new SemanticAnalyzerImpl();
        registry = new TestSignatureRegistry();
        registry.registerField("com.example.FieldHolder", "value", "com.example.Value");
        registry.registerMethod("com.example.UserRepository",
                new MethodSignature("loadAccount", TypeName.of("com.example.Account"), List.of(), false));
        registry.registerMethod("com.example.Account",
                new MethodSignature("getUsers", TypeName.of("List<com.example.User>"), List.of(), false));
        registry.registerMethod("com.example.AuditService",
                new MethodSignature("shouldRemove", TypeName.of("boolean"), List.of(TypeName.of("com.example.User")), false));
        registry.registerMethod("com.example.Helper",
                new MethodSignature("createStatic", TypeName.of("com.example.Notification"), List.of(TypeName.of("com.example.Value")), true));
        registry.registerConstructor("com.example.Notification",
                new ConstructorSignature(TypeName.of("com.example.Notification"), List.of(TypeName.of("com.example.Account"))));
        registry.registerOwnerInference("createStatic", TypeName.of("com.example.Helper"));
    }

    @Test
    void shouldCollectDomainTypesAndUsage() {
        MethodDeclaration declaration = parseMethod("""
                package com.example;
                import java.util.List;
                class Sample {
                    void process(com.example.UserRepository repository,
                                 com.example.AuditService auditService,
                                 com.example.FieldHolder holder) {
                        com.example.Account account = repository.loadAccount();
                        com.example.Notification notification = new com.example.Notification(account);
                        List<com.example.User> users = account.getUsers();
                        users.removeIf(user -> auditService.shouldRemove(user));
                        com.example.Value value = holder.value;
                        Helper.createStatic(value);
                        return notification;
                    }
                }
                """);

        SemanticMethodAnalysis analysis = analyzer.analyze(declaration, registry);

        assertThat(analysis.domainTypes())
                .extracting(TypeName::name)
                .contains("com.example.Account",
                        "com.example.Notification",
                        "com.example.User",
                        "com.example.Value",
                        "com.example.UserRepository",
                        "com.example.FieldHolder");

        assertThat(analysis.typeMethods().get(TypeName.of("com.example.UserRepository")))
                .anyMatch(signature -> "loadAccount".equals(signature.name()));

        assertThat(analysis.typeConstructors().get(TypeName.of("com.example.Notification")))
                .anyMatch(signature -> signature.parameterTypes().contains(TypeName.of("com.example.Account")));

        assertThat(analysis.staticCalls())
                .hasSize(1)
                .allSatisfy(staticInvocation -> assertThat(staticInvocation.signature().isStatic()).isTrue());
    }

    private MethodDeclaration parseMethod(String source) {
        CompilationUnit compilationUnit = StaticJavaParser.parse(source);
        Optional<MethodDeclaration> declaration = compilationUnit.findFirst(MethodDeclaration.class);
        return declaration.orElseThrow();
    }

    private static final class TestSignatureRegistry implements SignatureRegistry {
        private final Map<String, TypeName> fields = new HashMap<>();
        private final Map<String, List<MethodSignature>> methods = new LinkedHashMap<>();
        private final Map<String, List<ConstructorSignature>> constructors = new LinkedHashMap<>();
        private final Map<String, TypeName> inferredOwners = new HashMap<>();

        void registerField(String owner, String field, String type) {
            fields.put(owner + '#' + field, TypeName.of(type));
        }

        void registerMethod(String owner, MethodSignature signature) {
            methods.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(signature);
        }

        void registerConstructor(String owner, ConstructorSignature signature) {
            constructors.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(signature);
        }

        void registerOwnerInference(String method, TypeName type) {
            inferredOwners.put(method, type);
        }

        @Override
        public TypeName resolveFieldType(TypeName ownerType, String fieldName) {
            if (ownerType == null || fieldName == null) {
                return TypeName.unknown();
            }
            return fields.getOrDefault(ownerType.rawName() + '#' + fieldName, TypeName.unknown());
        }

        @Override
        public MethodSignature resolveMethod(TypeName ownerType, String methodName, List<TypeName> argumentTypes) {
            if (ownerType == null) {
                return null;
            }
            List<MethodSignature> signatures = methods.get(ownerType.rawName());
            if (signatures == null) {
                return null;
            }
            return signatures.stream()
                    .filter(signature -> signature.name().equals(methodName))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public ConstructorSignature resolveConstructor(TypeName ownerType, List<TypeName> argumentTypes) {
            if (ownerType == null) {
                return null;
            }
            return constructors.getOrDefault(ownerType.rawName(), List.of()).stream()
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<MethodSignature> methodsFor(TypeName ownerType) {
            if (ownerType == null) {
                return List.of();
            }
            return methods.getOrDefault(ownerType.rawName(), List.of());
        }

        @Override
        public List<ConstructorSignature> constructorsFor(TypeName ownerType) {
            if (ownerType == null) {
                return List.of();
            }
            return constructors.getOrDefault(ownerType.rawName(), List.of());
        }

        @Override
        public TypeName inferOwner(String methodName, List<TypeName> argumentTypes) {
            return inferredOwners.getOrDefault(methodName, TypeName.unknown());
        }

        @Override
        public boolean isStaticCall(TypeName ownerType, String methodName) {
            if (ownerType == null) {
                return false;
            }
            return methods.getOrDefault(ownerType.rawName(), List.of()).stream()
                    .filter(signature -> signature.name().equals(methodName))
                    .anyMatch(MethodSignature::isStatic);
        }
    }
}
