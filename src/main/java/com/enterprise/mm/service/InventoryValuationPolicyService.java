package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.enums.InventoryRoundingMode;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.RevaluationPostingMode;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.MaterialRepository;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryValuationPolicyService {
    private final InventoryAccountingSettingsService settingsService;
    private final MaterialRepository materialRepository;

    @Transactional(readOnly = true)
    public ValuationMethod resolveMethod(UUID materialId, UUID warehouseId) {
        InventoryAccountingSettings settings = settingsService.findOrDefault();
        if (settings.isAllowWarehouseValuationOverride() && materialId != null) {
            Material material = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
                .orElse(null);
            if (material != null && material.getValuationMethod() != null) {
                return material.getValuationMethod();
            }
        }
        return parseValuationMethod(settings.getDefaultValuationMethod());
    }

    @Transactional(readOnly = true)
    public boolean allowNegativeStock() {
        return settingsService.findOrDefault().isAllowNegativeStock();
    }

    @Transactional(readOnly = true)
    public boolean allowZeroCostReceipt() {
        return settingsService.findOrDefault().isAllowZeroCostReceipt();
    }

    @Transactional(readOnly = true)
    public RevaluationPostingMode impairmentPostingMode() {
        return postingMode(settingsService.findOrDefault().getImpairmentPostingMode());
    }

    @Transactional(readOnly = true)
    public RevaluationPostingMode revaluationPostingMode() {
        return postingMode(settingsService.findOrDefault().getRevaluationPostingMode());
    }

    @Transactional(readOnly = true)
    public int roundingScale() {
        return settingsService.findOrDefault().getRoundingScale();
    }

    @Transactional(readOnly = true)
    public RoundingMode roundingMode() {
        return parseRoundingMode(settingsService.findOrDefault().getRoundingMode());
    }

    private RevaluationPostingMode postingMode(String value) {
        try {
            return RevaluationPostingMode.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return RevaluationPostingMode.VALUATION_RECORD_ONLY;
        }
    }

    private ValuationMethod parseValuationMethod(String value) {
        try {
            return ValuationMethod.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (Exception ex) {
            throw new BusinessException("Unsupported valuation method: " + value);
        }
    }

    private RoundingMode parseRoundingMode(String value) {
        try {
            String normalized = value == null || value.isBlank() ? InventoryRoundingMode.HALF_UP.name() : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            return InventoryRoundingMode.valueOf(normalized).javaMode();
        } catch (Exception ex) {
            return RoundingMode.HALF_UP;
        }
    }
}
