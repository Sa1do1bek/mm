package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.QualityNonconformanceResponse;
import com.enterprise.mm.payload.QualityNonconformanceUpdateRequest;
import com.enterprise.mm.payload.QualityNonconformanceCreateRequest;
import com.enterprise.mm.entity.QualityNonconformance;
import org.springframework.stereotype.Component;

@Component
public class QualityNonconformanceMapper {
    public QualityNonconformanceResponse toResponse(QualityNonconformance entity) {
        if (entity == null) {
            return null;
        }
        QualityNonconformanceResponse dto = new QualityNonconformanceResponse();
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
        dto.setQualityInspectionId(entity.getQualityInspectionId());
        dto.setQualityInspectionCode(BaseMapper.code(entity.getQualityInspection()));
        dto.setQualityInspectionName(BaseMapper.name(entity.getQualityInspection()));
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setStockMovementId(entity.getStockMovementId());
        dto.setStockMovementCode(BaseMapper.code(entity.getStockMovement()));
        dto.setStockMovementName(BaseMapper.name(entity.getStockMovement()));
        dto.setSeverity(entity.getSeverity());
        dto.setNonconformanceType(entity.getNonconformanceType());
        dto.setDetectedQuantity(entity.getDetectedQuantity());
        dto.setDefectDescription(entity.getDefectDescription());
        dto.setRootCause(entity.getRootCause());
        dto.setDisposition(entity.getDisposition());
        dto.setCapaRequired(entity.isCapaRequired());
        dto.setClaimId(entity.getClaimId());
        dto.setClaimCode(BaseMapper.code(entity.getClaim()));
        dto.setClaimName(BaseMapper.name(entity.getClaim()));
        dto.setCorrectiveActionId(entity.getCorrectiveActionId());
        dto.setCorrectiveActionCode(BaseMapper.code(entity.getCorrectiveAction()));
        dto.setCorrectiveActionName(BaseMapper.name(entity.getCorrectiveAction()));
        dto.setDetectedAt(entity.getDetectedAt());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    public QualityNonconformance toEntity(QualityNonconformanceCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        QualityNonconformance entity = new QualityNonconformance();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setQualityInspectionId(dto.getQualityInspectionId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setSeverity(dto.getSeverity());
        entity.setNonconformanceType(dto.getNonconformanceType());
        entity.setDetectedQuantity(dto.getDetectedQuantity());
        entity.setDefectDescription(dto.getDefectDescription());
        entity.setRootCause(dto.getRootCause());
        entity.setDisposition(dto.getDisposition());
        entity.setCapaRequired(dto.isCapaRequired());
        entity.setClaimId(dto.getClaimId());
        entity.setCorrectiveActionId(dto.getCorrectiveActionId());
        entity.setDetectedAt(dto.getDetectedAt());
        entity.setClosedAt(dto.getClosedAt());
        return entity;
    }

    public void updateEntity(QualityNonconformance entity, QualityNonconformanceUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setQualityInspectionId(dto.getQualityInspectionId());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setSeverity(dto.getSeverity());
        entity.setNonconformanceType(dto.getNonconformanceType());
        entity.setDetectedQuantity(dto.getDetectedQuantity());
        entity.setDefectDescription(dto.getDefectDescription());
        entity.setRootCause(dto.getRootCause());
        entity.setDisposition(dto.getDisposition());
        entity.setCapaRequired(dto.isCapaRequired());
        entity.setClaimId(dto.getClaimId());
        entity.setCorrectiveActionId(dto.getCorrectiveActionId());
        entity.setDetectedAt(dto.getDetectedAt());
        entity.setClosedAt(dto.getClosedAt());
    }
}