package com.enterprise.mm.mapper;

import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.payload.DemandPlanningCycleCreateRequest;
import com.enterprise.mm.payload.DemandPlanningCycleResponse;
import com.enterprise.mm.payload.DemandPlanningCycleUpdateRequest;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class DemandPlanningCycleMapper {
    public DemandPlanningCycleResponse toResponse(DemandPlanningCycle entity) {
        if (entity == null) {
            return null;
        }
        DemandPlanningCycleResponse dto = new DemandPlanningCycleResponse();
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
        dto.setPlanYear(entity.getPlanYear());
        dto.setPeriodType(entity.getPeriodType());
        dto.setQuarter(entity.getQuarter());
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setCollectionStartDate(entity.getCollectionStartDate());
        dto.setCollectionEndDate(entity.getCollectionEndDate());
        dto.setBudgetReviewDeadline(entity.getBudgetReviewDeadline());
        dto.setConsolidationDeadline(entity.getConsolidationDeadline());
        dto.setActivationDate(entity.getActivationDate());
        dto.setOwnerEmployeeId(entity.getOwnerEmployee() == null ? null : entity.getOwnerEmployee().getId());
        dto.setOwnerEmployeeName(entity.getOwnerEmployee() == null ? null : entity.getOwnerEmployee().getName());
        dto.setOwnerUsername(ownerDisplayName(entity));
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setTotalPlanCount(entity.getTotalPlanCount());
        dto.setTotalItemCount(entity.getTotalItemCount());
        dto.setTotalPlannedAmount(DataQualityGuards.roundMoney(entity.getTotalPlannedAmount(), entity.getCurrencyCode()));
        dto.setTotalApprovedAmount(DataQualityGuards.roundMoney(entity.getTotalApprovedAmount(), entity.getCurrencyCode()));
        dto.setTotalReservedQuantity(entity.getTotalReservedQuantity());
        dto.setGeneratedAt(entity.getGeneratedAt());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setActivatedAt(entity.getActivatedAt());
        dto.setClosedAt(entity.getClosedAt());
        return dto;
    }

    static String ownerDisplayName(DemandPlanningCycle entity) {
        if (entity == null) {
            return null;
        }
        return BaseMapper.firstNonBlank(
            entity.getOwnerEmployee() == null ? null : BaseMapper.userName(entity.getOwnerEmployee().getUser()),
            entity.getOwnerEmployee() == null ? null : BaseMapper.employeeName(entity.getOwnerEmployee()),
            entity.getOwnerUsername()
        );
    }

    public DemandPlanningCycle toEntity(DemandPlanningCycleCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        DemandPlanningCycle entity = new DemandPlanningCycle();
        apply(entity, dto.getCode(), dto.getName(), dto.getDescription(), dto.getPlanYear(),
            dto.getPeriodType(), dto.getQuarter(), dto.getPeriodStart(), dto.getPeriodEnd(),
            dto.getCollectionStartDate(), dto.getCollectionEndDate(), dto.getBudgetReviewDeadline(),
            dto.getConsolidationDeadline(), dto.getActivationDate(), dto.getCurrencyCode());
        return entity;
    }

    public void updateEntity(DemandPlanningCycle entity, DemandPlanningCycleUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        apply(entity, dto.getCode(), dto.getName(), dto.getDescription(), dto.getPlanYear(),
            dto.getPeriodType(), dto.getQuarter(), dto.getPeriodStart(), dto.getPeriodEnd(),
            dto.getCollectionStartDate(), dto.getCollectionEndDate(), dto.getBudgetReviewDeadline(),
            dto.getConsolidationDeadline(), dto.getActivationDate(), dto.getCurrencyCode());
    }

    private void apply(
        DemandPlanningCycle entity,
        String code,
        String name,
        String description,
        Integer planYear,
        String periodType,
        Integer quarter,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate collectionStartDate,
        LocalDate collectionEndDate,
        LocalDate budgetReviewDeadline,
        LocalDate consolidationDeadline,
        LocalDate activationDate,
        String currencyCode
    ) {
        entity.setCode(code);
        entity.setName(name);
        entity.setDescription(description);
        entity.setPlanYear(planYear);
        entity.setPeriodType(periodType);
        entity.setQuarter(quarter);
        entity.setPeriodStart(DataQualityGuards.requireSaneDate(periodStart, "periodStart"));
        entity.setPeriodEnd(DataQualityGuards.requireSaneDate(periodEnd, "periodEnd"));
        DataQualityGuards.requireDateOrder(entity.getPeriodStart(), entity.getPeriodEnd(), "periodStart", "periodEnd");
        entity.setCollectionStartDate(DataQualityGuards.requireSaneDate(collectionStartDate, "collectionStartDate"));
        entity.setCollectionEndDate(DataQualityGuards.requireSaneDate(collectionEndDate, "collectionEndDate"));
        DataQualityGuards.requireDateOrder(entity.getCollectionStartDate(), entity.getCollectionEndDate(),
            "collectionStartDate", "collectionEndDate");
        entity.setBudgetReviewDeadline(DataQualityGuards.requireSaneDate(budgetReviewDeadline, "budgetReviewDeadline"));
        entity.setConsolidationDeadline(DataQualityGuards.requireSaneDate(consolidationDeadline, "consolidationDeadline"));
        entity.setActivationDate(DataQualityGuards.requireSaneDate(activationDate, "activationDate"));
        entity.setCurrencyCode(currencyCode);
    }
}
