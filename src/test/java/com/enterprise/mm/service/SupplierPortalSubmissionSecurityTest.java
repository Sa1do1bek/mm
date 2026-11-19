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
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.SupplierPortalSubmission;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.enums.SupplierPortalSubmissionStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierPortalSubmissionMapper;
import com.enterprise.mm.payload.SupplierPortalSubmissionCreateRequest;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierPortalSubmissionRepository;
import com.enterprise.mm.security.AccessScope;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierPortalSubmissionSecurityTest {
    private static final String TENANT = "supplier-portal-submission-security";

    @Mock private SupplierPortalSubmissionRepository repository;
    @Mock private SupplierPortalAccountRepository accountRepository;
    @Mock private SupplierComplianceService complianceService;
    @Mock private SupplierAccreditationService accreditationService;
    @Mock private AuditService auditService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
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
    void genericCreateForcesDraftAndClearsWorkflowProvenance() {
        UUID supplierId = UUID.randomUUID();
        when(complianceService.validateSupplier(supplierId)).thenReturn(supplier(supplierId, companyId));
        when(repository.save(any(SupplierPortalSubmission.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SupplierPortalSubmissionCreateRequest request = baseRequest(supplierId);
        request.setStatus(SupplierPortalSubmissionStatus.ACCEPTED.name());
        request.setSubmittedAt(LocalDateTime.now().minusDays(2));
        request.setReviewedAt(LocalDateTime.now().minusDays(1));
        request.setReviewedBy("client-forged-reviewer");
        request.setReviewComment("client-forged-decision");

        service().create(request);

        ArgumentCaptor<SupplierPortalSubmission> captor = ArgumentCaptor.forClass(SupplierPortalSubmission.class);
        verify(repository).save(captor.capture());
        SupplierPortalSubmission saved = captor.getValue();
        assertThat(saved.getCompanyId()).isEqualTo(companyId);
        assertThat(saved.getStatus()).isEqualTo(SupplierPortalSubmissionStatus.DRAFT.name());
        assertThat(saved.getSubmittedAt()).isNull();
        assertThat(saved.getReviewedAt()).isNull();
        assertThat(saved.getReviewedBy()).isNull();
        assertThat(saved.getReviewComment()).isNull();
    }

    @Test
    void genericCreateRejectsSupplierFromAnotherCompany() {
        UUID supplierId = UUID.randomUUID();
        when(complianceService.validateSupplier(supplierId))
            .thenReturn(supplier(supplierId, UUID.randomUUID()));

        assertThatThrownBy(() -> service().create(baseRequest(supplierId)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(repository, never()).save(any());
    }

    @Test
    void globalAdministratorCannotMutateOrRestoreSubmissionFromAnotherCompany() {
        SupplierPortalSubmission active = submission(UUID.randomUUID());
        active.setCompanyId(UUID.randomUUID());
        SupplierPortalSubmission deleted = submission(UUID.randomUUID());
        deleted.setCompanyId(UUID.randomUUID());
        deleted.softDelete();
        when(repository.findByIdAndIsDeletedFalse(active.getId())).thenReturn(Optional.of(active));
        when(repository.findAnyById(deleted.getId())).thenReturn(Optional.of(deleted));
        enableGlobalAdministratorScope();

        assertThatThrownBy(() -> service().softDelete(active.getId()))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service().restore(deleted.getId()))
            .isInstanceOf(NotFoundException.class);

        assertThat(active.isDeleted()).isFalse();
        assertThat(deleted.isDeleted()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void globalAdministratorCannotReferenceForeignCompanyPortalAccount() {
        UUID supplierId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setId(accountId);
        account.setTenantId(TENANT);
        account.setCompanyId(UUID.randomUUID());
        account.setSupplierId(supplierId);
        account.setStatus(SupplierPortalStatus.ACTIVE.name());
        when(complianceService.validateSupplier(supplierId)).thenReturn(supplier(supplierId, companyId));
        when(accountRepository.findByIdAndIsDeletedFalse(accountId)).thenReturn(Optional.of(account));
        SupplierPortalSubmissionCreateRequest request = baseRequest(supplierId);
        request.setPortalAccountId(accountId);
        enableGlobalAdministratorScope();

        assertThatThrownBy(() -> service().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("portal account not found");

        verify(repository, never()).save(any());
    }

    @Test
    void accreditationReferenceMustBelongToCurrentCompany() {
        UUID supplierId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        when(complianceService.validateSupplier(supplierId)).thenReturn(supplier(supplierId, companyId));
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(applicationId);
        application.setTenantId(TENANT);
        application.setCompanyId(UUID.randomUUID());
        application.setSupplierId(supplierId);
        when(accreditationService.validateApplication(applicationId)).thenReturn(application);
        SupplierPortalSubmissionCreateRequest request = baseRequest(supplierId);
        request.setReferenceEntityType("SUPPLIER_ACCREDITATION_APPLICATION");
        request.setReferenceId(applicationId);

        assertThatThrownBy(() -> service().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(repository, never()).save(any());
    }

    private SupplierPortalSubmissionService service() {
        return new SupplierPortalSubmissionService(
            repository,
            new SupplierPortalSubmissionMapper(),
            accountRepository,
            complianceService,
            accreditationService,
            auditService
        );
    }

    private SupplierPortalSubmissionCreateRequest baseRequest(UUID supplierId) {
        SupplierPortalSubmissionCreateRequest request = new SupplierPortalSubmissionCreateRequest();
        request.setSupplierId(supplierId);
        request.setCode("SUBMISSION-SECURITY");
        request.setName("Supplier evidence");
        request.setSubmissionType("KYC_DOCUMENT");
        return request;
    }

    private Supplier supplier(UUID id, UUID supplierCompanyId) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(supplierCompanyId);
        supplier.setCode("SUP-SECURITY");
        supplier.setName("Supplier");
        return supplier;
    }

    private SupplierPortalSubmission submission(UUID id) {
        SupplierPortalSubmission submission = new SupplierPortalSubmission();
        submission.setId(id);
        submission.setTenantId(TENANT);
        submission.setCompanyId(companyId);
        submission.setCode("SUB-" + id.toString().substring(0, 8));
        submission.setName("Submission");
        submission.setStatus(SupplierPortalSubmissionStatus.DRAFT.name());
        submission.setSupplierId(UUID.randomUUID());
        return submission;
    }

    private void enableGlobalAdministratorScope() {
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("global-admin", "n/a");
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
