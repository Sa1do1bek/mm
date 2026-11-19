package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierLicense;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierLicenseMapper;
import com.enterprise.mm.payload.SupplierLicenseCreateRequest;
import com.enterprise.mm.payload.SupplierLicenseUpdateRequest;
import com.enterprise.mm.repository.SupplierLicenseRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.security.AccessScope;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierLicenseIntegrityTest {
    private static final String TENANT = "supplier-license-integrity";

    @Mock private SupplierLicenseRepository repository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private AuditService auditService;

    private UUID companyId;
    private UUID supplierId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
    }

    @Test
    void createDerivesLifecycleAndIgnoresForgedRevocationAndRenewalFacts() {
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.of(supplier(supplierId, companyId)));
        when(repository.save(any(SupplierLicense.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        SupplierLicenseCreateRequest request = request(supplierId, " LIC-100 ");
        request.setStatus("REVOKED");
        request.setRenewalRequired(true);
        request.setRevokedAt(LocalDate.now().minusDays(2));
        request.setRevocationReason("client-forged revocation");
        request.setValidTo(LocalDate.now().plusDays(90));

        var response = service().create(request);

        assertThat(response.getCompanyId()).isEqualTo(companyId);
        assertThat(response.getLicenseNumber()).isEqualTo("LIC-100");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.isRenewalRequired()).isFalse();
        assertThat(response.getRevokedAt()).isNull();
        assertThat(response.getRevocationReason()).isNull();
        verify(repository).findActiveByNormalizedLicenseNumber(
            TENANT, companyId, supplierId, "LIC-100");
    }

    @Test
    void createDerivesExpiredAndRenewalRequiredFromValidityDates() {
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.of(supplier(supplierId, companyId)));
        when(repository.save(any(SupplierLicense.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        SupplierLicenseCreateRequest request = request(supplierId, "LIC-EXPIRED");
        request.setValidTo(LocalDate.now().minusDays(1));

        var response = service().create(request);

        assertThat(response.getStatus()).isEqualTo("EXPIRED");
        assertThat(response.isRenewalRequired()).isTrue();
    }

    @Test
    void updatePreservesServerOwnedRevocationFactsAndRejectsClientLifecycleChanges() {
        UUID id = UUID.randomUUID();
        SupplierLicense license = license(id, companyId, supplierId, "LIC-REVOKED");
        license.setStatus("REVOKED");
        license.setRenewalRequired(false);
        license.setIssueDate(LocalDate.now().minusYears(1));
        license.setValidFrom(LocalDate.now().minusYears(1));
        license.setValidTo(LocalDate.now().plusYears(1));
        license.setRevokedAt(LocalDate.now().minusDays(30));
        license.setRevocationReason("Authority decision");
        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(license));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.of(supplier(supplierId, companyId)));
        when(repository.save(license)).thenReturn(license);

        SupplierLicenseUpdateRequest request = updateRequest(supplierId, "LIC-REVOKED");
        request.setName("Updated display name");
        request.setStatus("ACTIVE");
        request.setRenewalRequired(true);
        request.setRevokedAt(null);
        request.setRevocationReason("forged replacement");
        request.setIssueDate(license.getIssueDate());
        request.setValidFrom(license.getValidFrom());
        request.setValidTo(license.getValidTo());

        service().update(id, request);

        assertThat(license.getName()).isEqualTo("Updated display name");
        assertThat(license.getStatus()).isEqualTo("REVOKED");
        assertThat(license.isRenewalRequired()).isFalse();
        assertThat(license.getRevokedAt()).isEqualTo(LocalDate.now().minusDays(30));
        assertThat(license.getRevocationReason()).isEqualTo("Authority decision");
    }

    @Test
    void createRejectsSupplierOutsideCurrentCompanyEvenForGlobalAdmin() {
        enableGlobalAdminScope();
        UUID otherCompanyId = UUID.randomUUID();
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.of(supplier(supplierId, otherCompanyId)));

        assertThatThrownBy(() -> service().create(request(supplierId, "LIC-REMOTE")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(repository, never()).save(any());
    }

    @Test
    void updateCannotReassignLicenseToSupplierOutsideCurrentCompany() {
        enableGlobalAdminScope();
        UUID id = UUID.randomUUID();
        UUID remoteSupplierId = UUID.randomUUID();
        SupplierLicense license = license(id, companyId, supplierId, "LIC-LOCAL");
        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(license));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, remoteSupplierId))
            .thenReturn(Optional.of(supplier(remoteSupplierId, UUID.randomUUID())));

        assertThatThrownBy(() -> service().update(
                id, updateRequest(remoteSupplierId, "LIC-LOCAL")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(repository, never()).save(any());
    }

    @Test
    void crossCompanyWritesAreHiddenFromGlobalAdmin() {
        enableGlobalAdminScope();
        UUID id = UUID.randomUUID();
        SupplierLicense active = license(id, UUID.randomUUID(), supplierId, "LIC-REMOTE");
        SupplierLicense deleted = license(id, active.getCompanyId(), supplierId, "LIC-REMOTE");
        deleted.softDelete();
        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(active));
        when(repository.findAnyById(id)).thenReturn(Optional.of(deleted));

        assertNotFound(() -> service().update(id, updateRequest(supplierId, "LIC-REMOTE")));
        assertNotFound(() -> service().softDelete(id));
        assertNotFound(() -> service().restore(id));

        verify(repository, never()).save(any());
    }

    @Test
    void restoreRevalidatesSupplierAndNormalizedUniquenessBeforeMutation() {
        UUID id = UUID.randomUUID();
        SupplierLicense deleted = license(id, companyId, supplierId, " lic-duplicate ");
        deleted.softDelete();
        SupplierLicense active = license(UUID.randomUUID(), companyId, supplierId, "LIC-DUPLICATE");
        when(repository.findAnyById(id)).thenReturn(Optional.of(deleted));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.of(supplier(supplierId, companyId)));
        when(repository.findActiveByNormalizedLicenseNumber(
            TENANT, companyId, supplierId, "lic-duplicate"))
            .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service().restore(id))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");

        assertThat(deleted.isDeleted()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void restoreRejectsDeletedOrMissingSupplierBeforeMutation() {
        UUID id = UUID.randomUUID();
        SupplierLicense deleted = license(id, companyId, supplierId, "LIC-ORPHAN");
        deleted.softDelete();
        when(repository.findAnyById(id)).thenReturn(Optional.of(deleted));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().restore(id))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Supplier not found");

        assertThat(deleted.isDeleted()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsIssueDateAfterValidityStart() {
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.of(supplier(supplierId, companyId)));
        SupplierLicenseCreateRequest request = request(supplierId, "LIC-DATES");
        request.setIssueDate(LocalDate.now());
        request.setValidFrom(LocalDate.now().minusDays(1));
        request.setValidTo(LocalDate.now().plusYears(1));

        assertThatThrownBy(() -> service().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("issueDate must be before or equal to validFrom");

        verify(repository, never()).save(any());
    }

    private SupplierLicenseService service() {
        return new SupplierLicenseService(repository, new SupplierLicenseMapper(), auditService, supplierRepository);
    }

    private SupplierLicenseCreateRequest request(UUID targetSupplierId, String licenseNumber) {
        SupplierLicenseCreateRequest request = new SupplierLicenseCreateRequest();
        request.setSupplierId(targetSupplierId);
        request.setName("License " + licenseNumber.trim());
        request.setLicenseNumber(licenseNumber);
        request.setLicenseType("OPERATING");
        request.setIssueDate(LocalDate.now().minusDays(30));
        request.setValidFrom(LocalDate.now().minusDays(30));
        request.setValidTo(LocalDate.now().plusYears(1));
        return request;
    }

    private SupplierLicenseUpdateRequest updateRequest(UUID targetSupplierId, String licenseNumber) {
        SupplierLicenseUpdateRequest request = new SupplierLicenseUpdateRequest();
        request.setSupplierId(targetSupplierId);
        request.setName("License " + licenseNumber.trim());
        request.setLicenseNumber(licenseNumber);
        request.setLicenseType("OPERATING");
        request.setIssueDate(LocalDate.now().minusDays(30));
        request.setValidFrom(LocalDate.now().minusDays(30));
        request.setValidTo(LocalDate.now().plusYears(1));
        return request;
    }

    private Supplier supplier(UUID id, UUID targetCompanyId) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(targetCompanyId);
        supplier.setName("Supplier");
        return supplier;
    }

    private SupplierLicense license(UUID id, UUID targetCompanyId, UUID targetSupplierId, String number) {
        SupplierLicense license = new SupplierLicense();
        license.setId(id);
        license.setTenantId(TENANT);
        license.setCompanyId(targetCompanyId);
        license.setSupplierId(targetSupplierId);
        license.setCode(number.trim());
        license.setName(number.trim());
        license.setStatus("ACTIVE");
        license.setLicenseNumber(number);
        license.setLicenseType("OPERATING");
        license.setIssueDate(LocalDate.now().minusDays(30));
        license.setValidFrom(LocalDate.now().minusDays(30));
        license.setValidTo(LocalDate.now().plusYears(1));
        return license;
    }

    private void enableGlobalAdminScope() {
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("global-admin", "n/a");
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("SupplierLicense not found");
    }
}
