package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupplierPortalEligibilityValidatorTest {
    private static final String TENANT = "tenant-supplier-portal-eligibility";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000006601");

    private SupplierPortalAccountRepository accountRepository;
    private SupplierPortalEligibilityValidator validator;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        accountRepository = mock(SupplierPortalAccountRepository.class);
        validator = new SupplierPortalEligibilityValidator(accountRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void acceptsSupplierWithoutExistingPortalAccount() {
        Supplier supplier = supplier("sales@supplier.test");
        stubAccounts(supplier, List.of(), List.of());

        assertThat(validator.validate(supplier)).isEmpty();
    }

    @Test
    void rejectsInvalidSupplierEmail() {
        Supplier supplier = supplier("invalid-address");

        assertThat(validator.validate(supplier))
            .extracting(SupplierPortalEligibilityValidator.EligibilityIssue::code)
            .containsExactly(SupplierPortalEligibilityValidator.SUPPLIER_EMAIL_INVALID);
    }

    @Test
    void rejectsExistingAccountThatCannotReceiveInvitation() {
        Supplier supplier = supplier("sales@supplier.test");
        SupplierPortalAccount suspended = account(supplier.getId(), supplier.getEmail(), "SUSPENDED");
        stubAccounts(supplier, List.of(suspended), List.of(suspended));

        assertThat(validator.validate(supplier))
            .extracting(SupplierPortalEligibilityValidator.EligibilityIssue::code)
            .contains(SupplierPortalEligibilityValidator.PORTAL_EMAIL_CONFLICT);
    }

    @Test
    void rejectsEmailAlreadyOwnedByAnotherSupplier() {
        Supplier supplier = supplier("shared@supplier.test");
        SupplierPortalAccount otherSupplierAccount = account(UUID.randomUUID(), supplier.getEmail(), "ACTIVE");
        stubAccounts(supplier, List.of(), List.of(otherSupplierAccount));

        assertThat(validator.validate(supplier))
            .extracting(SupplierPortalEligibilityValidator.EligibilityIssue::code)
            .containsExactly(SupplierPortalEligibilityValidator.PORTAL_EMAIL_CONFLICT);
    }

    private void stubAccounts(
        Supplier supplier,
        List<SupplierPortalAccount> bySupplier,
        List<SupplierPortalAccount> byEmail
    ) {
        when(accountRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, supplier.getId()
        )).thenReturn(bySupplier);
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, supplier.getEmail()
        )).thenReturn(byEmail);
    }

    private Supplier supplier(String email) {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setEmail(email);
        return supplier;
    }

    private SupplierPortalAccount account(UUID supplierId, String email, String status) {
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setSupplierId(supplierId);
        account.setLoginEmail(email);
        account.setStatus(status);
        return account;
    }
}
