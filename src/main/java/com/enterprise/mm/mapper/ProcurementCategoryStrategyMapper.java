package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ProcurementCategoryStrategyResponse;
import com.enterprise.mm.payload.ProcurementCategoryStrategyUpdateRequest;
import com.enterprise.mm.payload.ProcurementCategoryStrategyCreateRequest;
import com.enterprise.mm.entity.ProcurementCategoryStrategy;
import org.springframework.stereotype.Component;

@Component
public class ProcurementCategoryStrategyMapper {
    public ProcurementCategoryStrategyResponse toResponse(ProcurementCategoryStrategy entity) {
        if (entity == null) {
            return null;
        }
        ProcurementCategoryStrategyResponse dto = new ProcurementCategoryStrategyResponse();
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
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setStrategyYear(entity.getStrategyYear());
        dto.setOwnerEmployeeId(entity.getOwnerEmployeeId());
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setStrategyType(entity.getStrategyType());
        dto.setSourcingApproach(entity.getSourcingApproach());
        dto.setSupplierMarket(entity.getSupplierMarket());
        dto.setRiskLevel(entity.getRiskLevel());
        dto.setTargetSavingsPercent(entity.getTargetSavingsPercent());
        dto.setPlannedSpendAmount(entity.getPlannedSpendAmount());
        dto.setApprovedSpendLimit(entity.getApprovedSpendLimit());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setActivatedAt(entity.getActivatedAt());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    public ProcurementCategoryStrategy toEntity(ProcurementCategoryStrategyCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ProcurementCategoryStrategy entity = new ProcurementCategoryStrategy();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setStrategyYear(dto.getStrategyYear());
        entity.setOwnerEmployeeId(dto.getOwnerEmployeeId());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setStrategyType(dto.getStrategyType());
        entity.setSourcingApproach(dto.getSourcingApproach());
        entity.setSupplierMarket(dto.getSupplierMarket());
        entity.setRiskLevel(dto.getRiskLevel());
        entity.setTargetSavingsPercent(dto.getTargetSavingsPercent());
        entity.setPlannedSpendAmount(dto.getPlannedSpendAmount());
        entity.setApprovedSpendLimit(dto.getApprovedSpendLimit());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setApprovedAt(dto.getApprovedAt());
        entity.setActivatedAt(dto.getActivatedAt());
        entity.setClosedAt(dto.getClosedAt());
        return entity;
    }

    public void updateEntity(ProcurementCategoryStrategy entity, ProcurementCategoryStrategyUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setStrategyYear(dto.getStrategyYear());
        entity.setOwnerEmployeeId(dto.getOwnerEmployeeId());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setStrategyType(dto.getStrategyType());
        entity.setSourcingApproach(dto.getSourcingApproach());
        entity.setSupplierMarket(dto.getSupplierMarket());
        entity.setRiskLevel(dto.getRiskLevel());
        entity.setTargetSavingsPercent(dto.getTargetSavingsPercent());
        entity.setPlannedSpendAmount(dto.getPlannedSpendAmount());
        entity.setApprovedSpendLimit(dto.getApprovedSpendLimit());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setValidFrom(dto.getValidFrom());
        entity.setValidTo(dto.getValidTo());
        entity.setApprovedAt(dto.getApprovedAt());
        entity.setActivatedAt(dto.getActivatedAt());
        entity.setClosedAt(dto.getClosedAt());
    }
}