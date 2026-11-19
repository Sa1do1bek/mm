package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.QualityClaimResponse;
import com.enterprise.mm.payload.QualityClaimUpdateRequest;
import com.enterprise.mm.payload.QualityClaimCreateRequest;
import com.enterprise.mm.entity.QualityClaim;
import org.springframework.stereotype.Component;

@Component
public class QualityClaimMapper {
    public QualityClaimResponse toResponse(QualityClaim entity) {
        if (entity == null) {
            return null;
        }
        QualityClaimResponse dto = new QualityClaimResponse();
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
        dto.setClaimNumber(entity.getClaimNumber());
        dto.setSupplierId(entity.getSupplierId());
        dto.setSupplierCode(BaseMapper.supplierCode(entity.getSupplier()));
        dto.setSupplierName(BaseMapper.supplierName(entity.getSupplier()));
        dto.setQualityNonconformanceId(entity.getQualityNonconformanceId());
        dto.setQualityNonconformanceCode(BaseMapper.code(entity.getQualityNonconformance()));
        dto.setQualityNonconformanceName(BaseMapper.name(entity.getQualityNonconformance()));
        dto.setQualityInspectionId(entity.getQualityInspectionId());
        dto.setQualityInspectionCode(BaseMapper.code(entity.getQualityInspection()));
        dto.setQualityInspectionName(BaseMapper.name(entity.getQualityInspection()));
        dto.setPurchaseOrderId(entity.getPurchaseOrderId());
        dto.setPurchaseOrderCode(BaseMapper.code(entity.getPurchaseOrder()));
        dto.setPurchaseOrderName(BaseMapper.name(entity.getPurchaseOrder()));
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setClaimDate(entity.getClaimDate());
        dto.setClaimAmount(entity.getClaimAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setRequestedAction(entity.getRequestedAction());
        dto.setSupplierResponse(entity.getSupplierResponse());
        dto.setResponseDueDate(entity.getResponseDueDate());
        dto.setCorrectiveActionId(entity.getCorrectiveActionId());
        dto.setCorrectiveActionCode(BaseMapper.code(entity.getCorrectiveAction()));
        dto.setCorrectiveActionName(BaseMapper.name(entity.getCorrectiveAction()));
        dto.setSentAt(entity.getSentAt());
        dto.setAcknowledgedAt(entity.getAcknowledgedAt());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    public QualityClaim toEntity(QualityClaimCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        QualityClaim entity = new QualityClaim();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setClaimNumber(dto.getClaimNumber());
        entity.setSupplierId(dto.getSupplierId());
        entity.setQualityNonconformanceId(dto.getQualityNonconformanceId());
        entity.setQualityInspectionId(dto.getQualityInspectionId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setClaimDate(dto.getClaimDate());
        entity.setClaimAmount(dto.getClaimAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setRequestedAction(dto.getRequestedAction());
        entity.setSupplierResponse(dto.getSupplierResponse());
        entity.setResponseDueDate(dto.getResponseDueDate());
        entity.setCorrectiveActionId(dto.getCorrectiveActionId());
        entity.setSentAt(dto.getSentAt());
        entity.setAcknowledgedAt(dto.getAcknowledgedAt());
        entity.setClosedAt(dto.getClosedAt());
        return entity;
    }

    public void updateEntity(QualityClaim entity, QualityClaimUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setClaimNumber(dto.getClaimNumber());
        entity.setSupplierId(dto.getSupplierId());
        entity.setQualityNonconformanceId(dto.getQualityNonconformanceId());
        entity.setQualityInspectionId(dto.getQualityInspectionId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setClaimDate(dto.getClaimDate());
        entity.setClaimAmount(dto.getClaimAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setRequestedAction(dto.getRequestedAction());
        entity.setSupplierResponse(dto.getSupplierResponse());
        entity.setResponseDueDate(dto.getResponseDueDate());
        entity.setCorrectiveActionId(dto.getCorrectiveActionId());
        entity.setSentAt(dto.getSentAt());
        entity.setAcknowledgedAt(dto.getAcknowledgedAt());
        entity.setClosedAt(dto.getClosedAt());
    }
}