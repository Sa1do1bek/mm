package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.AttachmentEntityAccessPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class InventoryLineEvidenceAccessTest {
    @Mock InventoryAccessService access;
    @Mock AttachmentRepository attachments;
    @Mock com.enterprise.mm.service.InventoryDocumentService documents;
    @Mock com.enterprise.mm.service.InventoryLineHistoryService history;
    @InjectMocks AttachmentEntityAccessPolicy policy;
    @InjectMocks InventoryWorkspaceService workspace;
    private final UUID lineId = UUID.randomUUID(), documentId = UUID.randomUUID();
    private InventoryDocument document;
    private InventoryItem line;

    @BeforeEach void setup() {
        document = new InventoryDocument(); document.setId(documentId); document.setTenantId("tenant"); document.setCompanyId(UUID.randomUUID());
        document.setStatus("IN_PROGRESS"); document.setSnapshotAt(LocalDateTime.now());
        line = new InventoryItem(); line.setId(lineId); line.setInventoryDocumentId(documentId);
    }

    @Test void blindCountersCanReadAndUploadObservationFilesWithoutReviewExportAuthority() {
        when(access.line(lineId)).thenReturn(line); when(access.document(documentId)).thenReturn(document);
        policy.requireRead("INVENTORY_LINE_COUNT_EVIDENCE", lineId);
        policy.requireUpload("INVENTORY_LINE_COUNT_EVIDENCE", lineId, "EVIDENCE");
        verify(access).requireCount(document); verify(access, never()).requireVisibleExport(any());
    }

    @Test void readAccessDoesNotGrantCountingUploadAuthority() {
        when(access.line(lineId)).thenReturn(line); when(access.document(documentId)).thenReturn(document);
        doThrow(new AccessDeniedException("Not a counter")).when(access).requireCount(document);
        assertThatThrownBy(() -> policy.requireUpload("INVENTORY_LINE_COUNT_EVIDENCE", lineId, "EVIDENCE")).isInstanceOf(AccessDeniedException.class);
    }

    @Test void reviewedAndPostedCountsRejectNewUploadsAndEvidenceCannotBeDeletedAfterSnapshot() {
        when(access.line(lineId)).thenReturn(line); when(access.document(documentId)).thenReturn(document);
        for (var status : List.of("APPROVED", "WAITING_APPROVAL", "ADJUSTMENT_POSTED", "CLOSED", "CANCELLED")) {
            document.setStatus(status);
            assertThatThrownBy(() -> policy.requireUpload("INVENTORY_LINE_COUNT_EVIDENCE", lineId, "EVIDENCE")).isInstanceOf(BusinessException.class);
            assertThatCode(() -> policy.requireRead("INVENTORY_LINE_COUNT_EVIDENCE", lineId)).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> policy.requireDelete("INVENTORY_LINE_COUNT_EVIDENCE", lineId, "counter")).isInstanceOf(AccessDeniedException.class);
    }

    @Test void reviewAndFinancialAttachmentsKeepTheirExistingVisibilityRestriction() {
        when(access.line(lineId)).thenReturn(line); when(access.document(documentId)).thenReturn(document);
        doThrow(new AccessDeniedException("Blind counter")).when(access).requireVisibleExport(document);
        assertThatThrownBy(() -> policy.requireRead("INVENTORY_ITEM", lineId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireRead("INVENTORY_DOCUMENT", documentId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void rowFilesCanBeUsedOnlyForTheirOwnLineAndCompanyDecision() {
        var attachment = new Attachment(); attachment.setId(UUID.randomUUID()); attachment.setEntityType("INVENTORY_LINE_COUNT_EVIDENCE");
        attachment.setEntityId(lineId); attachment.setTenantId(document.getTenantId()); attachment.setCompanyId(document.getCompanyId());
        when(attachments.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        var evidence = new InventoryEvidenceService(attachments);
        assertThat(evidence.validate(document, lineId, List.of(attachment.getId()), true)).containsExactly(attachment.getId());
        assertThatThrownBy(() -> evidence.validate(document, UUID.randomUUID(), List.of(attachment.getId()), true)).isInstanceOf(BadRequestException.class);
        attachment.setCompanyId(UUID.randomUUID());
        assertThatThrownBy(() -> evidence.validate(document, lineId, List.of(attachment.getId()), true)).isInstanceOf(BadRequestException.class);
    }

    @Test void warehouseObserversCannotUploadOrDeleteLegacyDocumentAndLineAttachments() {
        when(access.document(documentId)).thenReturn(document);
        when(access.line(lineId)).thenReturn(line);
        for (String type : List.of("INVENTORY_DOCUMENT", "INVENTORY_ITEM")) {
            UUID id = type.equals("INVENTORY_ITEM") ? lineId : documentId;
            assertThatCode(() -> policy.requireRead(type, id)).doesNotThrowAnyException();
            assertThatThrownBy(() -> policy.requireUpload(type, id, "EVIDENCE")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> policy.requireDelete(type, id, "observer")).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test void workspaceLoadsFileIndicatorsInOneCompanyScopedQueryForItsOwnLines() {
        when(access.document(documentId)).thenReturn(document);
        var row = new com.enterprise.mm.payload.InventoryItemResponse(); row.setId(lineId);
        var dto = new com.enterprise.mm.payload.InventoryDocumentResponse(); dto.setId(documentId); dto.setItems(List.of(row));
        when(documents.get(documentId)).thenReturn(dto); when(history.list(lineId)).thenReturn(List.of());
        var first = new Attachment(); first.setEntityId(lineId);
        var second = new Attachment(); second.setEntityId(lineId);
        when(attachments.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdInAndIsDeletedFalseOrderByCreatedAtDesc(
            document.getTenantId(), document.getCompanyId(), "INVENTORY_LINE_COUNT_EVIDENCE", List.of(lineId))).thenReturn(List.of(first, second));
        var result = workspace.get(documentId);
        assertThat(result.lines().getFirst().getCountEvidenceCount()).isEqualTo(2);
        verify(attachments).findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdInAndIsDeletedFalseOrderByCreatedAtDesc(
            document.getTenantId(), document.getCompanyId(), "INVENTORY_LINE_COUNT_EVIDENCE", List.of(lineId));
        verifyNoMoreInteractions(attachments);
    }
}
