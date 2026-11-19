package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ManualWarehouseOperationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Shared STOCK_MOVEMENT approval for ordinary movements and manual warehouse documents. */
@Component
@RequiredArgsConstructor
public class StockMovementApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "STOCK_MOVEMENT";

    private final StockMovementRepository movementRepository;
    private final StockMovementDocumentRepository documentRepository;
    private final AuditService auditService;
    private final ObjectProvider<ManualWarehouseOperationService> manualWarehouseOperations;

    @Override public String moduleKey() { return MODULE_KEY; }

    @Override
    public String requesterUsername(UUID entityId) {
        return movement(entityId).map(StockMovement::getCreatedBy)
            .orElseGet(() -> manualDocument(entityId).getCreatedBy());
    }

    @Override
    public UUID companyId(UUID entityId) {
        return movement(entityId).map(StockMovement::getCompanyId)
            .orElseGet(() -> manualDocument(entityId).getCompanyId());
    }

    @Override
    public String displayName(UUID entityId) {
        return movement(entityId).map(StockMovement::getName)
            .orElseGet(() -> manualDocument(entityId).getName());
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        String current = movement(entityId).map(StockMovement::getStatus)
            .orElseGet(() -> manualDocument(entityId).getStatus());
        StockMovementStatus status = status(current);
        if (status != StockMovementStatus.DRAFT && status != StockMovementStatus.RETURNED)
            throw new BusinessException("Stock movement must be DRAFT or RETURNED, current status is " + current);
    }

    @Override
    public void markSubmitted(UUID entityId) {
        Optional<StockMovement> movement = movement(entityId);
        if (movement.isPresent()) {
            setMovementStatus(movement.get(), StockMovementStatus.PENDING);
        } else {
            StockMovementDocument document = manualDocument(entityId);
            document.setStatus(StockMovementStatus.PENDING.name());
            document.setPostedAt(null);
            documentRepository.save(document);
            lines(document).forEach(line -> setMovementStatus(line, StockMovementStatus.PENDING));
        }
        auditService.log("StockMovement", entityId, "SUBMIT_APPROVAL", displayName(entityId), null, null);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        Optional<StockMovement> movement = movement(entityId);
        if (movement.isPresent()) {
            requirePending(movement.get().getStatus());
            setMovementStatus(movement.get(), StockMovementStatus.APPROVED);
        } else {
            StockMovementDocument document = manualDocument(entityId);
            requirePending(document.getStatus());
            document.setStatus(StockMovementStatus.APPROVED.name());
            document.setPostedAt(null);
            documentRepository.save(document);
            lines(document).forEach(line -> {
                requirePending(line.getStatus());
                setMovementStatus(line, StockMovementStatus.APPROVED);
            });
            manualWarehouseOperations.getObject().postApprovedDocument(entityId);
        }
        auditService.log("StockMovement", entityId, "APPROVE", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        Optional<StockMovement> movement = movement(entityId);
        if (movement.isPresent()) {
            requirePending(movement.get().getStatus());
            setMovementStatus(movement.get(), StockMovementStatus.RETURNED);
        } else {
            StockMovementDocument document = manualDocument(entityId);
            requirePending(document.getStatus());
            document.setStatus(StockMovementStatus.RETURNED.name());
            document.setPostedAt(null);
            documentRepository.save(document);
            lines(document).forEach(line -> setMovementStatus(line, StockMovementStatus.RETURNED));
        }
        auditService.log("StockMovement", entityId, "REJECT", comment, null, null);
    }

    @Override public void onReturned(UUID entityId, String comment) { markRejected(entityId, comment); }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        Optional<StockMovement> movement = movement(entityId);
        return movement.isPresent() ? List.of(documentLine(movement.get()))
            : lines(manualDocument(entityId)).stream().map(this::documentLine).toList();
    }

    private ApprovalDetailItem documentLine(StockMovement line) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("materialId", line.getMaterialId());
        values.put("warehouseId", line.getWarehouseId());
        values.put("targetWarehouseId", line.getTargetWarehouseId());
        values.put("storageLocationId", line.getStorageLocationId());
        values.put("targetStorageLocationId", line.getTargetStorageLocationId());
        values.put("stockBatchId", line.getStockBatchId());
        values.put("contractSpecificationId", line.getContractSpecificationId());
        values.put("movementType", line.getMovementType());
        values.put("quantity", line.getQuantity());
        values.put("unitCost", line.getUnitCost());
        values.put("totalCost", line.getTotalCost());
        values.put("movementDate", line.getMovementDate());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return new ApprovalDetailItem(line.getId().toString(),
            line.getCode() == null ? line.getName() : line.getCode(), values);
    }

    private Optional<StockMovement> movement(UUID id) {
        return movementRepository.findByIdAndIsDeletedFalse(id)
            .filter(value -> TenantContext.currentTenant().equals(value.getTenantId()));
    }

    private StockMovementDocument manualDocument(UUID id) {
        return documentRepository.findByIdAndIsDeletedFalse(id)
            .filter(value -> TenantContext.currentTenant().equals(value.getTenantId())
                && "MANUAL_WAREHOUSE".equals(value.getReferenceType())
                && value.getManualOperationPayload() != null)
            .orElseThrow(() -> new NotFoundException("StockMovement not found: " + id));
    }

    private List<StockMovement> lines(StockMovementDocument document) {
        return movementRepository.findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), document.getId());
    }

    private void setMovementStatus(StockMovement movement, StockMovementStatus status) {
        movement.setStatus(status.name());
        movement.setPostedAt(null);
        movementRepository.save(movement);
    }

    private void requirePending(String value) {
        if (status(value) != StockMovementStatus.PENDING)
            throw new BusinessException("Stock movement must be PENDING, current status is " + value);
    }

    private StockMovementStatus status(String value) {
        try { return StockMovementStatus.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException("Unknown stock movement status: " + value);
        }
    }
}
