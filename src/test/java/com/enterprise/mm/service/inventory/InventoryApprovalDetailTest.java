package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.approval.InventoryDocumentApprovalAdapter;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryApprovalDetailTest {
    final InventoryAccessService access = mock(InventoryAccessService.class);
    final InventoryItemRepository items = mock(InventoryItemRepository.class);
    final AttachmentRepository attachments = mock(AttachmentRepository.class);
    final InventoryDocumentApprovalAdapter adapter = new InventoryDocumentApprovalAdapter(
        mock(InventoryDocumentRepository.class), items, null, access, null,
        new InventoryEvidenceService(attachments), null, null, attachments, null);

    private InventoryItem line() {
        var document = new InventoryDocument(); document.setId(UUID.randomUUID()); document.setTenantId("tenant"); document.setCompanyId(UUID.randomUUID());
        var line = new InventoryItem(); line.setId(UUID.randomUUID()); line.setInventoryDocumentId(document.getId());
        line.setName("Bearing 6204"); line.setSystemQuantity(BigDecimal.TEN); line.setCountedQuantity(BigDecimal.TEN); line.setDifferenceQuantity(BigDecimal.ZERO);
        line.setUnitCost(null);
        when(access.document(document.getId())).thenReturn(document);
        when(items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse("tenant", document.getId())).thenReturn(List.of(line));
        return line;
    }
    @Test void matchedRowsWithoutDecisionNoteOrCostHaveReadableDetail() {
        var line = line();
        var detail = adapter.documentLines(line.getInventoryDocumentId()).getFirst();
        assertThat(detail.values()).containsEntry("systemQuantity", BigDecimal.TEN).containsEntry("differenceQuantity", BigDecimal.ZERO);
        assertThat(detail.values()).doesNotContainKeys("decision", "reason", "unitCost");
    }
    @Test void optionalAttachmentMetadataDoesNotPreventEvidenceLoading() {
        var line = line(); var d = access.document(line.getInventoryDocumentId());
        var file = new Attachment(); file.setId(UUID.randomUUID()); file.setTenantId(d.getTenantId()); file.setCompanyId(d.getCompanyId());
        file.setEntityType("INVENTORY_COUNT_EVIDENCE"); file.setEntityId(d.getId()); file.setOriginalFileName("signed-sheet.xlsx");
        line.setDecisionEvidenceIds(List.of(file.getId())); when(attachments.findById(file.getId())).thenReturn(Optional.of(file));
        var detail = adapter.evidence(d.getId()).getFirst();
        assertThat(detail.values()).containsEntry("fileName", "signed-sheet.xlsx");
        assertThat(detail.values()).doesNotContainKeys("contentType", "uploadedAt", "sizeBytes");
    }
}
