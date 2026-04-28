package com.gigachat.unit.tests.generator.pipeline.helpers.repair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises repeated invalid User fixture shapes into the legal
 * {@code User(String, String)} constructor plus public state mutators.
 */
public final class UserConstructorStateNormalizer {

    private static final Pattern USER_ADD_STATE_CONSTRUCTOR = Pattern.compile(
            "^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\.add\\(new User\\(\"([^\"]+)\",\\s*\"([^\"]+)\",\\s*(true|false|\\d+)\\)\\);\\s*$");
    private static final Pattern USER_ADD_CHAINED_STATE_MUTATOR = Pattern.compile(
            "^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\.add\\(new User\\(\"([^\"]+)\",\\s*\"([^\"]+)\"\\)\\.(setActive|setLoginAttempts|incrementLoginAttempts|activate|deactivate|incrementAttempts)\\(([^)]*)\\)\\);\\s*$");
    private static final Pattern USER_DECLARATION_STATE_CONSTRUCTOR = Pattern.compile(
            "^(\\s*)((?:var|User))\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new User\\(\"([^\"]+)\",\\s*\"([^\"]+)\",\\s*(true|false|\\d+)\\);\\s*$");
    private static final Pattern USER_DECLARATION_CHAINED_STATE_MUTATOR = Pattern.compile(
            "^(\\s*)((?:var|User))\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new User\\(\"([^\"]+)\",\\s*\"([^\"]+)\"\\)\\.(setActive|setLoginAttempts|incrementLoginAttempts|activate|deactivate|incrementAttempts)\\(([^)]*)\\);\\s*$");
    private static final Pattern USER_DECLARATION_BASE_CONSTRUCTOR = Pattern.compile(
            "^(\\s*)((?:var|User))\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new User\\(\"([^\"]+)\",\\s*\"([^\"]+)\"\\);\\s*$");
    private static final Pattern USER_NO_ARG_DECLARATION = Pattern.compile(
            "^(\\s*)((?:var|User))\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new User\\(\\);\\s*$");
    private static final Pattern SET_USERNAME = Pattern.compile("^\\s*%s\\.setUsername\\(\"([^\"]+)\"\\);\\s*$");
    private static final Pattern SET_EMAIL = Pattern.compile("^\\s*%s\\.setEmail\\(\"([^\"]+)\"\\);\\s*$");
    private static final Pattern SET_ACTIVE = Pattern.compile("^\\s*%s\\.setActive\\((true|false)\\);\\s*$");
    private static final Pattern SET_LOGIN_ATTEMPTS = Pattern.compile("^\\s*%s\\.(?:setLoginAttempts|incrementLoginAttempts)\\((\\d+)\\);\\s*$");

    private UserConstructorStateNormalizer() {
    }

    public static String normalize(String source) {
        if (source == null || source.isBlank()) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        boolean changed = false;
        int generatedUserCounter = 1;
        Set<String> reservedNames = new LinkedHashSet<>();

        for (String line : lines) {
            Matcher declarationMatcher = USER_NO_ARG_DECLARATION.matcher(line);
            if (declarationMatcher.matches()) {
                reservedNames.add(declarationMatcher.group(3));
                continue;
            }
            Matcher assignmentMatcher = USER_DECLARATION_STATE_CONSTRUCTOR.matcher(line);
            if (assignmentMatcher.matches()) {
                reservedNames.add(assignmentMatcher.group(3));
            }
        }

        for (int index = 0; index < lines.size(); index++) {
            String currentLine = lines.get(index);

            Matcher addMatcher = USER_ADD_STATE_CONSTRUCTOR.matcher(currentLine);
            if (addMatcher.matches()) {
                String indent = addMatcher.group(1);
                String collectionVariable = addMatcher.group(2);
                String username = addMatcher.group(3);
                String email = addMatcher.group(4);
                String state = addMatcher.group(5);
                String variableName = nextGeneratedVariableName(reservedNames, generatedUserCounter++);
                List<String> replacement = buildStatefulUserLines(
                        indent,
                        "var " + variableName,
                        variableName,
                        username,
                        email,
                        state);
                replacement.add(indent + collectionVariable + ".add(" + variableName + ");");
                lines.subList(index, index + 1).clear();
                lines.addAll(index, replacement);
                index += replacement.size() - 1;
                reservedNames.add(variableName);
                changed = true;
                continue;
            }

            Matcher chainedAddMatcher = USER_ADD_CHAINED_STATE_MUTATOR.matcher(currentLine);
            if (chainedAddMatcher.matches()) {
                String indent = chainedAddMatcher.group(1);
                String collectionVariable = chainedAddMatcher.group(2);
                String username = chainedAddMatcher.group(3);
                String email = chainedAddMatcher.group(4);
                String state = stateFromChainedMutator(chainedAddMatcher.group(5), chainedAddMatcher.group(6));
                if (state == null) {
                    continue;
                }
                String variableName = nextGeneratedVariableName(reservedNames, generatedUserCounter++);
                List<String> replacement = buildStatefulUserLines(
                        indent,
                        "var " + variableName,
                        variableName,
                        username,
                        email,
                        state);
                replacement.add(indent + collectionVariable + ".add(" + variableName + ");");
                lines.subList(index, index + 1).clear();
                lines.addAll(index, replacement);
                index += replacement.size() - 1;
                reservedNames.add(variableName);
                changed = true;
                continue;
            }

            Matcher declarationMatcher = USER_DECLARATION_STATE_CONSTRUCTOR.matcher(currentLine);
            if (declarationMatcher.matches()) {
                String indent = declarationMatcher.group(1);
                String declarationType = declarationMatcher.group(2);
                String variableName = declarationMatcher.group(3);
                String username = declarationMatcher.group(4);
                String email = declarationMatcher.group(5);
                String state = declarationMatcher.group(6);
                List<String> replacement = buildStatefulUserLines(
                        indent,
                        declarationType + " " + variableName,
                        variableName,
                        username,
                        email,
                        state);
                lines.subList(index, index + 1).clear();
                lines.addAll(index, replacement);
                index += replacement.size() - 1;
                changed = true;
                continue;
            }

            Matcher chainedDeclarationMatcher = USER_DECLARATION_CHAINED_STATE_MUTATOR.matcher(currentLine);
            if (chainedDeclarationMatcher.matches()) {
                String indent = chainedDeclarationMatcher.group(1);
                String declarationType = chainedDeclarationMatcher.group(2);
                String variableName = chainedDeclarationMatcher.group(3);
                String username = chainedDeclarationMatcher.group(4);
                String email = chainedDeclarationMatcher.group(5);
                String state = stateFromChainedMutator(chainedDeclarationMatcher.group(6), chainedDeclarationMatcher.group(7));
                if (state == null) {
                    continue;
                }
                List<String> replacement = buildStatefulUserLines(
                        indent,
                        declarationType + " " + variableName,
                        variableName,
                        username,
                        email,
                        state);
                lines.subList(index, index + 1).clear();
                lines.addAll(index, replacement);
                index += replacement.size() - 1;
                changed = true;
                continue;
            }

            Matcher baseDeclarationMatcher = USER_DECLARATION_BASE_CONSTRUCTOR.matcher(currentLine);
            if (baseDeclarationMatcher.matches()) {
                String indent = baseDeclarationMatcher.group(1);
                String declarationType = baseDeclarationMatcher.group(2);
                String variableName = baseDeclarationMatcher.group(3);
                String username = baseDeclarationMatcher.group(4);
                String email = baseDeclarationMatcher.group(5);
                UserStateCapture capture = captureState(lines, index, variableName);
                if (!capture.hasStateMutation()) {
                    continue;
                }
                List<String> replacement = buildStatefulUserLines(
                        indent,
                        declarationType + " " + variableName,
                        variableName,
                        capture.username() == null || capture.username().isBlank() ? username : capture.username(),
                        capture.email() == null || capture.email().isBlank() ? email : capture.email(),
                        capture.renderedState());
                lines.subList(index, capture.lastConsumedLineIndex() + 1).clear();
                lines.addAll(index, replacement);
                index += replacement.size() - 1;
                changed = true;
                continue;
            }

            Matcher noArgMatcher = USER_NO_ARG_DECLARATION.matcher(currentLine);
            if (!noArgMatcher.matches()) {
                continue;
            }
            String indent = noArgMatcher.group(1);
            String declarationType = noArgMatcher.group(2);
            String variableName = noArgMatcher.group(3);
            UserStateCapture capture = captureState(lines, index, variableName);
            if (!capture.hasAnyState()) {
                continue;
            }
            String username = capture.username() == null || capture.username().isBlank()
                    ? titleCase(variableName)
                    : capture.username();
            String email = capture.email() == null || capture.email().isBlank()
                    ? defaultEmailFor(username)
                    : capture.email();
            List<String> replacement = buildStatefulUserLines(
                    indent,
                    declarationType + " " + variableName,
                    variableName,
                    username,
                    email,
                    capture.renderedState());
            lines.subList(index, capture.lastConsumedLineIndex() + 1).clear();
            lines.addAll(index, replacement);
            index += replacement.size() - 1;
            changed = true;
        }
        return changed ? String.join("\n", lines) : source;
    }

    private static UserStateCapture captureState(List<String> lines, int declarationIndex, String variableName) {
        String username = null;
        String email = null;
        Boolean active = null;
        Integer loginAttempts = null;
        int lastConsumed = declarationIndex;
        Pattern usernamePattern = Pattern.compile(String.format(Locale.ROOT, SET_USERNAME.pattern(), Pattern.quote(variableName)));
        Pattern emailPattern = Pattern.compile(String.format(Locale.ROOT, SET_EMAIL.pattern(), Pattern.quote(variableName)));
        Pattern activePattern = Pattern.compile(String.format(Locale.ROOT, SET_ACTIVE.pattern(), Pattern.quote(variableName)));
        Pattern loginAttemptsPattern = Pattern.compile(String.format(Locale.ROOT, SET_LOGIN_ATTEMPTS.pattern(), Pattern.quote(variableName)));
        for (int index = declarationIndex + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher usernameMatcher = usernamePattern.matcher(line);
            if (usernameMatcher.matches()) {
                username = usernameMatcher.group(1);
                lastConsumed = index;
                continue;
            }
            Matcher emailMatcher = emailPattern.matcher(line);
            if (emailMatcher.matches()) {
                email = emailMatcher.group(1);
                lastConsumed = index;
                continue;
            }
            Matcher activeMatcher = activePattern.matcher(line);
            if (activeMatcher.matches()) {
                active = Boolean.parseBoolean(activeMatcher.group(1));
                lastConsumed = index;
                continue;
            }
            Matcher loginAttemptsMatcher = loginAttemptsPattern.matcher(line);
            if (loginAttemptsMatcher.matches()) {
                loginAttempts = Integer.parseInt(loginAttemptsMatcher.group(1));
                lastConsumed = index;
                continue;
            }
            break;
        }
        return new UserStateCapture(username, email, active, loginAttempts, lastConsumed);
    }

    private static String stateFromChainedMutator(String methodName, String rawArgument) {
        if (methodName == null || methodName.isBlank()) {
            return null;
        }
        String argument = rawArgument == null ? "" : rawArgument.trim();
        return switch (methodName) {
            case "setActive" -> {
                if ("true".equalsIgnoreCase(argument) || "false".equalsIgnoreCase(argument)) {
                    yield argument.toLowerCase(Locale.ROOT);
                }
                yield null;
            }
            case "setLoginAttempts", "incrementLoginAttempts" -> isNonNegativeInteger(argument) ? argument : null;
            case "deactivate" -> argument.isBlank() ? "false" : null;
            case "activate" -> argument.isBlank() ? "true" : null;
            case "incrementAttempts" -> argument.isBlank() ? "1" : null;
            default -> null;
        };
    }

    private static boolean isNonNegativeInteger(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(text) >= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static List<String> buildStatefulUserLines(String indent,
                                                       String declaration,
                                                       String variableName,
                                                       String username,
                                                       String email,
                                                       String state) {
        List<String> replacement = new ArrayList<>();
        replacement.add(indent + declaration + " = new User(\"" + escape(username) + "\", \"" + escape(email) + "\");");
        appendStateLines(replacement, indent, variableName, state);
        return replacement;
    }

    private static void appendStateLines(List<String> replacement,
                                         String indent,
                                         String variableName,
                                         String state) {
        if (state == null || state.isBlank()) {
            return;
        }
        if ("false".equalsIgnoreCase(state)) {
            replacement.add(indent + variableName + ".deactivate();");
            return;
        }
        if ("true".equalsIgnoreCase(state)) {
            return;
        }
        int attempts;
        try {
            attempts = Integer.parseInt(state);
        } catch (NumberFormatException ignored) {
            return;
        }
        for (int index = 0; index < attempts; index++) {
            replacement.add(indent + variableName + ".incrementAttempts();");
        }
    }

    private static String nextGeneratedVariableName(Set<String> reservedNames, int counter) {
        int current = counter;
        String candidate = "generatedUser" + current;
        while (reservedNames.contains(candidate)) {
            current++;
            candidate = "generatedUser" + current;
        }
        return candidate;
    }

    private static String titleCase(String text) {
        if (text == null || text.isBlank()) {
            return "CoverageUser";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String defaultEmailFor(String username) {
        String base = username == null ? "coverage-user" : username
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", ".");
        String normalized = base.isBlank() ? "coverage-user" : base.replaceAll("^\\.+|\\.+$", "");
        return (normalized.isBlank() ? "coverage-user" : normalized) + "@example.com";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record UserStateCapture(String username,
                                    String email,
                                    Boolean active,
                                    Integer loginAttempts,
                                    int lastConsumedLineIndex) {

        private boolean hasAnyState() {
            return username != null || email != null || active != null || loginAttempts != null;
        }

        private boolean hasStateMutation() {
            return active != null || loginAttempts != null;
        }

        private String renderedState() {
            if (loginAttempts != null) {
                return Integer.toString(loginAttempts);
            }
            if (active != null) {
                return Boolean.toString(active);
            }
            return null;
        }
    }
}
