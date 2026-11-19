package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.SupplierAccreditationApplicationMapper;
import com.enterprise.mm.payload.SupplierAccreditationDecisionActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.approval.ApprovalModuleAdapter;
import com.enterprise.mm.service.approval.ApprovalModuleRegistry;
import com.enterprise.mm.service.approval.SupplierAccreditationApprovalAdapter;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierAccreditationApprovalEngineTest {
    @Mock private SupplierAccreditationApplicationRepository applicationRepository;
    @Mock private SupplierAccreditationDecisionRepository decisionRepository;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @Mock private SupplierAccreditationDocumentRepository documentRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private SupplierComplianceService complianceService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierEvidenceChecklistService checklistService;

    private SupplierAccreditationService accreditationService;
    private SupplierAccreditationApprovalAdapter adapter;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("accreditation-approval-engine-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        accreditationService = new SupplierAccreditationService(
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
        adapter = new SupplierAccreditationApprovalAdapter(
            applicationRepository, supplierRepository, decisionRepository, accreditationService
        );
        lenient().when(applicationRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        lenient().when(decisionRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void adapterRegistersUnderSupplierAccreditationModuleKey() {
        ApprovalModuleRegistry registry = new ApprovalModuleRegistry(List.of(adapter));

        ApprovalModuleAdapter resolved = registry.adapter("SUPPLIER_ACCREDITATION");

        assertThat(resolved).isSameAs(adapter);
        assertThat(resolved.moduleKey()).isEqualTo(SupplierAccreditationApprovalAdapter.MODULE_KEY);
    }

    @Test
    void proposeDecisionRecordsProposedRowAndDoesNotMutateApplicationStatus() {
        SupplierAccreditationApplication application = readyApplication();
        stubApprovalReadiness(application);
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));

        var response = accreditationService.proposeDecision(application.getId(), approvalRequest(application));

        assertThat(response.getStatus()).isEqualTo(SupplierAccreditationStatus.COMPLIANCE_CHECK.name());
        assertThat(application.getStatus()).isEqualTo(SupplierAccreditationStatus.COMPLIANCE_CHECK.name());
        org.mockito.ArgumentCaptor<SupplierAccreditationDecision> captor =
            org.mockito.ArgumentCaptor.forClass(SupplierAccreditationDecision.class);
        org.mockito.Mockito.verify(decisionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PROPOSED");
        assertThat(captor.getValue().getDecision()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());
    }

    @Test
    void proposeDecisionRejectsWhenAlreadyProposed() {
        SupplierAccreditationApplication application = readyApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, application.getId(), "PROPOSED"
        )).thenReturn(Optional.of(new SupplierAccreditationDecision()));

        assertThatThrownBy(() -> accreditationService.proposeDecision(application.getId(), approvalRequest(application)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("уже отправлена");
    }

    @Test
    void finalizeApprovedDecisionAppliesTheProposedDecisionWithoutRecomputing() {
        SupplierAccreditationApplication application = pendingApprovalApplication();
        SupplierAccreditationDecision proposed = proposedDecision(application, SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name());
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, application.getId(), "PROPOSED"
        )).thenReturn(Optional.of(proposed));

        var response = accreditationService.finalizeApprovedDecision(application.getId(), "Approved by committee");

        assertThat(response.getStatus()).isEqualTo(SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name());
        assertThat(application.getReviewComment()).isEqualTo("Approved by committee");
        assertThat(proposed.getStatus()).isEqualTo("RECORDED");
    }

    @Test
    void finalizeApprovedDecisionFailsFastWhenNoProposalExists() {
        SupplierAccreditationApplication application = pendingApprovalApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, application.getId(), "PROPOSED"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accreditationService.finalizeApprovedDecision(application.getId(), "comment"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("No proposed accreditation decision");
    }

    @Test
    void cancelProposedDecisionCancelsTheDraftAndRejectsTheApplication() {
        SupplierAccreditationApplication application = pendingApprovalApplication();
        SupplierAccreditationDecision proposed = proposedDecision(application, SupplierAccreditationStatus.APPROVED.name());
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, application.getId(), "PROPOSED"
        )).thenReturn(Optional.of(proposed));

        var response = accreditationService.cancelProposedDecision(application.getId(), "Rejected by committee");

        assertThat(response.getStatus()).isEqualTo(SupplierAccreditationStatus.REJECTED.name());
        assertThat(application.getReviewComment()).isEqualTo("Rejected by committee");
        assertThat(proposed.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void directApproveAndRejectAreBlockedOnceAnApprovalInstanceExists() {
        SupplierAccreditationApplication application = readyApplication();
        when(approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "SUPPLIER_ACCREDITATION", application.getId()
        )).thenReturn(Optional.of(new ApprovalInstance()));

        assertThatThrownBy(() -> accreditationService.approve(application.getId(), approvalRequest(application)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("routed through the approval workflow");
        assertThatThrownBy(() -> accreditationService.reject(
            application.getId(), new SupplierAccreditationDecisionActionRequest(
                "REJECTED", null, null, null, "no")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("routed through the approval workflow");
    }

    @Test
    void adapterValidateCanSubmitRequiresComplianceStatusAndAProposedDecision() {
        SupplierAccreditationApplication application = readyApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));
        when(decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, application.getId(), "PROPOSED"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.validateCanSubmit(application.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("No proposed accreditation decision");

        application.setStatus(SupplierAccreditationStatus.DRAFT.name());
        assertThatThrownBy(() -> adapter.validateCanSubmit(application.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("COMPLIANCE_CHECK or COMMITTEE_REVIEW");
    }

    @Test
    void adapterMarkSubmittedMovesApplicationToPendingApproval() {
        SupplierAccreditationApplication application = readyApplication();
        when(applicationRepository.findByIdAndIsDeletedFalse(application.getId())).thenReturn(Optional.of(application));

        adapter.markSubmitted(application.getId());

        assertThat(application.getStatus()).isEqualTo(SupplierAccreditationStatus.PENDING_APPROVAL.name());
    }

    private void stubApprovalReadiness(SupplierAccreditationApplication application) {
        when(decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, application.getId(), "PROPOSED"
        )).thenReturn(Optional.empty());
        when(complianceService.summarize(application.getSupplierId())).thenReturn(
            new SupplierComplianceSummary(application.getSupplierId(), 0, 0, 0, 0, "ACTIVE"));
        SupplierAccreditationDocument evidence = document(application);
        Attachment attachment = attachment(evidence);
        when(documentRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, application.getId())).thenReturn(List.of(evidence));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, evidence.getAttachmentId())).thenReturn(Optional.of(attachment));
        stubMaterialGroups(application.getRequestedMaterialGroupIds());
    }

    private void stubMaterialGroups(List<UUID> groupIds) {
        List<MaterialGroup> groups = groupIds.stream().map(groupId -> {
            MaterialGroup group = new MaterialGroup();
            group.setId(groupId);
            group.setTenantId(TenantContext.currentTenant());
            group.setCompanyId(companyId);
            group.setStatus("ACTIVE");
            return group;
        }).toList();
        lenient().when(materialGroupRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, groupIds)).thenReturn(groups);
    }

    private SupplierAccreditationDecisionActionRequest approvalRequest(SupplierAccreditationApplication application) {
        return new SupplierAccreditationDecisionActionRequest(
            "APPROVED",
            application.getRequestedMaterialGroupIds(),
            LocalDate.now(),
            LocalDate.now().plusMonths(6),
            "Initiator comment"
        );
    }

    private SupplierAccreditationApplication readyApplication() {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(UUID.randomUUID());
        application.setTenantId(TenantContext.currentTenant());
        application.setCompanyId(companyId);
        application.setCode("ACC-" + UUID.randomUUID().toString().substring(0, 8));
        application.setName("Accreditation");
        application.setStatus(SupplierAccreditationStatus.COMPLIANCE_CHECK.name());
        application.setSupplierId(UUID.randomUUID());
        application.setAccreditationType("GOODS");
        application.setRequestedScope("Scope");
        application.setRequestedMaterialGroupIds(List.of(UUID.randomUUID()));
        application.setComplianceRecommendation("ACTIVE");
        application.setComplianceSnapshotJson("{}");
        return application;
    }

    private SupplierAccreditationApplication pendingApprovalApplication() {
        SupplierAccreditationApplication application = readyApplication();
        application.setStatus(SupplierAccreditationStatus.PENDING_APPROVAL.name());
        return application;
    }

    private SupplierAccreditationDecision proposedDecision(SupplierAccreditationApplication application, String decision) {
        SupplierAccreditationDecision proposed = new SupplierAccreditationDecision();
        proposed.setId(UUID.randomUUID());
        proposed.setTenantId(TenantContext.currentTenant());
        proposed.setCompanyId(companyId);
        proposed.setApplicationId(application.getId());
        proposed.setStatus("PROPOSED");
        proposed.setDecision(decision);
        proposed.setValidFrom(LocalDate.now());
        proposed.setValidTo(LocalDate.now().plusMonths(6));
        return proposed;
    }

    private SupplierAccreditationDocument document(SupplierAccreditationApplication application) {
        SupplierAccreditationDocument document = new SupplierAccreditationDocument();
        document.setId(UUID.randomUUID());
        document.setTenantId(TenantContext.currentTenant());
        document.setCompanyId(companyId);
        document.setApplicationId(application.getId());
        document.setDocumentType("REGISTRATION_CERTIFICATE");
        document.setStatus("VERIFIED");
        document.setAttachmentId(UUID.randomUUID());
        document.setVerifiedBy("reviewer");
        document.setVerifiedAt(LocalDateTime.now());
        document.setValidFrom(LocalDate.now().minusDays(1));
        document.setValidTo(LocalDate.now().plusMonths(12));
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
}
