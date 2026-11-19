package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.InventoryLineHistory;
import com.enterprise.mm.payload.InventoryLineHistoryResponse;
import com.enterprise.mm.repository.InventoryLineHistoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryLineHistoryService {
    private final InventoryLineHistoryRepository repository;
    private final com.enterprise.mm.service.inventory.InventoryAccessService inventoryAccess;

    public List<InventoryLineHistoryResponse> list(UUID inventoryItemId) {
        var line = inventoryAccess.line(inventoryItemId);
        boolean visible = inventoryAccess.canSeeSystemQuantity(inventoryAccess.document(line.getInventoryDocumentId()));
        return repository.findByTenantIdAndInventoryItemIdAndIsDeletedFalseOrderByChangedAtAsc(
                TenantContext.currentTenant(),
                inventoryItemId
            )
            .stream()
            .map(this::toResponse)
            .map(row -> com.enterprise.mm.service.inventory.InventoryReadPolicy.project(row, visible))
            .toList();
    }

    public void record(
        String action,
        InventoryItem before,
        InventoryItem after,
        String reason,
        String changedBy
    ) {
        InventoryLineHistory history = new InventoryLineHistory();
        history.setCompanyId(after.getCompanyId());
        history.setInventoryItemId(after.getId());
        history.setInventoryDocumentId(after.getInventoryDocumentId());
        history.setAction(action);
        history.setOldCountRevision(before == null ? null : before.getCountRevision());
        history.setNewCountRevision(after.getCountRevision());
        var evidence = new java.util.LinkedHashMap<String,Object>();
        evidence.put("decisionType", after.getDecisionType()); evidence.put("decisionCountRevision", after.getDecisionCountRevision());
        evidence.put("decisionEvidenceIds", after.getDecisionEvidenceIds()); evidence.put("foundEvidenceIds", after.getFoundEvidenceIds()); evidence.put("valuationEvidenceIds", after.getValuationEvidenceIds());
        evidence.put("sourceCorrectionMovementId", after.getSourceCorrectionMovementId()); evidence.put("reviewBlocker", after.getReviewBlocker());
        evidence.put("unitCost", after.getUnitCost()); evidence.put("valuationBasis", after.getValuationBasis()); evidence.put("valuationCountRevision", after.getValuationCountRevision());
        evidence.put("explanationStatus", after.getExplanationStatus()); evidence.put("decidedBy", after.getDecidedBy());
        history.setReviewEvidence(evidence);
        history.setOldCountedQuantity(before == null ? null : before.getCountedQuantity());
        history.setNewCountedQuantity(after.getCountedQuantity());
        history.setOldStatus(before == null ? null : before.getLineStatus());
        history.setNewStatus(after.getLineStatus());
        history.setOldDifferenceQuantity(before == null ? null : before.getDifferenceQuantity());
        history.setNewDifferenceQuantity(after.getDifferenceQuantity());
        history.setReason(trim(reason));
        history.setChangedBy(changedBy == null || changedBy.isBlank() ? "system" : changedBy);
        history.setChangedAt(LocalDateTime.now());
        history.setStatus("ACTIVE");
        history.setCode("INV-LINE-HIST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        history.setName(history.getAction());
        repository.save(history);
    }

    private InventoryLineHistoryResponse toResponse(InventoryLineHistory entity) {
        InventoryLineHistoryResponse response = new InventoryLineHistoryResponse();
        response.setId(entity.getId());
        response.setTenantId(entity.getTenantId());
        response.setCompanyId(entity.getCompanyId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setDescription(entity.getDescription());
        response.setDeleted(entity.isDeleted());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setUpdatedBy(entity.getUpdatedBy());
        response.setVersion(entity.getVersion());
        response.setInventoryItemId(entity.getInventoryItemId());
        response.setInventoryDocumentId(entity.getInventoryDocumentId());
        response.setAction(entity.getAction());
        response.setOldCountRevision(entity.getOldCountRevision()); response.setNewCountRevision(entity.getNewCountRevision()); response.setReviewEvidence(entity.getReviewEvidence());
        response.setOldCountedQuantity(entity.getOldCountedQuantity());
        response.setNewCountedQuantity(entity.getNewCountedQuantity());
        response.setOldStatus(entity.getOldStatus());
        response.setNewStatus(entity.getNewStatus());
        response.setOldDifferenceQuantity(entity.getOldDifferenceQuantity());
        response.setNewDifferenceQuantity(entity.getNewDifferenceQuantity());
        response.setReason(entity.getReason());
        response.setChangedBy(entity.getChangedBy());
        response.setChangedAt(entity.getChangedAt());
        return response;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
