package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ProcurementPlanResponse;
import com.enterprise.mm.payload.ProcurementPlanUpdateRequest;
import com.enterprise.mm.payload.ProcurementPlanCreateRequest;
import com.enterprise.mm.entity.ProcurementPlan;
import org.springframework.stereotype.Component;

@Component
public class ProcurementPlanMapper {
    public ProcurementPlanResponse toResponse(ProcurementPlan entity) {
        if (entity == null) {
            return null;
        }
        ProcurementPlanResponse dto = new ProcurementPlanResponse();
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
        dto.setPlanNumber(entity.getPlanNumber());
        dto.setPlanYear(entity.getPlanYear());
        dto.setPeriodType(entity.getPeriodType());
        dto.setPeriodQuarter(entity.getPeriodQuarter());
        dto.setDemandPlanningCycleId(entity.getDemandPlanningCycleId());
        dto.setCategoryStrategyId(entity.getCategoryStrategyId());
        dto.setMaterialGroupId(entity.getMaterialGroupId());
        dto.setBudgetItemId(entity.getBudgetItemId());
        dto.setOwnerEmployeeId(entity.getOwnerEmployeeId());
        dto.setPlannedSpendAmount(entity.getPlannedSpendAmount());
        dto.setApprovedBudgetAmount(entity.getApprovedBudgetAmount());
        dto.setReservedBudgetAmount(entity.getReservedBudgetAmount());
        dto.setExpectedSavingsAmount(entity.getExpectedSavingsAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setPlanStartDate(entity.getPlanStartDate());
        dto.setPlanEndDate(entity.getPlanEndDate());
        dto.setDemandCount(entity.getDemandCount());
        dto.setCampaignCount(entity.getCampaignCount());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setActivatedAt(entity.getActivatedAt());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    public ProcurementPlan toEntity(ProcurementPlanCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ProcurementPlan entity = new ProcurementPlan();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPlanNumber(dto.getPlanNumber());
        entity.setPlanYear(dto.getPlanYear());
        entity.setPeriodType(dto.getPeriodType());
        entity.setPeriodQuarter(dto.getPeriodQuarter());
        entity.setDemandPlanningCycleId(dto.getDemandPlanningCycleId());
        entity.setCategoryStrategyId(dto.getCategoryStrategyId());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setOwnerEmployeeId(dto.getOwnerEmployeeId());
        entity.setPlannedSpendAmount(dto.getPlannedSpendAmount());
        entity.setApprovedBudgetAmount(dto.getApprovedBudgetAmount());
        entity.setReservedBudgetAmount(dto.getReservedBudgetAmount());
        entity.setExpectedSavingsAmount(dto.getExpectedSavingsAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setPlanStartDate(dto.getPlanStartDate());
        entity.setPlanEndDate(dto.getPlanEndDate());
        entity.setDemandCount(dto.getDemandCount());
        entity.setCampaignCount(dto.getCampaignCount());
        entity.setSubmittedAt(dto.getSubmittedAt());
        entity.setApprovedAt(dto.getApprovedAt());
        entity.setActivatedAt(dto.getActivatedAt());
        entity.setClosedAt(dto.getClosedAt());
        return entity;
    }

    public void updateEntity(ProcurementPlan entity, ProcurementPlanUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setPlanNumber(dto.getPlanNumber());
        entity.setPlanYear(dto.getPlanYear());
        entity.setPeriodType(dto.getPeriodType());
        entity.setPeriodQuarter(dto.getPeriodQuarter());
        entity.setDemandPlanningCycleId(dto.getDemandPlanningCycleId());
        entity.setCategoryStrategyId(dto.getCategoryStrategyId());
        entity.setMaterialGroupId(dto.getMaterialGroupId());
        entity.setBudgetItemId(dto.getBudgetItemId());
        entity.setOwnerEmployeeId(dto.getOwnerEmployeeId());
        entity.setPlannedSpendAmount(dto.getPlannedSpendAmount());
        entity.setApprovedBudgetAmount(dto.getApprovedBudgetAmount());
        entity.setReservedBudgetAmount(dto.getReservedBudgetAmount());
        entity.setExpectedSavingsAmount(dto.getExpectedSavingsAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setPlanStartDate(dto.getPlanStartDate());
        entity.setPlanEndDate(dto.getPlanEndDate());
        entity.setDemandCount(dto.getDemandCount());
        entity.setCampaignCount(dto.getCampaignCount());
        entity.setSubmittedAt(dto.getSubmittedAt());
        entity.setApprovedAt(dto.getApprovedAt());
        entity.setActivatedAt(dto.getActivatedAt());
        entity.setClosedAt(dto.getClosedAt());
    }
}