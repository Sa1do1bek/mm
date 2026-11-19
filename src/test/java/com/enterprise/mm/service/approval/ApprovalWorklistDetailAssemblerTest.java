package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalHistoryAction;
import com.enterprise.mm.enums.ApprovalStepType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalHistoryResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalStepProgressView;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskResponse;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewLineResponse;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewResponse;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApprovalWorklistDetailAssemblerTest {
    private final ApprovalWorklistDetailAssembler assembler = new ApprovalWorklistDetailAssembler();

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test void transferApprovalShowsOnlyEvidenceLinkedToItsReviewedCase() {
        var registry=mock(ApprovalModuleRegistry.class);
        var adapter=mock(ApprovalModuleAdapter.class);
        var id=UUID.randomUUID();
        when(registry.adapter("TRANSFER_EXCEPTION")).thenReturn(adapter);
        var evidence=new com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem(UUID.randomUUID().toString(),"Signed unloading.pdf",java.util.Map.of("downloadable",true));
        when(adapter.evidence(id)).thenReturn(List.of(evidence));
        var detail=new ApprovalWorklistDetailAssembler(mock(AttachmentRepository.class),mock(IntegrationEventRepository.class),mock(AuditLogRepository.class),registry,null)
            .assemble(task(UUID.randomUUID(),null,"TRANSFER_EXCEPTION",id),List.of());
        assertThat(detail.evidence().status()).isEqualTo("READY");
        assertThat(detail.evidence().items()).containsExactly(evidence);
    }

    @Test
    void exposesDemandStockReviewAsReadySourceLines() {
        UUID instanceId = UUID.randomUUID();
        ApprovalTaskResponse task = task(
            instanceId,
            new DemandStockReviewResponse(
                UUID.randomUUID(),
                "Central warehouse",
                List.of(new DemandStockReviewLineResponse(
                    UUID.randomUUID(), UUID.randomUUID(), "Bearing", "pcs",
                    BigDecimal.TEN, BigDecimal.valueOf(8), BigDecimal.valueOf(3),
                    BigDecimal.valueOf(7), BigDecimal.valueOf(3), "Stock constrained"
                ))
            )
        );

        var detail = assembler.assemble(task, List.of());

        assertThat(detail.source().status()).isEqualTo("READY");
        assertThat(detail.sourceLines().status()).isEqualTo("READY");
        assertThat(detail.sourceLines().items()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("Bearing");
            assertThat(item.values()).containsEntry("requestedQuantity", BigDecimal.TEN);
        });
        assertThat(detail.evidence().status()).isEqualTo("PARTIAL");
    }

    @Test
    void approvalStepProgressUsesCompactApprovalStepResponseShape() {
        ApprovalTaskResponse task = task(UUID.randomUUID(), null);

        assertThat(ApprovalStepProgressView.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly(
                "stepOrder",
                "status",
                "assigneeType",
                "assigneeUsername",
                "assigneeName",
                "assigneeRole",
                "actedBy",
                "actedByName",
                "actedAt",
                "commentText",
                "isApprover"
            );

        assertThat(task.steps()).singleElement().satisfies(step -> {
            assertThat(step.stepOrder()).isEqualTo(1);
            assertThat(step.status()).isEqualTo(ApprovalTaskStatus.PENDING);
            assertThat(step.assigneeUsername()).isEqualTo("approver");
            assertThat(step.assigneeRole()).isEqualTo("DEPARTMENT_HEAD");
            assertThat(step.actedBy()).isNull();
            assertThat(step.actedAt()).isNull();
            assertThat(step.commentText()).isNull();
        });
    }

    @Test
    void marksEveryUnsupportedContextSectionAsPartialInsteadOfFabricatingEmptyData() {
        ApprovalTaskResponse task = task(UUID.randomUUID(), null);

        var detail = assembler.assemble(task, List.of());

        assertThat(detail.sourceLines().status()).isEqualTo("PARTIAL");
        assertThat(detail.evidence().status()).isEqualTo("PARTIAL");
        assertThat(detail.relatedDocuments().status()).isEqualTo("PARTIAL");
        assertThat(detail.integrationImpact().status()).isEqualTo("PARTIAL");
        assertThat(detail.auditTrail().status()).isEqualTo("PARTIAL");
        assertThat(detail.sourceLines().message()).contains("пока не подключены");
    }

    @Test
    void resolvesApprovalHistoryActorsToFullNames() {
        String tenantId = "tenant-approval-history-names";
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        UserRepository users = mock(UserRepository.class);
        User actor = user("admin_navazot", "Administrator Navazot");
        when(users.resolveActiveUserByUsername(tenantId, companyId, "admin_navazot"))
            .thenReturn(Optional.of(actor));
        ApprovalWorklistDetailAssembler namedAssembler = new ApprovalWorklistDetailAssembler(
            null,
            null,
            null,
            users
        );

        var detail = namedAssembler.assemble(
            task(UUID.randomUUID(), null),
            List.of(new ApprovalHistoryResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ApprovalHistoryAction.LINKED,
                "admin_navazot",
                null,
                null,
                "Approval flow linked",
                LocalDateTime.of(2026, 7, 30, 9, 55)
            ))
        );

        assertThat(detail.history().items()).singleElement().satisfies(item ->
            assertThat(item.values()).containsEntry("actor", "Administrator Navazot")
        );
    }

    @Test
    void loadsCompanyAndTenantScopedContextWithoutExposingStoredPayloadsOrSecrets() {
        String tenantId = "tenant-approval-context";
        UUID companyId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        AttachmentRepository attachments = mock(AttachmentRepository.class);
        IntegrationEventRepository integrations = mock(IntegrationEventRepository.class);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        ApprovalWorklistDetailAssembler scopedAssembler = new ApprovalWorklistDetailAssembler(
            attachments, integrations, auditLogs
        );

        Attachment attachment = attachment(entityId);
        when(attachments.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, "PURCHASEORDER", entityId
        )).thenReturn(List.of(attachment));
        when(attachments.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, "PURCHASE_ORDER", entityId
        )).thenReturn(List.of(attachment));

        IntegrationEvent event = integrationEvent(entityId);
        when(integrations.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, entityId
        )).thenReturn(List.of(event));

        AuditLog auditLog = auditLog(entityId);
        when(auditLogs.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            tenantId, companyId, "PurchaseOrder", entityId
        )).thenReturn(List.of(auditLog));

        var detail = scopedAssembler.assemble(
            task(UUID.randomUUID(), null, "PROCUREMENT_PURCHASE_ORDER", entityId),
            List.of()
        );

        assertThat(detail.evidence().status()).isEqualTo("READY");
        assertThat(detail.evidence().items()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("supplier-certificate.pdf");
            assertThat(item.values()).containsEntry("attachmentType", "SUPPLIER_CERTIFICATE");
            assertThat(item.values()).doesNotContainKeys("storageKey", "checksumSha256", "storageProvider");
        });
        assertThat(detail.integrationImpact().status()).isEqualTo("READY");
        assertThat(detail.integrationImpact().items()).singleElement().satisfies(item -> {
            assertThat(item.values()).containsEntry("eventType", "MM_PO_SENT");
            assertThat(item.values()).containsEntry("hasError", true);
            assertThat(item.values()).doesNotContainKeys(
                "payload", "idempotencyKey", "lastError", "externalId", "manualReason"
            );
        });
        assertThat(detail.auditTrail().status()).isEqualTo("READY");
        assertThat(detail.auditTrail().items()).singleElement().satisfies(item -> {
            assertThat(item.values()).containsEntry("action", "SUBMIT_FOR_APPROVAL");
            assertThat(item.values()).doesNotContainKeys("details", "ipAddress");
        });

        verify(attachments).findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, "PURCHASEORDER", entityId
        );
        verify(integrations).findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, entityId
        );
        verify(auditLogs).findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            tenantId, companyId, "PurchaseOrder", entityId
        );
    }

    @Test
    void treatsSuccessfullyLoadedEmptySourcesAsReadyForDirectSourceMapping() {
        UUID companyId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        TenantContext.setTenant("tenant-empty-context");
        CompanyContext.setCompany(companyId);

        AttachmentRepository attachments = mock(AttachmentRepository.class);
        IntegrationEventRepository integrations = mock(IntegrationEventRepository.class);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        ApprovalWorklistDetailAssembler scopedAssembler = new ApprovalWorklistDetailAssembler(
            attachments, integrations, auditLogs
        );

        var detail = scopedAssembler.assemble(
            task(UUID.randomUUID(), null, "SERVICE_FINANCE_APPROVAL", entityId),
            List.of()
        );

        assertThat(detail.evidence().status()).isEqualTo("READY");
        assertThat(detail.evidence().items()).isEmpty();
        assertThat(detail.integrationImpact().status()).isEqualTo("READY");
        assertThat(detail.auditTrail().status()).isEqualTo("READY");
        verify(attachments).findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-empty-context", companyId, "SERVICE_ACCEPTANCE_ACT", entityId
        );
        verify(auditLogs).findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            "tenant-empty-context", companyId, "ServiceAcceptanceAct", entityId
        );
    }

    @Test
    void leavesIndirectOrUnknownSourceMappingsExplicitlyPartial() {
        AttachmentRepository attachments = mock(AttachmentRepository.class);
        IntegrationEventRepository integrations = mock(IntegrationEventRepository.class);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        ApprovalWorklistDetailAssembler scopedAssembler = new ApprovalWorklistDetailAssembler(
            attachments, integrations, auditLogs
        );

        var indirect = scopedAssembler.assemble(
            task(UUID.randomUUID(), null, "NON_MOVING_STOCK_DECISION", UUID.randomUUID()),
            List.of()
        );

        assertThat(indirect.evidence().status()).isEqualTo("PARTIAL");
        assertThat(indirect.evidence().message()).contains("mapping");
        assertThat(indirect.integrationImpact().status()).isEqualTo("PARTIAL");
        assertThat(indirect.auditTrail().status()).isEqualTo("PARTIAL");
        verifyNoInteractions(attachments, integrations, auditLogs);
    }

    @Test
    void isolatesSourceLoadFailuresAndMarksOnlyThatSectionPartial() {
        String tenantId = "tenant-load-failure";
        UUID companyId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        AttachmentRepository attachments = mock(AttachmentRepository.class);
        IntegrationEventRepository integrations = mock(IntegrationEventRepository.class);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        when(attachments.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, "INVENTORY_DOCUMENT", entityId
        )).thenThrow(new IllegalStateException("database detail that must not escape"));
        ApprovalWorklistDetailAssembler scopedAssembler = new ApprovalWorklistDetailAssembler(
            attachments, integrations, auditLogs
        );

        var detail = scopedAssembler.assemble(
            task(UUID.randomUUID(), null, "INVENTORY_DOCUMENT", entityId),
            List.of()
        );

        assertThat(detail.evidence().status()).isEqualTo("PARTIAL");
        assertThat(detail.evidence().message())
            .contains("could not be loaded")
            .doesNotContain("database detail");
        assertThat(detail.integrationImpact().status()).isEqualTo("READY");
        assertThat(detail.auditTrail().status()).isEqualTo("READY");
        verify(integrations).findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, entityId
        );
        verify(auditLogs).findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            tenantId, companyId, "InventoryDocument", entityId
        );
        verify(attachments, never()).findByTenantIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, "INVENTORY_DOCUMENT", entityId
        );
    }

    private ApprovalTaskResponse task(UUID instanceId, DemandStockReviewResponse review) {
        return task(instanceId, review, "DEMAND_PLAN", UUID.randomUUID());
    }

    private ApprovalTaskResponse task(
        UUID instanceId,
        DemandStockReviewResponse review,
        String moduleKey,
        UUID entityId
    ) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 14, 10, 0);
        return new ApprovalTaskResponse(
            UUID.randomUUID(), instanceId, moduleKey, entityId, "DP-001",
            1, ApprovalStepType.DEMAND_STOCK_CHECK, review, ApprovalAssigneeType.USER,
            "DEPARTMENT_HEAD", UUID.randomUUID(), "Approver Full Name", ApprovalTaskStatus.PENDING, null, null,
            null, createdAt, 2,
            List.of(new ApprovalStepProgressView(
                1,
                ApprovalTaskStatus.PENDING,
                ApprovalAssigneeType.USER,
                "approver",
                "Approver Full Name",
                "DEPARTMENT_HEAD",
                null,
                null,
                null,
                null,
                true
            )),

            true, false, true,
            createdAt.plusHours(ApprovalWorklistQuery.SLA_HOURS), 24, "DAYS_1_TO_3", "DUE_SOON", "WARNING",
            null, null, "REVIEW_AND_DECIDE", List.of("APPROVE", "REJECT"), 0, "READY"
        );
    }

    private Attachment attachment(UUID entityId) {
        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID());
        attachment.setEntityId(entityId);
        attachment.setEntityType("PURCHASEORDER");
        attachment.setAttachmentType("SUPPLIER_CERTIFICATE");
        attachment.setOriginalFileName("supplier-certificate.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(2048L);
        attachment.setUploadedAt(LocalDateTime.of(2026, 7, 14, 9, 0));
        attachment.setPreviewable(true);
        attachment.setDownloadable(true);
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("secret/storage/key");
        attachment.setChecksumSha256("secret-checksum");
        return attachment;
    }

    private IntegrationEvent integrationEvent(UUID entityId) {
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateId(entityId);
        event.setAggregateType("PurchaseOrder");
        event.setEventType("MM_PO_SENT");
        event.setStatus("RETRY");
        event.setRetryCount(2);
        event.setNextRetryAt(LocalDateTime.of(2026, 7, 14, 12, 0));
        event.setLastAttemptAt(LocalDateTime.of(2026, 7, 14, 11, 0));
        event.setCreatedAt(LocalDateTime.of(2026, 7, 14, 10, 0));
        event.setPayload("{\"password\":\"must-not-escape\"}");
        event.setIdempotencyKey("secret-idempotency-key");
        event.setLastError("Authorization: Bearer must-not-escape");
        event.setExternalId("secret-external-id");
        event.setManualReason("secret-manual-reason");
        return event;
    }

    private AuditLog auditLog(UUID entityId) {
        AuditLog log = new AuditLog();
        log.setId(UUID.randomUUID());
        log.setEntityId(entityId);
        log.setEntityType("PurchaseOrder");
        log.setAction("SUBMIT_FOR_APPROVAL");
        log.setUsername("approver");
        log.setOccurredAt(LocalDateTime.of(2026, 7, 14, 10, 30));
        log.setDetails("{\"password\":\"must-not-escape\"}");
        log.setIpAddress("10.0.0.1");
        return log;
    }

    private User user(String username, String fullName) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setName(fullName);
        user.setFullName(fullName);
        user.setStatus("ACTIVE");
        return user;
    }
}
