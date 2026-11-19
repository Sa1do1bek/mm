package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.InventoryOptimizationRecommendationResponse;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationUpdateRequest;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationCreateRequest;
import com.enterprise.mm.entity.InventoryOptimizationRecommendation;
import org.springframework.stereotype.Component;

@Component
public class InventoryOptimizationRecommendationMapper {
    public InventoryOptimizationRecommendationResponse toResponse(InventoryOptimizationRecommendation entity) {
        if (entity == null) {
            return null;
        }
        InventoryOptimizationRecommendationResponse dto = new InventoryOptimizationRecommendationResponse();
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
        dto.setMaterialId(entity.getMaterialId());
        dto.setWarehouseId(entity.getWarehouseId());
        dto.setDemandForecastId(entity.getDemandForecastId());
        dto.setAnnualDemand(entity.getAnnualDemand());
        dto.setAverageMonthlyDemand(entity.getAverageMonthlyDemand());
        dto.setOrderCost(entity.getOrderCost());
        dto.setHoldingCostPerUnit(entity.getHoldingCostPerUnit());
        dto.setUnitCost(entity.getUnitCost());
        dto.setHoldingRate(entity.getHoldingRate());
        dto.setEoqQuantity(entity.getEoqQuantity());
        dto.setOrderFrequencyDays(entity.getOrderFrequencyDays());
        dto.setAnnualOrderingCost(entity.getAnnualOrderingCost());
        dto.setAnnualHoldingCost(entity.getAnnualHoldingCost());
        dto.setAnnualTotalCost(entity.getAnnualTotalCost());
        dto.setLeadTimeDays(entity.getLeadTimeDays());
        dto.setServiceLevel(entity.getServiceLevel());
        dto.setRecommendedSafetyStock(entity.getRecommendedSafetyStock());
        dto.setRecommendedReorderPoint(entity.getRecommendedReorderPoint());
        dto.setRecommendedMinStock(entity.getRecommendedMinStock());
        dto.setRecommendedMaxStock(entity.getRecommendedMaxStock());
        dto.setGeneratedAt(entity.getGeneratedAt());
        dto.setAppliedAt(entity.getAppliedAt());
        return dto;
    }

    public InventoryOptimizationRecommendation toEntity(InventoryOptimizationRecommendationCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        InventoryOptimizationRecommendation entity = new InventoryOptimizationRecommendation();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setDemandForecastId(dto.getDemandForecastId());
        entity.setAnnualDemand(dto.getAnnualDemand());
        entity.setAverageMonthlyDemand(dto.getAverageMonthlyDemand());
        entity.setOrderCost(dto.getOrderCost());
        entity.setHoldingCostPerUnit(dto.getHoldingCostPerUnit());
        entity.setUnitCost(dto.getUnitCost());
        entity.setHoldingRate(dto.getHoldingRate());
        entity.setEoqQuantity(dto.getEoqQuantity());
        entity.setOrderFrequencyDays(dto.getOrderFrequencyDays());
        entity.setAnnualOrderingCost(dto.getAnnualOrderingCost());
        entity.setAnnualHoldingCost(dto.getAnnualHoldingCost());
        entity.setAnnualTotalCost(dto.getAnnualTotalCost());
        entity.setLeadTimeDays(dto.getLeadTimeDays());
        entity.setServiceLevel(dto.getServiceLevel());
        entity.setRecommendedSafetyStock(dto.getRecommendedSafetyStock());
        entity.setRecommendedReorderPoint(dto.getRecommendedReorderPoint());
        entity.setRecommendedMinStock(dto.getRecommendedMinStock());
        entity.setRecommendedMaxStock(dto.getRecommendedMaxStock());
        entity.setGeneratedAt(dto.getGeneratedAt());
        entity.setAppliedAt(dto.getAppliedAt());
        return entity;
    }

    public void updateEntity(InventoryOptimizationRecommendation entity, InventoryOptimizationRecommendationUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setDemandForecastId(dto.getDemandForecastId());
        entity.setAnnualDemand(dto.getAnnualDemand());
        entity.setAverageMonthlyDemand(dto.getAverageMonthlyDemand());
        entity.setOrderCost(dto.getOrderCost());
        entity.setHoldingCostPerUnit(dto.getHoldingCostPerUnit());
        entity.setUnitCost(dto.getUnitCost());
        entity.setHoldingRate(dto.getHoldingRate());
        entity.setEoqQuantity(dto.getEoqQuantity());
        entity.setOrderFrequencyDays(dto.getOrderFrequencyDays());
        entity.setAnnualOrderingCost(dto.getAnnualOrderingCost());
        entity.setAnnualHoldingCost(dto.getAnnualHoldingCost());
        entity.setAnnualTotalCost(dto.getAnnualTotalCost());
        entity.setLeadTimeDays(dto.getLeadTimeDays());
        entity.setServiceLevel(dto.getServiceLevel());
        entity.setRecommendedSafetyStock(dto.getRecommendedSafetyStock());
        entity.setRecommendedReorderPoint(dto.getRecommendedReorderPoint());
        entity.setRecommendedMinStock(dto.getRecommendedMinStock());
        entity.setRecommendedMaxStock(dto.getRecommendedMaxStock());
        entity.setGeneratedAt(dto.getGeneratedAt());
        entity.setAppliedAt(dto.getAppliedAt());
    }
}