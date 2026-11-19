package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.InventoryAiDraft;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.InventoryAiDraftRepository;
import com.enterprise.mm.service.inventory.InventoryAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class InventoryAiDraftServiceTest {
    private final UUID company = UUID.randomUUID();
    private final UUID draftId = UUID.randomUUID();
    private final InventoryAiDraftRepository drafts = mock(InventoryAiDraftRepository.class);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final AiAnalysisJobRepository jobs = mock(AiAnalysisJobRepository.class);
    private final AttachmentService fileService = mock(AttachmentService.class);
    private final InventoryAccessService access = mock(InventoryAccessService.class);
    private final InventoryAiDraftService service = new InventoryAiDraftService(drafts, attachments, jobs, fileService, access,
        mock(InventoryOrderAnalysisScopeService.class));

    @BeforeEach void setup() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
        when(access.username()).thenReturn("planner");
        when(access.hasRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD")).thenReturn(true);
        InventoryAiDraft draft = new InventoryAiDraft();
        draft.setId(draftId);
        draft.setStatus("OPEN");
        draft.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(drafts.findForUpdateByIdAndTenantIdAndCompanyIdAndOwnerUsername(draftId, "tenant-a", company, "planner"))
            .thenReturn(Optional.of(draft));
    }

    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); }

    @Test
    void rejectsDuplicateDocumentBeforeStorage() throws Exception {
        byte[] bytes = "%PDF-1.7\nexample".getBytes(StandardCharsets.US_ASCII);
        Attachment existing = new Attachment();
        existing.setChecksumSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        existing.setSizeBytes((long) bytes.length);
        when(attachments.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-a", company, InventoryAiDraftService.ENTITY_TYPE, draftId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.upload(draftId, new MockMultipartFile("file", "order.pdf", "application/pdf", bytes)))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("already attached");
        verify(fileService, never()).uploadDraftSource(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsSpoofedPdfAndForeignDraft() {
        assertThatThrownBy(() -> service.upload(draftId,
            new MockMultipartFile("file", "order.pdf", "application/pdf", "not a PDF".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("does not match");
        when(drafts.findForUpdateByIdAndTenantIdAndCompanyIdAndOwnerUsername(draftId, "tenant-a", company, "planner"))
            .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(draftId,
            new MockMultipartFile("file", "order.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.US_ASCII))))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void newAnalysisSnapshotRequestsCommissionVersionOne() {
        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID());
        attachment.setOriginalFileName("order.pdf");
        attachment.setContentType("application/pdf");
        attachment.setChecksumSha256("a".repeat(64));
        when(attachments.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-a", company, InventoryAiDraftService.ENTITY_TYPE, draftId)).thenReturn(List.of(attachment));

        assertThat(service.snapshot(draftId, "planner").commissionAnalysisVersion()).isEqualTo(1);
    }
}
