package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.VmiStockAgreementResponse;
import com.enterprise.mm.payload.VmiStockAgreementUpdateRequest;
import com.enterprise.mm.payload.VmiStockAgreementCreateRequest;
import com.enterprise.mm.entity.VmiStockAgreement;
import org.springframework.stereotype.Component;

@Component
public class VmiStockAgreementMapper {
    public VmiStockAgreementResponse toResponse(VmiStockAgreement entity) {
        if (entity == null) {
            return null;
        }
        VmiStockAgreementResponse dto = new VmiStockAgreementResponse();
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
        dto.setMaterialId(entity.getMaterialId());
        dto.setMaterialCode(BaseMapper.materialCode(entity.getMaterial()));
        dto.setMaterialName(BaseMapper.materialName(entity.getMaterial()));
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setWarehouseCode(BaseMapper.code(entity.getWarehouse()));
        dto.setWarehouseName(BaseMapper.name(entity.getWarehouse()));
        dto.setStorageLocationId(entity.getStorageLocationId());
        dto.setStorageLocationCode(BaseMapper.code(entity.getStorageLocation()));
        dto.setStorageLocationName(BaseMapper.name(entity.getStorageLocation()));
        dto.setStockType(entity.getStockType());
        dto.setSupplierSku(entity.getSupplierSku());
        dto.setMinStock(entity.getMinStock());
        dto.setReorderPoint(entity.getReorderPoint());
        dto.setTargetStock(entity.getTargetStock());
        dto.setMaxStock(entity.getMaxStock());
        dto.setLeadTimeDays(entity.getLeadTimeDays());
        dto.setReviewCycleDays(entity.getReviewCycleDays());
        dto.setOwnershipTransferEvent(entity.getOwnershipTransferEvent());
        dto.setSettlementCycle(entity.getSettlementCycle());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setLastReviewedAt(entity.getLastReviewedAt());
        dto.setLastReplenishmentRequestAt(entity.getLastReplenishmentRequestAt());
        return dto;
    }

    public VmiStockAgreement toEntity(VmiStockAgreementCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        VmiStockAgreement entity = new VmiStockAgreement();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockType(dto.getStockType());
        entity.setSupplierSku(dto.getSupplierSku());
        entity.setMinStock(dto.getMinStock());
        entity.setReorderPoint(dto.getReorderPoint());
        entity.setTargetStock(dto.getTargetStock());
        entity.setMaxStock(dto.getMaxStock());
        entity.setLeadTimeDays(dto.getLeadTimeDays());
        entity.setReviewCycleDays(dto.getReviewCycleDays());
        entity.setOwnershipTransferEvent(dto.getOwnershipTransferEvent());
        entity.setSettlementCycle(dto.getSettlementCycle());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setLastReviewedAt(dto.getLastReviewedAt());
        entity.setLastReplenishmentRequestAt(dto.getLastReplenishmentRequestAt());
        return entity;
    }

    public void updateEntity(VmiStockAgreement entity, VmiStockAgreementUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSupplierId(dto.getSupplierId());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setStorageLocationId(dto.getStorageLocationId());
        entity.setStockType(dto.getStockType());
        entity.setSupplierSku(dto.getSupplierSku());
        entity.setMinStock(dto.getMinStock());
        entity.setReorderPoint(dto.getReorderPoint());
        entity.setTargetStock(dto.getTargetStock());
        entity.setMaxStock(dto.getMaxStock());
        entity.setLeadTimeDays(dto.getLeadTimeDays());
        entity.setReviewCycleDays(dto.getReviewCycleDays());
        entity.setOwnershipTransferEvent(dto.getOwnershipTransferEvent());
        entity.setSettlementCycle(dto.getSettlementCycle());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setLastReviewedAt(dto.getLastReviewedAt());
        entity.setLastReplenishmentRequestAt(dto.getLastReplenishmentRequestAt());
    }
}