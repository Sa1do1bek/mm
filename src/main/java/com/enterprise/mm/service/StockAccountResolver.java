package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ChartOfAccount;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.ChartOfAccountRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the company-owned stock account and returns the code to snapshot on a posting. */
@Service
@RequiredArgsConstructor
public class StockAccountResolver {
    private final MaterialRepository materials;
    private final InventoryAccountingSettingsRepository settings;
    private final ChartOfAccountRepository accounts;
    private final AuditService audit;

    public record Resolution(UUID accountId, String accountCode, String accountName,
        UUID configuredMaterialGroupId, String source) {}
    public record EligibleAccount(UUID id, String accountCode, String accountName) {}

    @Transactional(readOnly = true)
    public Resolution resolveForMaterial(UUID materialId) {
        Material material = material(materialId);
        return findConfiguredAccount(material).orElseThrow(() -> new BadRequestException(
            material.getMaterialSubtype() == MaterialSubtype.RETURNABLE_PACKAGING
                ? "Configure the returnable packaging stock account in inventory accounting settings"
                : "Configure the default material stock account in inventory accounting settings"));
    }

    @Transactional(readOnly = true)
    public Optional<Resolution> findForMaterial(UUID materialId) {
        return findConfiguredAccount(material(materialId));
    }

    /** Manual receipts use only the account explicitly configured for this material. */
    @Transactional(readOnly = true)
    public Resolution resolveManualReceiptAccount(UUID materialId) {
        return findManualReceiptAccount(materialId).orElseThrow(() ->
            new BadRequestException("Configure a stock account for this material before recording a manual receipt"));
    }

    @Transactional(readOnly = true)
    public Optional<Resolution> findManualReceiptAccount(UUID materialId) {
        Material material = material(materialId);
        if (material.getManualReceiptStockAccountId() == null)
            return Optional.empty();
        ChartOfAccount account = eligibleAccount(material.getManualReceiptStockAccountId());
        return Optional.of(new Resolution(account.getId(), account.getAccountCode().trim(), account.getName(), null, "MATERIAL"));
    }

    @Transactional
    public Resolution configureManualReceiptAccount(UUID materialId, UUID accountId) {
        Material material = materials.lockForStockBalanceCreation(TenantContext.currentTenant(), CompanyContext.currentCompany(), materialId)
            .orElseThrow(() -> new NotFoundException("Material not found"));
        if (material.getMaterialType() != MaterialType.TMB || !"ACTIVE".equals(material.getStatus()))
            throw new BadRequestException("Select an active inventory material");
        ChartOfAccount account = eligibleAccount(accountId);
        UUID previous = material.getManualReceiptStockAccountId();
        material.setManualReceiptStockAccountId(account.getId());
        materials.save(material);
        audit.logInCurrentTransaction("Material", materialId, "UPDATE_STOCK_ACCOUNT", material.getCode(), previous, account.getId());
        return new Resolution(account.getId(), account.getAccountCode(), account.getName(), null, "MATERIAL");
    }

    private Material material(UUID materialId) {
        return materials.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new NotFoundException("Material not found"));
    }

    private Optional<Resolution> findConfiguredAccount(Material material) {
        var configured = settings.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElse(null);
        UUID accountId = configured == null ? null
            : material.getMaterialSubtype() == MaterialSubtype.RETURNABLE_PACKAGING
                ? configured.getReturnablePackagingStockAccountId()
                : configured.getDefaultStockAccountId();
        return accountId == null ? Optional.empty() : Optional.of(resolution(accountId));
    }

    @Transactional(readOnly = true)
    public List<EligibleAccount> eligibleAccounts() {
        return accounts.findByTenantIdAndCompanyIdAndStatusAndGroupFalseAndIsDeletedFalseOrderByAccountCodeAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), "ACTIVE").stream()
            .filter(account -> account.getAccountCode() != null)
            .filter(account -> !account.getAccountCode().trim().isEmpty())
            .filter(account -> !account.getAccountCode().trim().startsWith("002"))
            .map(account -> new EligibleAccount(account.getId(), account.getAccountCode().trim(), account.getName()))
            .toList();
    }

    @Transactional(readOnly = true)
    public ChartOfAccount requireEligibleOwnedAccount(UUID accountId) {
        return eligibleAccount(accountId);
    }

    private Resolution resolution(UUID accountId) {
        ChartOfAccount account = eligibleAccount(accountId);
        return new Resolution(account.getId(), account.getAccountCode(), account.getName(), null, "COMPANY_DEFAULT");
    }

    private ChartOfAccount eligibleAccount(UUID accountId) {
        ChartOfAccount account = accounts.findByIdAndIsDeletedFalse(accountId)
            .filter(value -> TenantContext.currentTenant().equals(value.getTenantId()))
            .filter(value -> Objects.equals(CompanyContext.currentCompany(), value.getCompanyId()))
            .orElseThrow(() -> new BadRequestException("Configured stock account is unavailable"));
        String code = account.getAccountCode() == null ? "" : account.getAccountCode().trim().toUpperCase();
        if (account.isGroup() || !"ACTIVE".equals(account.getStatus())) {
            throw new BadRequestException("Configured stock account must be active and postable");
        }
        if (code.isEmpty() || code.startsWith("002")) {
            throw new BadRequestException("Company-owned stock account cannot use custody account 002");
        }
        return account;
    }
}
