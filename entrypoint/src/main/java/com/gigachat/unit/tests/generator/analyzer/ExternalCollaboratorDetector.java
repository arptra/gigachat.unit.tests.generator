package com.gigachat.unit.tests.generator.analyzer;

import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;
import com.testagent.entrypoint.pipeline.helpers.analyze.InvocationInfo;
import com.testagent.entrypoint.pipeline.helpers.analyze.MethodAnalysisResult;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Detects whether a class declares collaborators that should be mocked.
 */
public class ExternalCollaboratorDetector {
    private static final String EXTERNAL_TYPE_SUFFIX_PATTERN =
            ".*(Service|Repository|Client|Component|Manager|Controller)$";
    private static final String CORE_MODEL_PATTERN =
            ".*(\\.model|\\.util)(\\.|$)";

    public boolean hasExternalCollaborators(ClassMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        return metadata.getFields().stream()
                .anyMatch(this::isExternalField);
    }

    public Optional<FieldMetadata> findFirstExternalCollaborator(ClassMetadata metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        return metadata.getFields().stream()
                .filter(this::isExternalField)
                .findFirst();
    }

    public boolean methodInvokesExternalCollaborator(MethodAnalysisResult analysis, ClassMetadata metadata) {
        if (analysis == null || metadata == null) {
            return false;
        }
        Set<String> collaboratorNames = new LinkedHashSet<>();
        for (FieldMetadata field : metadata.getFields()) {
            if (!isExternalField(field)) {
                continue;
            }
            String name = normalise(field.getName());
            if (!name.isEmpty()) {
                collaboratorNames.add(name);
            }
        }
        if (collaboratorNames.isEmpty()) {
            return false;
        }
        for (InvocationInfo invocation : analysis.invocations()) {
            if (invocation == null) {
                continue;
            }
            String target = normaliseInvocationTarget(invocation.target());
            if (target.isEmpty()) {
                continue;
            }
            if (collaboratorNames.contains(target)) {
                return true;
            }
            for (String collaborator : collaboratorNames) {
                if (target.startsWith(collaborator + '.')
                        || target.equals("this." + collaborator)
                        || target.startsWith("this." + collaborator + '.')) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExternalField(FieldMetadata field) {
        if (field == null) {
            return false;
        }
        String typeName = normalise(field.getTypeName());
        if (typeName.isEmpty()) {
            return false;
        }
        String lower = typeName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("java.")) {
            return false;
        }
        if (lower.matches(CORE_MODEL_PATTERN)) {
            return false;
        }
        if (matchesKnownPackage(lower)) {
            return true;
        }
        String simple = simpleName(typeName);
        return simple.matches(EXTERNAL_TYPE_SUFFIX_PATTERN);
    }

    private boolean matchesKnownPackage(String lowerCaseType) {
        if (lowerCaseType == null || lowerCaseType.isBlank()) {
            return false;
        }
        for (String hint : new String[]{".service", ".repository", ".gateway", ".client"}) {
            if (lowerCaseType.contains(hint + '.') || lowerCaseType.endsWith(hint)) {
                return true;
            }
        }
        return false;
    }

    private String simpleName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        int lastDot = type.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < type.length()) {
            return type.substring(lastDot + 1);
        }
        return type;
    }

    private String normaliseInvocationTarget(String target) {
        String text = normalise(target);
        if (text.startsWith("this.")) {
            return text.substring(5);
        }
        int dot = text.indexOf('.');
        if (dot > 0) {
            return text.substring(0, dot);
        }
        return text;
    }

    private String normalise(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }
}
