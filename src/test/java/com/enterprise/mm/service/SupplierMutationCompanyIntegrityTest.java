package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.ReferenceMappingMapper;
import com.enterprise.mm.mapper.SupplierAccreditationApplicationMapper;
import com.enterprise.mm.mapper.SupplierCorrectiveActionMapper;
import com.enterprise.mm.mapper.SupplierKycCheckMapper;
import com.enterprise.mm.mapper.SupplierMapper;
import com.enterprise.mm.mapper.SupplierPortalAccountMapper;
import com.enterprise.mm.payload.ReferenceMappingCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationApplicationCreateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionCreateRequest;
import com.enterprise.mm.payload.SupplierCreateRequest;
import com.enterprise.mm.payload.SupplierKycCheckCreateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountCreateRequest;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.ReferenceMappingRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SupplierMutationCompanyIntegrityTest {
    private static final String TENANT = "supplier-mutation-company-integrity";

    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierPortalAccountRepository portalAccountRepository;
    @Mock private SupplierKycCheckRepository kycCheckRepository;
    @Mock private SupplierCorrectiveActionRepository correctiveActionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private SupplierAccreditationApplicationRepository accreditationRepository;
    @Mock private SupplierAccreditationDecisionRepository accreditationDecisionRepository;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @Mock private SupplierAccreditationDocumentRepository accreditationDocumentRepository;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private ReferenceMappingRepository referenceMappingRepository;
    @Mock private SupplierComplianceService complianceService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private SupplierEvidenceChecklistService checklistService;
    @Mock private EntityManager entityManager;
    @Mock private IdempotencyService idempotencyService;

    private UUID currentCompanyId;
    private UUID otherCompanyId;

    @BeforeEach
    void setUp() {
        currentCompanyId = UUID.randomUUID();
        otherCompanyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(currentCompanyId);

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("global-admin", "n/a");
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        lenient().when(idempotencyService.execute(any(), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                java.util.function.Supplier<?> action = invocation.getArgument(5);
                return action.get();
            });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
    }

    @Test
    void globalAdminCannotCreateSupplierBranchUnderParentFromAnotherCompany() {
        Supplier parent = supplier(UUID.randomUUID(), otherCompanyId, "301234567");
        parent.setBaseTaxId("301234567");
        parent.setSupplierType("HEAD_OFFICE");
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, parent.getId()))
            .thenReturn(Optional.of(parent));
        lenient().when(supplierRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierCreateRequest request = new SupplierCreateRequest();
        request.setName("Remote-company branch");
        request.setParentSupplierId(parent.getId());
        request.setCreateAsBranch(true);
        request.setTaxIdSuffix(1);

        assertThatThrownBy(() -> supplierService().create(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Parent supplier not found");

        verify(supplierRepository, never()).save(any());
    }

    @Test
    void restoreRejectsDuplicateNormalizedTaxIdentityBeforeMutation() {
        UUID restoredId = UUID.randomUUID();
        Supplier deleted = supplier(restoredId, currentCompanyId, " 301234567 ");
        deleted.softDelete();
        Supplier active = supplier(UUID.randomUUID(), currentCompanyId, "301234567");
        when(supplierRepository.findAnyById(restoredId)).thenReturn(Optional.of(deleted));
        when(supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TENANT, currentCompanyId))
            .thenReturn(List.of(active));
        lenient().when(supplierRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> supplierService().restore(restoredId))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Supplier INN already exists");

        assertThat(deleted.isDeleted()).isTrue();
        verify(supplierRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotCreatePortalAccountForSupplierFromAnotherCompany() {
        UUID supplierId = UUID.randomUUID();
        when(complianceService.validateSupplier(supplierId))
            .thenReturn(supplier(supplierId, otherCompanyId, "302345678"));
        lenient().when(portalAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierPortalAccountCreateRequest request = new SupplierPortalAccountCreateRequest();
        request.setSupplierId(supplierId);
        request.setLoginEmail("supplier@example.uz");

        assertThatThrownBy(() -> portalAccountService().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(portalAccountRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotCreateKycCheckForSupplierFromAnotherCompany() {
        UUID supplierId = UUID.randomUUID();
        when(complianceService.validateSupplier(supplierId))
            .thenReturn(supplier(supplierId, otherCompanyId, "303456789"));
        lenient().when(kycCheckRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierKycCheckCreateRequest request = new SupplierKycCheckCreateRequest();
        request.setSupplierId(supplierId);
        request.setCheckType("SANCTIONS");

        assertThatThrownBy(() -> kycCheckService().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(kycCheckRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotCreateCorrectiveActionForSupplierFromAnotherCompany() {
        UUID supplierId = UUID.randomUUID();
        when(complianceService.validateSupplier(supplierId))
            .thenReturn(supplier(supplierId, otherCompanyId, "304567890"));
        lenient().when(correctiveActionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierCorrectiveActionCreateRequest request = new SupplierCorrectiveActionCreateRequest();
        request.setSupplierId(supplierId);
        request.setIssueTitle("Cross-company CAPA");

        assertThatThrownBy(() -> correctiveActionService().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(correctiveActionRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotCreateAccreditationForSupplierFromAnotherCompany() {
        UUID supplierId = UUID.randomUUID();
        when(complianceService.validateSupplier(supplierId))
            .thenReturn(supplier(supplierId, otherCompanyId, "305678901"));
        lenient().when(accreditationRepository.save(any(SupplierAccreditationApplication.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accreditationRepository.saveAndFlush(any(SupplierAccreditationApplication.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        SupplierAccreditationApplicationCreateRequest request =
            new SupplierAccreditationApplicationCreateRequest();
        request.setSupplierId(supplierId);
        request.setAccreditationType("GOODS");

        assertThatThrownBy(() -> accreditationService().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(accreditationRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotMapReferenceOwnedByAnotherCompany() {
        UUID referenceId = UUID.randomUUID();
        Supplier remoteSupplier = supplier(referenceId, otherCompanyId, "306789012");
        when(entityManager.find(Supplier.class, referenceId)).thenReturn(remoteSupplier);
        lenient().when(referenceMappingRepository.save(any(ReferenceMapping.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ReferenceMappingCreateRequest request = new ReferenceMappingCreateRequest();
        request.setReferenceEntityType("SUPPLIER");
        request.setReferenceId(referenceId);
        request.setExternalSystem("SAP");
        request.setExternalCode("SUP-REMOTE");
        request.setAccountingArea("MANAGEMENT");

        assertThatThrownBy(() -> referenceMappingService().create(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("текущей компании");

        verify(referenceMappingRepository, never()).save(any());
    }

    private SupplierService supplierService() {
        return new SupplierService(
            supplierRepository,
            new SupplierMapper(),
            auditService,
            entityManager,
            employeeRepository,
            idempotencyService
        );
    }

    private SupplierPortalAccountService portalAccountService() {
        return new SupplierPortalAccountService(
            portalAccountRepository,
            new SupplierPortalAccountMapper(),
            auditService,
            complianceService,
            null
        );
    }

    private SupplierKycCheckService kycCheckService() {
        return new SupplierKycCheckService(
            kycCheckRepository,
            new SupplierKycCheckMapper(),
            complianceService,
            auditService
        );
    }

    private SupplierCorrectiveActionService correctiveActionService() {
        return new SupplierCorrectiveActionService(
            correctiveActionRepository,
            new SupplierCorrectiveActionMapper(),
            complianceService,
            employeeRepository,
            auditService
        );
    }

    private SupplierAccreditationService accreditationService() {
        return new SupplierAccreditationService(
            accreditationRepository,
            accreditationDecisionRepository,
            accreditationDocumentRepository,
            materialGroupRepository,
            new SupplierAccreditationEvidencePolicy(attachmentRepository),
            checklistService,
            new SupplierAccreditationApplicationMapper(),
            complianceService,
            integrationEventService,
            auditService,
            new ObjectMapper(),
            approvalInstanceRepository,
            new com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy()
        );
    }

    private ReferenceMappingService referenceMappingService() {
        ReferenceMappingService service = new ReferenceMappingService(
            referenceMappingRepository,
            new ReferenceMappingMapper(),
            auditService
        );
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        return service;
    }

    private Supplier supplier(UUID id, UUID companyId, String taxId) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(companyId);
        supplier.setCode("SUP-" + id.toString().substring(0, 8));
        supplier.setName("Supplier " + id.toString().substring(0, 8));
        supplier.setTaxId(taxId);
        supplier.setEmail("supplier-" + id.toString().substring(0, 8) + "@example.uz");
        supplier.setStatus(SupplierStatus.ACTIVE.name());
        supplier.setSupplierType("HEAD_OFFICE");
        return supplier;
    }
}
