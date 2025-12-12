package com.gigachat.unit.tests.generator.analysis.deepsemantic;

import com.gigachat.unit.tests.generator.dto.TestClassInfo;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import com.github.javaparser.ast.type.WildcardType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves type names inside a method using import information from {@link TestClassInfo}.
 */
final class DeepSemanticTypeResolver {
    private static final Map<String, String> DEFAULT_TYPES = Map.of(
            "List", "java.util.List",
            "Optional", "java.util.Optional",
            "Map", "java.util.Map",
            "Set", "java.util.Set"
    );
    private static final Set<String> NOISE_TYPES = Set.of(
            "java.lang.String",
            "String"
    );
    private final Map<String, String> simpleImports = new LinkedHashMap<>();
    private final List<String> wildcardImports = new ArrayList<>();
    private final String packageName;

    DeepSemanticTypeResolver(TestClassInfo classInfo) {
        if (classInfo != null) {
            this.packageName = extractPackage(classInfo.getClassName());
            for (String importLine : classInfo.getImports()) {
                registerImport(importLine);
            }
        } else {
            this.packageName = "";
        }
    }

    String resolve(Type type) {
        if (type == null) {
            return "";
        }
        if (type.isPrimitiveType()) {
            return type.asString();
        }
        if (type.isVoidType()) {
            return "void";
        }
        if (type.isArrayType()) {
            ArrayType arrayType = type.asArrayType();
            return resolve(arrayType.getComponentType()) + "[]";
        }
        if (type.isClassOrInterfaceType()) {
            return resolveClassOrInterface(type.asClassOrInterfaceType());
        }
        if (type.isIntersectionType()) {
            IntersectionType intersection = type.asIntersectionType();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < intersection.getElements().size(); i++) {
                if (i > 0) {
                    builder.append(" & ");
                }
                builder.append(resolve(intersection.getElements().get(i)));
            }
            return builder.toString();
        }
        if (type.isUnionType()) {
            UnionType union = type.asUnionType();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < union.getElements().size(); i++) {
                if (i > 0) {
                    builder.append(" | ");
                }
                builder.append(resolve(union.getElements().get(i)));
            }
            return builder.toString();
        }
        if (type.isWildcardType()) {
            WildcardType wildcard = type.asWildcardType();
            if (wildcard.getExtendedType().isPresent()) {
                return "? extends " + resolve(wildcard.getExtendedType().get());
            }
            if (wildcard.getSuperType().isPresent()) {
                return "? super " + resolve(wildcard.getSuperType().get());
            }
            return "?";
        }
        return type.asString();
    }

    String resolve(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "";
        }
        try {
            Type parsed = StaticJavaParser.parseType(rawType.trim());
            return resolve(parsed);
        } catch (ParseProblemException ignored) {
            return qualifyName(rawType);
        }
    }

    String resolveClassName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        if (candidate.contains(".")) {
            if (Character.isLowerCase(candidate.charAt(0))) {
                return candidate.trim();
            }
            return qualifyNested(candidate.trim());
        }
        String hit = simpleImports.get(candidate);
        if (hit != null) {
            return hit;
        }
        if (DEFAULT_TYPES.containsKey(candidate)) {
            return DEFAULT_TYPES.get(candidate);
        }
        if (!packageName.isBlank()) {
            return packageName + '.' + candidate;
        }
        return candidate;
    }

    String simpleName(String typeName) {
        String normalised = normalise(typeName);
        if (normalised.isEmpty()) {
            return "";
        }
        int genericIndex = normalised.indexOf('<');
        if (genericIndex > 0) {
            normalised = normalised.substring(0, genericIndex);
        }
        int lastDot = normalised.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < normalised.length()) {
            return normalised.substring(lastDot + 1);
        }
        return normalised;
    }

    String normalise(String typeName) {
        return typeName == null ? "" : typeName.trim();
    }

    String eraseGenerics(String typeName) {
        String normalised = normalise(typeName);
        int genericIndex = normalised.indexOf('<');
        if (genericIndex > 0) {
            return normalised.substring(0, genericIndex);
        }
        return normalised;
    }

    boolean isPrimitive(String typeName) {
        if (typeName == null) {
            return false;
        }
        String lower = typeName.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "byte", "short", "int", "long", "float", "double", "boolean", "char", "void" -> true;
            default -> false;
        };
    }

    boolean isNoise(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return true;
        }
        if (isPrimitive(typeName)) {
            return true;
        }
        return NOISE_TYPES.contains(typeName.trim());
    }

    boolean isCollectionType(String typeName) {
        String base = eraseGenerics(typeName);
        return base.equals("java.util.List") || base.equals("List");
    }

    boolean isOptionalType(String typeName) {
        String base = eraseGenerics(typeName);
        return base.equals("java.util.Optional") || base.equals("Optional");
    }

    String resolveLiteral(LiteralExpr literal) {
        if (literal == null) {
            return "";
        }
        if (literal.isStringLiteralExpr()) {
            return "java.lang.String";
        }
        if (literal.isBooleanLiteralExpr()) {
            return "boolean";
        }
        if (literal.isIntegerLiteralExpr()) {
            return literal.asIntegerLiteralExpr().getValue().endsWith("L") ? "long" : "int";
        }
        if (literal.isLongLiteralExpr()) {
            return "long";
        }
        if (literal.isDoubleLiteralExpr()) {
            return "double";
        }
        if (literal.isCharLiteralExpr()) {
            return "char";
        }
        if (literal.isNullLiteralExpr()) {
            return "";
        }
        return literal.toString();
    }

    private void registerImport(String importLine) {
        if (importLine == null) {
            return;
        }
        String trimmed = importLine.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith("import static")) {
            return;
        }
        if (!trimmed.startsWith("import")) {
            return;
        }
        trimmed = trimmed.substring("import".length()).trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.endsWith(".*")) {
            String pkg = trimmed.substring(0, trimmed.length() - 2).trim();
            if (!pkg.isEmpty()) {
                wildcardImports.add(pkg);
            }
            return;
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot < 0 || lastDot + 1 >= trimmed.length()) {
            return;
        }
        String simple = trimmed.substring(lastDot + 1);
        simpleImports.put(simple, trimmed);
    }

    private String extractPackage(String className) {
        if (className == null || className.isBlank()) {
            return "";
        }
        String trimmed = className.trim();
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot > 0) {
            return trimmed.substring(0, lastDot);
        }
        return "";
    }

    private String resolveClassOrInterface(ClassOrInterfaceType type) {
        String nameWithScope = type.getNameWithScope();
        String qualified = qualifyName(nameWithScope);
        NodeList<Type> arguments = type.getTypeArguments().orElse(null);
        if (arguments == null || arguments.isEmpty()) {
            return qualified;
        }
        List<String> argumentTypes = new ArrayList<>(arguments.size());
        for (Type argument : arguments) {
            argumentTypes.add(resolve(argument));
        }
        return qualified + "<" + String.join(", ", argumentTypes) + ">";
    }

    private String qualifyName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.contains(".")) {
            if (Character.isLowerCase(trimmed.charAt(0))) {
                return trimmed;
            }
            return qualifyNested(trimmed);
        }
        String hit = simpleImports.get(trimmed);
        if (hit != null) {
            return hit;
        }
        if (DEFAULT_TYPES.containsKey(trimmed)) {
            return DEFAULT_TYPES.get(trimmed);
        }
        if (!wildcardImports.isEmpty()) {
            for (String wildcard : wildcardImports) {
                if (!wildcard.isBlank()) {
                    return wildcard + '.' + trimmed;
                }
            }
        }
        if (!packageName.isBlank()) {
            return packageName + '.' + trimmed;
        }
        return trimmed;
    }

    private String qualifyNested(String qualified) {
        String[] parts = qualified.split("\\.");
        if (parts.length == 0) {
            return qualified;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                builder.append(qualifyName(part));
            } else {
                builder.append('.').append(part);
            }
        }
        return builder.toString();
    }
}
