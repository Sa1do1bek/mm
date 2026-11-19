package com.enterprise.mm.mapper;

import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.payload.PurchaseOrderItemResponse;
import com.enterprise.mm.payload.PurchaseOrderItemUpdateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class PurchaseOrderItemMapper {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    public PurchaseOrderItemResponse toResponse(PurchaseOrderItem entity) {
        if (entity == null) {
            return null;
        }
        PurchaseOrderItemResponse dto = new PurchaseOrderItemResponse();
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
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setLineType(entity.getLineType() == null ? null : entity.getLineType().name());
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setMaterialTypeSnapshot(entity.getMaterialTypeSnapshot());
        dto.setTrackingModeSnapshot(entity.getTrackingModeSnapshot());
        dto.setBatchTrackingSnapshot(entity.getBatchTrackingSnapshot());
        dto.setSerialTrackingSnapshot(entity.getSerialTrackingSnapshot());
        dto.setServiceRequestId(entity.getServiceRequestId());
        dto.setServiceRequestCode(BaseMapper.code(entity.getServiceRequest()));
        dto.setServiceRequestName(BaseMapper.name(entity.getServiceRequest()));
        dto.setServiceOrderId(entity.getServiceOrderId());
        dto.setServiceOrderCode(BaseMapper.code(entity.getServiceOrder()));
        dto.setServiceOrderName(BaseMapper.name(entity.getServiceOrder()));
        dto.setServiceActId(entity.getServiceActId());
        dto.setServiceActCode(BaseMapper.code(entity.getServiceAct()));
        dto.setServiceActName(BaseMapper.name(entity.getServiceAct()));
        dto.setServiceDescription(entity.getServiceDescription());
        dto.setServiceCategory(entity.getServiceCategory());
        dto.setUnit(entity.getUnit());
        dto.setUnitShortName(unitDisplay.shortName(entity, entity.getUnit(), entity.getMaterial()));
        dto.setPeriod(entity.getPeriod());
        dto.setStage(entity.getStage());
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setBudgetItemCode(BaseMapper.code(entity.getBudgetItem()));
        dto.setBudgetItemName(BaseMapper.name(entity.getBudgetItem()));
        dto.setQuantity(entity.getQuantity());
        dto.setUnitPrice(DataQualityGuards.roundMoney(
            entity.getUnitPrice(),
            entity.getPurchaseOrder() == null ? null : entity.getPurchaseOrder().getCurrencyCode()));
        dto.setReceivedQuantity(entity.getReceivedQuantity());
        dto.setRejectedQuantity(entity.getRejectedQuantity());
        dto.setDamagedQuantity(entity.getDamagedQuantity());
        dto.setShortageReason(entity.getShortageReason());
        dto.setReceiptBatchNumber(entity.getReceiptBatchNumber());
        dto.setReceiptWarehouseId(entity.getReceiptWarehouseId());
        dto.setReceiptWarehouseCode(BaseMapper.code(entity.getReceiptWarehouse()));
        dto.setReceiptWarehouseName(BaseMapper.name(entity.getReceiptWarehouse()));
        dto.setReceiptStorageLocationId(entity.getReceiptStorageLocationId());
        dto.setReceiptStorageLocationCode(BaseMapper.code(entity.getReceiptStorageLocation()));
        dto.setReceiptStorageLocationName(BaseMapper.name(entity.getReceiptStorageLocation()));
        dto.setQualityStatus(entity.getQualityStatus());
        dto.setAccreditationWarning(entity.getAccreditationWarning());
        if (dto.getPurchaseOrderId() == null && entity.getPurchaseOrder() != null) {
            dto.setPurchaseOrderId(entity.getPurchaseOrder().getId());
        }
        if (dto.getMaterialId() == null && entity.getMaterial() != null) {
            dto.setMaterialId(entity.getMaterial().getId());
        }
        return dto;
    }

    public PurchaseOrderItem toEntity(PurchaseOrderItemCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        PurchaseOrderItem entity = new PurchaseOrderItem();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setTrackingModeSnapshot(dto.getTrackingModeSnapshot());
        entity.setBatchTrackingSnapshot(dto.getBatchTrackingSnapshot());
        entity.setSerialTrackingSnapshot(dto.getSerialTrackingSnapshot());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceOrderId(dto.getServiceOrderId());
        entity.setServiceActId(dto.getServiceActId());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitPrice(DataQualityGuards.roundMoney(dto.getUnitPrice(), null));
        entity.setReceivedQuantity(dto.getReceivedQuantity());
        entity.setRejectedQuantity(dto.getRejectedQuantity());
        entity.setDamagedQuantity(dto.getDamagedQuantity());
        entity.setShortageReason(dto.getShortageReason());
        entity.setReceiptBatchNumber(dto.getReceiptBatchNumber());
        entity.setReceiptWarehouseId(dto.getReceiptWarehouseId());
        entity.setReceiptStorageLocationId(dto.getReceiptStorageLocationId());
        entity.setQualityStatus(dto.getQualityStatus());
        entity.setAccreditationWarning(dto.getAccreditationWarning());
        return entity;
    }

    public void updateEntity(PurchaseOrderItem entity, PurchaseOrderItemUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setLineType(dto.getLineType() == null ? null : com.enterprise.mm.enums.ProcurementLineType.valueOf(dto.getLineType().trim().toUpperCase(java.util.Locale.ROOT)));
        entity.setMaterialId(dto.getMaterialId());
        entity.setMaterialTypeSnapshot(dto.getMaterialTypeSnapshot());
        entity.setTrackingModeSnapshot(dto.getTrackingModeSnapshot());
        entity.setBatchTrackingSnapshot(dto.getBatchTrackingSnapshot());
        entity.setSerialTrackingSnapshot(dto.getSerialTrackingSnapshot());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceOrderId(dto.getServiceOrderId());
        entity.setServiceActId(dto.getServiceActId());
        entity.setServiceDescription(dto.getServiceDescription());
        entity.setServiceCategory(dto.getServiceCategory());
        entity.setUnit(dto.getUnit());
        entity.setPeriod(dto.getPeriod());
        entity.setStage(dto.getStage());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitPrice(DataQualityGuards.roundMoney(dto.getUnitPrice(), null));
        entity.setReceivedQuantity(dto.getReceivedQuantity());
        entity.setRejectedQuantity(dto.getRejectedQuantity());
        entity.setDamagedQuantity(dto.getDamagedQuantity());
        entity.setShortageReason(dto.getShortageReason());
        entity.setReceiptBatchNumber(dto.getReceiptBatchNumber());
        entity.setReceiptWarehouseId(dto.getReceiptWarehouseId());
        entity.setReceiptStorageLocationId(dto.getReceiptStorageLocationId());
        entity.setQualityStatus(dto.getQualityStatus());
        entity.setAccreditationWarning(dto.getAccreditationWarning());
    }
}
