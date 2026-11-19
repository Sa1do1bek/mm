package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.enums.InventoryRoundingMode;
import com.enterprise.mm.enums.PeriodCloseMode;
import com.enterprise.mm.enums.RevaluationPostingMode;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.InventoryAccountingSettingsMapper;
import com.enterprise.mm.payload.InventoryAccountingSettingsOptionsResponse;
import com.enterprise.mm.payload.InventoryAccountingSettingsResponse;
import com.enterprise.mm.payload.InventoryAccountingSettingsUpdateRequest;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryAccountingSettingsService {
    private final InventoryAccountingSettingsRepository repository;
    private final InventoryAccountingSettingsMapper mapper;
    private final AuditService auditService;
    private final StockAccountResolver stockAccountResolver;

    @Transactional(readOnly = true)
    public InventoryAccountingSettingsResponse getSettings() {
        return mapper.toResponse(findOrDefault());
    }

    public InventoryAccountingSettingsOptionsResponse getOptions() {
        return new InventoryAccountingSettingsOptionsResponse(
            java.util.List.of(
                new InventoryAccountingSettingsOptionsResponse.Option(InventoryRoundingMode.HALF_UP.name(), "Standard rounding", "1.235 -> 1.24", true),
                new InventoryAccountingSettingsOptionsResponse.Option(InventoryRoundingMode.HALF_EVEN.name(), "Banker's rounding to nearest even digit", "1.235 -> 1.24, 1.225 -> 1.22", true),
                new InventoryAccountingSettingsOptionsResponse.Option(InventoryRoundingMode.DOWN.name(), "Truncate toward zero", "1.239 -> 1.23", true),
                new InventoryAccountingSettingsOptionsResponse.Option(InventoryRoundingMode.UP.name(), "Round away from zero", "1.231 -> 1.24", true)
            ),
            java.util.List.of(
                new InventoryAccountingSettingsOptionsResponse.Option(PeriodCloseMode.CURRENT_BALANCE.name(), "Use current stock balance at close posting time", "Implemented current balance close", true),
                new InventoryAccountingSettingsOptionsResponse.Option(PeriodCloseMode.AS_OF_PERIOD_END.name(), "Recalculate stock as of the period end date", "Planned as-of close", false)
            )
        );
    }

    @Transactional
    public InventoryAccountingSettingsResponse saveSettings(InventoryAccountingSettingsUpdateRequest request) {
        InventoryAccountingSettings settings = repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseGet(this::defaultSettings);
        apply(settings, request);
        InventoryAccountingSettings saved = repository.save(settings);
        auditService.log("InventoryAccountingSettings", saved.getId(), "UPDATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public InventoryAccountingSettings findOrDefault() {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseGet(this::defaultSettings);
    }

    private InventoryAccountingSettings defaultSettings() {
        InventoryAccountingSettings settings = new InventoryAccountingSettings();
        settings.setCode("INV-ACCOUNTING-SETTINGS");
        settings.setName("Inventory accounting settings");
        settings.setStatus("ACTIVE");
        settings.setCompanyId(CompanyContext.currentCompany());
        settings.setDefaultValuationMethod(ValuationMethod.ACTUAL_COST.name());
        settings.setPeriodCloseMode(PeriodCloseMode.CURRENT_BALANCE.name());
        settings.setBaseCurrencyCode("UZS");
        settings.setRoundingScale(4);
        settings.setRoundingMode(InventoryRoundingMode.HALF_UP.name());
        settings.setRevaluationPostingMode(RevaluationPostingMode.VALUATION_RECORD_ONLY.name());
        settings.setImpairmentPostingMode(RevaluationPostingMode.VALUATION_RECORD_ONLY.name());
        return settings;
    }

    private void apply(InventoryAccountingSettings settings, InventoryAccountingSettingsUpdateRequest request) {
        if (request == null) {
            return;
        }
        settings.setCode(firstNonBlank(request.getCode(), settings.getCode()));
        settings.setName(firstNonBlank(request.getName(), settings.getName()));
        settings.setStatus(firstNonBlank(request.getStatus(), "ACTIVE"));
        settings.setDescription(request.getDescription());
        settings.setDefaultValuationMethod(parseEnum(ValuationMethod.class, request.getDefaultValuationMethod(), ValuationMethod.ACTUAL_COST).name());
        settings.setDefaultStockAccountId(request.getDefaultStockAccountId());
        settings.setDefaultStockAccount(request.getDefaultStockAccountId() == null ? null
            : stockAccountResolver.requireEligibleOwnedAccount(request.getDefaultStockAccountId()));
        settings.setReturnablePackagingStockAccountId(request.getReturnablePackagingStockAccountId());
        settings.setReturnablePackagingStockAccount(request.getReturnablePackagingStockAccountId() == null ? null
            : stockAccountResolver.requireEligibleOwnedAccount(request.getReturnablePackagingStockAccountId()));
        settings.setAllowWarehouseValuationOverride(request.isAllowWarehouseValuationOverride());
        settings.setAllowNegativeStock(request.isAllowNegativeStock());
        settings.setAllowZeroCostReceipt(request.isAllowZeroCostReceipt());
        settings.setPeriodCloseMode(parseEnum(PeriodCloseMode.class, request.getPeriodCloseMode(), PeriodCloseMode.CURRENT_BALANCE).name());
        settings.setBaseCurrencyCode(normalizeCurrency(request.getBaseCurrencyCode()));
        int scale = request.getRoundingScale() == null ? 4 : request.getRoundingScale();
        if (scale < 0 || scale > 8) {
            throw new BusinessException("roundingScale must be between 0 and 8");
        }
        settings.setRoundingScale(scale);
        settings.setRoundingMode(parseEnum(InventoryRoundingMode.class, request.getRoundingMode(), InventoryRoundingMode.HALF_UP).name());
        settings.setRevaluationPostingMode(parseEnum(RevaluationPostingMode.class, request.getRevaluationPostingMode(), RevaluationPostingMode.VALUATION_RECORD_ONLY).name());
        settings.setImpairmentPostingMode(parseEnum(RevaluationPostingMode.class, request.getImpairmentPostingMode(), RevaluationPostingMode.VALUATION_RECORD_ONLY).name());
    }

    private String normalizeCurrency(String value) {
        String code = value == null || value.isBlank() ? "UZS" : value.trim().toUpperCase(Locale.ROOT);
        if (code.length() != 3) {
            throw new BusinessException("baseCurrencyCode must be a 3-letter ISO code");
        }
        return code;
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unsupported " + type.getSimpleName() + ": " + value);
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? Objects.requireNonNullElse(fallback, "Inventory accounting settings") : value.trim();
    }
}
