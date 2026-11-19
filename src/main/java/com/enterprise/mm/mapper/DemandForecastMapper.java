package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.DemandForecastResponse;
import com.enterprise.mm.payload.DemandForecastUpdateRequest;
import com.enterprise.mm.payload.DemandForecastCreateRequest;
import com.enterprise.mm.entity.DemandForecast;
import org.springframework.stereotype.Component;

@Component
public class DemandForecastMapper {
    public DemandForecastResponse toResponse(DemandForecast entity) {
        if (entity == null) {
            return null;
        }
        DemandForecastResponse dto = new DemandForecastResponse();
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
        dto.setForecastMethod(entity.getForecastMethod());
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setHistoryMonths(entity.getHistoryMonths());
        dto.setForecastMonths(entity.getForecastMonths());
        dto.setAverageMonthlyDemand(entity.getAverageMonthlyDemand());
        dto.setForecastQuantity(entity.getForecastQuantity());
        dto.setAnnualizedForecastQuantity(entity.getAnnualizedForecastQuantity());
        dto.setDemandStdDev(entity.getDemandStdDev());
        dto.setVariationCoefficient(entity.getVariationCoefficient());
        dto.setConfidence(entity.getConfidence());
        dto.setModelQuality(entity.getModelQuality());
        dto.setTrendSlope(entity.getTrendSlope());
        dto.setSeasonalityIndex(entity.getSeasonalityIndex());
        dto.setLeadTimeDays(entity.getLeadTimeDays());
        dto.setServiceLevel(entity.getServiceLevel());
        dto.setLeadTimeDemand(entity.getLeadTimeDemand());
        dto.setRecommendedSafetyStock(entity.getRecommendedSafetyStock());
        dto.setRecommendedReorderPoint(entity.getRecommendedReorderPoint());
        dto.setRecommendedMinStock(entity.getRecommendedMinStock());
        dto.setRecommendedMaxStock(entity.getRecommendedMaxStock());
        dto.setGeneratedAt(entity.getGeneratedAt());
        dto.setSource(entity.getSource());
        return dto;
    }

    public DemandForecast toEntity(DemandForecastCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        DemandForecast entity = new DemandForecast();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setForecastMethod(dto.getForecastMethod());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setHistoryMonths(dto.getHistoryMonths());
        entity.setForecastMonths(dto.getForecastMonths());
        entity.setAverageMonthlyDemand(dto.getAverageMonthlyDemand());
        entity.setForecastQuantity(dto.getForecastQuantity());
        entity.setAnnualizedForecastQuantity(dto.getAnnualizedForecastQuantity());
        entity.setDemandStdDev(dto.getDemandStdDev());
        entity.setVariationCoefficient(dto.getVariationCoefficient());
        entity.setConfidence(dto.getConfidence());
        entity.setModelQuality(dto.getModelQuality());
        entity.setTrendSlope(dto.getTrendSlope());
        entity.setSeasonalityIndex(dto.getSeasonalityIndex());
        entity.setLeadTimeDays(dto.getLeadTimeDays());
        entity.setServiceLevel(dto.getServiceLevel());
        entity.setLeadTimeDemand(dto.getLeadTimeDemand());
        entity.setRecommendedSafetyStock(dto.getRecommendedSafetyStock());
        entity.setRecommendedReorderPoint(dto.getRecommendedReorderPoint());
        entity.setRecommendedMinStock(dto.getRecommendedMinStock());
        entity.setRecommendedMaxStock(dto.getRecommendedMaxStock());
        entity.setGeneratedAt(dto.getGeneratedAt());
        entity.setSource(dto.getSource());
        return entity;
    }

    public void updateEntity(DemandForecast entity, DemandForecastUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setMaterialId(dto.getMaterialId());
        entity.setWarehouseId(dto.getWarehouseId());
        entity.setForecastMethod(dto.getForecastMethod());
        entity.setPeriodStart(dto.getPeriodStart());
        entity.setPeriodEnd(dto.getPeriodEnd());
        entity.setHistoryMonths(dto.getHistoryMonths());
        entity.setForecastMonths(dto.getForecastMonths());
        entity.setAverageMonthlyDemand(dto.getAverageMonthlyDemand());
        entity.setForecastQuantity(dto.getForecastQuantity());
        entity.setAnnualizedForecastQuantity(dto.getAnnualizedForecastQuantity());
        entity.setDemandStdDev(dto.getDemandStdDev());
        entity.setVariationCoefficient(dto.getVariationCoefficient());
        entity.setConfidence(dto.getConfidence());
        entity.setModelQuality(dto.getModelQuality());
        entity.setTrendSlope(dto.getTrendSlope());
        entity.setSeasonalityIndex(dto.getSeasonalityIndex());
        entity.setLeadTimeDays(dto.getLeadTimeDays());
        entity.setServiceLevel(dto.getServiceLevel());
        entity.setLeadTimeDemand(dto.getLeadTimeDemand());
        entity.setRecommendedSafetyStock(dto.getRecommendedSafetyStock());
        entity.setRecommendedReorderPoint(dto.getRecommendedReorderPoint());
        entity.setRecommendedMinStock(dto.getRecommendedMinStock());
        entity.setRecommendedMaxStock(dto.getRecommendedMaxStock());
        entity.setGeneratedAt(dto.getGeneratedAt());
        entity.setSource(dto.getSource());
    }
}