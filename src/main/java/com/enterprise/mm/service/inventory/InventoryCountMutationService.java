package com.enterprise.mm.service.inventory;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.mapper.InventoryItemMapper;
import com.enterprise.mm.payload.InventoryCountBatchDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Short parent-then-line transactions; never called through self-invocation from the batch loop. */
@Service
@RequiredArgsConstructor
public class InventoryCountMutationService {
    private final InventoryAccessService access;
    private final InventoryRevisionService revisions;
    private final InventoryDocumentRepository documents;
    private final InventoryItemRepository items;
    private final InventoryItemMapper mapper;
    private final InventoryLineHistoryService history;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long admitBatch(UUID id, BatchCommand command, String key) {
        var document = lock(id);
        access.requireCount(document);
        validateBatch(command, key);
        return idempotency.execute("INVENTORY_COUNT_BATCH_ADMIT", id, key, command, Long.class, () -> {
            if (!Objects.equals(document.getVersion(), command.expectedDocumentVersion())) throw new ConflictException("The count changed. Refresh its version while keeping your observations.", "DOCUMENT_VERSION_CONFLICT");
            audit.log("InventoryDocument", id, "COUNT_BATCH_ADMITTED", "Document version " + document.getVersion(), null, null);
            return document.getVersion();
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RowResult saveOne(UUID id, RowCommand command, String rowKey) {
        var document = lock(id);
        access.requireCount(document);
        if (rowKey == null || rowKey.isBlank() || rowKey.length() > 160) throw new BadRequestException("A stable row operation key is required");
        var result = idempotency.execute("INVENTORY_COUNT_ROW", id, rowKey, command, RowResult.class,
            () -> mutate(document, command));
        boolean visible = access.canSeeSystemQuantity(document);
        return new RowResult(result.lineId(), result.outcome(), InventoryReadPolicy.project(result.saved(), visible),
            InventoryReadPolicy.project(result.current(), visible), result.errorCode(), result.message());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DocumentRevision currentRevision(UUID id) {
        var document = access.document(id);
        return new DocumentRevision(document.getVersion(), document.getCountRevision());
    }

    private InventoryDocument lock(UUID id) {
        var document = access.document(id);
        // Refresh while acquiring the lock: upgrading a stale managed entity via a locking query
        // can fail Hibernate's version check before its newer row is loaded.
        entityManager.refresh(document, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        access.requireRead(document);
        return document;
    }

    private RowResult mutate(InventoryDocument document, RowCommand command) {
        var line = items.findByIdAndInventoryDocumentIdAndTenantIdAndCompanyIdAndIsDeletedFalse(command.lineId(), document.getId(), document.getTenantId(), document.getCompanyId()).orElse(null);
        if (line == null) return failure(command, "INVALID", null, "LINE_OUTSIDE_DOCUMENT", "This line is not part of the selected count.");
        entityManager.refresh(line, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (document.getSnapshotAt() == null || line.getSystemQuantity() == null) return failure(command, "NOT_EDITABLE", line, "SNAPSHOT_REQUIRED", "Restart this count with a valid stock snapshot.");
        if (!InventoryStatus.from(document.getStatus()).countable()) return failure(command, "NOT_EDITABLE", line, "COUNT_NOT_EDITABLE", "Counting is closed for this revision. Keep your observation and ask the reviewer to return it.");
        if (!Objects.equals(line.getVersion(), command.expectedLineVersion())) return failure(command, "CONFLICT", line, "LINE_VERSION_CONFLICT", "Another counter changed this line. Review their saved observation before saving yours.");
        boolean serial = line.getObservedSerialNumber() != null || line.getStockBatch() != null && line.getStockBatch().getSerialNumber() != null && !line.getStockBatch().getSerialNumber().isBlank();
        try {
            validateQuantity(command.countedQuantity(), command.clearCount(), quantityScale(line), serial);
            if (command.comment() != null && command.comment().length() > 1000) throw new BadRequestException("Count comments may contain at most 1000 characters");
        } catch (BadRequestException invalid) {
            return failure(command, "INVALID", line, "INVALID_OBSERVATION", invalid.getMessage());
        }
        BigDecimal quantity = command.clearCount() ? null : command.countedQuantity();
        String comment = command.comment() == null || command.comment().isBlank() ? null : command.comment().trim();
        boolean changed = !sameQuantity(line.getCountedQuantity(), quantity) || !Objects.equals(line.getCountComment(), comment);
        if (changed) {
            var before = new InventoryItem(); before.setCountedQuantity(line.getCountedQuantity()); before.setDifferenceQuantity(line.getDifferenceQuantity()); before.setLineStatus(line.getLineStatus());
            before.setCountRevision(line.getCountRevision());
            revisions.invalidate(document.getId(), "Count observation changed");
            document.setStatus(InventoryStatus.IN_PROGRESS.name());
            line.setCountRevision(document.getCountRevision()); line.setCountedQuantity(quantity);
            line.setCountedAt(quantity == null ? null : LocalDateTime.now()); line.setCountedBy(quantity == null ? null : access.username());
            line.setCountComment(comment);
            clearReview(line);
            var difference = quantity == null ? BigDecimal.ZERO : quantity.subtract(line.getSystemQuantity());
            line.setDifferenceQuantity(difference); line.setDifferenceAmount(difference.abs().multiply(line.getUnitCost() == null ? BigDecimal.ZERO : line.getUnitCost()));
            line.setLineStatus(quantity == null ? InventoryLineStatus.NOT_COUNTED : difference.signum() == 0 ? InventoryLineStatus.MATCHED : InventoryLineStatus.DISCREPANCY);
            line.setStatus(line.getLineStatus().name());
            line.setDiscrepancyType(quantity == null || difference.signum() == 0 ? null : difference.signum() < 0 ? InventoryDiscrepancyType.SHORTAGE : InventoryDiscrepancyType.SURPLUS);
            items.saveAndFlush(line);
            document.setTotalItems((int) items.countByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(document.getTenantId(), document.getId()));
            document.setCountedItems((int) items.countByTenantIdAndInventoryDocumentIdAndCountedQuantityIsNotNullAndIsDeletedFalse(document.getTenantId(), document.getId()));
            document.setDiscrepancyItems((int) items.countByTenantIdAndInventoryDocumentIdAndDifferenceQuantityNotAndIsDeletedFalse(document.getTenantId(), document.getId(), BigDecimal.ZERO));
            documents.saveAndFlush(document);
            history.record(command.clearCount() ? "CLEAR_COUNT" : "COUNT", before, line, comment, access.username());
            audit.log("InventoryItem", line.getId(), command.clearCount() ? "CLEAR_COUNT" : "COUNT", "Count revision " + line.getCountRevision(), null, null);
        }
        return new RowResult(line.getId(), "SAVED", mapper.toResponse(line), null, null, null);
    }

    public static void clearReview(InventoryItem line) {
        line.setDecisionEvidenceIds(java.util.List.of()); line.setValuationCountRevision(null); line.setReviewBlocker(line.getSourceCorrectionMovementId() == null ? null : "SOURCE_CORRECTION_REQUIRES_RECONCILIATION");
        line.setDecisionType(null); line.setDecisionCountRevision(null); line.setDecidedAt(null); line.setDecidedBy(null);
        line.setDecisionComment(null); line.setDiscrepancyReason(null); line.setCompensationAmount(null); line.setReconciledSourceMovementId(null);
        line.setExplanationStatus(InventoryExplanationStatus.NOT_REQUIRED); line.setExplanationRequestedFrom(null); line.setExplanationRequestedAt(null);
        line.setExplanationText(null); line.setExplanationProvidedAt(null); line.setExplanationReviewComment(null);
        line.setRecountRequestedAt(null); line.setRecountReason(null);
    }
    private RowResult failure(RowCommand command, String outcome, InventoryItem current, String code, String message) {
        return new RowResult(command.lineId(), outcome, null, current == null ? null : mapper.toResponse(current), code, message);
    }
    private boolean sameQuantity(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }

    public static void validateBatch(BatchCommand command, String key) {
        if (key == null || key.isBlank() || key.length() > 120) throw new BadRequestException("Idempotency-Key must contain 1 to 120 characters");
        if (command == null || command.expectedDocumentVersion() == null || command.expectedDocumentVersion() < 0 || command.rows() == null || command.rows().isEmpty() || command.rows().size() > 100) throw new BadRequestException("A document version and 1 to 100 changed rows are required");
        var ids = new HashSet<UUID>();
        for (var row : command.rows()) if (row == null || row.lineId() == null || row.expectedLineVersion() == null || row.expectedLineVersion() < 0 || !ids.add(row.lineId())) throw new BadRequestException("Each row requires a unique line ID and current version");
    }
    public static int quantityScale(InventoryItem line) {
        if (line.getObservedSerialNumber() != null && !line.getObservedSerialNumber().isBlank()
            || line.getStockBatch() != null && line.getStockBatch().getSerialNumber() != null
                && !line.getStockBatch().getSerialNumber().isBlank()) return 0;
        return line.getMaterial() == null ? 4 : com.enterprise.mm.service.StockUomPolicy.quantityScale(line.getMaterial());
    }

    public static void validateQuantity(BigDecimal value, boolean clear, int scale, boolean serialized) {
        if (clear) { if (value != null) throw new BadRequestException("Clear count must not include a quantity"); return; }
        if (value == null) throw new BadRequestException("Enter a quantity, including zero, or explicitly clear the count");
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > Math.min(com.enterprise.mm.service.StockUomPolicy.MAX_QUANTITY_SCALE, scale) || value.precision() - value.scale() > 15) throw new BadRequestException("Enter a non-negative quantity using the unit's allowed decimal precision");
        if (serialized && (value.stripTrailingZeros().scale() > 0 || value.compareTo(BigDecimal.ONE) > 0)) throw new BadRequestException("A serialized stock position can be counted as zero or one");
    }
}
