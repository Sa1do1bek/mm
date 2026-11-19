package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.SupplierEvaluationResponse;
import com.enterprise.mm.payload.SupplierEvaluationUpdateRequest;
import com.enterprise.mm.payload.SupplierEvaluationCreateRequest;
import com.enterprise.mm.entity.SupplierEvaluation;
import org.springframework.stereotype.Component;

@Component
public class SupplierEvaluationMapper {
    public SupplierEvaluationResponse toResponse(SupplierEvaluation entity) {
        if (entity == null) {
            return null;
        }
        SupplierEvaluationResponse dto = new SupplierEvaluationResponse();
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
        dto.setOrderedQuantity(entity.getOrderedQuantity());
        dto.setReceivedQuantity(entity.getReceivedQuantity());
        dto.setAcceptedQuantity(entity.getAcceptedQuantity());
        dto.setRejectedQuantity(entity.getRejectedQuantity());
        dto.setLateDays(entity.getLateDays());
        dto.setDeliveryScore(entity.getDeliveryScore());
        dto.setQualityScore(entity.getQualityScore());
        dto.setQuantityScore(entity.getQuantityScore());
        dto.setPriceScore(entity.getPriceScore());
        dto.setClaimsScore(entity.getClaimsScore());
        dto.setTotalScore(entity.getTotalScore());
        dto.setEvaluationSource(entity.getEvaluationSource());
        dto.setCalculationDetailsJson(entity.getCalculationDetailsJson());
        dto.setEvaluatedAt(entity.getEvaluatedAt());
        return dto;
    }

    public SupplierEvaluation toEntity(SupplierEvaluationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        SupplierEvaluation entity = new SupplierEvaluation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setPurchaseOrderId(dto.getPurchaseOrderId());
        entity.setPurchaseOrderItemId(dto.getPurchaseOrderItemId());
        entity.setStockMovementId(dto.getStockMovementId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setOrderedQuantity(dto.getOrderedQuantity());
        entity.setReceivedQuantity(dto.getReceivedQuantity());
        entity.setAcceptedQuantity(dto.getAcceptedQuantity());
        entity.setRejectedQuantity(dto.getRejectedQuantity());
        entity.setLateDays(dto.getLateDays());
        entity.setDeliveryScore(dto.getDeliveryScore());
        entity.setQualityScore(dto.getQualityScore());
        entity.setQuantityScore(dto.getQuantityScore());
        entity.setPriceScore(dto.getPriceScore());
        entity.setClaimsScore(dto.getClaimsScore());
        entity.setTotalScore(dto.getTotalScore());
        entity.setEvaluationSource(dto.getEvaluationSource());
        entity.setCalculationDetailsJson(dto.getCalculationDetailsJson());
        entity.setEvaluatedAt(dto.getEvaluatedAt());
        return entity;
    }

    public void updateEntity(SupplierEvaluation entity, SupplierEvaluationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        // Evaluation identity, provenance and total score are command-derived.
        // Generic corrections may change descriptive data and score components only.
        if (dto.getName() != null) entity.setName(dto.getName());
        if (dto.getDescription() != null) entity.setDescription(dto.getDescription());
        if (dto.getDeliveryScore() != null) entity.setDeliveryScore(dto.getDeliveryScore());
        if (dto.getQualityScore() != null) entity.setQualityScore(dto.getQualityScore());
        if (dto.getQuantityScore() != null) entity.setQuantityScore(dto.getQuantityScore());
        if (dto.getPriceScore() != null) entity.setPriceScore(dto.getPriceScore());
        if (dto.getClaimsScore() != null) entity.setClaimsScore(dto.getClaimsScore());
    }
}
