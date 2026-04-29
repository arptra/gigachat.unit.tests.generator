package com.gigachat.unit.tests.generator.reasoning.service;

import com.gigachat.unit.tests.generator.pipeline.helpers.repair.UserConstructorStateNormalizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies deterministic recipe operations to a generated test source.
 */
public class RecipeOperationApplier {

    private final String targetTestMethodName;

    public RecipeOperationApplier(String targetTestMethodName) {
        this.targetTestMethodName = targetTestMethodName;
    }

    public String apply(String source, Map<String, Object> operation) {
        if (source == null || source.isBlank() || operation == null || operation.isEmpty()) {
            return source;
        }
        String type = requireString(operation, "type");
        if (type == null || type.isBlank()) {
            return source;
        }
        return switch (type) {
            case "replace_string_literal_argument" -> replaceStringLiteralArgument(source,
                    requireString(operation, "mock"),
                    requireString(operation, "method"),
                    requireString(operation, "literal"),
                    requireString(operation, "returnLiteral"));
            case "rewrite_verify_block_with_prefixes" -> rewriteVerifyBlockWithPrefixes(source,
                    requireString(operation, "mock"),
                    requireString(operation, "method"),
                    toStringList(operation.get("prefixes")));
            case "align_verify_literals_to_prefixes" -> alignVerifyLiteralsToPrefixes(source,
                    requireString(operation, "mock"),
                    requireString(operation, "method"),
                    toStringList(operation.get("prefixes")));
            case "wrap_verify_argument_with_ref_eq" -> wrapVerifyArgumentWithRefEq(source,
                    requireString(operation, "mock"),
                    requireString(operation, "method"));
            case "wrap_act_with_static_void_mock" -> wrapActWithStaticVoidMock(source,
                    requireString(operation, "ownerClass"),
                    requireString(operation, "staticMethod"),
                    requireString(operation, "stringLiteral"),
                    requireString(operation, "sutMethod"));
            case "align_threshold_rejection_branch" -> alignThresholdRejectionBranch(source,
                    requireString(operation, "testMethodName"),
                    requireString(operation, "sutMethod"),
                    requireString(operation, "featureMock"),
                    requireString(operation, "featureName"),
                    requireString(operation, "numericArgumentValue"),
                    requireString(operation, "notificationMock"),
                    requireString(operation, "promotionMethod"),
                    requireString(operation, "rejectionMethod"),
                    requireString(operation, "auditMock"),
                    requireString(operation, "auditMethod"),
                    requireString(operation, "auditPrefix"));
            case "stabilize_temporal_now_assertion" -> stabilizeTemporalNowAssertion(source,
                    requireString(operation, "testMethodName"),
                    requireString(operation, "sutMethod"));
            case "ensure_minimum_rebound_attempts" -> ensureMinimumReboundAttempts(source,
                    requireString(operation, "testMethodName"),
                    requireString(operation, "sutMethod"),
                    requireString(operation, "userVariable"),
                    parseInt(operation.get("minimumAttempts"), 3));
            case "promote_ref_initializer_to_created_object" -> promoteRefInitializerToCreatedObject(source,
                    requireString(operation, "testMethodName"),
                    requireString(operation, "refVariable"),
                    requireString(operation, "refTypeExpression"),
                    requireString(operation, "objectTypeFqcn"));
            case "replace_ref_initializer_with_mock_fixture" -> replaceRefInitializerWithMockFixture(source,
                    requireString(operation, "testMethodName"),
                    requireString(operation, "refVariable"),
                    requireString(operation, "refTypeExpression"),
                    requireString(operation, "classIdLiteral"),
                    requireString(operation, "objectTypeFqcn"));
            case "collapse_consecutive_annotation" -> collapseConsecutiveAnnotation(source,
                    requireString(operation, "annotation"));
            case "insert_method_before_class_end" -> insertMethodBeforeClassEnd(source,
                    requireString(operation, "methodSource"));
            case "normalize_user_constructor_state_variants" -> UserConstructorStateNormalizer.normalize(source);
            default -> source;
        };
    }

    private String collapseConsecutiveAnnotation(String source, String annotation) {
        if (source == null || source.isBlank() || annotation == null || annotation.isBlank()) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        List<String> filtered = new ArrayList<>(lines.size());
        String trimmedAnnotation = annotation.trim();
        String previousKeptTrimmed = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals(trimmedAnnotation) && trimmed.equals(previousKeptTrimmed)) {
                continue;
            }
            filtered.add(line);
            previousKeptTrimmed = trimmed;
        }
        return String.join("\n", filtered);
    }

    private String promoteRefInitializerToCreatedObject(String source,
                                                        String testMethodName,
                                                        String refVariable,
                                                        String refTypeExpression,
                                                        String objectTypeFqcn) {
        if (source == null
                || source.isBlank()
                || refVariable == null
                || refVariable.isBlank()
                || refTypeExpression == null
                || refTypeExpression.isBlank()
                || objectTypeFqcn == null
                || objectTypeFqcn.isBlank()) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        String methodName = testMethodName == null || testMethodName.isBlank() ? targetTestMethodName : testMethodName;
        int methodStart = findMethodStart(lines, methodName);
        int methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : -1;
        if (methodStart < 0 || methodEnd < methodStart) {
            return source;
        }
        Pattern pattern = Pattern.compile("(\\b"
                + Pattern.quote(refVariable)
                + "\\s*=\\s*)new\\s+"
                + Pattern.quote(refTypeExpression)
                + "\\s*\\(\\s*\\)\\s*;");
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String replacement = matcher.replaceFirst(Matcher.quoteReplacement(
                    matcher.group(1) + "new " + refTypeExpression + "(new " + objectTypeFqcn + "());"));
            lines.set(index, replacement);
            return String.join("\n", lines);
        }
        return source;
    }

    private String replaceRefInitializerWithMockFixture(String source,
                                                        String testMethodName,
                                                        String refVariable,
                                                        String refTypeExpression,
                                                        String classIdLiteral,
                                                        String objectTypeFqcn) {
        if (source == null
                || source.isBlank()
                || refVariable == null
                || refVariable.isBlank()
                || refTypeExpression == null
                || refTypeExpression.isBlank()) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        String methodName = testMethodName == null || testMethodName.isBlank() ? targetTestMethodName : testMethodName;
        int methodStart = findMethodStart(lines, methodName);
        int methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : -1;
        if (methodStart < 0 || methodEnd < methodStart) {
            return source;
        }
        Pattern pattern = Pattern.compile("(?<prefix>\\b"
                + Pattern.quote(refVariable)
                + "\\s*=\\s*)new\\s+"
                + Pattern.quote(refTypeExpression)
                + "\\s*\\((?<args>[^;]*)\\)\\s*;");
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String constructorArgs = matcher.group("args") == null ? "" : matcher.group("args").trim();
            if (!constructorArgs.isBlank()
                    && !looksLikeVariableReference(constructorArgs)
                    && !looksLikeInlineObjectConstructor(constructorArgs, objectTypeFqcn)) {
                continue;
            }
            String replacement = matcher.replaceFirst(Matcher.quoteReplacement(
                    matcher.group("prefix") + "mock(" + refTypeExpression + ".class);"));
            lines.set(index, replacement);
            if (looksLikeVariableReference(constructorArgs)) {
                neutralizeObjectConstructor(lines, methodStart, methodEnd, constructorArgs, objectTypeFqcn);
            }
            String indent = leadingIndent(line);
            insertIfMissing(lines,
                    index + 1,
                    indent + "when(" + refVariable + ".isNull_booleanValue()).thenReturn(false);");
            insertIfMissing(lines,
                    index + 2,
                    indent + "when(" + refVariable + ".isCreated()).thenReturn(true);");
            String normalizedClassId = defaultClassIdLiteral(classIdLiteral, objectTypeFqcn);
            insertIfMissing(lines,
                    index + 3,
                    indent + "when(" + refVariable + ".getClassId()).thenReturn(new Varchar2(\""
                            + escapeJava(normalizedClassId)
                            + "\"));");
            return String.join("\n", lines);
        }
        return source;
    }

    private void neutralizeObjectConstructor(List<String> lines,
                                             int methodStart,
                                             int methodEnd,
                                             String variableName,
                                             String objectTypeFqcn) {
        if (lines == null || variableName == null || variableName.isBlank()) {
            return;
        }
        String objectSimpleName = simpleName(objectTypeFqcn);
        if (objectSimpleName.isBlank()) {
            return;
        }
        Pattern pattern = Pattern.compile("(?<indent>\\s*)(?:final\\s+)?(?<type>(?:[A-Za-z_][A-Za-z0-9_$.]*\\.)?"
                + Pattern.quote(objectSimpleName)
                + ")\\s+"
                + Pattern.quote(variableName)
                + "\\s*=\\s*new\\s+(?:[A-Za-z_][A-Za-z0-9_$.]*\\.)?"
                + Pattern.quote(objectSimpleName)
                + "\\s*\\(\\s*\\)\\s*;");
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            lines.set(index, matcher.group("indent") + matcher.group("type") + " " + variableName + " = null;");
            return;
        }
    }

    private boolean looksLikeVariableReference(String value) {
        return value != null && value.trim().matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private boolean looksLikeInlineObjectConstructor(String value, String objectTypeFqcn) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String objectSimpleName = simpleName(objectTypeFqcn);
        if (objectSimpleName.isBlank()) {
            return false;
        }
        return value.trim().matches("new\\s+(?:[A-Za-z_][A-Za-z0-9_$.]*\\.)?"
                + Pattern.quote(objectSimpleName)
                + "\\s*\\(\\s*\\)");
    }

    private String defaultClassIdLiteral(String classIdLiteral, String objectTypeFqcn) {
        if (classIdLiteral != null && !classIdLiteral.isBlank()) {
            return classIdLiteral;
        }
        String simpleName = simpleName(objectTypeFqcn);
        if (simpleName.isBlank()) {
            return "UNKNOWN";
        }
        return simpleName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .toUpperCase(Locale.ROOT);
    }

    private String simpleName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) {
            return "";
        }
        int separator = fqcn.lastIndexOf('.');
        return separator >= 0 ? fqcn.substring(separator + 1) : fqcn;
    }

    private String insertMethodBeforeClassEnd(String source, String methodSource) {
        if (source == null || source.isBlank() || methodSource == null || methodSource.isBlank()) {
            return source;
        }
        int lastBrace = source.lastIndexOf('}');
        if (lastBrace < 0) {
            return source;
        }
        String insertion = methodSource.stripTrailing();
        String prefix = source.substring(0, lastBrace).stripTrailing();
        return prefix + "\n\n" + insertion + "\n}\n";
    }

    private String replaceStringLiteralArgument(String source,
                                                String mock,
                                                String method,
                                                String literal,
                                                String returnLiteral) {
        if (source == null || mock == null || method == null || literal == null) {
            return source;
        }
        Pattern pattern = Pattern.compile("("
                + Pattern.quote(mock)
                + "\\s*\\.\\s*"
                + Pattern.quote(method)
                + "\\s*\\(\\s*\")[^\"]*(\"\\s*\\))");
        Matcher matcher = pattern.matcher(source);
        StringBuffer buffer = new StringBuffer();
        boolean replaced = false;
        while (matcher.find()) {
            String replacement = matcher.group(1) + escapeJava(literal) + matcher.group(2);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
            replaced = true;
        }
        matcher.appendTail(buffer);
        if (replaced) {
            return buffer.toString();
        }
        return ensureStubbedLiteralCall(buffer.toString(), mock, method, literal, returnLiteral);
    }

    private String alignVerifyLiteralsToPrefixes(String source,
                                                 String mock,
                                                 String method,
                                                 List<String> prefixes) {
        if (source == null || mock == null || method == null || prefixes == null || prefixes.isEmpty()) {
            return source;
        }
        Pattern pattern = Pattern.compile("(verify\\(\\s*"
                + Pattern.quote(mock)
                + "(?:\\s*,\\s*never\\(\\))?\\s*\\)\\s*\\.\\s*"
                + Pattern.quote(method)
                + "\\s*\\(\\s*)\"([^\"]*)\"(?:\\s*\\+[^;\\n]*)?(\\s*\\)\\s*;)");
        Matcher matcher = pattern.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String currentLiteral = matcher.group(2);
            String selectedPrefix = selectBestPrefix(currentLiteral, prefixes);
            if (selectedPrefix == null || selectedPrefix.isBlank()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String replacement = matcher.group(1)
                    + "startsWith(\""
                    + escapeJava(selectedPrefix)
                    + "\")"
                    + matcher.group(3);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String wrapActWithStaticVoidMock(String source,
                                             String ownerClass,
                                             String staticMethod,
                                             String stringLiteral,
                                             String sutMethod) {
        if (source == null
                || source.isBlank()
                || ownerClass == null
                || staticMethod == null
                || stringLiteral == null
                || sutMethod == null
                || sutMethod.isBlank()) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        int methodStart = findMethodStart(lines, targetTestMethodName);
        int methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : lines.size() - 1;
        if (methodStart < 0 || methodEnd < methodStart) {
            return source;
        }
        if (repairPrematureInjectMocksInitialization(lines, methodStart, methodEnd, sutMethod)) {
            methodStart = findMethodStart(lines, targetTestMethodName);
            methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : lines.size() - 1;
            if (methodStart < 0 || methodEnd < methodStart) {
                return source;
            }
        }
        String mockVariable = lowerCamel(ownerClass) + "Mock";
        String staticVerifyStatement = mockVariable + ".verify(() -> " + ownerClass + "." + staticMethod
                + "(\"" + escapeJava(stringLiteral) + "\"));";
        if (methodContainsStaticMock(lines, methodStart, methodEnd, ownerClass)) {
            int actIndex = findActLineIndex(lines, methodStart, methodEnd, sutMethod);
            if (actIndex >= 0 && !methodContainsText(lines, methodStart, methodEnd, staticVerifyStatement)) {
                lines.add(actIndex + 1, leadingIndent(lines.get(actIndex)) + staticVerifyStatement);
                methodEnd++;
            }
            removeStaleInstanceStaticVerifications(lines, methodStart, methodEnd, ownerClass, staticMethod);
            return String.join("\n", lines);
        }
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.contains("." + sutMethod + "(") || line.trim().startsWith("//")) {
                continue;
            }
            String indent = leadingIndent(line);
            String trimmed = line.trim();
            if (!trimmed.endsWith(";")) {
                continue;
            }
            String statement = trimmed.substring(0, trimmed.length() - 1).trim();
            int equalsIndex = statement.indexOf('=');
            List<String> replacement = new ArrayList<>();
            if (equalsIndex > 0) {
                String left = statement.substring(0, equalsIndex).trim();
                String call = statement.substring(equalsIndex + 1).trim();
                int lastSpace = left.lastIndexOf(' ');
                if (lastSpace <= 0) {
                    return source;
                }
                String type = left.substring(0, lastSpace).trim();
                String variableName = left.substring(lastSpace + 1).trim();
                if (type.isBlank() || variableName.isBlank() || "var".equals(type)) {
                    return source;
                }
                replacement.add(indent + type + " " + variableName + ";");
                replacement.add(indent + "try (MockedStatic<" + ownerClass + "> " + mockVariable
                        + " = mockStatic(" + ownerClass + ".class)) {");
                replacement.add(indent + "    " + mockVariable + ".when(() -> " + ownerClass + "." + staticMethod
                        + "(\"" + escapeJava(stringLiteral) + "\"))");
                replacement.add(indent + "            .thenAnswer(invocation -> null);");
                replacement.add(indent + "    " + variableName + " = " + call + ";");
                replacement.add(indent + "    " + staticVerifyStatement);
                replacement.add(indent + "}");
            } else {
                replacement.add(indent + "try (MockedStatic<" + ownerClass + "> " + mockVariable
                        + " = mockStatic(" + ownerClass + ".class)) {");
                replacement.add(indent + "    " + mockVariable + ".when(() -> " + ownerClass + "." + staticMethod
                        + "(\"" + escapeJava(stringLiteral) + "\"))");
                replacement.add(indent + "            .thenAnswer(invocation -> null);");
                replacement.add(indent + "    " + statement + ";");
                replacement.add(indent + "    " + staticVerifyStatement);
                replacement.add(indent + "}");
            }
            lines.subList(index, index + 1).clear();
            lines.addAll(index, replacement);
            removeStaleInstanceStaticVerifications(lines,
                    methodStart,
                    methodEnd + replacement.size() - 1,
                    ownerClass,
                    staticMethod);
            return String.join("\n", lines);
        }
        return source;
    }

    private boolean repairPrematureInjectMocksInitialization(List<String> lines,
                                                            int methodStart,
                                                            int methodEnd,
                                                            String sutMethod) {
        if (lines == null || lines.isEmpty() || sutMethod == null || sutMethod.isBlank()) {
            return false;
        }
        int actIndex = findActLineIndex(lines, methodStart, methodEnd, sutMethod);
        if (actIndex < 0) {
            return false;
        }
        String targetVariable = extractActTargetVariable(lines.get(actIndex), sutMethod);
        if (targetVariable.isBlank()) {
            return false;
        }
        Pattern fieldPattern = Pattern.compile("^(?<prefix>\\s*(?:(?:private|protected|public)\\s+)?(?:[\\w<>.$]+\\s+)+"
                + Pattern.quote(targetVariable)
                + "\\s*)=\\s*(?<initializer>.+;\\s*)$");
        int fieldIndex = -1;
        Matcher fieldMatcher = null;
        for (int index = 0; index < methodStart && index < lines.size(); index++) {
            Matcher matcher = fieldPattern.matcher(lines.get(index));
            if (!matcher.matches() || !matcher.group("initializer").contains("new ")) {
                continue;
            }
            int annotationIndex = previousNonBlankLine(lines, index - 1);
            if (annotationIndex < 0 || !lines.get(annotationIndex).trim().equals("@InjectMocks")) {
                continue;
            }
            fieldIndex = index;
            fieldMatcher = matcher;
            lines.remove(annotationIndex);
            if (annotationIndex < fieldIndex) {
                fieldIndex--;
            }
            break;
        }
        if (fieldIndex < 0 || fieldMatcher == null) {
            return false;
        }
        String assignmentLine = targetVariable + " = " + fieldMatcher.group("initializer").trim();
        lines.set(fieldIndex, fieldMatcher.group("prefix").stripTrailing() + ";");

        int updatedMethodStart = findMethodStart(lines, targetTestMethodName);
        int updatedMethodEnd = updatedMethodStart >= 0 ? findMethodEnd(lines, updatedMethodStart) : -1;
        if (updatedMethodStart < 0 || updatedMethodEnd < updatedMethodStart) {
            return true;
        }
        if (methodContainsText(lines, updatedMethodStart, updatedMethodEnd, assignmentLine)) {
            return true;
        }
        int updatedActIndex = findActLineIndex(lines, updatedMethodStart, updatedMethodEnd, sutMethod);
        if (updatedActIndex < 0) {
            return true;
        }
        lines.add(updatedActIndex, leadingIndent(lines.get(updatedActIndex)) + assignmentLine);
        return true;
    }

    private String extractActTargetVariable(String line, String sutMethod) {
        if (line == null || sutMethod == null || sutMethod.isBlank()) {
            return "";
        }
        Pattern pattern = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*"
                + Pattern.quote(sutMethod)
                + "\\s*\\(");
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : "";
    }

    private int previousNonBlankLine(List<String> lines, int startIndex) {
        if (lines == null) {
            return -1;
        }
        for (int index = Math.min(startIndex, lines.size() - 1); index >= 0; index--) {
            if (!lines.get(index).trim().isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private String ensureMinimumReboundAttempts(String source,
                                                String testMethodName,
                                                String sutMethod,
                                                String userVariable,
                                                int minimumAttempts) {
        if (source == null || source.isBlank() || sutMethod == null || sutMethod.isBlank() || minimumAttempts <= 0) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        String methodName = testMethodName == null || testMethodName.isBlank()
                ? targetTestMethodName
                : testMethodName;
        int methodStart = findMethodStart(lines, methodName);
        int methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : -1;
        if (methodStart < 0 || methodEnd < methodStart) {
            methodStart = findMethodContaining(lines, "." + sutMethod + "(");
            methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : -1;
        }
        if (methodStart < 0 || methodEnd < methodStart) {
            return source;
        }
        int actIndex = findActLineIndex(lines, methodStart, methodEnd, sutMethod);
        if (actIndex < 0) {
            return source;
        }
        String resolvedUserVariable = userVariable == null || userVariable.isBlank()
                ? extractSingleArgument(lines.get(actIndex), sutMethod)
                : userVariable;
        if (resolvedUserVariable == null || resolvedUserVariable.isBlank()) {
            return source;
        }
        int existingAttempts = countMethodCalls(lines,
                methodStart,
                methodEnd,
                resolvedUserVariable,
                "incrementAttempts");
        int missingAttempts = minimumAttempts - existingAttempts;
        if (missingAttempts <= 0) {
            return source;
        }
        String indent = leadingIndent(lines.get(actIndex));
        for (int index = 0; index < missingAttempts; index++) {
            lines.add(actIndex + index, indent + resolvedUserVariable + ".incrementAttempts();");
        }
        return String.join("\n", lines);
    }

    private boolean methodContainsStaticMock(List<String> lines, int methodStart, int methodEnd, String ownerClass) {
        if (lines == null || ownerClass == null || ownerClass.isBlank()) {
            return false;
        }
        for (int index = Math.max(0, methodStart); index <= methodEnd && index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.contains("MockedStatic<" + ownerClass + ">")
                    && line.contains("mockStatic(" + ownerClass + ".class)")) {
                return true;
            }
        }
        return false;
    }

    private void insertIfMissing(List<String> lines, int index, String statement) {
        if (lines == null || statement == null || statement.isBlank()) {
            return;
        }
        for (String line : lines) {
            if (statement.equals(line.trim()) || line.trim().equals(statement.trim())) {
                return;
            }
        }
        int boundedIndex = Math.max(0, Math.min(index, lines.size()));
        lines.add(boundedIndex, statement);
    }

    private boolean methodContainsText(List<String> lines, int methodStart, int methodEnd, String expected) {
        if (lines == null || expected == null || expected.isBlank()) {
            return false;
        }
        for (int index = Math.max(0, methodStart); index <= methodEnd && index < lines.size(); index++) {
            if (lines.get(index).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private void removeStaleInstanceStaticVerifications(List<String> lines,
                                                        int methodStart,
                                                        int methodEnd,
                                                        String ownerClass,
                                                        String staticMethod) {
        if (lines == null || ownerClass == null || ownerClass.isBlank() || staticMethod == null || staticMethod.isBlank()) {
            return;
        }
        String staleMockName = lowerCamel(ownerClass);
        for (int index = Math.min(methodEnd, lines.size() - 1); index >= Math.max(0, methodStart); index--) {
            String trimmed = lines.get(index).trim();
            if (trimmed.startsWith("verify(")
                    && trimmed.contains(staleMockName)
                    && trimmed.contains("." + staticMethod + "(")) {
                lines.remove(index);
            }
        }
    }

    private String alignThresholdRejectionBranch(String source,
                                                 String testMethodName,
                                                 String sutMethod,
                                                 String featureMock,
                                                 String featureName,
                                                 String numericArgumentValue,
                                                 String notificationMock,
                                                 String promotionMethod,
                                                 String rejectionMethod,
                                                 String auditMock,
                                                 String auditMethod,
                                                 String auditPrefix) {
        if (source == null || source.isBlank() || sutMethod == null || sutMethod.isBlank()) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        String methodName = testMethodName == null || testMethodName.isBlank()
                ? targetTestMethodName
                : testMethodName;
        int methodStart = findMethodStart(lines, methodName);
        int methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : -1;
        if (methodStart < 0 || methodEnd < methodStart) {
            methodStart = findMethodContaining(lines, rejectionMethod == null ? "rejected" : rejectionMethod);
            methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : -1;
        }
        if (methodStart < 0 || methodEnd < methodStart) {
            return source;
        }

        boolean actArgumentRewritten = false;
        String assignedVariable = "result";
        String rawSignalVariable = null;
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.contains("." + sutMethod + "(")) {
                continue;
            }
            String extracted = extractAssignedVariableName(line);
            if (extracted != null && !extracted.isBlank()) {
                assignedVariable = extracted;
            }
            String argument = extractSecondArgument(line, sutMethod);
            if (argument != null && argument.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                rawSignalVariable = argument;
            }
            String updated = rewriteSecondArgument(line, sutMethod, numericArgumentValue);
            if (!line.equals(updated)) {
                lines.set(index, updated);
                actArgumentRewritten = true;
            }
            break;
        }
        if (!actArgumentRewritten && rawSignalVariable != null) {
            actArgumentRewritten = rewriteIntegerInitializer(lines,
                    methodStart,
                    methodEnd,
                    rawSignalVariable,
                    numericArgumentValue);
        } else if (rawSignalVariable != null) {
            rewriteIntegerInitializer(lines, methodStart, methodEnd, rawSignalVariable, numericArgumentValue);
        }

        ensureFeatureToggle(lines, methodStart, methodEnd, featureMock, featureName, "true");
        rewriteBooleanAssertion(lines, methodStart, methodEnd, assignedVariable, false);
        rewriteNotificationVerification(lines,
                methodStart,
                methodEnd,
                notificationMock,
                promotionMethod,
                rejectionMethod);
        rewriteAuditVerification(lines,
                methodStart,
                methodEnd,
                auditMock,
                auditMethod,
                auditPrefix);
        removeNoInteractionsLine(lines, methodStart, methodEnd, notificationMock);
        return String.join("\n", lines);
    }

    private String stabilizeTemporalNowAssertion(String source,
                                                 String testMethodName,
                                                 String sutMethod) {
        if (source == null || source.isBlank() || sutMethod == null || sutMethod.isBlank()) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        String methodName = testMethodName == null || testMethodName.isBlank()
                ? targetTestMethodName
                : testMethodName;
        int methodStart = findMethodStart(lines, methodName);
        int methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : -1;
        if (methodStart < 0 || methodEnd < methodStart) {
            return source;
        }

        String beforeVariable = uniqueVariableName(lines, methodStart, methodEnd, "beforeAct");
        String afterVariable = uniqueVariableName(lines, methodStart, methodEnd, "afterAct");
        boolean assertionRewritten = false;
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            String updated = rewriteTemporalNowAssertionLine(lines.get(index), beforeVariable, afterVariable);
            if (!lines.get(index).equals(updated)) {
                lines.set(index, updated);
                assertionRewritten = true;
            }
        }
        if (!assertionRewritten) {
            return source;
        }

        int actIndex = findActLineIndex(lines, methodStart, methodEnd, sutMethod);
        if (actIndex < 0) {
            return source;
        }
        boolean hasBeforeWindow = methodDeclaresVariable(lines, methodStart, methodEnd, beforeVariable);
        boolean hasAfterWindow = methodDeclaresVariable(lines, methodStart, methodEnd, afterVariable);
        String indent = leadingIndent(lines.get(actIndex));
        if (!hasBeforeWindow) {
            lines.add(actIndex, indent + "Instant " + beforeVariable + " = Instant.now();");
            actIndex++;
            methodEnd++;
        }
        if (!hasAfterWindow) {
            lines.add(actIndex + 1, indent + "Instant " + afterVariable + " = Instant.now();");
        }
        return String.join("\n", lines);
    }

    private String rewriteTemporalNowAssertionLine(String line,
                                                   String beforeVariable,
                                                   String afterVariable) {
        if (line == null
                || beforeVariable == null
                || afterVariable == null
                || !line.contains("assertThat(")) {
            return line;
        }
        String lower = line.toLowerCase();
        if (!lower.contains("instant")
                && !lower.contains("time")
                && !lower.contains("date")
                && !lower.contains("login")) {
            return line;
        }
        String windowAssertion = ".isBetween(" + beforeVariable + ", " + afterVariable + ")";
        String updated = line.replaceFirst("\\.isBetween\\s*\\([^;]*\\)", Matcher.quoteReplacement(windowAssertion));
        if (!updated.equals(line)) {
            return updated;
        }
        return line.replaceFirst(
                "\\.is(?:EqualTo|After(?:OrEqualTo)?|Before(?:OrEqualTo)?)\\s*\\([^;]*\\)",
                Matcher.quoteReplacement(windowAssertion));
    }

    private String wrapVerifyArgumentWithRefEq(String source,
                                               String mock,
                                               String method) {
        if (source == null || source.isBlank() || mock == null || method == null) {
            return source;
        }
        Pattern eqMatcherPattern = Pattern.compile("(verify\\(\\s*"
                + Pattern.quote(mock)
                + "(?:\\s*,\\s*never\\(\\))?\\s*\\)\\s*\\.\\s*"
                + Pattern.quote(method)
                + "\\s*\\(\\s*)eq\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)(\\s*\\)\\s*;)");
        Matcher eqMatcher = eqMatcherPattern.matcher(source);
        StringBuffer eqBuffer = new StringBuffer();
        while (eqMatcher.find()) {
            String currentArgument = eqMatcher.group(2);
            String replacement = eqMatcher.group(1) + "refEq(" + currentArgument + ")" + eqMatcher.group(3);
            eqMatcher.appendReplacement(eqBuffer, Matcher.quoteReplacement(replacement));
        }
        eqMatcher.appendTail(eqBuffer);
        String updatedSource = eqBuffer.toString();

        Pattern pattern = Pattern.compile("(verify\\(\\s*"
                + Pattern.quote(mock)
                + "(?:\\s*,\\s*never\\(\\))?\\s*\\)\\s*\\.\\s*"
                + Pattern.quote(method)
                + "\\s*\\(\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*\\)\\s*;)");
        Matcher matcher = pattern.matcher(updatedSource);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String currentArgument = matcher.group(2);
            String replacement = matcher.group(1) + "refEq(" + currentArgument + ")" + matcher.group(3);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean rewriteIntegerInitializer(List<String> lines,
                                              int methodStart,
                                              int methodEnd,
                                              String variableName,
                                              String value) {
        if (lines == null || variableName == null || variableName.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile("(\\bint\\s+"
                + Pattern.quote(variableName)
                + "\\s*=\\s*)-?\\d+(\\s*;)");
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            Matcher matcher = pattern.matcher(lines.get(index));
            if (matcher.find()) {
                lines.set(index, matcher.replaceFirst("$1" + Matcher.quoteReplacement(value) + "$2"));
                return true;
            }
        }
        return false;
    }

    private void ensureFeatureToggle(List<String> lines,
                                     int methodStart,
                                     int methodEnd,
                                     String featureMock,
                                     String featureName,
                                     String returnLiteral) {
        if (lines == null
                || featureMock == null
                || featureMock.isBlank()
                || featureName == null
                || featureName.isBlank()
                || returnLiteral == null
                || returnLiteral.isBlank()) {
            return;
        }
        String expectedCall = "when(" + featureMock + ".isEnabled(\"" + escapeJava(featureName) + "\"))";
        Pattern existing = Pattern.compile("(when\\(\\s*"
                + Pattern.quote(featureMock)
                + "\\s*\\.\\s*isEnabled\\(\\s*\""
                + Pattern.quote(featureName)
                + "\"\\s*\\)\\s*\\)\\s*\\.\\s*thenReturn\\s*\\()\\s*(true|false)\\s*(\\)\\s*;)");
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            Matcher matcher = existing.matcher(lines.get(index));
            if (matcher.find()) {
                lines.set(index, matcher.replaceFirst("$1" + returnLiteral + "$3"));
                return;
            }
            if (lines.get(index).contains(expectedCall)) {
                return;
            }
        }
        int insertionIndex = findActCommentIndex(lines, methodStart, methodEnd);
        if (insertionIndex < 0) {
            insertionIndex = findActLineIndex(lines, methodStart, methodEnd, "");
        }
        if (insertionIndex < 0) {
            insertionIndex = methodEnd;
        }
        String indent = insertionIndex >= 0 && insertionIndex < lines.size()
                ? leadingIndent(lines.get(insertionIndex))
                : "        ";
        lines.add(insertionIndex,
                indent + "when(" + featureMock + ".isEnabled(\"" + escapeJava(featureName) + "\")).thenReturn("
                        + returnLiteral + ");");
    }

    private void rewriteBooleanAssertion(List<String> lines,
                                         int methodStart,
                                         int methodEnd,
                                         String variableName,
                                         boolean expected) {
        if (lines == null || variableName == null || variableName.isBlank()) {
            return;
        }
        String from = expected ? "assertFalse" : "assertTrue";
        String to = expected ? "assertTrue" : "assertFalse";
        Pattern pattern = Pattern.compile("\\b" + from + "\\s*\\(\\s*"
                + Pattern.quote(variableName)
                + "\\s*\\)\\s*;");
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            Matcher matcher = pattern.matcher(lines.get(index));
            if (matcher.find()) {
                lines.set(index, matcher.replaceFirst(to + "(" + variableName + ");"));
                return;
            }
        }
    }

    private void rewriteNotificationVerification(List<String> lines,
                                                 int methodStart,
                                                 int methodEnd,
                                                 String notificationMock,
                                                 String promotionMethod,
                                                 String rejectionMethod) {
        if (lines == null
                || notificationMock == null
                || notificationMock.isBlank()
                || promotionMethod == null
                || promotionMethod.isBlank()
                || rejectionMethod == null
                || rejectionMethod.isBlank()) {
            return;
        }
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.contains("verify(" + notificationMock + ")")
                    && line.contains("." + promotionMethod + "(")) {
                lines.set(index, line.replace("." + promotionMethod + "(", "." + rejectionMethod + "("));
                return;
            }
        }
    }

    private void rewriteAuditVerification(List<String> lines,
                                          int methodStart,
                                          int methodEnd,
                                          String auditMock,
                                          String auditMethod,
                                          String auditPrefix) {
        if (lines == null
                || auditMock == null
                || auditMock.isBlank()
                || auditMethod == null
                || auditMethod.isBlank()
                || auditPrefix == null
                || auditPrefix.isBlank()) {
            return;
        }
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            String line = lines.get(index);
            if (isVerifyLine(line, auditMock, auditMethod)) {
                String indent = leadingIndent(line);
                lines.set(index,
                        indent + "verify(" + auditMock + ")." + auditMethod + "(startsWith(\""
                                + escapeJava(auditPrefix) + "\"));");
                return;
            }
        }
    }

    private void removeNoInteractionsLine(List<String> lines,
                                          int methodStart,
                                          int methodEnd,
                                          String mock) {
        if (lines == null || mock == null || mock.isBlank()) {
            return;
        }
        for (int index = methodEnd; index >= methodStart && index < lines.size(); index--) {
            String trimmed = lines.get(index).trim();
            if (trimmed.startsWith("verifyNoInteractions(") && trimmed.contains(mock + ")")) {
                lines.remove(index);
            }
        }
    }

    private String rewriteVerifyBlockWithPrefixes(String source,
                                                  String mock,
                                                  String method,
                                                  List<String> prefixes) {
        if (source == null || source.isBlank() || mock == null || method == null || prefixes == null || prefixes.isEmpty()) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        int methodStart = findMethodStart(lines, targetTestMethodName);
        int methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : lines.size() - 1;
        int searchStart = methodStart >= 0 ? methodStart : 0;
        int searchEnd = methodStart >= 0 ? methodEnd : lines.size() - 1;
        for (int index = searchStart; index <= searchEnd && index < lines.size(); index++) {
            if (!isVerifyLine(lines.get(index), mock, method)) {
                continue;
            }
            int end = index;
            List<String> preservedNonRecordVerifyLines = new ArrayList<>();
            while (end + 1 < lines.size() && end + 1 <= searchEnd) {
                String nextLine = lines.get(end + 1);
                if (isVerifyLine(nextLine, mock, method)) {
                    end++;
                    continue;
                }
                if (isVerifyNoMoreInteractionsLine(nextLine, mock)) {
                    preservedNonRecordVerifyLines.add(nextLine);
                    end++;
                    continue;
                }
                if (isOtherVerifyLine(nextLine, mock)) {
                    preservedNonRecordVerifyLines.add(nextLine);
                    end++;
                    continue;
                }
                break;
            }
            String indent = leadingIndent(lines.get(index));
            List<String> replacement = new ArrayList<>();
            for (String prefix : prefixes) {
                replacement.add(indent
                        + "verify("
                        + mock
                        + ")."
                        + method
                        + "(startsWith(\""
                        + escapeJava(prefix)
                        + "\"));");
            }
            for (String preservedLine : preservedNonRecordVerifyLines) {
                replacement.add(preservedLine);
            }
            lines.subList(index, end + 1).clear();
            lines.addAll(index, replacement);
            return String.join("\n", lines);
        }
        return source;
    }

    private String ensureStubbedLiteralCall(String source,
                                            String mock,
                                            String method,
                                            String literal,
                                            String returnLiteral) {
        if (source == null
                || source.isBlank()
                || mock == null
                || method == null
                || literal == null
                || returnLiteral == null
                || returnLiteral.isBlank()) {
            return source;
        }
        String expectedCall = "when(" + mock + "." + method + "(\"" + escapeJava(literal) + "\"))";
        if (source.contains(expectedCall)) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        int methodStart = findMethodStart(lines, targetTestMethodName);
        int methodEnd = methodStart >= 0 ? findMethodEnd(lines, methodStart) : lines.size() - 1;
        if (methodStart < 0 || methodEnd < methodStart) {
            return source;
        }
        int insertionIndex = -1;
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            if (lines.get(index).contains("// Act")) {
                insertionIndex = index;
                break;
            }
        }
        if (insertionIndex < 0) {
            insertionIndex = methodStart + 1;
        }
        String indent = "        ";
        if (insertionIndex < lines.size()) {
            String line = lines.get(insertionIndex);
            if (!line.isBlank()) {
                indent = leadingIndent(line);
            }
        }
        lines.add(insertionIndex,
                indent + "when(" + mock + "." + method + "(\"" + escapeJava(literal) + "\")).thenReturn(" + returnLiteral + ");");
        return String.join("\n", lines);
    }

    private String selectBestPrefix(String literal, List<String> prefixes) {
        if (literal == null || prefixes == null || prefixes.isEmpty()) {
            return null;
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String prefix : prefixes) {
            int score = weightedTokenOverlap(literal, prefix);
            if (score > bestScore) {
                best = prefix;
                bestScore = score;
            }
        }
        return best;
    }

    private int weightedTokenOverlap(String left, String right) {
        if (left == null || right == null) {
            return Integer.MIN_VALUE;
        }
        List<String> leftTokens = tokenize(left);
        List<String> rightTokens = tokenize(right);
        int score = 0;
        for (String leftToken : leftTokens) {
            if (rightTokens.contains(leftToken)) {
                score += leftToken.length();
            }
        }
        return score;
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : value.toLowerCase().split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private List<String> toStringList(Object rawValue) {
        if (!(rawValue instanceof List<?> values)) {
            return List.of();
        }
        List<String> converted = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                converted.add(value.toString());
            }
        }
        return List.copyOf(converted);
    }

    private String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isVerifyLine(String line, String mock, String method) {
        if (line == null || mock == null || method == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("verify(")
                && trimmed.contains(mock)
                && trimmed.contains("." + method + "(");
    }

    private boolean isVerifyNoMoreInteractionsLine(String line, String mock) {
        if (line == null || mock == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("verifyNoMoreInteractions(")
                && trimmed.contains(mock + ")");
    }

    private boolean isOtherVerifyLine(String line, String mock) {
        if (line == null || mock == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("verify(")
                && trimmed.contains(mock)
                && !trimmed.contains(".recordEvent(");
    }

    private String extractAssignedVariableName(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher typedMatcher = Pattern.compile("\\b(?:boolean|Boolean|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=")
                .matcher(line);
        if (typedMatcher.find()) {
            return typedMatcher.group(1);
        }
        Matcher assignmentMatcher = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=").matcher(line);
        return assignmentMatcher.find() ? assignmentMatcher.group(1) : null;
    }

    private String extractSecondArgument(String line, String method) {
        if (line == null || method == null || method.isBlank()) {
            return null;
        }
        Pattern pattern = Pattern.compile("\\."
                + Pattern.quote(method)
                + "\\s*\\(\\s*[^,\\n]+,\\s*([^\\)]+?)\\s*\\)");
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractSingleArgument(String line, String method) {
        if (line == null || method == null || method.isBlank()) {
            return null;
        }
        Pattern pattern = Pattern.compile("\\."
                + Pattern.quote(method)
                + "\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private int countMethodCalls(List<String> lines,
                                 int methodStart,
                                 int methodEnd,
                                 String variableName,
                                 String methodName) {
        if (lines == null
                || variableName == null
                || variableName.isBlank()
                || methodName == null
                || methodName.isBlank()) {
            return 0;
        }
        Pattern pattern = Pattern.compile("\\b"
                + Pattern.quote(variableName)
                + "\\s*\\.\\s*"
                + Pattern.quote(methodName)
                + "\\s*\\(\\s*\\)");
        int count = 0;
        for (int index = Math.max(0, methodStart); index <= methodEnd && index < lines.size(); index++) {
            if (pattern.matcher(lines.get(index)).find()) {
                count++;
            }
        }
        return count;
    }

    private String rewriteSecondArgument(String line, String method, String value) {
        if (line == null || method == null || method.isBlank() || value == null || value.isBlank()) {
            return line;
        }
        Pattern pattern = Pattern.compile("(\\."
                + Pattern.quote(method)
                + "\\s*\\(\\s*[^,\\n]+,\\s*)([^\\)]+?)(\\s*\\))");
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        return matcher.replaceFirst("$1" + Matcher.quoteReplacement(value) + "$3");
    }

    private int findActCommentIndex(List<String> lines, int methodStart, int methodEnd) {
        if (lines == null) {
            return -1;
        }
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            if (lines.get(index).contains("// Act")) {
                return index;
            }
        }
        return -1;
    }

    private int findActLineIndex(List<String> lines, int methodStart, int methodEnd, String sutMethod) {
        if (lines == null || sutMethod == null || sutMethod.isBlank()) {
            return -1;
        }
        for (int index = methodStart; index <= methodEnd && index < lines.size(); index++) {
            if (lines.get(index).contains("." + sutMethod + "(")) {
                return index;
            }
        }
        return -1;
    }

    private String uniqueVariableName(List<String> lines, int methodStart, int methodEnd, String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return "value";
        }
        String candidate = baseName;
        int suffix = 2;
        while (methodContainsVariable(lines, methodStart, methodEnd, candidate)) {
            candidate = baseName + suffix++;
        }
        return candidate;
    }

    private boolean methodContainsVariable(List<String> lines, int methodStart, int methodEnd, String variableName) {
        if (lines == null || variableName == null || variableName.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(variableName) + "\\b");
        for (int index = Math.max(0, methodStart); index <= methodEnd && index < lines.size(); index++) {
            if (pattern.matcher(lines.get(index)).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean methodDeclaresVariable(List<String> lines, int methodStart, int methodEnd, String variableName) {
        if (lines == null || variableName == null || variableName.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile("\\b(?:final\\s+)?(?:var|Instant|java\\.time\\.Instant)\\s+"
                + Pattern.quote(variableName)
                + "\\b");
        for (int index = Math.max(0, methodStart); index <= methodEnd && index < lines.size(); index++) {
            if (pattern.matcher(lines.get(index)).find()) {
                return true;
            }
        }
        return false;
    }

    private int findMethodContaining(List<String> lines, String token) {
        if (lines == null || token == null || token.isBlank()) {
            return -1;
        }
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).contains(token)) {
                continue;
            }
            for (int candidate = index; candidate >= 0; candidate--) {
                String trimmed = lines.get(candidate).trim();
                if (trimmed.startsWith("void ")
                        || trimmed.startsWith("boolean ")
                        || lines.get(candidate).contains(" void ")
                        || lines.get(candidate).contains(" boolean ")) {
                    return candidate;
                }
            }
        }
        return -1;
    }

    private String leadingIndent(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return line.substring(0, index);
    }

    private String lowerCamel(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return "staticMock";
        }
        return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
    }

    private int findMethodStart(List<String> lines, String methodName) {
        if (lines == null || methodName == null || methodName.isBlank()) {
            return -1;
        }
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(methodName + "(")) {
                return index;
            }
        }
        return -1;
    }

    private int findMethodEnd(List<String> lines, int startIndex) {
        if (lines == null || startIndex < 0 || startIndex >= lines.size()) {
            return lines == null ? -1 : lines.size() - 1;
        }
        int depth = 0;
        boolean opened = false;
        for (int index = startIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            for (int charIndex = 0; charIndex < line.length(); charIndex++) {
                char current = line.charAt(charIndex);
                if (current == '{') {
                    depth++;
                    opened = true;
                } else if (current == '}') {
                    depth--;
                    if (opened && depth == 0) {
                        return index;
                    }
                }
            }
        }
        return lines.size() - 1;
    }

    private String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
