package com.enterprise.mm.service;

import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProcurementLineValidationService {
    public static final String GOODS_PO_ITEM_REQUIRES_MATERIAL = "Goods purchase order item requires material";

    public ProcurementLineType normalize(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return ProcurementLineType.MATERIAL;
        }
        try {
            return ProcurementLineType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unsupported procurement line type: " + rawType);
        }
    }

    public void validateLine(
        String rawType,
        UUID materialId,
        String serviceDescription,
        BigDecimal quantity,
        String fieldPrefix
    ) {
        ProcurementLineType lineType = normalize(rawType);
        if (quantity != null && quantity.signum() < 0) {
            throw new BusinessException(fieldPrefix + ".quantity cannot be negative");
        }
        if (lineType == ProcurementLineType.MATERIAL) {
            if (materialId == null) {
                throw new BusinessException(fieldPrefix + ".materialId is required for MATERIAL line");
            }
            return;
        }
        if (serviceDescription == null || serviceDescription.isBlank()) {
            throw new BusinessException(fieldPrefix + ".serviceDescription is required for SERVICE line");
        }
        if (materialId != null) {
            throw new BusinessException(fieldPrefix + ".materialId must be empty for SERVICE line");
        }
    }
}
