package com.enterprise.mm.supplier.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.SupplierQualificationStatus;
import com.enterprise.mm.payload.SupplierQualificationResponse;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import com.enterprise.mm.repository.SupplierLicenseRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierUnblockRequestRepository;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierComplianceService;
import com.enterprise.mm.service.SupplierQualificationService;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceFilter;
import com.enterprise.mm.supplier.governance.repository.SupplierGovernanceQueryRepository;
import com.enterprise.mm.supplier.governance.repository.SupplierPerformanceQueryRepository;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceWorkspaceService;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistService;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessAction;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.OverallStatus;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessService;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierGovernanceWorkspaceServiceTest {
    @Mock private SupplierGovernanceQueryRepository queryRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierAccreditationApplicationRepository applicationRepository;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @Mock private SupplierAccreditationDecisionRepository decisionRepository;
    @Mock private SupplierAccreditationDocumentRepository documentRepository;
    @Mock private SupplierLicenseRepository licenseRepository;
    @Mock private SupplierKycCheckRepository kycRepository;
    @Mock private SupplierCorrectiveActionRepository correctiveActionRepository;
    @Mock private SupplierUnblockRequestRepository unblockRequestRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private IntegrationEventRepository integrationEventRepository;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private SupplierQualificationService qualificationService;
    @Mock private SupplierAccreditationService accreditationService;
    @Mock private SupplierComplianceService complianceService;
    @Mock private SupplierActivationRecordRepository activationRecordRepository;
    @Mock private SupplierReadinessService readinessService;
    @Mock private SupplierEvidenceChecklistService checklistService;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private SupplierPerformanceQueryRepository performanceRepository;
    @InjectMocks private SupplierGovernanceWorkspaceService service;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-governance");
        CompanyContext.setCompany(companyId);
        org.mockito.Mockito.lenient().when(readinessService.get(any())).thenReturn(
            new SupplierReadinessSummary(
                OverallStatus.NOT_READY, 0, false, List.of(), List.of(), List.of(),
                null, LocalDateTime.now(), "supplier-readiness-v1"));
        org.mockito.Mockito.lenient().when(readinessService.get(any(), any())).thenReturn(
            new SupplierReadinessSummary(
                OverallStatus.NOT_READY, 0, false, List.of(), List.of(), List.of(),
                null, LocalDateTime.now(), "supplier-readiness-v1"));
        org.mockito.Mockito.lenient().when(complianceService.summarize(any()))
            .thenAnswer(invocation -> new SupplierComplianceSummary(invocation.getArgument(0), 1, 0, 0, 0, "ACTIVE"));
        org.mockito.Mockito.lenient().when(checklistService.checklist(any(), any()))
            .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(approvalEngineService.hasActiveFlow(any(), any(), any()))
            .thenReturn(true);
        org.mockito.Mockito.lenient().when(accreditationService.effectiveEligibility(any(), any(), any(), any()))
            .thenAnswer(invocation -> new com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility(
                invocation.getArgument(0), LocalDate.now(), "UNKNOWN", false, List.of(), List.of(), null));
        org.mockito.Mockito.lenient().when(performanceRepository.summary(any()))
            .thenReturn(new com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierPerformanceSummary(
                new com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.PerformancePeriod(
                    LocalDate.now().minusMonths(12), LocalDate.now(), "LAST_12_MONTHS"),
                new com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.ProcurementPerformance(
                    0, 0, 0, 0, null, List.of(), false),
                new com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.OperationalPerformance(
                    null, null, 0L, 0L, 0L, false),
                List.of(),
                LocalDateTime.now()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void procurementManagerReceivesTheSameGovernanceActionsAsTheMutationEndpoints() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "procurement-manager",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_PROCUREMENT_MANAGER"))
            )
        );
        Supplier supplier = supplier("SUP-30", "Compressor Works");
        SupplierAccreditationApplication application = application(supplier.getId(), "SUBMITTED");
        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
        when(applicationRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-governance", companyId, supplier.getId())).thenReturn(List.of(application));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.BLOCKED, "Review required", "compliance", null,
            LocalDate.now().plusDays(30), List.of()));

        var context = service.context(supplier.getId(), null);

        assertThat(context.allowedActions())
            .filteredOn(action -> action.code().equals("START_REVIEW") || action.code().equals("REQUEST_UNBLOCK"))
            .allSatisfy(action -> {
                assertThat(action.requiredRoles()).contains("PROCUREMENT_MANAGER");
                assertThat(action.enabled()).isTrue();
            });
        assertThat(context.allowedActions())
            .filteredOn(action -> action.code().equals("SUBMIT_FOR_APPROVAL"))
            .singleElement()
            .satisfies(action -> {
                assertThat(action.enabled()).isFalse();
                assertThat(action.disabledReasonCode()).isEqualTo("STATUS_NOT_ALLOWED");
                assertThat(action.disabledReasonParams()).containsEntry("status", "SUBMITTED");
            });
    }

    @Test
    void complianceManagerCanRunComplianceCheckFromGovernanceActions() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "compliance-manager",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_COMPLIANCE_MANAGER"))
            )
        );
        Supplier supplier = supplier("SUP-COMP", "Compliance Ready Supplier");
        SupplierAccreditationApplication application = application(supplier.getId(), "UNDER_REVIEW");
        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
        when(applicationRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-governance", companyId, supplier.getId())).thenReturn(List.of(application));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.ACTIVE, null, null, null, null, List.of()));

        var context = service.context(supplier.getId(), null);

        assertThat(context.allowedActions())
            .filteredOn(action -> action.code().equals("RUN_COMPLIANCE_CHECK"))
            .singleElement()
            .satisfies(action -> {
                assertThat(action.enabled()).isTrue();
                assertThat(action.requiredRoles()).contains("COMPLIANCE_MANAGER");
                assertThat(action.disabledReasonCode()).isNull();
            });
    }

    @Test
    void detailContainsAuthoritativeReadinessAndActivationAction() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "supplier-manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPPLIER_MANAGER"))));
        Supplier supplier = supplier("SUP-READY", "Ready Supplier");
        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId()))
            .thenReturn(Optional.of(supplier));
        when(readinessService.get(eq(supplier.getId()), any())).thenReturn(new SupplierReadinessSummary(
            OverallStatus.READY_TO_ACTIVATE, 95, true, List.of(), List.of(), List.of(),
            new ReadinessAction("ACTIVATE_SUPPLIER", true, null, "SUPPLIER_MANAGER", null, supplier.getVersion()),
            LocalDateTime.now(), "supplier-readiness-v1"));

        var context = service.context(supplier.getId(), null);

        assertThat(context.readiness().overallStatus()).isEqualTo(OverallStatus.READY_TO_ACTIVATE);
        assertThat(context.complianceSummary().recommendedStatus()).isEqualTo("ACTIVE");
        assertThat(context.complianceSummary().activePortalAccounts()).isEqualTo(1);
        assertThat(context.allowedActions())
            .filteredOn(action -> "ACTIVATE_SUPPLIER".equals(action.code()))
            .singleElement()
            .satisfies(action -> assertThat(action.enabled()).isTrue());
    }

    @Test
    void queueCountAndDrilldownUseTheSameNormalizedFilter() {
        Supplier supplier = supplier("SUP-10", "Industrial Pumps LLC");
        SupplierAccreditationApplication application = application(supplier.getId(), "SUBMITTED");
        SupplierGovernanceFilter input = new SupplierGovernanceFilter(
            SupplierGovernanceQueueKey.ACCREDITATION_REVIEW, "  Pumps ", 0, null);
        PageRequest page = PageRequest.of(0, 25);
        when(queryRepository.search(any(), eq(page))).thenReturn(new PageImpl<>(List.of(supplier), page, 1));
        when(queryRepository.count(any())).thenReturn(1L);
        when(applicationRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-governance", companyId, supplier.getId())).thenReturn(List.of(application));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.EXPIRED, null, null, null, null, List.of()));

        var definition = service.queueDefinition(input);
        var queue = service.queue(input, page);

        assertThat(definition.count()).isEqualTo(queue.totalElements());
        assertThat(queue.appliedFilters().search()).isEqualTo("pumps");
        assertThat(queue.appliedFilters().horizonDays()).isEqualTo(30);
        assertThat(queue.items()).singleElement().satisfies(row -> {
            assertThat(row.supplier().displayName()).isEqualTo("Industrial Pumps LLC");
            assertThat(row.nextAction()).isEqualTo("START_REVIEW");
            assertThat(row.ownerRole()).isEqualTo("SUPPLIER_MANAGER");
        });

        ArgumentCaptor<SupplierGovernanceFilter> filter = ArgumentCaptor.forClass(SupplierGovernanceFilter.class);
        verify(queryRepository).count(filter.capture());
        assertThat(filter.getValue()).isEqualTo(queue.appliedFilters());
    }

    @Test
    void detailIsSupplierCentricAndPreservesAffectedPurchaseOrder() {
        Supplier supplier = supplier("SUP-20", "Valve Systems");
        UUID purchaseOrderId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("tenant-governance");
        purchaseOrder.setCompanyId(companyId);
        purchaseOrder.setSupplierId(supplier.getId());
        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.BLOCKED, "License expired", "compliance", null,
            LocalDate.now().minusDays(1), List.of()));

        var context = service.context(supplier.getId(), purchaseOrderId);

        assertThat(context.header().code()).isEqualTo("SUP-20");
        assertThat(context.qualification().status()).isEqualTo("BLOCKED");
        assertThat(context.originPurchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(context.allowedActions()).allSatisfy(action ->
            assertThat(action.requiredRoles()).contains("SUPPLIER_MANAGER"));
    }

    @Test
    void purchaseOrderContextLimitsScopeMatrixToPurchaseOrderMaterialGroups() {
        Supplier supplier = supplier("SUP-PO", "PO Scoped Supplier");
        UUID purchaseOrderId = UUID.randomUUID();
        UUID purchaseOrderGroupId = UUID.randomUUID();
        UUID unrelatedAccreditationGroupId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("tenant-governance");
        purchaseOrder.setCompanyId(companyId);
        purchaseOrder.setSupplierId(supplier.getId());
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrderId(purchaseOrderId);
        Material material = new Material();
        material.setMaterialGroupId(purchaseOrderGroupId);
        item.setMaterial(material);
        SupplierAccreditationApplication application = application(supplier.getId(), "APPROVED");
        application.setRequestedMaterialGroupIds(List.of(unrelatedAccreditationGroupId));
        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setId(UUID.randomUUID());
        decision.setApplicationId(application.getId());
        decision.setAllowedMaterialGroupIds(List.of(unrelatedAccreditationGroupId));

        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(applicationRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-governance", companyId, supplier.getId())).thenReturn(List.of(application));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-governance", companyId, application.getId())).thenReturn(List.of(decision));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            "tenant-governance", purchaseOrderId)).thenReturn(List.of(item));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.ACTIVE, null, null, null, null, List.of()));

        service.context(supplier.getId(), purchaseOrderId);

        ArgumentCaptor<Collection<UUID>> groupIds = ArgumentCaptor.forClass(Collection.class);
        verify(accreditationService).effectiveEligibility(
            eq(supplier.getId()), groupIds.capture(), any(), eq("supplier governance"));
        assertThat(groupIds.getValue()).containsExactly(purchaseOrderGroupId);
    }

    @Test
    void addToRfqAndTenderActionsAreEnabledForActiveSupplierAndActivationAlwaysAppearsInTheMenu() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "supplier-manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPPLIER_MANAGER"))));
        Supplier supplier = supplier("SUP-40", "Active Bearings Co");
        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.ACTIVE, null, null, null, null, List.of()));

        var context = service.context(supplier.getId(), null);

        assertThat(context.allowedActions())
            .filteredOn(action -> action.code().equals("ADD_TO_RFQ") || action.code().equals("ADD_TO_TENDER"))
            .hasSize(2)
            .allSatisfy(action -> {
                assertThat(action.enabled()).isTrue();
                assertThat(action.disabledReasonCode()).isNull();
            });
        // The default readiness mock (see setUp) is NOT_READY / activationEligible=false, so
        // ACTIVATE_SUPPLIER must still show up in the menu -- disabled with a reason -- instead of
        // silently disappearing, which is the UX bug this menu-shape test guards against.
        assertThat(context.allowedActions())
            .filteredOn(action -> action.code().equals("ACTIVATE_SUPPLIER"))
            .singleElement()
            .satisfies(action -> {
                assertThat(action.enabled()).isFalse();
                assertThat(action.disabledReasonCode()).isEqualTo("NOT_READY_FOR_ACTIVATION");
            });
    }

    @Test
    void addToRfqAndTenderActionsAreDisabledForABlockedSupplier() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "supplier-manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPPLIER_MANAGER"))));
        Supplier supplier = supplier("SUP-41", "Blocked Bearings Co");
        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.BLOCKED, "Blocked", "compliance", null, null, List.of()));

        var context = service.context(supplier.getId(), null);

        assertThat(context.allowedActions())
            .filteredOn(action -> action.code().equals("ADD_TO_RFQ") || action.code().equals("ADD_TO_TENDER"))
            .hasSize(2)
            .allSatisfy(action -> {
                assertThat(action.enabled()).isFalse();
                assertThat(action.disabledReasonCode()).isEqualTo("SUPPLIER_NOT_ACTIVE");
            });
    }

    @Test
    void submitForApprovalIsDisabledWithAClearReasonWhenNoApprovalFlowIsConfigured() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "supplier-manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPPLIER_MANAGER"))));
        Supplier supplier = supplier("SUP-50", "Precision Bearings");
        SupplierAccreditationApplication application = application(supplier.getId(), "COMPLIANCE_CHECK");
        application.setCompanyId(companyId);
        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
        when(applicationRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-governance", companyId, supplier.getId())).thenReturn(List.of(application));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.ACTIVE, null, null, null, null, List.of()));
        when(approvalEngineService.hasActiveFlow("SUPPLIER_ACCREDITATION", companyId, null)).thenReturn(false);

        var context = service.context(supplier.getId(), null);

        assertThat(context.allowedActions())
            .filteredOn(action -> action.code().equals("SUBMIT_FOR_APPROVAL"))
            .singleElement()
            .satisfies(action -> {
                assertThat(action.enabled()).isFalse();
                assertThat(action.disabledReasonCode()).isEqualTo("NO_APPROVAL_FLOW_CONFIGURED");
            });
    }

    @Test
    void submitForApprovalRequiresBothAFlowAndThisApplicationsReviewInputs() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "supplier-manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPPLIER_MANAGER"))));
        Supplier supplier = supplier("SUP-51", "Precision Valves");
        SupplierAccreditationApplication application = application(supplier.getId(), "COMPLIANCE_CHECK");
        application.setCompanyId(companyId);
        when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
        when(applicationRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-governance", companyId, supplier.getId())).thenReturn(List.of(application));
        when(qualificationService.get(supplier.getId())).thenReturn(new SupplierQualificationResponse(
            supplier.getId(), SupplierQualificationStatus.ACTIVE, null, null, null, null, List.of()));
        when(approvalEngineService.hasActiveFlow("SUPPLIER_ACCREDITATION", companyId, null)).thenReturn(true);
        when(readinessService.getForApplication(supplier.getId(), application.getId()))
            .thenReturn(reviewInputs(true, true));

        var context = service.context(supplier.getId(), null);

        assertThat(context.allowedActions())
            .filteredOn(action -> action.code().equals("SUBMIT_FOR_APPROVAL"))
            .singleElement()
            .satisfies(action -> {
                assertThat(action.enabled()).isTrue();
                assertThat(action.disabledReasonCode()).isNull();
            });

        when(readinessService.getForApplication(supplier.getId(), application.getId()))
            .thenReturn(reviewInputs(false, true));
        assertThat(service.context(supplier.getId(), null).allowedActions())
            .filteredOn(action -> action.code().equals("SUBMIT_FOR_APPROVAL"))
            .singleElement().satisfies(action -> {
                assertThat(action.enabled()).isFalse();
                assertThat(action.disabledReasonCode()).isEqualTo("REVIEW_PREREQUISITES_NOT_MET");
            });

        when(readinessService.getForApplication(supplier.getId(), application.getId()))
            .thenReturn(reviewInputs(true, false));
        assertThat(service.context(supplier.getId(), null).allowedActions())
            .filteredOn(action -> action.code().equals("SUBMIT_FOR_APPROVAL"))
            .singleElement().satisfies(action -> {
                assertThat(action.enabled()).isFalse();
                assertThat(action.disabledReasonCode()).isEqualTo("REVIEW_PREREQUISITES_NOT_MET");
            });
    }

    private SupplierReadinessSummary reviewInputs(boolean evidenceComplete, boolean complianceComplete) {
        return new SupplierReadinessSummary(OverallStatus.NOT_READY, 0, false,
            List.of(
                new com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessStage(
                    com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey.EVIDENCE,
                    evidenceComplete ? com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState.COMPLETED
                        : com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState.IN_PROGRESS,
                    evidenceComplete ? 1 : 0, 1, List.of()),
                new com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessStage(
                    com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey.COMPLIANCE_KYC,
                    complianceComplete ? com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState.COMPLETED
                        : com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState.IN_PROGRESS,
                    complianceComplete ? 1 : 0, 1, List.of())
            ), List.of(), List.of(), null, LocalDateTime.now(), "test");
    }

    private Supplier supplier(String code, String name) {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId("tenant-governance");
        supplier.setCompanyId(companyId);
        supplier.setCode(code);
        supplier.setName(name);
        supplier.setLegalName(name);
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private SupplierAccreditationApplication application(UUID supplierId, String status) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(UUID.randomUUID());
        application.setSupplierId(supplierId);
        application.setStatus(status);
        application.setCode("ACC-10");
        application.setName("Initial accreditation");
        application.setSubmittedAt(java.time.LocalDateTime.now());
        return application;
    }
}
