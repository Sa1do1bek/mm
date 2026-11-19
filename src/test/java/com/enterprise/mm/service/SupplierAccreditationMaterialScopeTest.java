package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.mapper.SupplierAccreditationApplicationMapper;
import com.enterprise.mm.payload.SupplierAccreditationApplicationResponse;
import com.enterprise.mm.payload.SupplierAccreditationDecisionActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.payload.SupplierEligibilityDtos.MaterialGroupEligibility;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistService;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierAccreditationMaterialScopeTest {
    @Mock private SupplierAccreditationApplicationRepository repository;
    @Mock private SupplierAccreditationDecisionRepository decisionRepository;
    @Mock private SupplierAccreditationDocumentRepository documentRepository;
    @Mock private SupplierAccreditationEvidencePolicy evidencePolicy;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private SupplierComplianceService complianceService;
    @Mock private SupplierAccreditationApplicationMapper mapper;
    @Mock private SupplierEvidenceChecklistService checklistService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @Mock private SupplierGovernanceActionPolicy supplierGovernanceActionPolicy;
    @InjectMocks private SupplierAccreditationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("accreditation-scope-test");
        CompanyContext.setCompany(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void oneCoveredGroupDoesNotAuthorizeReceiptForTwoRequestedGroups() {
        UUID supplierId = UUID.randomUUID();
        UUID coveredGroupId = UUID.randomUUID();
        UUID uncoveredGroupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setAllowedMaterialGroupIds(List.of(coveredGroupId));

        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            supplierId,
            Set.of(SupplierAccreditationStatus.APPROVED.name(), SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name())
        )).thenReturn(List.of(application));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()
        )).thenReturn(List.of(decision));
        stubCurrentEvidence(application);

        String blockedReason = service.supplierEligibilityBlockedReason(
            supplierId,
            List.of(coveredGroupId, uncoveredGroupId),
            "RFQ invitation for goods receipt"
        );

        assertThat(blockedReason).isEqualTo(
            "Supplier accreditation does not cover the material group for RFQ invitation for goods receipt");
    }

    @Test
    void operationallyBlockedSupplierCannotUseOtherwiseValidAccreditation() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(complianceService.operationalBlockReason(supplierId, "RFQ invitation for purchase order receipt"))
            .thenReturn("Supplier is blocked for RFQ invitation for purchase order receipt: Manual blacklist BL-42");

        assertThat(service.supplierEligibilityBlockedReason(
            supplierId, List.of(groupId), "RFQ invitation for purchase order receipt"
        )).contains("Manual blacklist BL-42");
    }

    @Test
    void operationalSourcesAlsoBlockOnMissingAccreditation() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();

        assertThat(service.supplierEligibilityBlockedReason(
            supplierId, List.of(groupId), "goods receipt"
        )).isEqualTo("Supplier accreditation is required for goods receipt");
    }

    @Test
    void eligibilityMatrixExplainsCoveredAndUncoveredGroupsIndividually() {
        UUID supplierId = UUID.randomUUID();
        UUID coveredGroupId = UUID.randomUUID();
        UUID uncoveredGroupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setAllowedMaterialGroupIds(List.of(coveredGroupId));
        decision.setConditions("Approved for rotating equipment only");

        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            supplierId,
            Set.of(SupplierAccreditationStatus.APPROVED.name(), SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name())
        )).thenReturn(List.of(application));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()
        )).thenReturn(List.of(decision));
        stubCurrentEvidence(application);

        List<MaterialGroupEligibility> matrix = service.materialGroupEligibility(
            supplierId, List.of(coveredGroupId, uncoveredGroupId), "purchase order dispatch");

        assertThat(matrix).extracting(MaterialGroupEligibility::materialGroupId)
            .containsExactly(coveredGroupId, uncoveredGroupId);
        assertThat(matrix.get(0)).satisfies(row -> {
            assertThat(row.eligible()).isTrue();
            assertThat(row.applicationId()).isEqualTo(application.getId());
            assertThat(row.decisionId()).isEqualTo(decision.getId());
            assertThat(row.conditions()).isEqualTo("Approved for rotating equipment only");
        });
        assertThat(matrix.get(1)).satisfies(row -> {
            assertThat(row.eligible()).isFalse();
            assertThat(row.reasonCode()).isEqualTo("SUPPLIER_MATERIAL_GROUP_NOT_COVERED");
        });
    }

    @Test
    void emptyDecisionScopeDoesNotAuthorizeRequestedMaterialGroup() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setAllowedMaterialGroupIds(List.of());
        stubEligibility(supplierId, application, List.of(decision));

        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for RFQ"))
            .isEqualTo("Supplier accreditation does not cover the material group for RFQ invitation for RFQ");
        assertThat(service.materialGroupEligibility(supplierId, List.of(groupId), "RFQ"))
            .singleElement()
            .extracting(MaterialGroupEligibility::eligible)
            .isEqualTo(false);
    }

    @Test
    void missingDecisionDoesNotFallBackToRequestedApplicationGroups() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        application.setRequestedMaterialGroupIds(List.of(groupId));
        stubEligibility(supplierId, application, List.of());

        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for purchase order"))
            .isNotNull();
    }

    @Test
    void futureApplicationOrDecisionAndExpiredDecisionFailClosed() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setAllowedMaterialGroupIds(List.of(groupId));
        stubEligibility(supplierId, application, List.of(decision));

        application.setValidFrom(LocalDate.now().plusDays(1));
        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for future application"))
            .isNotNull();

        application.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidFrom(LocalDate.now().plusDays(1));
        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for future decision"))
            .isNotNull();

        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().minusDays(1));
        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for expired decision"))
            .isNotNull();
    }

    @Test
    void latestNonApprovedDecisionFailsClosedInsteadOfAuthorizingItsGroups() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setDecision(SupplierAccreditationStatus.REJECTED.name());
        decision.setAllowedMaterialGroupIds(List.of(groupId));
        stubEligibility(supplierId, application, List.of(decision));

        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for RFQ"))
            .isNotNull();
    }

    @Test
    void emptyRequestedMaterialGroupsNeverMeanGlobalEligibility() {
        UUID supplierId = UUID.randomUUID();

        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(), "RFQ invitation for unscoped transaction"))
            .isNotNull();
    }

    @Test
    void expiredOrUnlinkedEvidenceInvalidatesOtherwiseCurrentDecision() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setAllowedMaterialGroupIds(List.of(groupId));
        stubEligibility(supplierId, application, List.of(decision));
        SupplierAccreditationDocument evidence = verifiedEvidence(application);
        evidence.setValidTo(LocalDate.now().minusDays(1));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()
        )).thenReturn(List.of(evidence));

        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for expired evidence"))
            .isNotNull();

        evidence.setValidTo(LocalDate.now().plusDays(10));
        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for unlinked evidence"))
            .isNotNull();
    }

    @Test
    void transactionGateAndMatrixUnionMultipleCurrentScopeDecisionsConsistently() {
        UUID supplierId = UUID.randomUUID();
        UUID firstGroupId = UUID.randomUUID();
        UUID secondGroupId = UUID.randomUUID();
        SupplierAccreditationApplication firstApplication = approvedApplication(supplierId);
        SupplierAccreditationApplication secondApplication = approvedApplication(supplierId);
        SupplierAccreditationDecision firstDecision = approvedDecision(firstApplication);
        firstDecision.setAllowedMaterialGroupIds(List.of(firstGroupId));
        SupplierAccreditationDecision secondDecision = approvedDecision(secondApplication);
        secondDecision.setAllowedMaterialGroupIds(List.of(secondGroupId));
        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId,
            Set.of(SupplierAccreditationStatus.APPROVED.name(), SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name())
        )).thenReturn(List.of(firstApplication, secondApplication));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), firstApplication.getId()
        )).thenReturn(List.of(firstDecision));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), secondApplication.getId()
        )).thenReturn(List.of(secondDecision));
        stubCurrentEvidence(firstApplication);
        stubCurrentEvidence(secondApplication);

        assertThat(service.supplierEligibilityBlockedReason(
            supplierId, List.of(firstGroupId, secondGroupId), "multi-scope purchase order"
        )).isNull();
        assertThat(service.materialGroupEligibility(
            supplierId, List.of(firstGroupId, secondGroupId), "multi-scope purchase order"
        )).allSatisfy(row -> assertThat(row.eligible()).isTrue());
    }

    @Test
    void servicesAccreditationUsesApprovedScopeWithoutPretendingItHasMaterialGroups() {
        UUID supplierId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        application.setAccreditationType("SERVICES");
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setAllowedMaterialGroupIds(List.of());
        decision.setApprovedScope("Industrial maintenance services");
        stubEligibility(supplierId, application, List.of(decision));
        stubCurrentEvidence(application);

        assertThat(service.supplierServiceEligibilityBlockedReason(supplierId, "RFQ invitation for service contract activation"))
            .isNull();

        decision.setApprovedScope(" ");
        assertThat(service.supplierServiceEligibilityBlockedReason(supplierId, "RFQ invitation for service contract activation"))
            .isNotNull();
    }

    @Test
    void servicesAccreditationCannotAuthorizeGoodsEvenIfAGroupWasRecorded() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        application.setAccreditationType("SERVICES");
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setAllowedMaterialGroupIds(List.of(groupId));
        decision.setApprovedScope("Industrial maintenance services");
        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId,
            Set.of(SupplierAccreditationStatus.APPROVED.name(), SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name())
        )).thenReturn(List.of(application));

        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for goods purchase order"))
            .isEqualTo("Supplier accreditation does not cover the material group for RFQ invitation for goods purchase order");
    }

    @Test
    void servicesAccreditationWithRecordedGroupClearsSupplierGovernanceScopeOnly() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        application.setAccreditationType("SERVICES");
        SupplierAccreditationDecision decision = approvedDecision(application);
        decision.setAllowedMaterialGroupIds(List.of(groupId));
        decision.setApprovedScope("SERVICES");
        stubEligibility(supplierId, application, List.of(decision));
        stubCurrentEvidence(application);

        assertThat(service.materialGroupEligibility(supplierId, List.of(groupId), "supplier governance"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.eligible()).isTrue();
                assertThat(row.decisionId()).isEqualTo(decision.getId());
            });
        assertThat(service.supplierEligibilityBlockedReason(supplierId, List.of(groupId), "RFQ invitation for goods purchase order"))
            .isEqualTo("Supplier accreditation does not cover the material group for RFQ invitation for goods purchase order");
    }

    @Test
    void approvingServicesAccreditationCarriesRequestedMaterialGroupsIntoDecision() throws Exception {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication application = approvedApplication(supplierId);
        application.setStatus(SupplierAccreditationStatus.COMPLIANCE_CHECK.name());
        application.setAccreditationType("SERVICES");
        application.setRequestedScope("SERVICES");
        application.setRequestedMaterialGroupIds(List.of(groupId));
        application.setComplianceRecommendation("ACTIVE");
        application.setComplianceSnapshotJson("{}");
        application.setValidFrom(null);
        application.setValidTo(null);
        MaterialGroup group = new MaterialGroup();
        group.setId(groupId);

        when(repository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            eq(TenantContext.currentTenant()), eq(CompanyContext.currentCompany()), any(), eq(application.getId())
        )).thenReturn(Optional.empty());
        when(complianceService.operationalBlockReason(supplierId, "supplier accreditation approval")).thenReturn(null);
        when(complianceService.summarize(supplierId))
            .thenReturn(new SupplierComplianceSummary(supplierId, 1, 0, 0, 0, "ACTIVE"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(materialGroupRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), List.of(groupId)
        )).thenReturn(List.of(group));
        stubCurrentEvidence(application);
        when(repository.save(application)).thenReturn(application);
        when(mapper.toResponse(application)).thenReturn(new SupplierAccreditationApplicationResponse());

        service.approve(
            application.getId(),
            new SupplierAccreditationDecisionActionRequest(
                "APPROVED",
                List.of(groupId),
                LocalDate.now(),
                LocalDate.now().plusMonths(12),
                "Approved"
            )
        );

        ArgumentCaptor<SupplierAccreditationDecision> decisionCaptor =
            ArgumentCaptor.forClass(SupplierAccreditationDecision.class);
        verify(decisionRepository).save(decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().getAllowedMaterialGroupIds()).containsExactly(groupId);
    }

    @Test
    void serviceAndWorksAccreditationsAreIndependent() {
        UUID supplierId = UUID.randomUUID();
        SupplierAccreditationApplication worksApplication = approvedApplication(supplierId);
        worksApplication.setAccreditationType("WORKS");
        SupplierAccreditationDecision worksDecision = approvedDecision(worksApplication);
        worksDecision.setApprovedScope("Civil construction works");
        stubEligibility(supplierId, worksApplication, List.of(worksDecision));
        stubCurrentEvidence(worksApplication);

        assertThat(service.supplierEligibilityBlockedReasonForDemand(
            supplierId, AccreditationDemand.works(), "RFQ invitation for works order")).isNull();
        assertThat(service.supplierEligibilityBlockedReasonForDemand(
            supplierId, AccreditationDemand.services(), "RFQ invitation for service order"))
            .isEqualTo("Supplier service accreditation is required for RFQ invitation for service order");
    }

    @Test
    void mixedDemandRequiresEveryScopeAndFailsClosedForUnresolvedMaterialLines() {
        UUID supplierId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        SupplierAccreditationApplication goodsApplication = approvedApplication(supplierId);
        SupplierAccreditationDecision goodsDecision = approvedDecision(goodsApplication);
        goodsDecision.setAllowedMaterialGroupIds(List.of(groupId));
        SupplierAccreditationApplication serviceApplication = approvedApplication(supplierId);
        serviceApplication.setAccreditationType("SERVICES");
        SupplierAccreditationDecision serviceDecision = approvedDecision(serviceApplication);
        serviceDecision.setApprovedScope("Industrial maintenance");
        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId,
            Set.of(SupplierAccreditationStatus.APPROVED.name(), SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name())
        )).thenReturn(List.of(goodsApplication, serviceApplication));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), goodsApplication.getId()))
            .thenReturn(List.of(goodsDecision));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), serviceApplication.getId()))
            .thenReturn(List.of(serviceDecision));
        stubCurrentEvidence(goodsApplication);
        stubCurrentEvidence(serviceApplication);

        AccreditationDemand complete = new AccreditationDemand(Set.of(groupId), true, true, true, false);
        assertThat(service.supplierEligibilityBlockedReasonForDemand(supplierId, complete, "RFQ invitation for mixed contract")).isNull();

        serviceDecision.setApprovedScope(" ");
        assertThat(service.supplierEligibilityBlockedReasonForDemand(supplierId, complete, "RFQ invitation for mixed contract"))
            .isEqualTo("Supplier service accreditation is required for RFQ invitation for mixed contract");

        AccreditationDemand unresolved = new AccreditationDemand(Set.of(groupId), true, false, false, false);
        assertThat(service.supplierEligibilityBlockedReasonForDemand(supplierId, unresolved, "RFQ invitation for partial material scope"))
            .isEqualTo("Material group is required for every material line in RFQ invitation for partial material scope");
    }

    private void stubEligibility(
        UUID supplierId,
        SupplierAccreditationApplication application,
        List<SupplierAccreditationDecision> decisions
    ) {
        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            supplierId,
            Set.of(SupplierAccreditationStatus.APPROVED.name(), SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name())
        )).thenReturn(List.of(application));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()
        )).thenReturn(decisions);
    }

    private SupplierAccreditationDecision approvedDecision(SupplierAccreditationApplication application) {
        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setId(UUID.randomUUID());
        decision.setTenantId(TenantContext.currentTenant());
        decision.setCompanyId(CompanyContext.currentCompany());
        decision.setApplicationId(application.getId());
        decision.setStatus("RECORDED");
        decision.setDecision(application.getStatus());
        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().plusDays(20));
        return decision;
    }

    private void stubCurrentEvidence(SupplierAccreditationApplication application) {
        SupplierAccreditationDocument evidence = verifiedEvidence(application);
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()
        )).thenReturn(List.of(evidence));
        when(evidencePolicy.isCurrentVerifiedEvidence(evidence, LocalDate.now())).thenReturn(true);
    }

    private SupplierAccreditationDocument verifiedEvidence(SupplierAccreditationApplication application) {
        SupplierAccreditationDocument evidence = new SupplierAccreditationDocument();
        evidence.setId(UUID.randomUUID());
        evidence.setTenantId(TenantContext.currentTenant());
        evidence.setCompanyId(CompanyContext.currentCompany());
        evidence.setApplicationId(application.getId());
        evidence.setStatus("VERIFIED");
        evidence.setAttachmentId(UUID.randomUUID());
        evidence.setValidFrom(LocalDate.now().minusDays(1));
        evidence.setValidTo(LocalDate.now().plusDays(20));
        return evidence;
    }

    private SupplierAccreditationApplication approvedApplication(UUID supplierId) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(UUID.randomUUID());
        application.setTenantId(TenantContext.currentTenant());
        application.setCompanyId(CompanyContext.currentCompany());
        application.setSupplierId(supplierId);
        application.setAccreditationType("GOODS");
        application.setStatus(SupplierAccreditationStatus.APPROVED.name());
        application.setValidFrom(LocalDate.now().minusDays(1));
        application.setValidTo(LocalDate.now().plusDays(30));
        return application;
    }
}
