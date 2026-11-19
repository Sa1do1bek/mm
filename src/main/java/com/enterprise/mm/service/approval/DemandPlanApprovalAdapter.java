package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.ApprovalStepType;
import com.enterprise.mm.enums.DemandPlanItemStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewLineDecision;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewLineResponse;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewResponse;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.BudgetItemService;
import com.enterprise.mm.service.DemandPlanItemService;
import com.enterprise.mm.service.IntegrationEventService;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DemandPlanApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "DEMAND_PLAN";

    private final DemandPlanRepository demandPlanRepository;
    private final DemandPlanItemRepository itemRepository;
    private final DemandPlanItemService demandPlanItemService;
    private final IntegrationEventService integrationEventService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final AuditService auditService;
    private final BudgetItemService budgetItemService;
    private final StockBalanceRepository stockBalanceRepository;
    private final MaterialRepository materialRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final WarehouseRepository warehouseRepository;



    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return find(entityId).getCompanyId();
    }

    @Override
    public UUID departmentId(UUID entityId) {
        DemandPlan plan = find(entityId);
        return plan.getDepartment() == null ? null : plan.getDepartment().getId();
    }

    @Override
    public boolean usesRequestType() {
        return true;
    }

    @Override
    public RequestType requestType(UUID entityId) {
        DemandPlan plan = find(entityId);
        return plan.getRequestType() == null ? RequestType.GOODS : plan.getRequestType();
    }

    @Override
    public String displayName(UUID entityId) {
        DemandPlan plan = find(entityId);
        return plan.getName();
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        DemandPlan plan = find(entityId);
        if (!DemandPlanStatus.DRAFT.name().equals(plan.getStatus())
                && !DemandPlanStatus.COLLECTING.name().equals(plan.getStatus())) {
            throw new BusinessException("Demand plan must be DRAFT or COLLECTING, current status is " + plan.getStatus());
        }
        // Позиции разделяют жизненный цикл плана: отдельная отправка позиций не
        // требуется — при отправке плана черновые позиции отправляются каскадно
        // (см. markSubmitted). Достаточно, чтобы в плане была хотя бы одна
        // отправляемая позиция (DRAFT или уже продвинутая по статусу).
        boolean hasSubmittableItem = itemRepository
            .findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)
            .stream()
            .anyMatch(item -> DemandPlanItemStatus.DRAFT.name().equals(item.getStatus())
                || isSubmittedOrApproved(item));
        if (!hasSubmittableItem) {
            throw new BusinessException("Demand plan must contain at least one demand item");
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        DemandPlan plan = find(entityId);
        // Каскадно отправляем черновые позиции вместе с планом, чтобы их не нужно
        // было отправлять вручную по отдельности.
        if (DemandPlanStatus.SUBMITTED.name().equals(plan.getStatus())) {
            return;
        }
        if (!DemandPlanStatus.DRAFT.name().equals(plan.getStatus())
                && !DemandPlanStatus.COLLECTING.name().equals(plan.getStatus())) {
            throw new BusinessException("Demand plan must be DRAFT, COLLECTING or SUBMITTED, current status is " + plan.getStatus());
        }
        for (DemandPlanItem item : itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)) {
            if (DemandPlanItemStatus.DRAFT.name().equals(item.getStatus())) {
                item.setStatus(DemandPlanItemStatus.SUBMITTED.name());
                itemRepository.save(item);
                auditService.log("DemandPlanItem", item.getId(), "SUBMIT", item.getCode(), null, null);
            }
        }
        plan.setStatus(DemandPlanStatus.SUBMITTED.name());
        demandPlanItemService.recalculatePlanTotals(entityId);
        integrationEventService.emit("BUDGETING.DEMAND_PLAN_SUBMITTED", "BUDGETING", plan.getId(), Map.of(
            "demandPlanId", plan.getId(),
            "amount", nvl(plan.getTotalPlannedAmount()),
            "currency", currency(plan)
        ));
        auditService.log("DemandPlan", entityId, "SUBMIT", plan.getCode(), null, null);
        demandPlanRepository.save(plan);
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        find(entityId);
        return itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)
            .stream()
            .map(this::documentLine)
            .toList();
    }

    @Override
    public DemandStockReviewResponse demandStockReview(UUID entityId, ApprovalFlowStep step) {
        if (step == null || step.getStepType() != ApprovalStepType.DEMAND_STOCK_CHECK) {
            return null;
        }
        DemandPlan plan = find(entityId);
        List<DemandPlanItem> items = itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId);
        List<UUID> materialIds = items.stream()
            .map(DemandPlanItem::getMaterialId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        Map<UUID, BigDecimal> availableByMaterial = availableStock(plan.getCompanyId(), step.getWarehouseId(), materialIds);
        List<DemandStockReviewLineResponse> lines = items.stream()
            .map(item -> stockReviewLine(item, availableByMaterial.getOrDefault(item.getMaterialId(), BigDecimal.ZERO)))
            .toList();
        Warehouse warehouse = step.getWarehouseId() == null
            ? null
            : warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), plan.getCompanyId(), step.getWarehouseId()).orElse(null);
        return new DemandStockReviewResponse(
            step.getWarehouseId(),
            warehouse == null ? (step.getWarehouseId() == null ? "All warehouses" : null) : warehouse.getName(),
            lines
        );
    }

    @Override
    public void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
        if (step == null || step.getStepType() != ApprovalStepType.DEMAND_STOCK_CHECK) {
            return;
        }
        List<DemandStockReviewLineDecision> decisions = request == null ? null : request.demandStockReviewLines();
        if (decisions == null || decisions.isEmpty()) {
            return;
        }
        DemandPlan plan = find(entityId);
        Map<UUID, DemandPlanItem> itemsById = itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)
            .stream()
            .collect(Collectors.toMap(DemandPlanItem::getId, Function.identity()));
        boolean changed = false;
        for (DemandStockReviewLineDecision decision : decisions) {
            if (decision == null || decision.demandPlanItemId() == null) {
                continue;
            }
            DemandPlanItem item = itemsById.get(decision.demandPlanItemId());
            if (item == null) {
                throw new BusinessException("Demand stock review line does not belong to this demand plan");
            }
            BigDecimal approvedQuantity = nvl(decision.approvedQuantity());
            if (approvedQuantity.signum() < 0) {
                throw new BusinessException("Approved quantity cannot be negative");
            }
            if (approvedQuantity.compareTo(nvl(item.getRequestedQuantity())) > 0) {
                throw new BusinessException("Approved quantity cannot exceed requested quantity");
            }
            BigDecimal stockQuantity = decision.stockFulfillmentQuantity() == null
                ? nvl(item.getStockFulfillmentQuantity()) : decision.stockFulfillmentQuantity();
            if (stockQuantity.signum() < 0 || stockQuantity.stripTrailingZeros().scale() > com.enterprise.mm.service.StockUomPolicy.MAX_QUANTITY_SCALE
                || stockQuantity.add(approvedQuantity).compareTo(nvl(item.getRequestedQuantity())) > 0) {
                throw new BusinessException("Approved stock and procurement cannot exceed the demand need", "demandSource.quantity");
            }
            if (item.getPurchaseRequestItemId() != null) {
                throw new BusinessException("Procurement already exists. Ask the planner to resolve it before changing demand", "demandSource.procurementExists");
            }
            if (stockQuantity.signum() > 0) {
                com.enterprise.mm.service.StockUomPolicy.quantity(resolveMaterial(item.getMaterialId()), stockQuantity, true);
                com.enterprise.mm.service.StockUomPolicy.quantity(resolveMaterial(item.getMaterialId()), approvedQuantity, true);
                BigDecimal available = stockBalanceRepository.sumAvailableQuantityByMaterialIds(
                    TenantContext.currentTenant(), plan.getCompanyId(), step.getWarehouseId(), List.of(item.getMaterialId()))
                    .stream().map(row -> nvl(row.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (stockQuantity.compareTo(available) > 0)
                    throw new BusinessException("Approved stock exceeds current usable stock. Ask the warehouse to refresh the review", "demandSource.quantity");
            }
            BigDecimal previousQuantity = nvl(item.getApprovedQuantity());
            if (previousQuantity.compareTo(approvedQuantity) == 0 && stockQuantity.compareTo(nvl(item.getStockFulfillmentQuantity())) == 0) {
                continue;
            }
            String reason = decision.changeReason() == null ? "" : decision.changeReason().trim();
            if (reason.isBlank()) {
                throw new BusinessException("Change reason is required when approved quantity changes");
            }
            item.setStockFulfillmentQuantity(stockQuantity);
            item.setApprovedQuantity(approvedQuantity);
            item.setEstimatedAmount(approvedQuantity.multiply(nvl(item.getEstimatedUnitPrice())));
            itemRepository.save(item);
            auditService.log(
                "DemandPlanItem",
                item.getId(),
                "STOCK_REVIEW_ADJUST",
                "approvedQuantity " + previousQuantity + " -> " + approvedQuantity
                    + "; approvedStock=" + stockQuantity
                    + "; warehouseId=" + (step.getWarehouseId() == null ? "ALL" : step.getWarehouseId())
                    + "; reason=" + reason
                    + "; demandPlan=" + plan.getCode()
            , null, null);
            changed = true;
        }
        if (changed) {
            demandPlanItemService.recalculatePlanTotals(entityId);
        }
    }

    @Override
    public Boolean isApprovalTaskActive(UUID entityId) {
        return DemandPlanStatus.SUBMITTED.name().equals(find(entityId).getStatus());
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        DemandPlan plan = find(entityId);
        if (!DemandPlanStatus.SUBMITTED.name().equals(plan.getStatus())) {
            throw new BusinessException("Demand plan must be SUBMITTED, current status is " + plan.getStatus());
        }
        segregationOfDutyRuleService.validate("DemandPlan", "APPROVE", plan);
        for (DemandPlanItem item : itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)) {
            if (DemandPlanItemStatus.SUBMITTED.name().equals(item.getStatus()) || DemandPlanItemStatus.DRAFT.name().equals(item.getStatus())) {
                item.setStatus(DemandPlanItemStatus.APPROVED.name());
                if (nvl(item.getApprovedQuantity()).signum() == 0 && nvl(item.getStockFulfillmentQuantity()).signum() == 0) {
                    item.setApprovedQuantity(nvl(item.getRequestedQuantity()));
                }
                itemRepository.save(item);
            }
        }
        demandPlanItemService.recalculatePlanTotals(entityId);
        plan = find(entityId);
        allocateApprovedBudget(entityId, plan);
        plan.setStatus(DemandPlanStatus.APPROVED.name());
        plan.setApprovedAt(LocalDateTime.now());
        integrationEventService.emit("BUDGETING.DEMAND_PLAN_APPROVED", "BUDGETING", plan.getId(), Map.of(
            "demandPlanId", plan.getId(),
            "amount", nvl(plan.getTotalApprovedAmount()),
            "currency", currency(plan)
        ));
        auditService.log("DemandPlan", entityId, "APPROVE", comment, null, null);
        demandPlanRepository.save(plan);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        DemandPlan plan = find(entityId);
        if (!"SUBMITTED".equals(plan.getStatus())) throw new BusinessException("Only a submitted demand can be returned for correction");
        var items = itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId);
        if (items.stream().anyMatch(item -> item.getPurchaseRequestItemId() != null))
            throw new BusinessException("Procurement already exists; demand correction requires procurement resolution");
        for (var item : items) {
            item.setStatus("DRAFT"); item.setApprovedQuantity(BigDecimal.ZERO); item.setStockFulfillmentQuantity(BigDecimal.ZERO);
            itemRepository.save(item);
        }
        plan.setStatus("COLLECTING"); plan.setApprovedAt(null); demandPlanRepository.save(plan);
        demandPlanItemService.recalculatePlanTotals(entityId);
        auditService.log("DemandPlan", entityId, "RETURN_FOR_CORRECTION", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        DemandPlan plan = find(entityId);
        if (!DemandPlanStatus.SUBMITTED.name().equals(plan.getStatus())) {
            throw new BusinessException("Demand plan must be SUBMITTED, current status is " + plan.getStatus());
        }
        plan.setStatus(DemandPlanStatus.REJECTED.name());
        demandPlanRepository.save(plan);
        auditService.log("DemandPlan", entityId, "REJECT", comment, null, null);
    }

    private DemandPlan find(UUID id) {
        return demandPlanRepository.findByIdAndIsDeletedFalse(id)
            .filter(plan -> TenantContext.currentTenant().equals(plan.getTenantId()))
            .orElseThrow(() -> new NotFoundException("DemandPlan not found: " + id));
    }

    private boolean isSubmittedOrApproved(DemandPlanItem item) {
        return DemandPlanItemStatus.SUBMITTED.name().equals(item.getStatus())
            || DemandPlanItemStatus.APPROVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.RESERVED.name().equals(item.getStatus());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<UUID, BigDecimal> availableStock(UUID companyId, UUID warehouseId, List<UUID> materialIds) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> availableByMaterial = new HashMap<>();
        stockBalanceRepository.sumAvailableQuantityByMaterialIds(
                TenantContext.currentTenant(), companyId, warehouseId, materialIds)
            .forEach(row -> availableByMaterial.put(row.getMaterialId(), nvl(row.getQuantity())));
        return availableByMaterial;
    }

    private DemandStockReviewLineResponse stockReviewLine(DemandPlanItem item, BigDecimal availableStock) {
        BigDecimal requestedQuantity = nvl(item.getRequestedQuantity());
        BigDecimal approvedQuantity = nvl(item.getApprovedQuantity());
        if (approvedQuantity.signum() == 0 && nvl(item.getStockFulfillmentQuantity()).signum() == 0) {
            approvedQuantity = requestedQuantity;
        }
        BigDecimal differenceQuantity = requestedQuantity.subtract(nvl(availableStock)).max(BigDecimal.ZERO);
        Material material = resolveMaterial(item.getMaterialId());
        String unitOfMeasureShortName = unitOfMeasureShortName(material);
        return new DemandStockReviewLineResponse(
            item.getId(),
            item.getMaterialId(),
            material == null ? item.getName() : material.getName(),
            unitOfMeasureShortName,
            requestedQuantity,
            approvedQuantity,
            nvl(availableStock),
            differenceQuantity,
            differenceQuantity.min(requestedQuantity),
            nvl(item.getStockFulfillmentQuantity()),
            null
        );
    }

    private ApprovalDetailItem documentLine(DemandPlanItem item) {
        Material material = resolveMaterial(item.getMaterialId());
        String label = material == null
            ? (hasText(item.getName()) ? item.getName() : "Позиция")
            : material.getName();
        if (material != null && hasText(material.getCode())) {
            label = material.getCode() + " — " + label;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("materialId", item.getMaterialId());
        values.put("materialCode", material == null ? null : material.getCode());
        values.put("materialName", material == null ? null : material.getName());
        values.put("unitOfMeasure", unitOfMeasureShortName(material));
        values.put("requestedQuantity", nvl(item.getRequestedQuantity()));
        values.put("approvedQuantity", nvl(item.getApprovedQuantity()));
        values.put("estimatedUnitPrice", nvl(item.getEstimatedUnitPrice()));
        values.put("estimatedAmount", nvl(item.getEstimatedAmount()));
        values.put("currencyCode", item.getCurrencyCode());
        values.put("priority", item.getPriority());
        values.put("status", item.getStatus());
        values.put("requiredDate", item.getRequiredDate());
        values.put("needByDate", item.getNeedByDate());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return new ApprovalDetailItem(
            item.getId() == null ? null : item.getId().toString(),
            label,
            values
        );
    }

    private Material resolveMaterial(UUID materialId) {
        if (materialId == null) {
            return null;
        }
        return materialRepository.findByIdAndIsDeletedFalse(materialId).orElse(null);
    }

    private String unitOfMeasureShortName(Material material) {
        if (material == null || material.getUnitOfMeasureId() == null) {
            return null;
        }
        UnitOfMeasure unit = unitOfMeasureRepository
            .findByIdAndIsDeletedFalse(material.getUnitOfMeasureId())
            .orElse(null);
        if (unit == null) {
            return null;
        }
        if (hasText(unit.getMeasurementNameShort())) {
            return unit.getMeasurementNameShort();
        }
        return hasText(unit.getCode()) ? unit.getCode() : unit.getName();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void allocateApprovedBudget(UUID entityId, DemandPlan plan) {
        Set<UUID> budgetItemIds = new HashSet<>();
        for (DemandPlanItem item : itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entityId)) {
            if (item.getBudgetItemId() != null && isBudgetApprovedItem(item)) {
                budgetItemIds.add(item.getBudgetItemId());
            }
        }
        budgetItemIds.forEach(budgetItemId ->
            budgetItemService.ensureAnnualLimitAtLeast(budgetItemId, totalApprovedAmount(budgetItemId), "DemandPlan " + plan.getCode())
        );
    }

    private BigDecimal totalApprovedAmount(UUID budgetItemId) {
        return itemRepository.findByTenantIdAndBudgetItemIdAndIsDeletedFalse(TenantContext.currentTenant(), budgetItemId)
            .stream()
            .filter(this::isBudgetApprovedItem)
            .map(this::approvedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isBudgetApprovedItem(DemandPlanItem item) {
        return DemandPlanItemStatus.APPROVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.RESERVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.CONVERTED_TO_PR.name().equals(item.getStatus());
    }

    private BigDecimal approvedAmount(DemandPlanItem item) {
        BigDecimal amount = nvl(item.getEstimatedAmount());
        if (amount.signum() == 0) {
            amount = nvl(item.getApprovedQuantity()).multiply(nvl(item.getEstimatedUnitPrice()));
        }
        return amount;
    }

    private String currency(DemandPlan plan) {
        return plan.getCurrencyCode() == null || plan.getCurrencyCode().isBlank() ? "UZS" : plan.getCurrencyCode();
    }
}
