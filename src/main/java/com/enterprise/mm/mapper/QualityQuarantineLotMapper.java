package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.QualityQuarantineLotResponse;
import com.enterprise.mm.payload.QualityQuarantineLotUpdateRequest;
import com.enterprise.mm.payload.QualityQuarantineLotCreateRequest;
import com.enterprise.mm.entity.QualityQuarantineLot;
import org.springframework.stereotype.Component;

@Component
public class QualityQuarantineLotMapper {
    public QualityQuarantineLotResponse toResponse(QualityQuarantineLot entity) {
        if (entity == null) {
            return null;
        }
        QualityQuarantineLotResponse dto = new QualityQuarantineLotResponse();
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
        dto.setQuarantineNumber(entity.getQuarantineNumber());
        dto.setTransferAllocationId(entity.getTransferAllocationId());
        dto.setTransferClearanceId(entity.getTransferClearanceId());
        dto.setQualityInspectionId(entity.getQualityInspectionId());
        dto.setQualityInspectionCode(BaseMapper.code(entity.getQualityInspection()));
        dto.setQualityInspectionName(BaseMapper.name(entity.getQualityInspection()));
        dto.setQualityNonconformanceId(entity.getQualityNonconformanceId());
        dto.setQualityNonconformanceCode(BaseMapper.code(entity.getQualityNonconformance()));
        dto.setQualityNonconformanceName(BaseMapper.name(entity.getQualityNonconformance()));
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
        dto.setQuarantineReason(entity.getQuarantineReason());
        dto.setQuarantinedQuantity(entity.getQuarantinedQuantity());
        dto.setReleasedQuantity(entity.getReleasedQuantity());
        dto.setRejectedQuantity(entity.getRejectedQuantity());
        dto.setRemainingQuantity(entity.getRemainingQuantity());
        dto.setDisposition(entity.getDisposition());
        dto.setResponsibleEmployeeId(entity.getResponsibleEmployeeId());
        dto.setResponsibleEmployeeCode(BaseMapper.employeeCode(entity.getResponsibleEmployee()));
        dto.setResponsibleEmployeeName(BaseMapper.employeeName(entity.getResponsibleEmployee()));
        dto.setQuarantineStartedAt(entity.getQuarantineStartedAt());
        dto.setReleasedAt(entity.getReleasedAt());
        dto.setRejectedAt(entity.getRejectedAt());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    public QualityQuarantineLot toEntity(QualityQuarantineLotCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        QualityQuarantineLot entity = new QualityQuarantineLot();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setQuarantineNumber(dto.getQuarantineNumber());
        entity.setQualityInspectionId(dto.getQualityInspectionId());
        entity.setQualityNonconformanceId(dto.getQualityNonconformanceId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setPurchaseOrderItemId(dto.getPurchaseOrderItemId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setQuarantineReason(dto.getQuarantineReason());
        entity.setQuarantinedQuantity(dto.getQuarantinedQuantity());
        entity.setReleasedQuantity(dto.getReleasedQuantity());
        entity.setRejectedQuantity(dto.getRejectedQuantity());
        entity.setRemainingQuantity(dto.getRemainingQuantity());
        entity.setDisposition(dto.getDisposition());
        entity.setResponsibleEmployeeId(dto.getResponsibleEmployeeId());
        entity.setQuarantineStartedAt(dto.getQuarantineStartedAt());
        entity.setReleasedAt(dto.getReleasedAt());
        entity.setRejectedAt(dto.getRejectedAt());
        entity.setClosedAt(dto.getClosedAt());
        return entity;
    }

    public void updateEntity(QualityQuarantineLot entity, QualityQuarantineLotUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setQuarantineNumber(dto.getQuarantineNumber());
        entity.setQualityInspectionId(dto.getQualityInspectionId());
        entity.setQualityNonconformanceId(dto.getQualityNonconformanceId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setPurchaseOrderItemId(dto.getPurchaseOrderItemId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockBatchId(dto.getStockBatchId());
        entity.setQuarantineReason(dto.getQuarantineReason());
        entity.setQuarantinedQuantity(dto.getQuarantinedQuantity());
        entity.setReleasedQuantity(dto.getReleasedQuantity());
        entity.setRejectedQuantity(dto.getRejectedQuantity());
        entity.setRemainingQuantity(dto.getRemainingQuantity());
        entity.setDisposition(dto.getDisposition());
        entity.setResponsibleEmployeeId(dto.getResponsibleEmployeeId());
        entity.setQuarantineStartedAt(dto.getQuarantineStartedAt());
        entity.setReleasedAt(dto.getReleasedAt());
        entity.setRejectedAt(dto.getRejectedAt());
        entity.setClosedAt(dto.getClosedAt());
    }
}