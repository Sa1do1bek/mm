package com.enterprise.mm.supplier.governance.readiness;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SupplierEvidenceRequirementDefaults {
    public record Definition(
        String requirementCode,
        String documentType,
        boolean mandatory,
        boolean reusable,
        boolean validityRequired,
        String ownerRole,
        int slaDays,
        int displayOrder
    ) {
    }

    private static final List<Definition> COMMON = List.of(
        definition("REGISTRATION", true, false, "SUPPLIER_MANAGER", 5, 10),
        definition("TAX_STATUS", true, true, "COMPLIANCE_MANAGER", 5, 20),
        definition("BANK_CONFIRMATION", true, true, "FINANCE_MANAGER", 5, 30)
    );

    private static final Map<String, List<Definition>> BY_TYPE = Map.of(
        "GOODS", append(
            definition("QUALITY_CERTIFICATE", false, true, "QUALITY_MANAGER", 5, 40),
            definition("FINANCIAL_CAPABILITY", true, true, "FINANCE_MANAGER", 7, 50),
            definition("TECHNICAL_CAPABILITY", false, false, "SUPPLIER_MANAGER", 7, 60)
        ),
        "SERVICES", append(
            definition("SERVICE_LICENSE", true, true, "COMPLIANCE_MANAGER", 5, 40),
            definition("STAFF_CAPABILITY", false, false, "SUPPLIER_MANAGER", 7, 50),
            definition("INSURANCE", true, true, "RISK_MANAGER", 5, 60)
        ),
        "WORKS", append(
            definition("WORKS_LICENSE", true, true, "COMPLIANCE_MANAGER", 5, 40),
            definition("SAFETY_CERTIFICATE", true, true, "HSE_MANAGER", 5, 50),
            definition("EQUIPMENT_CAPABILITY", false, false, "SUPPLIER_MANAGER", 7, 60)
        )
    );

    private SupplierEvidenceRequirementDefaults() {
    }

    public static List<Definition> forType(String accreditationType) {
        String normalized = accreditationType == null
            ? ""
            : accreditationType.trim().toUpperCase(Locale.ROOT);
        return BY_TYPE.getOrDefault(normalized, List.of());
    }

    private static Definition definition(
        String code,
        boolean reusable,
        boolean validityRequired,
        String ownerRole,
        int slaDays,
        int displayOrder
    ) {
        return new Definition(
            code,
            code,
            true,
            reusable,
            validityRequired,
            ownerRole,
            slaDays,
            displayOrder
        );
    }

    private static List<Definition> append(Definition first, Definition second, Definition third) {
        return List.of(
            COMMON.get(0),
            COMMON.get(1),
            COMMON.get(2),
            first,
            second,
            third
        );
    }
}
