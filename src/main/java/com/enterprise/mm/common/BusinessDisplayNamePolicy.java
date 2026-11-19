package com.enterprise.mm.common;

import com.enterprise.mm.util.BusinessNameGenerator;
import java.util.regex.Pattern;

public final class BusinessDisplayNamePolicy {
    private static final Pattern UUID = Pattern.compile(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Pattern GENERATED_CODE = Pattern.compile(
        "(?i)^[a-z][a-z0-9_]{2,}(?:-[a-z0-9_]+)*-[0-9a-f]{8}$"
    );

    private BusinessDisplayNamePolicy() {
    }

    public static String requireHumanName(String entityType, String name, String internalCode) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            return readableEntityType(entityType);
        }

        String code = internalCode == null ? "" : internalCode.trim();
        if ((!code.isEmpty() && normalized.equalsIgnoreCase(code)) || isInternalIdentifier(normalized)) {
            return readableEntityType(entityType);
        }
        return normalized;
    }

    public static String requireWorkflowDocumentName(
        String documentType,
        String name,
        String internalCode,
        String parentWorkflowName
    ) {
        String normalized = name == null ? "" : name.trim();
        if (isGeneratedName(documentType, normalized, internalCode)) {
            String parentBase = BusinessNameGenerator.workflowBaseName(parentWorkflowName, documentType);
            if (parentBase != null && !parentBase.isBlank()) {
                return BusinessNameGenerator.workflowDocumentName(documentType, parentWorkflowName);
            }
            return readableEntityType(documentType);
        }
        return normalized;
    }

    public static boolean isInternalIdentifier(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return UUID.matcher(normalized).matches() || GENERATED_CODE.matcher(normalized).matches();
    }

    private static String readableEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return "Record";
        }
        return entityType.trim().replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
    }

    private static boolean isGeneratedName(String entityType, String name, String internalCode) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String code = internalCode == null ? "" : internalCode.trim();
        if ((!code.isEmpty() && name.equalsIgnoreCase(code)) || isInternalIdentifier(name)) {
            return true;
        }
        return name.equalsIgnoreCase(readableEntityType(entityType));
    }
}
