package com.enterprise.mm.service;

import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.error.BusinessException;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ProcurementMethodPolicy {
    public String normalize(String value) {
        String normalized = normalizeToken(value);
        normalized = switch (normalized) {
            case "SINGLE_SUPPLIER" -> ProcurementMethodType.SINGLE_SOURCE.name();
            case "COMPETITIVE_TENDER" -> ProcurementMethodType.OPEN_TENDER.name();
            case "SPOT_BUY" -> ProcurementMethodType.DIRECT_PURCHASE.name();
            default -> normalized;
        };
        try {
            return ProcurementMethodType.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Unsupported procurement method: " + value);
        }
    }

    public ProcurementMethodType require(String value) {
        return ProcurementMethodType.valueOf(normalize(value));
    }

    private String normalizeToken(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Procurement method is required");
        }
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
