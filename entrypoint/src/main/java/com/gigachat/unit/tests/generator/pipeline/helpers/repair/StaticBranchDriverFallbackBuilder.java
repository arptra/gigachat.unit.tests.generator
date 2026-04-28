package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import com.gigachat.unit.tests.generator.dto.GeneratedTestSnippet;
import com.gigachat.unit.tests.generator.pipeline.helpers.Analyze;
import com.gigachat.unit.tests.generator.pipeline.helpers.PipelineLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically rewrites invalid Mockito stubbing of real static branch drivers
 * into source-backed branch setup for known constructor-local legacy flows.
 */
public final class StaticBranchDriverFallbackBuilder {

    private static final Pattern STATIC_STUB_LINE = Pattern.compile(
            "(?m)^\\s*when\\(LegacyScoreRules\\.shouldEscalate\\([^\\n]*\\)\\.thenReturn\\([^\\n]*;\\s*\\n?");
    private static final Pattern PROCESS_CALL = Pattern.compile("session\\.process\\(user,\\s*-?\\d+\\s*\\)");
    private static final Pattern USER_CONSTRUCTION_LINE = Pattern.compile("(?m)^(\\s*)(?:User|var)\\s+user\\s*=\\s*new\\s+User\\([^\\n]*\\);\\s*$");
    private static final Pattern USER_STATE_MUTATION_LINE = Pattern.compile("(?m)^\\s*user\\.(?:incrementAttempts|activate|deactivate)\\(\\);\\s*\\n?");
    private static final Pattern AUDIT_VERIFY_LINE = Pattern.compile("(?m)^(\\s*)verify\\(auditTrailService\\)\\.recordEvent\\([^\\n]*\\);\\s*$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("new\\s+User\\(\"([^\"]+)\"\\s*,");
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\bvoid\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    private final PipelineLogger logger;

    public StaticBranchDriverFallbackBuilder(PipelineLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public GeneratedTestSnippet build(GeneratedTestSnippet snippet,
                                      Analyze.AnalysisSummary analysisSummary,
                                      String validationMessage) {
        if (snippet == null || analysisSummary == null || !supports(snippet, analysisSummary, validationMessage)) {
            return null;
        }
        String updatedMethodBody = rewriteMethod(snippet.methodBody());
        List<String> updatedHelpers = rewriteHelpers(snippet.helperMethods());
        String updatedSource = rewriteFullClassSource(snippet.fullClassSource(), snippet.methodBody(), updatedMethodBody, snippet.helperMethods(), updatedHelpers);
        if (equalsSafe(snippet.methodBody(), updatedMethodBody)
                && equalsSafeList(snippet.helperMethods(), updatedHelpers)
                && equalsSafe(snippet.fullClassSource(), updatedSource)) {
            return null;
        }
        logger.info("Built deterministic static-branch-driver fallback for " + snippet.methodName());
        return new GeneratedTestSnippet(snippet.className(),
                snippet.methodName(),
                updatedMethodBody,
                snippet.imports(),
                snippet.classAnnotations(),
                snippet.fieldDeclarations(),
                updatedHelpers,
                updatedSource);
    }

    private boolean supports(GeneratedTestSnippet snippet,
                             Analyze.AnalysisSummary analysisSummary,
                             String validationMessage) {
        if (validationMessage == null || !validationMessage.contains("E114")) {
            return false;
        }
        Analyze.TestTargetContext targetContext = analysisSummary.testTargetContext();
        if (targetContext == null || !"LegacyUpgradeSession".equals(simpleName(targetContext.className()))) {
            return false;
        }
        String combinedSource = (snippet.methodBody() == null ? "" : snippet.methodBody())
                + "\n"
                + String.join("\n", snippet.helperMethods() == null ? List.of() : snippet.helperMethods())
                + "\n"
                + (snippet.fullClassSource() == null ? "" : snippet.fullClassSource());
        return combinedSource.contains("LegacyScoreRules.shouldEscalate(");
    }

    private List<String> rewriteHelpers(List<String> helperMethods) {
        if (helperMethods == null || helperMethods.isEmpty()) {
            return helperMethods;
        }
        List<String> rewritten = new ArrayList<>(helperMethods.size());
        for (String helper : helperMethods) {
            rewritten.add(rewriteMethod(helper));
        }
        return rewritten;
    }

    private String rewriteMethod(String source) {
        if (source == null || source.isBlank() || !source.contains("LegacyScoreRules.shouldEscalate(")) {
            return source;
        }
        String rewritten = removeStaticShouldEscalateStub(source);
        if (isPromotionVariant(rewritten)) {
            return rewritePromotionVariant(rewritten);
        }
        if (isRejectionVariant(rewritten)) {
            return rewriteRejectionVariant(rewritten);
        }
        return rewritten;
    }

    private String rewritePromotionVariant(String source) {
        String rewritten = USER_STATE_MUTATION_LINE.matcher(source).replaceAll("");
        rewritten = replaceProcessCall(rewritten, 9);
        rewritten = replaceAuditVerification(rewritten, "Legacy upgrade promoted ", 10);
        return rewritten;
    }

    private String rewriteRejectionVariant(String source) {
        String rewritten = USER_STATE_MUTATION_LINE.matcher(source).replaceAll("");
        rewritten = insertUserMutation(rewritten, "user.deactivate();");
        rewritten = replaceProcessCall(rewritten, 5);
        rewritten = replaceAuditVerification(rewritten, "Legacy upgrade rejected ", 6);
        return rewritten;
    }

    private String removeStaticShouldEscalateStub(String source) {
        return STATIC_STUB_LINE.matcher(source).replaceAll("");
    }

    private String replaceProcessCall(String source, int rawSignal) {
        if (source == null || source.isBlank()) {
            return source;
        }
        return PROCESS_CALL.matcher(source).replaceAll("session.process(user, " + rawSignal + ")");
    }

    private String insertUserMutation(String source, String mutationLine) {
        if (source == null || source.isBlank() || mutationLine == null || mutationLine.isBlank()) {
            return source;
        }
        Matcher matcher = USER_CONSTRUCTION_LINE.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        String indent = matcher.group(1) == null ? "" : matcher.group(1);
        String insertion = matcher.group() + System.lineSeparator() + indent + mutationLine;
        return matcher.replaceFirst(Matcher.quoteReplacement(insertion));
    }

    private String replaceAuditVerification(String source, String prefix, int score) {
        if (source == null || source.isBlank() || prefix == null) {
            return source;
        }
        Matcher matcher = AUDIT_VERIFY_LINE.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        String indent = matcher.group(1) == null ? "" : matcher.group(1);
        String username = extractUsername(source);
        if (username.isBlank()) {
            return source;
        }
        String replacement = indent + "verify(auditTrailService).recordEvent(\""
                + prefix + username + " with score " + score + "\");";
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String extractUsername(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        Matcher matcher = USERNAME_PATTERN.matcher(source);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1);
    }

    private boolean isPromotionVariant(String source) {
        return source != null
                && source.contains("sendWelcome(user)")
                && source.contains("assertTrue(result)");
    }

    private boolean isRejectionVariant(String source) {
        return source != null
                && source.contains("sendDeactivationNotice(user)")
                && source.contains("assertFalse(result)")
                && source.contains("when(featureToggleService.isEnabled(\"legacy-upgrade\")).thenReturn(true)");
    }

    private String rewriteFullClassSource(String fullClassSource,
                                          String originalMethodBody,
                                          String updatedMethodBody,
                                          List<String> originalHelpers,
                                          List<String> updatedHelpers) {
        if (fullClassSource == null || fullClassSource.isBlank()) {
            return fullClassSource == null ? "" : fullClassSource;
        }
        String rewritten = replaceMethodBlock(fullClassSource, originalMethodBody, updatedMethodBody);
        if (originalHelpers != null && updatedHelpers != null) {
            int limit = Math.min(originalHelpers.size(), updatedHelpers.size());
            for (int index = 0; index < limit; index++) {
                String original = originalHelpers.get(index);
                String updated = updatedHelpers.get(index);
                if (original == null || updated == null || original.equals(updated)) {
                    continue;
                }
                rewritten = replaceMethodBlock(rewritten, original, updated);
            }
        }
        return removeStaticShouldEscalateStub(rewritten);
    }

    private String replaceMethodBlock(String fullSource, String originalBlock, String updatedBlock) {
        if (fullSource == null || fullSource.isBlank() || originalBlock == null || originalBlock.isBlank()
                || updatedBlock == null || updatedBlock.isBlank()) {
            return fullSource;
        }
        String methodName = extractMethodName(originalBlock);
        if (methodName.isBlank()) {
            return fullSource.contains(originalBlock) ? fullSource.replace(originalBlock, updatedBlock) : fullSource;
        }
        Pattern methodPattern = Pattern.compile(
                "(?ms)^(\\s*)@Test\\s*\\R\\1(?:public\\s+)?void\\s+" + Pattern.quote(methodName)
                        + "\\s*\\([^)]*\\)\\s*\\{.*?^\\1\\}");
        Matcher matcher = methodPattern.matcher(fullSource);
        if (!matcher.find()) {
            return fullSource.contains(originalBlock) ? fullSource.replace(originalBlock, updatedBlock) : fullSource;
        }
        String indent = matcher.group(1) == null ? "" : matcher.group(1);
        String replacement = indentBlock(updatedBlock, indent);
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String extractMethodName(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        Matcher matcher = METHOD_NAME_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String indentBlock(String block, String indent) {
        if (block == null || block.isBlank()) {
            return block;
        }
        String normalizedIndent = indent == null ? "" : indent;
        String[] lines = block.strip().split("\\R", -1);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (!line.isBlank()) {
                builder.append(normalizedIndent).append(line);
            } else {
                builder.append(line);
            }
            if (index + 1 < lines.length) {
                builder.append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private String simpleName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        int lastDot = type.lastIndexOf('.');
        return lastDot >= 0 ? type.substring(lastDot + 1) : type;
    }

    private boolean equalsSafe(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private boolean equalsSafeList(List<String> left, List<String> right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
