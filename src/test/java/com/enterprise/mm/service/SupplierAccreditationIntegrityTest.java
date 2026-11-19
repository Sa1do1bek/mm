package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.SupplierAccreditationApplicationMapper;
import com.enterprise.mm.mapper.SupplierAccreditationDecisionMapper;
import com.enterprise.mm.mapper.SupplierAccreditationDocumentMapper;
import com.enterprise.mm.payload.SupplierAccreditationApplicationCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationApplicationUpdateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDecisionActionRequest;
import com.enterprise.mm.payload.SupplierAccreditationDecisionCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDecisionUpdateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentActionRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentUpdateRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierAccreditationIntegrityTest {
    @Mock private SupplierAccreditationApplicationRepository applicationRepository;
    @Mock private SupplierAccreditationDecisionRepository decisionRepository;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @Mock private SupplierAccreditationDocumentRepository documentRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private SupplierComplianceService complianceService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private SupplierEvidenceChecklistService checklistService;

    private SupplierAccreditationService applicationService;
    private SupplierAccreditationDecisionService decisionService;
    private SupplierAccreditationDocumentService documentService;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("accreditation-integrity-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "test-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            )
        );
        applicationService = new SupplierAccreditationService(
            applicationRepository,
            decisionRepository,
            documentRepository,
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
        decisionService = new SupplierAccreditationDecisionService(
            decisionRepository,
            new SupplierAccreditationDecisionMapper()
        );
        documentService = new SupplierAccreditationDocumentService(
            documentRepository,
            new SupplierAccreditationDocumentMapper(),
            applicationService,
            new SupplierAccreditationEvidencePolicy(attachmentRepository),
            auditService
        );
        lenient().when(applicationRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        lenient().when(applicationRepository.saveAndFlush(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        lenient().when(decisionRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        lenient().when(documentRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        lenient().when(complianceService.validateSupplier(any()))
            .thenAnswer(invocation -> supplier(invocation.getArgument(0), CompanyContext.currentCompany()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void searchTreatsNullRequestAsEmptyFilter() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(applicationRepository.findAll(
            any(Specification.class),
            eq(pageable)
        )).thenReturn(Page.empty(pageable));

        assertThat(applicationService.search(null, pageable)).isEmpty();
    }

    @Test
    void decisionSearchTreatsNullRequestAsEmptyFilter() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(decisionRepository.findAll(
            any(Specification.class),
            eq(pageable)
        )).thenReturn(Page.empty(pageable));

        assertThat(decisionService.search(null, pageable)).isEmpty();
    }

    @Test
    void documentSearchTreatsNullRequestAsEmptyFilter() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(documentRepository.findAll(
            any(Specification.class),
            eq(pageable)
        )).thenReturn(Page.empty(pageable));

        assertThat(documentService.search(null, pageable)).isEmpty();
    }

    @Test
    void createRejectsNullRequestWithoutNullPointerException() {
        assertThatThrownBy(() -> applicationService.create(null))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Supplier accreditation request is required");
    }

    @Test
    void createRejectsGoodsAccreditationWithoutRequestedMaterialGroups() {
        SupplierAccreditationApplicationCreateRequest request = applicationRequest();
        request.setRequestedMaterialGroupIds(null);

        assertThatThrownBy(() -> applicationService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Requested material groups are required");
    }

    @Test
    void createForcesDraftAndClearsWorkflowControlledFields() {
        SupplierAccreditationApplicationCreateRequest request = applicationRequest();
        request.setParentApplicationId(UUID.randomUUID());
        request.setStatus(SupplierAccreditationStatus.APPROVED.name());
        request.setSubmittedAt(LocalDateTime.now().minusDays(3));
        request.setReviewStartedAt(LocalDateTime.now().minusDays(2));
        request.setRiskScore(BigDecimal.valueOf(99));
        request.setReviewComment("forged review");
        request.setComplianceRecommendation("ACTIVE");
        request.setComplianceSnapshotJson("{\"forged\":true}");

        var created = applicationService.create(request);

        assertThat(created.getStatus()).isEqualTo(SupplierAccreditationStatus.DRAFT.name());
        assertThat(created.getSubmittedAt()).isNull();
        assertThat(created.getReviewStartedAt()).isNull();
        assertThat(created.getRiskScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getReviewComment()).isNull();
        assertThat(created.getComplianceRecommendation()).isNull();
        assertThat(created.getComplianceSnapshotJson()).isNull();
        assertThat(created.getParentApplicationId()).isNull();
        verify(checklistService).snapshotFor(any(SupplierAccreditationApplication.class));
    }

    @Test
    void updateRejectsNullRequestWithoutNullPointerException() {
        assertThatThrownBy(() -> applicationService.update(UUID.randomUUID(), null))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Supplier accreditation request is required");
    }

    @Test
    void genericUpdatePreservesControlledFieldsAndRejectsNonEditableApplication() {
        SupplierAccreditationApplication draft = application(SupplierAccreditationStatus.DRAFT.name());
        draft.setSubmittedAt(LocalDateTime.now().minusDays(2));
        draft.setRiskScore(BigDecimal.valueOf(42));
        draft.setComplianceRecommendation("LIMITED");
        UUID originalSupplierId = draft.getSupplierId();
        UUID renewalParentId = UUID.randomUUID();
        draft.setParentApplicationId(renewalParentId);
        when(applicationRepository.findByIdAndIsDeletedFalse(draft.getId())).thenReturn(Optional.of(draft));
        SupplierAccreditationApplicationUpdateRequest update = updateRequest();
        update.setStatus(SupplierAccreditationStatus.APPROVED.name());
        update.setSubmittedAt(LocalDateTime.now());
        update.setRiskScore(BigDecimal.ONE);
        update.setComplianceRecommendation("ACTIVE");
        update.setParentApplicationId(UUID.randomUUID());

        var updated = applicationService.update(draft.getId(), update);

        assertThat(updated.getStatus()).isEqualTo(SupplierAccreditationStatus.DRAFT.name());
        assertThat(updated.getSupplierId()).isEqualTo(originalSupplierId);
        assertThat(updated.getSubmittedAt()).isEqualTo(draft.getSubmittedAt());
        assertThat(updated.getRiskScore()).isEqualByComparingTo("42");
        assertThat(updated.getComplianceRecommendation()).isEqualTo("LIMITED");
        assertThat(updated.getParentApplicationId()).isEqualTo(renewalParentId);

        SupplierAccreditationApplication submitted = application(SupplierAccreditationStatus.SUBMITTED.name());
        when(applicationRepository.findByIdAndIsDeletedFalse(submitted.getId())).thenReturn(Optional.of(submitted));
        assertThatThrownBy(() -> applicationService.update(submitted.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("editable");
        assertThatThrownBy(() -> applicationService.softDelete(submitted.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("editable");
    }

    @Test
    void runComplianceCheckStoresSnapshotAndRecalculatesSupplierRisk() {
        SupplierAccreditationApplication application = application(SupplierAccreditationStatus.UNDER_REVIEW.name());
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));

        var checked = applicationService.runComplianceCheck(application.getId());

        assertThat(checked.getStatus()).isEqualTo(SupplierAccreditationStatus.COMPLIANCE_CHECK.name());
        assertThat(checked.getComplianceRecommendation()).isEqualTo("ACTIVE");
        assertThat(checked.getComplianceSnapshotJson()).contains("\"recommendedStatus\":\"ACTIVE\"");
        verify(complianceService).applySupplierRisk(application.getSupplierId());
    }

    @Test
    void genericDecisionMutationsAreImmutableDomainCommands() {
        SupplierAccreditationApplication application = application(SupplierAccreditationStatus.APPROVED.name());
        SupplierAccreditationDecisionCreateRequest create = new SupplierAccreditationDecisionCreateRequest();
        create.setApplicationId(application.getId());
        create.setDecision(SupplierAccreditationStatus.APPROVED.name());

        assertImmutable(() -> decisionService.create(create));

        SupplierAccreditationDecision decision = decision(application);
        when(decisionRepository.findByIdAndIsDeletedFalse(decision.getId())).thenReturn(Optional.of(decision));
        SupplierAccreditationDecisionUpdateRequest update = new SupplierAccreditationDecisionUpdateRequest();
        update.setApplicationId(application.getId());
        update.setDecision(SupplierAccreditationStatus.APPROVED.name());
        assertImmutable(() -> decisionService.update(decision.getId(), update));
        assertImmutable(() -> decisionService.softDelete(decision.getId()));

        decision.setDeleted(true);
        when(decisionRepository.findAnyById(decision.getId())).thenReturn(Optional.of(decision));
        assertImmutable(() -> decisionService.restore(decision.getId()));
    }

    @Test
    void applicationAndDocumentRestoreValidateScopeBeforeDeletedState() {
        SupplierAccreditationApplication application = application(SupplierAccreditationStatus.DRAFT.name());
        application.setDeleted(true);
        when(applicationRepository.findAnyById(application.getId())).thenReturn(Optional.of(application));

        var restoredApplication = applicationService.restore(application.getId());

        assertThat(restoredApplication.isDeleted()).isFalse();

        SupplierAccreditationDocument document = document(application, "PENDING_VERIFICATION");
        document.setDeleted(true);
        when(documentRepository.findAnyById(document.getId())).thenReturn(Optional.of(document));

        var restoredDocument = documentService.restore(document.getId());

        assertThat(restoredDocument.isDeleted()).isFalse();

        SupplierAccreditationApplication otherTenant = application(SupplierAccreditationStatus.DRAFT.name());
        otherTenant.setDeleted(true);
        otherTenant.setTenantId("other-tenant");
        when(applicationRepository.findAnyById(otherTenant.getId())).thenReturn(Optional.of(otherTenant));
        assertThatThrownBy(() -> applicationService.restore(otherTenant.getId()))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
    }

    @Test
    void genericDocumentCrudCannotForgeVerifiedEvidence() {
        SupplierAccreditationApplication application = application(SupplierAccreditationStatus.DRAFT.name());
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        SupplierAccreditationDocumentCreateRequest create = new SupplierAccreditationDocumentCreateRequest();
        create.setApplicationId(application.getId());
        create.setDocumentType("REGISTRATION_CERTIFICATE");
        create.setAttachmentId(UUID.randomUUID());
        create.setStatus("VERIFIED");
        create.setVerifiedBy("forged-verifier");
        create.setVerifiedAt(LocalDateTime.now());

        var created = documentService.create(create);

        assertThat(created.getStatus()).isEqualTo("PENDING_VERIFICATION");
        assertThat(created.getVerifiedBy()).isNull();
        assertThat(created.getVerifiedAt()).isNull();

        SupplierAccreditationDocument verified = document(application, "VERIFIED");
        when(documentRepository.findByIdAndIsDeletedFalse(verified.getId())).thenReturn(Optional.of(verified));
        SupplierAccreditationDocumentUpdateRequest update = new SupplierAccreditationDocumentUpdateRequest();
        update.setApplicationId(application.getId());
        update.setDocumentType("CHANGED");
        assertThatThrownBy(() -> documentService.update(verified.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("verified");
    }

    @Test
    void pendingEvidenceCannotBeMovedToAnotherAccreditationApplication() {
        SupplierAccreditationApplication originalApplication = application(SupplierAccreditationStatus.DRAFT.name());
        SupplierAccreditationApplication otherApplication = application(SupplierAccreditationStatus.DRAFT.name());
        SupplierAccreditationDocument pending = document(originalApplication, "PENDING_VERIFICATION");
        when(documentRepository.findByIdAndIsDeletedFalse(pending.getId())).thenReturn(Optional.of(pending));
        when(applicationRepository.findByIdAndIsDeletedFalse(originalApplication.getId()))
            .thenReturn(Optional.of(originalApplication));
        SupplierAccreditationDocumentUpdateRequest update = new SupplierAccreditationDocumentUpdateRequest();
        update.setApplicationId(otherApplication.getId());
        update.setDocumentType("INSURANCE_CERTIFICATE");
        update.setAttachmentId(UUID.randomUUID());

        var updated = documentService.update(pending.getId(), update);

        assertThat(updated.getApplicationId()).isEqualTo(originalApplication.getId());
    }

    @Test
    void verificationRequiresAnActiveAttachmentLinkedToTheEvidenceRecord() {
        SupplierAccreditationApplication application = application(SupplierAccreditationStatus.DRAFT.name());
        SupplierAccreditationDocument pending = document(application, "PENDING_VERIFICATION");
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(documentRepository.findByIdAndIsDeletedFalse(pending.getId())).thenReturn(Optional.of(pending));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, pending.getAttachmentId()
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.verify(
            pending.getId(), new SupplierAccreditationDocumentActionRequest("Attachment reviewed")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("linked attachment");
    }

    @Test
    void evidenceVerificationAndRejectionRequireReasonAndLinkedAttachment() {
        SupplierAccreditationApplication application = application(SupplierAccreditationStatus.DRAFT.name());
        SupplierAccreditationDocument pending = document(application, "PENDING_VERIFICATION");
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(documentRepository.findByIdAndIsDeletedFalse(pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> documentService.verify(pending.getId(), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason");
        assertThatThrownBy(() -> documentService.reject(
            pending.getId(), new SupplierAccreditationDocumentActionRequest(" ")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason");
        assertThatThrownBy(() -> documentService.reject(
            pending.getId(), new SupplierAccreditationDocumentActionRequest("Attachment is unreadable")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("linked attachment");
    }

    @Test
    void approvalUsesApplicationScopeWithoutProtocolOrConditions() {
        SupplierAccreditationApplication application = application(SupplierAccreditationStatus.COMPLIANCE_CHECK.name());
        application.setAccreditationType("GOODS");
        application.setRequestedMaterialGroupIds(List.of(UUID.randomUUID()));
        application.setComplianceRecommendation("ACTIVE");
        application.setComplianceSnapshotJson("{}");
        SupplierAccreditationDocument evidence = document(application, "VERIFIED");
        Attachment attachment = attachment(evidence);
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(
            new SupplierComplianceSummary(application.getSupplierId(), 1, 0, 0, 0, "ACTIVE")
        );
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(evidence));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, evidence.getAttachmentId())).thenReturn(Optional.of(attachment));
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);
        SupplierAccreditationDecisionActionRequest request = new SupplierAccreditationDecisionActionRequest(
            "APPROVED",
            null,
            LocalDate.now(),
            LocalDate.now().plusMonths(6),
            "approve"
        );

        var approved = applicationService.approve(application.getId(), request);

        assertThat(approved.getStatus()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());
        ArgumentCaptor<SupplierAccreditationDecision> captor = ArgumentCaptor.forClass(SupplierAccreditationDecision.class);
        verify(decisionRepository).save(captor.capture());
        assertThat(captor.getValue().getApprovedScope()).isEqualTo(application.getRequestedScope());
        assertThat(captor.getValue().getAllowedMaterialGroupIds()).isEqualTo(application.getRequestedMaterialGroupIds());
        assertThat(captor.getValue().getConditions()).isNull();
        assertThat(captor.getValue().getCommissionProtocolNo()).isNull();
    }

    @Test
    void approvalRejectsAnOperationallyBlockedSupplierEvenWithCompletedComplianceSnapshot() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId()))
            .thenReturn(Optional.of(application));
        when(complianceService.operationalBlockReason(
            application.getSupplierId(), "supplier accreditation approval"))
            .thenReturn("Supplier is blocked for supplier accreditation approval: sanctions hold");

        assertThatThrownBy(() -> applicationService.approve(
            application.getId(),
            approvalRequest(
                application.getRequestedMaterialGroupIds(),
                LocalDate.now(),
                LocalDate.now().plusMonths(6))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("sanctions hold");
    }

    @Test
    void approvalRejectsEmptyGoodsScopeInvalidDatesAndMissingVerifiedEvidence() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));

        SupplierAccreditationDecisionActionRequest emptyScope = approvalRequest(List.of(), LocalDate.now(), LocalDate.now().plusMonths(6));
        assertThatThrownBy(() -> applicationService.approve(application.getId(), emptyScope))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("material group");

        SupplierAccreditationDecisionActionRequest invalidDates = approvalRequest(
            application.getRequestedMaterialGroupIds(), LocalDate.now().plusDays(2), LocalDate.now().plusDays(1));
        assertThatThrownBy(() -> applicationService.approve(application.getId(), invalidDates))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("validity");

        SupplierAccreditationDecisionActionRequest noEvidence = approvalRequest(
            application.getRequestedMaterialGroupIds(), LocalDate.now(), LocalDate.now().plusMonths(6));
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of());
        assertThatThrownBy(() -> applicationService.approve(application.getId(), noEvidence))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("VERIFIED");

        SupplierAccreditationDocument unlinkedEvidence = document(application, "VERIFIED");
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(unlinkedEvidence));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, unlinkedEvidence.getAttachmentId()
        )).thenReturn(Optional.empty());
        assertThatThrownBy(() -> applicationService.approve(application.getId(), noEvidence))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("linked attachment");
    }

    @Test
    void approvalAtomicallyRecordsFinalizedDecisionAfterVerifiedEvidence() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        SupplierAccreditationDocument evidence = document(application, "VERIFIED");
        evidence.setValidFrom(LocalDate.now().minusDays(1));
        evidence.setValidTo(LocalDate.now().plusMonths(12));
        Attachment attachment = attachment(evidence);
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(evidence));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, evidence.getAttachmentId()
        )).thenReturn(Optional.of(attachment));
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);
        LocalDate validFrom = LocalDate.now();
        LocalDate validTo = LocalDate.now().plusMonths(6);

        UUID approvedGroupId = application.getRequestedMaterialGroupIds().getFirst();
        var approved = applicationService.approve(
            application.getId(), approvalRequest(
                java.util.Arrays.asList(approvedGroupId, null, approvedGroupId), validFrom, validTo));

        assertThat(approved.getStatus()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());
        assertThat(approved.getValidFrom()).isEqualTo(validFrom);
        assertThat(approved.getValidTo()).isEqualTo(validTo);
        ArgumentCaptor<SupplierAccreditationDecision> captor = ArgumentCaptor.forClass(SupplierAccreditationDecision.class);
        verify(decisionRepository).save(captor.capture());
        assertThat(captor.getValue()).satisfies(decision -> {
            assertThat(decision.getStatus()).isEqualTo("RECORDED");
            assertThat(decision.getDecision()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());
            assertThat(decision.getAllowedMaterialGroupIds()).containsExactly(approvedGroupId);
            assertThat(decision.getValidFrom()).isEqualTo(validFrom);
            assertThat(decision.getValidTo()).isEqualTo(validTo);
            assertThat(decision.getApprovedScope()).isEqualTo(application.getRequestedScope());
            assertThat(decision.getConditions()).isNull();
            assertThat(decision.getCommissionProtocolNo()).isNull();
            assertThat(decision.getCompanyId()).isEqualTo(companyId);
        });
    }

    @Test
    void proposeDecisionIgnoresLegacyMaterialGroupsForServiceAccreditation() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        application.setAccreditationType("SERVICES");
        application.setRequestedScope("Maintenance service scope");
        application.setRequestedMaterialGroupIds(List.of(UUID.randomUUID()));
        SupplierAccreditationDocument evidence = document(application, "VERIFIED");
        Attachment attachment = attachment(evidence);
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, application.getId(), "PROPOSED")).thenReturn(Optional.empty());
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(evidence));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, evidence.getAttachmentId())).thenReturn(Optional.of(attachment));

        applicationService.proposeDecision(
            application.getId(),
            new SupplierAccreditationDecisionActionRequest(
                "APPROVED",
                null,
                LocalDate.now(),
                LocalDate.now().plusMonths(12),
                "Approved"
            )
        );

        ArgumentCaptor<SupplierAccreditationDecision> captor = ArgumentCaptor.forClass(SupplierAccreditationDecision.class);
        verify(decisionRepository).save(captor.capture());
        assertThat(captor.getValue().getAllowedMaterialGroupIds()).isEmpty();
        assertThat(captor.getValue().getApprovedScope()).isEqualTo("Maintenance service scope");
    }

    @Test
    void approvalRejectsScopeExpansionAndUnknownMaterialGroups() {
        SupplierAccreditationApplication expansion = approvalReadyApplication();
        UUID unrequestedGroupId = UUID.randomUUID();
        when(applicationRepository.findByIdAndIsDeletedFalse(expansion.getId())).thenReturn(Optional.of(expansion));
        when(complianceService.summarize(expansion.getSupplierId())).thenReturn(activeSummary(expansion));

        assertThatThrownBy(() -> applicationService.approve(
            expansion.getId(),
            approvalRequest(List.of(unrequestedGroupId), LocalDate.now(), LocalDate.now().plusMonths(6))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requested scope");

        SupplierAccreditationApplication unknown = approvalReadyApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(unknown.getId())).thenReturn(Optional.of(unknown));
        when(complianceService.summarize(unknown.getSupplierId())).thenReturn(activeSummary(unknown));
        when(materialGroupRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, unknown.getRequestedMaterialGroupIds())).thenReturn(List.of());

        assertThatThrownBy(() -> applicationService.approve(
            unknown.getId(),
            approvalRequest(unknown.getRequestedMaterialGroupIds(), LocalDate.now(), LocalDate.now().plusMonths(6))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active material groups");
    }

    @Test
    void limitedComplianceCapsExplicitApprovalWithoutRequiringConditions() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        SupplierAccreditationDocument evidence = document(application, "VERIFIED");
        Attachment attachment = attachment(evidence);
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(
            new SupplierComplianceSummary(application.getSupplierId(), 1, 0, 1, 0, "LIMITED"));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(evidence));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, evidence.getAttachmentId())).thenReturn(Optional.of(attachment));
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);

        var approved = applicationService.approve(application.getId(), approvalRequest(
            application.getRequestedMaterialGroupIds(), LocalDate.now(), LocalDate.now().plusMonths(6)));

        assertThat(approved.getStatus()).isEqualTo(SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name());
    }

    @Test
    void staleDraftRemainsEditableAndExpiredApprovalIsDerivedWithoutMutatingReadModel() {
        SupplierAccreditationApplication staleDraft = application(SupplierAccreditationStatus.DRAFT.name());
        staleDraft.setValidTo(LocalDate.now().minusDays(1));
        when(applicationRepository.findByIdAndIsDeletedFalse(staleDraft.getId())).thenReturn(Optional.of(staleDraft));

        var updated = applicationService.update(staleDraft.getId(), updateRequest());

        assertThat(updated.getStatus()).isEqualTo(SupplierAccreditationStatus.DRAFT.name());

        SupplierAccreditationApplication expiredApproval = application(SupplierAccreditationStatus.APPROVED.name());
        expiredApproval.setValidTo(LocalDate.now().minusDays(1));
        when(applicationRepository.findByIdAndIsDeletedFalse(expiredApproval.getId())).thenReturn(Optional.of(expiredApproval));

        assertThat(applicationService.get(expiredApproval.getId()).getStatus())
            .isEqualTo(SupplierAccreditationStatus.EXPIRED.name());
        assertThat(expiredApproval.getStatus()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());
    }

    @Test
    void globalAdminMustSelectTheTargetCompanyBeforeAccreditationMutation() {
        UUID otherCompanyId = UUID.randomUUID();
        var authentication = new UsernamePasswordAuthenticationToken("global-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        SupplierAccreditationApplication source = application(SupplierAccreditationStatus.APPROVED.name());
        source.setCompanyId(otherCompanyId);
        when(applicationRepository.findByIdAndIsDeletedFalse(source.getId())).thenReturn(Optional.of(source));

        assertThat(applicationService.get(source.getId()).getCompanyId()).isEqualTo(otherCompanyId);
        assertThatThrownBy(() -> applicationService.renew(source.getId()))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);

        CompanyContext.setCompany(otherCompanyId);
        var renewal = applicationService.renew(source.getId());
        assertThat(renewal.getCompanyId()).isEqualTo(otherCompanyId);

        SupplierAccreditationDocumentCreateRequest create = new SupplierAccreditationDocumentCreateRequest();
        create.setApplicationId(source.getId());
        create.setDocumentType("INSURANCE_CERTIFICATE");
        create.setAttachmentId(UUID.randomUUID());
        var created = documentService.create(create);
        assertThat(created.getCompanyId()).isEqualTo(otherCompanyId);

        SupplierAccreditationDocument pending = document(source, "PENDING_VERIFICATION");
        pending.setCompanyId(otherCompanyId);
        Attachment linkedAttachment = attachment(pending);
        linkedAttachment.setCompanyId(otherCompanyId);
        when(documentRepository.findByIdAndIsDeletedFalse(pending.getId())).thenReturn(Optional.of(pending));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), otherCompanyId, pending.getAttachmentId()))
            .thenReturn(Optional.of(linkedAttachment));

        assertThat(documentService.verify(
            pending.getId(), new SupplierAccreditationDocumentActionRequest("Evidence reviewed"))
            .getStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void renewalCreatesEditableDraftWithoutRevokingTheSourceApproval() {
        SupplierAccreditationApplication current = application(SupplierAccreditationStatus.APPROVED.name());
        when(applicationRepository.findByIdAndIsDeletedFalse(current.getId())).thenReturn(Optional.of(current));

        var earlyRenewal = applicationService.renew(current.getId());

        assertThat(earlyRenewal.getStatus()).isEqualTo(SupplierAccreditationStatus.DRAFT.name());
        assertThat(earlyRenewal.getParentApplicationId()).isEqualTo(current.getId());
        assertThat(earlyRenewal.getValidFrom()).isNull();
        assertThat(earlyRenewal.getValidTo()).isNull();
        assertThat(current.getStatus()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());

        SupplierAccreditationApplication expired = application(SupplierAccreditationStatus.EXPIRED.name());
        expired.setValidFrom(LocalDate.now().minusYears(2));
        expired.setValidTo(LocalDate.now().minusDays(1));
        when(applicationRepository.findByIdAndIsDeletedFalse(expired.getId())).thenReturn(Optional.of(expired));

        var expiredRenewal = applicationService.renew(expired.getId());

        assertThat(expiredRenewal.getStatus()).isEqualTo(SupplierAccreditationStatus.DRAFT.name());
        assertThat(expiredRenewal.getValidFrom()).isNull();
        assertThat(expiredRenewal.getValidTo()).isNull();
        assertThat(expired.getStatus()).isEqualTo(SupplierAccreditationStatus.EXPIRED.name());
        verify(checklistService, times(2)).snapshotFor(any(SupplierAccreditationApplication.class));
    }

    private void assertImmutable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.getMessage()).containsIgnoringCase("immutable");
                assertThat(exception.getMessageKey()).isEqualTo("ACCREDITATION_DECISION_IMMUTABLE");
            });
    }

    private SupplierAccreditationApplicationCreateRequest applicationRequest() {
        SupplierAccreditationApplicationCreateRequest request = new SupplierAccreditationApplicationCreateRequest();
        request.setSupplierId(UUID.randomUUID());
        request.setAccreditationType("GOODS");
        request.setRequestedScope("Rotating equipment");
        request.setRequestedMaterialGroupIds(List.of(UUID.randomUUID()));
        request.setValidFrom(LocalDate.now());
        request.setValidTo(LocalDate.now().plusMonths(6));
        return request;
    }

    private SupplierAccreditationApplicationUpdateRequest updateRequest() {
        SupplierAccreditationApplicationUpdateRequest update = new SupplierAccreditationApplicationUpdateRequest();
        update.setSupplierId(UUID.randomUUID());
        update.setAccreditationType("GOODS");
        update.setRequestedScope("Updated scope");
        update.setRequestedMaterialGroupIds(List.of(UUID.randomUUID()));
        update.setValidFrom(LocalDate.now());
        update.setValidTo(LocalDate.now().plusMonths(3));
        return update;
    }

    private SupplierAccreditationApplication approvalReadyApplication() {
        SupplierAccreditationApplication application = application(SupplierAccreditationStatus.COMPLIANCE_CHECK.name());
        application.setComplianceRecommendation("ACTIVE");
        application.setComplianceSnapshotJson("{}");
        return application;
    }

    private SupplierComplianceSummary activeSummary(SupplierAccreditationApplication application) {
        return new SupplierComplianceSummary(application.getSupplierId(), 1, 0, 0, 0, "ACTIVE");
    }

    private SupplierAccreditationDecisionActionRequest approvalRequest(
        List<UUID> groups,
        LocalDate validFrom,
        LocalDate validTo
    ) {
        return new SupplierAccreditationDecisionActionRequest(
            "APPROVED",
            groups,
            validFrom,
            validTo,
            "Approved"
        );
    }

    private SupplierAccreditationApplication application(String status) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(UUID.randomUUID());
        application.setTenantId(TenantContext.currentTenant());
        application.setCompanyId(companyId);
        application.setCode("ACC-" + UUID.randomUUID().toString().substring(0, 8));
        application.setName("Accreditation");
        application.setStatus(status);
        application.setSupplierId(UUID.randomUUID());
        application.setAccreditationType("GOODS");
        application.setRequestedScope("Scope");
        application.setRequestedMaterialGroupIds(List.of(UUID.randomUUID()));
        application.setValidFrom(LocalDate.now());
        application.setValidTo(LocalDate.now().plusMonths(6));
        return application;
    }

    private Supplier supplier(UUID supplierId, UUID supplierCompanyId) {
        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(supplierCompanyId);
        supplier.setCode("SUP-" + supplierId.toString().substring(0, 8));
        supplier.setName("Supplier");
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private SupplierAccreditationDecision decision(SupplierAccreditationApplication application) {
        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setId(UUID.randomUUID());
        decision.setTenantId(TenantContext.currentTenant());
        decision.setCompanyId(companyId);
        decision.setApplicationId(application.getId());
        decision.setStatus("RECORDED");
        decision.setDecision(SupplierAccreditationStatus.APPROVED.name());
        return decision;
    }

    private SupplierAccreditationDocument document(SupplierAccreditationApplication application, String status) {
        SupplierAccreditationDocument document = new SupplierAccreditationDocument();
        document.setId(UUID.randomUUID());
        document.setTenantId(TenantContext.currentTenant());
        document.setCompanyId(companyId);
        document.setApplicationId(application.getId());
        document.setDocumentType("REGISTRATION_CERTIFICATE");
        document.setStatus(status);
        document.setAttachmentId(UUID.randomUUID());
        document.setVerifiedBy("reviewer");
        document.setVerifiedAt(LocalDateTime.now());
        return document;
    }

    private Attachment attachment(SupplierAccreditationDocument document) {
        Attachment attachment = new Attachment();
        attachment.setId(document.getAttachmentId());
        attachment.setTenantId(TenantContext.currentTenant());
        attachment.setCompanyId(companyId);
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("SUPPLIER_ACCREDITATION_DOCUMENT");
        attachment.setEntityId(document.getId());
        return attachment;
    }

    private void stubMaterialGroups(List<UUID> groupIds, UUID groupCompanyId) {
        List<MaterialGroup> groups = groupIds.stream().map(groupId -> {
            MaterialGroup group = new MaterialGroup();
            group.setId(groupId);
            group.setTenantId(TenantContext.currentTenant());
            group.setCompanyId(groupCompanyId);
            group.setStatus("ACTIVE");
            return group;
        }).toList();
        when(materialGroupRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), groupCompanyId, groupIds)).thenReturn(groups);
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T withId(T entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.currentTenant());
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(companyId);
        }
        return entity;
    }

    @Test
    void approvalPassesWhenVerifiedDocumentAndActiveAttachmentAndCurrentValidity() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        SupplierAccreditationDocument evidence = document(application, "VERIFIED");
        evidence.setValidFrom(LocalDate.now().minusDays(1));
        evidence.setValidTo(LocalDate.now().plusMonths(12));
        Attachment attachment = attachment(evidence);
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(evidence));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, evidence.getAttachmentId())).thenReturn(Optional.of(attachment));
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);

        var approved = applicationService.approve(application.getId(), approvalRequest(
            application.getRequestedMaterialGroupIds(), LocalDate.now(), LocalDate.now().plusMonths(6)));

        assertThat(approved.getStatus()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());
    }

    @Test
    void approvalBlockedWhenVerifiedStatusButAttachmentIdIsNull() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        SupplierAccreditationDocument evidence = document(application, "VERIFIED");
        evidence.setAttachmentId(null);
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(evidence));
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);
        when(checklistService.checklist(eq(application), any())).thenReturn(List.of());

        assertThatThrownBy(() -> applicationService.approve(application.getId(), approvalRequest(
            application.getRequestedMaterialGroupIds(), LocalDate.now(), LocalDate.now().plusMonths(6))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active linked attachment");
    }

    @Test
    void approvalBlockedWhenDocumentBelongsToAnotherApplicationAndIsNotReusable() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of());
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);
        when(checklistService.checklist(eq(application), any())).thenReturn(List.of());

        assertThatThrownBy(() -> applicationService.approve(application.getId(), approvalRequest(
            application.getRequestedMaterialGroupIds(), LocalDate.now(), LocalDate.now().plusMonths(6))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active linked attachment");
    }

    @Test
    void approvalBlockedWhenVerifiedDocumentIsExpired() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        SupplierAccreditationDocument expiredEvidence = document(application, "VERIFIED");
        expiredEvidence.setValidFrom(LocalDate.now().minusMonths(12));
        expiredEvidence.setValidTo(LocalDate.now().minusDays(1));
        Attachment attachment = attachment(expiredEvidence);
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(expiredEvidence));
        lenient().when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, expiredEvidence.getAttachmentId())).thenReturn(Optional.of(attachment));
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);
        when(checklistService.checklist(eq(application), any())).thenReturn(List.of());

        assertThatThrownBy(() -> applicationService.approve(application.getId(), approvalRequest(
            application.getRequestedMaterialGroupIds(), LocalDate.now(), LocalDate.now().plusMonths(6))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active linked attachment");
    }

    @Test
    void approvalPassesAndMatchesChecklistWhenReusableVerifiedEvidenceExists() {
        SupplierAccreditationApplication application = approvalReadyApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(complianceService.summarize(application.getSupplierId())).thenReturn(activeSummary(application));
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of());
        stubMaterialGroups(application.getRequestedMaterialGroupIds(), companyId);

        com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistItem verifiedItem =
            new com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistItem(
                UUID.randomUUID(), application.getId(), "TAX_CERTIFICATE", "TAX_CERTIFICATE",
                true, true, UUID.randomUUID(),
                com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.EvidenceChecklistItemState.VERIFIED,
                LocalDate.now().plusMonths(12), "SUPPLIER_MANAGER", LocalDate.now().plusDays(10)
            );
        when(checklistService.checklist(eq(application), any())).thenReturn(List.of(verifiedItem));

        var approved = applicationService.approve(application.getId(), approvalRequest(
            application.getRequestedMaterialGroupIds(), LocalDate.now(), LocalDate.now().plusMonths(6)));

        assertThat(approved.getStatus()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());
    }
}

