package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.InventoryItemResponse;
import com.enterprise.mm.payload.InventoryItemUpdateRequest;
import com.enterprise.mm.payload.InventoryItemCreateRequest;
import com.enterprise.mm.entity.InventoryItem;
import org.springframework.stereotype.Component;

@Component
public class InventoryItemMapper {
    public InventoryItemResponse toResponse(InventoryItem entity) {
        if (entity == null) {
            return null;
        }
        InventoryItemResponse dto = new InventoryItemResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setInventoryDocumentId(entity.getInventoryDocumentId());
        dto.setInventoryDocumentCode(BaseMapper.code(entity.getInventoryDocument()));
        dto.setInventoryDocumentName(BaseMapper.name(entity.getInventoryDocument()));
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setSystemQuantity(entity.getSystemQuantity());
        dto.setAsOfQuantity(entity.getAsOfQuantity());
        dto.setAsOfValue(entity.getAsOfValue());
        if (entity.getAsOfQuantity() != null && entity.getSystemQuantity() != null)
            dto.setMovementDelta(entity.getSystemQuantity().subtract(entity.getAsOfQuantity()));
        dto.setCountedQuantity(entity.getCountedQuantity());
        dto.setDifferenceQuantity(entity.getDifferenceQuantity());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStorageLocationCode(BaseMapper.code(entity.getStorageLocation()));
        dto.setStorageLocationName(BaseMapper.name(entity.getStorageLocation()));
        dto.setStorageBinId(entity.getStorageBinId());
        dto.setStorageBinCode(BaseMapper.storageBinCode(entity.getStorageBin()));
        dto.setStorageBinName(BaseMapper.storageBinName(entity.getStorageBin()));
        dto.setStockBatchId(entity.getStockBatchId());
        dto.setStockBatchCode(BaseMapper.stockBatchCode(entity.getStockBatch()));
        dto.setStockBatchName(BaseMapper.stockBatchName(entity.getStockBatch()));
        dto.setUnitCost(entity.getUnitCost());
        dto.setDifferenceAmount(entity.getDifferenceAmount());
        dto.setLineStatus(entity.getLineStatus());
        dto.setDiscrepancyType(entity.getDiscrepancyType());
        dto.setDiscrepancyReason(entity.getDiscrepancyReason());
        dto.setDecisionType(entity.getDecisionType());
        dto.setResponsibleUserId(entity.getResponsibleUserId());
        dto.setResponsibleUserCode(BaseMapper.userCode(entity.getResponsibleUser()));
        dto.setResponsibleUserName(BaseMapper.userName(entity.getResponsibleUser()));
        dto.setLinkedStockMovementId(entity.getLinkedStockMovementId());
        dto.setLinkedStockMovementCode(BaseMapper.code(entity.getLinkedStockMovement()));
        dto.setLinkedStockMovementName(BaseMapper.name(entity.getLinkedStockMovement()));
        dto.setLinkedPurchaseOrderId(entity.getLinkedPurchaseOrderId());
        dto.setLinkedPurchaseOrderCode(BaseMapper.code(entity.getLinkedPurchaseOrder()));
        dto.setLinkedPurchaseOrderName(BaseMapper.name(entity.getLinkedPurchaseOrder()));
        dto.setLinkedSupplierId(entity.getLinkedSupplierId());
        dto.setLinkedSupplierCode(BaseMapper.supplierCode(entity.getLinkedSupplier()));
        dto.setLinkedSupplierName(BaseMapper.supplierName(entity.getLinkedSupplier()));
        dto.setLinkedContractId(entity.getLinkedContractId());
        dto.setLinkedContractCode(BaseMapper.code(entity.getLinkedContract()));
        dto.setLinkedContractName(BaseMapper.name(entity.getLinkedContract()));
        dto.setCompensationAmount(entity.getCompensationAmount());
        dto.setDecisionComment(entity.getDecisionComment());
        dto.setDecidedBy(entity.getDecidedBy());
        dto.setDecidedAt(entity.getDecidedAt());
        dto.setCommentText(entity.getCommentText());
        dto.setCountedBy(entity.getCountedBy());
        dto.setCountedAt(entity.getCountedAt());
        dto.setExplanationStatus(entity.getExplanationStatus());
        dto.setExplanationRequestedFrom(entity.getExplanationRequestedFrom());
        dto.setExplanationRequestedAt(entity.getExplanationRequestedAt());
        dto.setExplanationText(entity.getExplanationText());
        dto.setExplanationProvidedAt(entity.getExplanationProvidedAt());
        dto.setExplanationReviewComment(entity.getExplanationReviewComment());
        dto.setRecountRequestedAt(entity.getRecountRequestedAt());
        dto.setRecountReason(entity.getRecountReason());
        dto.setCountComment(entity.getCountComment()); dto.setFoundStock(entity.isFoundStock()); dto.setFoundReason(entity.getFoundReason());
        dto.setFoundEvidenceIds(entity.getFoundEvidenceIds()); dto.setDecisionEvidenceIds(entity.getDecisionEvidenceIds());
        dto.setValuationBasis(entity.getValuationBasis()); dto.setValuationEvidenceIds(entity.getValuationEvidenceIds());
        dto.setValuationCountRevision(entity.getValuationCountRevision()); dto.setValuedBy(entity.getValuedBy()); dto.setValuedAt(entity.getValuedAt());
        dto.setReviewBlocker(entity.getReviewBlocker()); dto.setSourceCorrectionMovementId(entity.getSourceCorrectionMovementId()); dto.setReconciledSourceMovementId(entity.getReconciledSourceMovementId());
        dto.setBatchNumber(entity.getObservedBatchNumber()); dto.setSerialNumber(entity.getObservedSerialNumber()); dto.setExpiryDate(entity.getObservedExpiryDate());
        dto.setCountRevision(entity.getCountRevision());
        dto.setDecisionCountRevision(entity.getDecisionCountRevision());
        dto.setCountState(entity.getCountedQuantity() == null ? "UNCOUNTED" : "COUNTED");
        if (entity.getMaterial() != null && entity.getMaterial().getUnitOfMeasure() != null) {
            var unit = entity.getMaterial().getUnitOfMeasure();
            dto.setUnitCode(unit.getCode()); dto.setUnitLabel(com.enterprise.mm.common.UnitOfMeasureLabels.localizedShort(unit, org.springframework.context.i18n.LocaleContextHolder.getLocale()));
            dto.setQuantityScale(com.enterprise.mm.service.StockUomPolicy.quantityScale(entity.getMaterial()));
        }
        if (entity.getObservedSerialNumber() != null && !entity.getObservedSerialNumber().isBlank()) dto.setQuantityScale(0);
        if (entity.getStockBatch() != null) {
            var batch = entity.getStockBatch();
            dto.setBatchNumber(batch.getBatchNumber()); dto.setSerialNumber(batch.getSerialNumber()); dto.setExpiryDate(batch.getExpirationDate());
            if (batch.getSerialNumber() != null && !batch.getSerialNumber().isBlank()) dto.setQuantityScale(0);
        }
        return dto;
    }

    public InventoryItemResponse toCountingResponse(InventoryItem entity, boolean showSystemQuantity) {
        return com.enterprise.mm.service.inventory.InventoryReadPolicy.project(toResponse(entity), showSystemQuantity);
    }

    public InventoryItem toEntity(InventoryItemCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        InventoryItem entity = new InventoryItem();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setInventoryDocumentId(dto.getInventoryDocumentId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setSystemQuantity(dto.getSystemQuantity());
        entity.setCountedQuantity(dto.getCountedQuantity());
        entity.setDifferenceQuantity(dto.getDifferenceQuantity());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStorageBinId(dto.getStorageBinId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setUnitCost(dto.getUnitCost());
        entity.setDifferenceAmount(dto.getDifferenceAmount());
        entity.setLineStatus(dto.getLineStatus());
        entity.setDiscrepancyType(dto.getDiscrepancyType());
        entity.setDiscrepancyReason(dto.getDiscrepancyReason());
        entity.setCommentText(dto.getCommentText());
        return entity;
    }

    public void updateEntity(InventoryItem entity, InventoryItemUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setDifferenceAmount(dto.getDifferenceAmount());
        entity.setLineStatus(dto.getLineStatus());
        entity.setDiscrepancyType(dto.getDiscrepancyType());
        entity.setDiscrepancyReason(dto.getDiscrepancyReason());
        entity.setCommentText(dto.getCommentText());
    }
}
