package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ChartOfAccount;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.ChartOfAccountRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StockAccountResolverTest {
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final InventoryAccountingSettingsRepository settings = mock(InventoryAccountingSettingsRepository.class);
    private final ChartOfAccountRepository accounts = mock(ChartOfAccountRepository.class);
    private final StockAccountResolver resolver = new StockAccountResolver(materials, settings, accounts, mock(AuditService.class));
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach void setup() {
        TenantContext.setTenant("account-resolver");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach void clear() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test void ordinaryMaterialsShareTheDefaultMaterialAccount() {
        UUID accountId = UUID.randomUUID();
        UUID firstMaterialId = material();
        UUID secondMaterialId = material();
        configure(accountId, null);
        account(accountId, "1060");

        assertThat(resolver.resolveForMaterial(firstMaterialId).accountCode()).isEqualTo("1060");
        assertThat(resolver.resolveForMaterial(secondMaterialId).accountCode()).isEqualTo("1060");
        assertThat(resolver.resolveForMaterial(firstMaterialId).source()).isEqualTo("COMPANY_DEFAULT");
        assertThat(resolver.resolveForMaterial(firstMaterialId).configuredMaterialGroupId()).isNull();
    }

    @Test void manualReceiptUsesTheAccountConfiguredOnTheSpecificMaterial() {
        UUID id = material();
        Material material = materials.findByTenantIdAndIdAndIsDeletedFalse("account-resolver", id).orElseThrow();
        UUID idOfAccount = UUID.randomUUID();
        material.setManualReceiptStockAccountId(idOfAccount);
        account(idOfAccount, "1030");
        assertThat(resolver.resolveManualReceiptAccount(id).accountCode()).isEqualTo("1030");
        assertThat(resolver.resolveManualReceiptAccount(id).source()).isEqualTo("MATERIAL");
    }

    @Test void manualReceiptDoesNotGuessAnAccountWhenMaterialIsUnconfigured() {
        UUID id = material();
        assertThatThrownBy(() -> resolver.resolveManualReceiptAccount(id))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("this material");
    }

    @Test void accountConfigurationIsPersistedOnTheChosenMaterial() {
        UUID id = UUID.randomUUID(), accountId = UUID.randomUUID();
        Material value = new Material(); value.setId(id); value.setCompanyId(companyId); value.setStatus("ACTIVE");
        when(materials.lockForStockBalanceCreation("account-resolver", companyId, id)).thenReturn(Optional.of(value));
        account(accountId, "1030");
        assertThat(resolver.configureManualReceiptAccount(id, accountId).accountCode()).isEqualTo("1030");
        assertThat(value.getManualReceiptStockAccountId()).isEqualTo(accountId);
        org.mockito.Mockito.verify(materials).save(value);
    }

    @Test void returnablePackagingUsesItsSeparateConfiguredAccount() {
        UUID defaultAccountId = UUID.randomUUID();
        UUID packagingAccountId = UUID.randomUUID();
        UUID ordinaryMaterialId = material();
        UUID packagingMaterialId = material(MaterialSubtype.RETURNABLE_PACKAGING);
        configure(defaultAccountId, packagingAccountId);
        account(defaultAccountId, "1010");
        account(packagingAccountId, "1060");

        assertThat(resolver.resolveForMaterial(ordinaryMaterialId).accountCode()).isEqualTo("1010");
        assertThat(resolver.resolveForMaterial(packagingMaterialId).accountCode()).isEqualTo("1060");
    }

    @Test void missingConfigurationIsOptionalForLegacyReceiptsButRequiredForAccountAwarePosting() {
        UUID materialId = material();
        when(settings.findByTenantIdAndCompanyIdAndIsDeletedFalse("account-resolver", companyId))
            .thenReturn(Optional.empty());

        assertThat(resolver.findForMaterial(materialId)).isEmpty();
        assertThatThrownBy(() -> resolver.resolveForMaterial(materialId))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("inventory accounting settings");
    }

    @Test void custodyAccountCannotBeConfiguredForCompanyOwnedStock() {
        UUID materialId = material();
        UUID accountId = UUID.randomUUID();
        configure(accountId, null);
        account(accountId, "002");

        assertThatThrownBy(() -> resolver.resolveForMaterial(materialId))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("custody account 002");
    }

    @Test void eligibleAccountListExcludesCustodyAndBlankCodes() {
        ChartOfAccount stock = rawAccount(UUID.randomUUID(), "1060");
        ChartOfAccount custody = rawAccount(UUID.randomUUID(), "002.1");
        ChartOfAccount blank = rawAccount(UUID.randomUUID(), " ");
        when(accounts.findByTenantIdAndCompanyIdAndStatusAndGroupFalseAndIsDeletedFalseOrderByAccountCodeAsc(
            "account-resolver", companyId, "ACTIVE")).thenReturn(List.of(custody, stock, blank));

        assertThat(resolver.eligibleAccounts())
            .extracting(StockAccountResolver.EligibleAccount::accountCode)
            .containsExactly("1060");
    }

    private UUID material() {
        return material(null);
    }

    private UUID material(MaterialSubtype subtype) {
        UUID id = UUID.randomUUID();
        Material value = new Material();
        value.setId(id);
        value.setCompanyId(companyId);
        value.setMaterialSubtype(subtype);
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("account-resolver", id))
            .thenReturn(Optional.of(value));
        return id;
    }

    private void configure(UUID accountId, UUID packagingAccountId) {
        InventoryAccountingSettings value = new InventoryAccountingSettings();
        value.setDefaultStockAccountId(accountId);
        value.setReturnablePackagingStockAccountId(packagingAccountId);
        when(settings.findByTenantIdAndCompanyIdAndIsDeletedFalse("account-resolver", companyId))
            .thenReturn(Optional.of(value));
    }

    private void account(UUID id, String code) {
        ChartOfAccount value = rawAccount(id, code);
        when(accounts.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(value));
    }

    private ChartOfAccount rawAccount(UUID id, String code) {
        ChartOfAccount value = new ChartOfAccount();
        value.setId(id);
        value.setTenantId("account-resolver");
        value.setCompanyId(companyId);
        value.setAccountCode(code);
        value.setName("Account " + code);
        value.setStatus("ACTIVE");
        return value;
    }
}
