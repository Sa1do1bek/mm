package com.enterprise.mm.mapper;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.payload.DemandPlanResponse;
import com.enterprise.mm.payload.DemandPlanUpdateRequest;
import com.enterprise.mm.payload.DemandPlanCreateRequest;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DemandPlanMapper {
    @PersistenceContext
    private EntityManager entityManager;

    public DemandPlanResponse toResponse(DemandPlan entity) {
        if (entity == null) {
            return null;
        }
        DemandPlanResponse dto = new DemandPlanResponse();
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
        dto.setPlanningCycleId(idOf(entity.getPlanningCycle()));
        dto.setDemandPlanName(entity.getName());
        dto.setRequestType(entity.getRequestType() == null ? null : entity.getRequestType().name());
        dto.setPlanYear(entity.getPlanYear());
        dto.setPeriodType(entity.getPeriodType());
        dto.setQuarter(entity.getQuarter());
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setDepartmentId(idOf(entity.getDepartment()));
        dto.setDepartmentName(nameOf(entity.getDepartment()));
        dto.setBudgetItemId(idOf(entity.getBudgetItem()));
        dto.setBudgetItemName(nameOf(entity.getBudgetItem()));
        dto.setOwnerUsername(ownerDisplayName(entity));
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setTotalPlannedAmount(DataQualityGuards.roundMoney(entity.getTotalPlannedAmount(), entity.getCurrencyCode()));
        dto.setTotalApprovedAmount(DataQualityGuards.roundMoney(entity.getTotalApprovedAmount(), entity.getCurrencyCode()));
        dto.setTotalReservedQuantity(entity.getTotalReservedQuantity());
        dto.setConsolidatedAt(entity.getConsolidatedAt());
        dto.setApprovedAt(entity.getApprovedAt());
        return dto;
    }

    public DemandPlan toEntity(DemandPlanCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        DemandPlan entity = new DemandPlan();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRequestType(parseRequestType(dto.getRequestType()));
        entity.setPlanningCycle(reference(DemandPlanningCycle.class, dto.getPlanningCycleId()));
        entity.setPlanYear(dto.getPlanYear());
        entity.setPeriodType(dto.getPeriodType());
        entity.setQuarter(dto.getQuarter());
        entity.setPeriodStart(DataQualityGuards.requireSaneDate(dto.getPeriodStart(), "periodStart"));
        entity.setPeriodEnd(DataQualityGuards.requireSaneDate(dto.getPeriodEnd(), "periodEnd"));
        DataQualityGuards.requireDateOrder(entity.getPeriodStart(), entity.getPeriodEnd(), "periodStart", "periodEnd");
        entity.setDepartment(reference(Department.class, dto.getDepartmentId()));
        entity.setBudgetItem(reference(BudgetItem.class, dto.getBudgetItemId()));
        entity.setOwnerUsername(dto.getOwnerUsername());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setTotalPlannedAmount(DataQualityGuards.roundMoney(dto.getTotalPlannedAmount(), dto.getCurrencyCode()));
        entity.setTotalApprovedAmount(DataQualityGuards.roundMoney(dto.getTotalApprovedAmount(), dto.getCurrencyCode()));
        entity.setTotalReservedQuantity(dto.getTotalReservedQuantity());
        entity.setConsolidatedAt(dto.getConsolidatedAt());
        entity.setApprovedAt(dto.getApprovedAt());
        return entity;
    }

    public void updateEntity(DemandPlan entity, DemandPlanUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setRequestType(parseRequestType(dto.getRequestType()));
        entity.setPlanningCycle(reference(DemandPlanningCycle.class, dto.getPlanningCycleId()));
        entity.setPlanYear(dto.getPlanYear());
        entity.setPeriodType(dto.getPeriodType());
        entity.setQuarter(dto.getQuarter());
        entity.setPeriodStart(DataQualityGuards.requireSaneDate(dto.getPeriodStart(), "periodStart"));
        entity.setPeriodEnd(DataQualityGuards.requireSaneDate(dto.getPeriodEnd(), "periodEnd"));
        DataQualityGuards.requireDateOrder(entity.getPeriodStart(), entity.getPeriodEnd(), "periodStart", "periodEnd");
        entity.setDepartment(reference(Department.class, dto.getDepartmentId()));
        entity.setBudgetItem(reference(BudgetItem.class, dto.getBudgetItemId()));
        entity.setOwnerUsername(dto.getOwnerUsername());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setTotalPlannedAmount(DataQualityGuards.roundMoney(dto.getTotalPlannedAmount(), dto.getCurrencyCode()));
        entity.setTotalApprovedAmount(DataQualityGuards.roundMoney(dto.getTotalApprovedAmount(), dto.getCurrencyCode()));
        entity.setTotalReservedQuantity(dto.getTotalReservedQuantity());
        entity.setConsolidatedAt(dto.getConsolidatedAt());
        entity.setApprovedAt(dto.getApprovedAt());
    }

    // Blank defaults to GOODS; anything else must be a valid RequestType.
    private RequestType parseRequestType(String value) {
        if (value == null || value.isBlank()) {
            return RequestType.GOODS;
        }
        try {
            return RequestType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid request type: " + value, "demand_plan.request_type.invalid", value);
        }
    }

    private <T> T reference(Class<T> entityClass, UUID id) {
        return id == null ? null : entityManager.getReference(entityClass, id);
    }

    private UUID idOf(BusinessEntity entity) {
        return entity == null ? null : entity.getId();
    }

    private String nameOf(BusinessEntity entity) {
        return entity == null ? null : entity.getName();
    }

    private String ownerDisplayName(DemandPlan entity) {
        return BaseMapper.firstNonBlank(
            DemandPlanningCycleMapper.ownerDisplayName(entity.getPlanningCycle()),
            entity.getOwnerUsername()
        );
    }
}
