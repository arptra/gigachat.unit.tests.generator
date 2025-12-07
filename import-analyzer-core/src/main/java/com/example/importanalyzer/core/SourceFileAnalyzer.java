package com.example.importanalyzer.core;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SourceFileAnalyzer {
    private SourceFileAnalyzer() {}

    static {
        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        StaticJavaParser.setConfiguration(configuration);
    }

    public static SourceFileResult analyze(Path file) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(Files.readString(file));

        Map<String, Integer> imports = new HashMap<>();
        Map<String, Integer> wildcardImports = new HashMap<>();
        Map<String, Integer> staticImports = new HashMap<>();
        Map<String, Integer> staticWildcardImports = new HashMap<>();
        Map<String, Set<String>> methodCallsByType = new HashMap<>();
        cu.getImports().forEach(imp -> {
            if (imp.isStatic()) {
                if (imp.isAsterisk()) {
                    staticWildcardImports.put(imp.getNameAsString(), imp.getBegin().map(p -> p.line).orElse(1));
                } else {
                    staticImports.put(imp.getNameAsString(), imp.getBegin().map(p -> p.line).orElse(1));
                }
            } else {
                if (imp.isAsterisk()) {
                    wildcardImports.put(imp.getNameAsString(), imp.getBegin().map(p -> p.line).orElse(1));
                } else {
                    imports.put(imp.getNameAsString(), imp.getBegin().map(p -> p.line).orElse(1));
                }
            }
        });

        Set<String> declaredTypes = new HashSet<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> declaredTypes.add(decl.getNameAsString()));
        cu.findAll(EnumDeclaration.class).forEach(decl -> declaredTypes.add(decl.getNameAsString()));
        cu.findAll(RecordDeclaration.class).forEach(decl -> declaredTypes.add(decl.getNameAsString()));

        Set<String> usedTypes = new HashSet<>();
        Set<String> usedIdentifiers = new HashSet<>();
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceType n, Void arg) {
                super.visit(n, arg);
                usedTypes.add(n.getName().getIdentifier());
            }

            @Override
            public void visit(com.github.javaparser.ast.expr.NameExpr n, Void arg) {
                super.visit(n, arg);
                usedIdentifiers.add(n.getName().getIdentifier());
                if (!n.getName().getIdentifier().isEmpty() && Character.isUpperCase(n.getName().getIdentifier().charAt(0))) {
                    usedTypes.add(n.getName().getIdentifier());
                }
            }

            @Override
            public void visit(com.github.javaparser.ast.expr.MethodCallExpr n, Void arg) {
                super.visit(n, arg);
                usedIdentifiers.add(n.getName().getIdentifier());
                n.getScope().filter(scope -> scope instanceof com.github.javaparser.ast.expr.NameExpr).ifPresent(scope -> {
                    String qualifier = ((com.github.javaparser.ast.expr.NameExpr) scope).getName().getIdentifier();
                    if (!qualifier.isEmpty() && Character.isUpperCase(qualifier.charAt(0))) {
                        usedTypes.add(qualifier);
                        methodCallsByType.computeIfAbsent(qualifier, k -> new HashSet<>()).add(n.getName().getIdentifier());
                    }
                });
            }

            @Override
            public void visit(com.github.javaparser.ast.expr.FieldAccessExpr n, Void arg) {
                super.visit(n, arg);
                usedIdentifiers.add(n.getName().getIdentifier());
            }
        }, null);

        cu.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class)
                .forEach(annotation -> {
                    usedIdentifiers.add(annotation.getName().getIdentifier());
                    usedTypes.add(annotation.getName().getIdentifier());
                });

        String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
        return new SourceFileResult(file, pkg, imports, wildcardImports, staticImports, staticWildcardImports, declaredTypes, usedTypes, usedIdentifiers, methodCallsByType);
    }
}
