package com.gigachat.unit.tests.generator.analyzer;

import com.gigachat.unit.tests.generator.dto.ClassMetadata;
import com.gigachat.unit.tests.generator.dto.FieldMetadata;

import java.util.Optional;

/**
 * Detects whether a class declares collaborators that should be mocked.
 */
public class ExternalCollaboratorDetector {
    private static final String EXTERNAL_TYPE_SUFFIX_PATTERN = 
            ".*(Service|Repository|Client|Component|Manager|Controller)$";

    public boolean hasExternalCollaborators(ClassMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        return metadata.getFields().stream()
                .map(FieldMetadata::getTypeName)
                .map(this::normalise)
                .filter(name -> !name.isEmpty())
                .anyMatch(name -> name.matches(EXTERNAL_TYPE_SUFFIX_PATTERN));
    }

    public Optional<FieldMetadata> findFirstExternalCollaborator(ClassMetadata metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        return metadata.getFields().stream()
                .filter(field -> {
                    String typeName = normalise(field.getTypeName());
                    return !typeName.isEmpty() && typeName.matches(EXTERNAL_TYPE_SUFFIX_PATTERN);
                })
                .findFirst();
    }

    private String normalise(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }
}
