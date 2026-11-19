package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.service.DemandPlanningCycleService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class DemandPlanningCycleApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "DEMAND_PLANNING_CYCLE";

    private final DemandPlanningCycleRepository repository;
    private final DemandPlanningCycleService cycleService;

    public DemandPlanningCycleApprovalAdapter(
        DemandPlanningCycleRepository repository,
        @Lazy DemandPlanningCycleService cycleService
    ) {
        this.repository = repository;
        this.cycleService = cycleService;
    }

    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return find(entityId).getCompanyId();
    }

    @Override
    public String displayName(UUID entityId) {
        DemandPlanningCycle cycle = find(entityId);
        return cycle.getName();
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        DemandPlanningCycle cycle = find(entityId);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", cycle.getCode());
        values.put("planYear", cycle.getPlanYear());
        values.put("periodType", cycle.getPeriodType());
        values.put("quarter", cycle.getQuarter());
        values.put("ownerUsername", cycle.getOwnerUsername());
        values.put("currencyCode", cycle.getCurrencyCode());
        values.put("totalPlanCount", cycle.getTotalPlanCount());
        values.put("totalItemCount", cycle.getTotalItemCount());
        values.put("totalPlannedAmount", cycle.getTotalPlannedAmount());
        values.put("totalApprovedAmount", cycle.getTotalApprovedAmount());
        values.put("status", cycle.getStatus());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return List.of(new ApprovalDetailItem(
            cycle.getId() == null ? null : cycle.getId().toString(),
            cycle.getCode() == null ? cycle.getName() : cycle.getCode(),
            values
        ));
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        cycleService.validateBudgetSubmission(entityId);
    }

    @Override
    public void markSubmitted(UUID entityId) {
        cycleService.applyBudgetSubmitted(entityId);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        cycleService.completeBudgetApprovalFromWorkflow(entityId, comment);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        cycleService.rejectBudgetFromWorkflow(entityId, comment);
    }

    private DemandPlanningCycle find(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(cycle -> TenantContext.currentTenant().equals(cycle.getTenantId()))
            .orElseThrow(() -> new NotFoundException("DemandPlanningCycle not found: " + id));
    }
}
