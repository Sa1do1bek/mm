package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UX-LIVE-02: {@link SupplierAccreditationService#effectiveEligibility} is the single dated/scoped
 * eligibility snapshot shared by the supplier list, dossier, PO, and receipt surfaces.
 */
@ExtendWith(MockitoExtension.class)
class SupplierAccreditationServiceTest {
    @Mock private SupplierAccreditationApplicationRepository repository;
    @Mock private SupplierAccreditationDecisionRepository decisionRepository;
    @Mock private SupplierAccreditationDocumentRepository documentRepository;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private SupplierAccreditationEvidencePolicy evidencePolicy;
    @Mock private SupplierEvidenceChecklistService checklistService;
    @Mock private com.enterprise.mm.mapper.SupplierAccreditationApplicationMapper mapper;
    @Mock private SupplierComplianceService complianceService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @InjectMocks private SupplierAccreditationService service;

    private UUID companyId;
    private UUID supplierId;
    private UUID coveredGroupId;
    private UUID uncoveredGroupId;
    private LocalDate asOfDate;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        coveredGroupId = UUID.randomUUID();
        uncoveredGroupId = UUID.randomUUID();
        asOfDate = LocalDate.of(2026, 8, 1);
        TenantContext.setTenant("supplier-accreditation-eligibility-test");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void allRequestedGroupsCoveredYieldsEligible() {
        stubCoveredApplication(coveredGroupId);

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(coveredGroupId), asOfDate, "goods receipt");

        assertThat(result.overallStatus()).isEqualTo("ELIGIBLE");
        assertThat(result.eligibleForRequestedScope()).isTrue();
        assertThat(result.blockers()).isEmpty();
        assertThat(result.groups()).singleElement().satisfies(group -> assertThat(group.eligible()).isTrue());
    }

    @Test
    void noRequestedGroupsCoveredYieldsNotEligible() {
        when(complianceService.operationalBlockReason(supplierId, "goods receipt")).thenReturn(null);
        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            anyString(), any(UUID.class), any(UUID.class), any(Set.class)
        )).thenReturn(List.of());

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(uncoveredGroupId), asOfDate, "goods receipt");

        assertThat(result.overallStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(result.eligibleForRequestedScope()).isFalse();
        assertThat(result.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.scopeId()).isEqualTo(uncoveredGroupId);
            assertThat(blocker.code()).isEqualTo("SUPPLIER_MATERIAL_GROUP_NOT_COVERED");
        });
    }

    @Test
    void mixedScopesYieldPartiallyEligible() {
        stubCoveredApplication(coveredGroupId);

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(coveredGroupId, uncoveredGroupId), asOfDate, "goods receipt");

        assertThat(result.overallStatus()).isEqualTo("PARTIALLY_ELIGIBLE");
        assertThat(result.eligibleForRequestedScope()).isFalse();
        assertThat(result.groups()).hasSize(2);
        assertThat(result.blockers()).singleElement().satisfies(blocker ->
            assertThat(blocker.scopeId()).isEqualTo(uncoveredGroupId));
    }

    @Test
    void expiredDecisionYieldsAccreditationExpiredReasonCode() {
        SupplierAccreditationApplication application = coveredApplication(coveredGroupId);
        SupplierAccreditationDecision decision = coveredDecision(application, coveredGroupId);
        decision.setValidTo(asOfDate.minusDays(1));
        stubApplicationWithDecision(application, decision);

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(coveredGroupId), asOfDate, "goods receipt");

        assertThat(result.overallStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(result.eligibleForRequestedScope()).isFalse();
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.eligible()).isFalse();
            assertThat(group.reasonCode()).isEqualTo("SUPPLIER_ACCREDITATION_EXPIRED");
            assertThat(group.decisionId()).isEqualTo(decision.getId());
            assertThat(group.applicationId()).isEqualTo(application.getId());
        });
        assertThat(result.blockers()).singleElement().satisfies(blocker ->
            assertThat(blocker.code()).isEqualTo("SUPPLIER_ACCREDITATION_EXPIRED"));
    }

    @Test
    void decisionValidToIsInclusiveForEffectiveEligibility() {
        SupplierAccreditationApplication application = coveredApplication(coveredGroupId);
        SupplierAccreditationDecision decision = coveredDecision(application, coveredGroupId);
        application.setValidTo(asOfDate);
        decision.setValidTo(asOfDate);
        stubApplicationWithDecision(application, decision);
        stubCurrentEvidence(application);

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(coveredGroupId), asOfDate, "goods receipt");

        assertThat(result.overallStatus()).isEqualTo("ELIGIBLE");
        assertThat(result.eligibleForRequestedScope()).isTrue();
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.eligible()).isTrue();
            assertThat(group.validTo()).isEqualTo(asOfDate);
        });
    }

    @Test
    void missingCurrentEvidenceYieldsEvidenceMissingReasonCode() {
        SupplierAccreditationApplication application = coveredApplication(coveredGroupId);
        SupplierAccreditationDecision decision = coveredDecision(application, coveredGroupId);
        stubApplicationWithDecision(application, decision);
        SupplierAccreditationDocument document = new SupplierAccreditationDocument();
        document.setId(UUID.randomUUID());
        document.setApplicationId(application.getId());
        document.setCompanyId(companyId);
        document.setTenantId(TenantContext.currentTenant());
        document.setDocumentType("CERTIFICATE");
        document.setStatus("EXPIRED");
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId()
        )).thenReturn(List.of(document));
        when(evidencePolicy.isCurrentVerifiedEvidence(document, asOfDate)).thenReturn(false);

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(coveredGroupId), asOfDate, "goods receipt");

        assertThat(result.overallStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.eligible()).isFalse();
            assertThat(group.reasonCode()).isEqualTo("SUPPLIER_EVIDENCE_MISSING");
            assertThat(group.applicationId()).isEqualTo(application.getId());
            assertThat(group.decisionId()).isNull();
        });
        assertThat(result.blockers()).singleElement().satisfies(blocker ->
            assertThat(blocker.code()).isEqualTo("SUPPLIER_EVIDENCE_MISSING"));
    }

    @Test
    void proposedDecisionCoveringMaterialGroupYieldsPendingApprovalReasonCode() {
        SupplierAccreditationApplication application = coveredApplication(coveredGroupId);
        SupplierAccreditationDecision decision = coveredDecision(application, coveredGroupId);
        decision.setStatus("PROPOSED");
        stubApplicationWithDecision(application, decision);

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(coveredGroupId), asOfDate, "supplier governance");

        assertThat(result.overallStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.eligible()).isFalse();
            assertThat(group.reasonCode()).isEqualTo("SUPPLIER_ACCREDITATION_DECISION_PENDING");
            assertThat(group.reason()).isEqualTo("Supplier accreditation decision is pending approval for supplier governance");
            assertThat(group.applicationId()).isEqualTo(application.getId());
            assertThat(group.decisionId()).isEqualTo(decision.getId());
        });
        assertThat(result.blockers()).singleElement().satisfies(blocker ->
            assertThat(blocker.code()).isEqualTo("SUPPLIER_ACCREDITATION_DECISION_PENDING"));
    }

    @Test
    void blacklistedSupplierYieldsBlacklistedReasonCode() {
        when(complianceService.operationalBlockReason(supplierId, "goods receipt"))
            .thenReturn("Supplier is blocked for goods receipt: Manual blacklist BL-77");

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(coveredGroupId), asOfDate, "goods receipt");

        assertThat(result.overallStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.eligible()).isFalse();
            assertThat(group.reasonCode()).isEqualTo("SUPPLIER_BLACKLISTED");
            assertThat(group.reason()).isEqualTo("Supplier is blocked for goods receipt: Manual blacklist BL-77");
        });
        assertThat(result.blockers()).singleElement().satisfies(blocker ->
            assertThat(blocker.code()).isEqualTo("SUPPLIER_BLACKLISTED"));
    }

    @Test
    void noKnownScopeYieldsUnknown() {
        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, supplierId
        )).thenReturn(List.of());

        EffectiveSupplierEligibility result = service.effectiveEligibility(
            supplierId, List.of(), asOfDate, "supplier governance list");

        assertThat(result.overallStatus()).isEqualTo("UNKNOWN");
        assertThat(result.eligibleForRequestedScope()).isFalse();
        assertThat(result.groups()).isEmpty();
        assertThat(result.blockers()).isEmpty();
    }

    private void stubCoveredApplication(UUID groupId) {
        SupplierAccreditationApplication application = coveredApplication(groupId);
        SupplierAccreditationDecision decision = coveredDecision(application, groupId);
        stubApplicationWithDecision(application, decision);
        stubCurrentEvidence(application);
    }

    private void stubCurrentEvidence(SupplierAccreditationApplication application) {
        SupplierAccreditationDocument document = new SupplierAccreditationDocument();
        document.setId(UUID.randomUUID());
        document.setApplicationId(application.getId());
        document.setCompanyId(companyId);
        document.setTenantId(TenantContext.currentTenant());
        document.setDocumentType("CERTIFICATE");
        document.setStatus("VERIFIED");

        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId()
        )).thenReturn(List.of(document));
        when(evidencePolicy.isCurrentVerifiedEvidence(document, asOfDate)).thenReturn(true);
    }

    private SupplierAccreditationApplication coveredApplication(UUID groupId) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(UUID.randomUUID());
        application.setSupplierId(supplierId);
        application.setCompanyId(companyId);
        application.setTenantId(TenantContext.currentTenant());
        application.setCode("ACC-1");
        application.setName("Goods accreditation");
        application.setAccreditationType("MATERIAL");
        application.setStatus("APPROVED");
        application.setRequestedMaterialGroupIds(List.of(groupId));
        application.setValidFrom(asOfDate.minusMonths(1));
        application.setValidTo(asOfDate.plusMonths(6));
        return application;
    }

    private SupplierAccreditationDecision coveredDecision(SupplierAccreditationApplication application, UUID groupId) {
        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setId(UUID.randomUUID());
        decision.setApplicationId(application.getId());
        decision.setCompanyId(companyId);
        decision.setTenantId(TenantContext.currentTenant());
        decision.setStatus("RECORDED");
        decision.setDecision("APPROVED");
        decision.setAllowedMaterialGroupIds(List.of(groupId));
        decision.setValidFrom(asOfDate.minusMonths(1));
        decision.setValidTo(asOfDate.plusMonths(6));
        return decision;
    }

    private void stubApplicationWithDecision(
        SupplierAccreditationApplication application,
        SupplierAccreditationDecision decision
    ) {
        when(complianceService.operationalBlockReason(any(UUID.class), anyString())).thenReturn(null);
        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            anyString(), any(UUID.class), any(UUID.class), any(Set.class)
        )).thenReturn(List.of(application));
        when(decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, application.getId()
        )).thenReturn(List.of(decision));
    }
}
