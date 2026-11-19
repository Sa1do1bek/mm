package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.QualityInspectionResponse;
import com.enterprise.mm.payload.QualityInspectionUpdateRequest;
import com.enterprise.mm.payload.QualityInspectionCreateRequest;
import com.enterprise.mm.entity.QualityInspection;
import org.springframework.stereotype.Component;

@Component
public class QualityInspectionMapper {
    public QualityInspectionResponse toResponse(QualityInspection entity) {
        if (entity == null) {
            return null;
        }
        QualityInspectionResponse dto = new QualityInspectionResponse();
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
        dto.setInspectionNumber(entity.getInspectionNumber());
        dto.setInspectionType(entity.getInspectionType());
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setPurchaseOrderItemId(entity.getPurchaseOrderItemId());
        dto.setPurchaseOrderItemCode(BaseMapper.code(entity.getPurchaseOrderItem()));
        dto.setPurchaseOrderItemName(BaseMapper.name(entity.getPurchaseOrderItem()));
        dto.setStockMovementId(entity.getStockMovementId());
        dto.setStockMovementCode(BaseMapper.code(entity.getStockMovement()));
        dto.setStockMovementName(BaseMapper.name(entity.getStockMovement()));
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStorageLocationCode(BaseMapper.code(entity.getStorageLocation()));
        dto.setStorageLocationName(BaseMapper.name(entity.getStorageLocation()));
        dto.setStockBatchId(entity.getStockBatchId());
        dto.setStockBatchCode(BaseMapper.stockBatchCode(entity.getStockBatch()));
        dto.setStockBatchName(BaseMapper.stockBatchName(entity.getStockBatch()));
        dto.setReceivedQuantity(entity.getReceivedQuantity());
        dto.setSampleQuantity(entity.getSampleQuantity());
        dto.setAcceptedQuantity(entity.getAcceptedQuantity());
        dto.setRejectedQuantity(entity.getRejectedQuantity());
        dto.setQuarantinedQuantity(entity.getQuarantinedQuantity());
        dto.setInspectionDate(entity.getInspectionDate());
        dto.setDueDate(entity.getDueDate());
        dto.setInspectorEmployeeId(entity.getInspectorEmployeeId());
        dto.setInspectorEmployeeCode(BaseMapper.employeeCode(entity.getInspectorEmployee()));
        dto.setInspectorEmployeeName(BaseMapper.employeeName(entity.getInspectorEmployee()));
        dto.setQualityPassportAttachmentId(entity.getQualityPassportAttachmentId());
        dto.setQuarantineStartedAt(entity.getQuarantineStartedAt());
        dto.setQuarantineReleasedAt(entity.getQuarantineReleasedAt());
        dto.setInspectionStartedAt(entity.getInspectionStartedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setConclusion(entity.getConclusion());
        return dto;
    }

    public QualityInspection toEntity(QualityInspectionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        QualityInspection entity = new QualityInspection();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setInspectionNumber(dto.getInspectionNumber());
        entity.setInspectionType(dto.getInspectionType());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setPurchaseOrderItemId(dto.getPurchaseOrderItemId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setReceivedQuantity(dto.getReceivedQuantity());
        entity.setSampleQuantity(dto.getSampleQuantity());
        entity.setAcceptedQuantity(dto.getAcceptedQuantity());
        entity.setRejectedQuantity(dto.getRejectedQuantity());
        entity.setQuarantinedQuantity(dto.getQuarantinedQuantity());
        entity.setInspectionDate(dto.getInspectionDate());
        entity.setDueDate(dto.getDueDate());
        entity.setInspectorEmployeeId(dto.getInspectorEmployeeId());
        entity.setQualityPassportAttachmentId(dto.getQualityPassportAttachmentId());
        entity.setQuarantineStartedAt(dto.getQuarantineStartedAt());
        entity.setQuarantineReleasedAt(dto.getQuarantineReleasedAt());
        entity.setInspectionStartedAt(dto.getInspectionStartedAt());
        entity.setCompletedAt(dto.getCompletedAt());
        entity.setConclusion(dto.getConclusion());
        return entity;
    }

    public void updateEntity(QualityInspection entity, QualityInspectionUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setInspectionNumber(dto.getInspectionNumber());
        entity.setInspectionType(dto.getInspectionType());
        entity.setSampleQuantity(dto.getSampleQuantity());
        entity.setInspectionDate(dto.getInspectionDate());
        entity.setDueDate(dto.getDueDate());
        entity.setInspectorEmployeeId(dto.getInspectorEmployeeId());
        entity.setQualityPassportAttachmentId(dto.getQualityPassportAttachmentId());
        entity.setConclusion(dto.getConclusion());
    }
}
