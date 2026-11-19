package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.DemandPlanItemPurchaseRequestAllocation;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.DispatchReadiness;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ReadinessCheck;
import com.enterprise.mm.payload.SupplierEligibilityDtos.MaterialGroupEligibility;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.payload.SupplierEligibilityDtos.UnifiedSupplierEligibility;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.DemandPlanItemPurchaseRequestAllocationRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.service.ContractService;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierAccreditationDemandResolver;
import com.enterprise.mm.service.SupplierEligibilityService;
import com.enterprise.mm.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class PurchaseOrderDispatchReadinessService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository itemRepository;
    private final SupplierAccreditationDemandResolver accreditationDemandResolver;
    private final SupplierAccreditationService supplierAccreditationService;
    private final SupplierEligibilityService supplierEligibilityService;
    private final ContractService contractService;
    private final PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    private final DemandPlanItemPurchaseRequestAllocationRepository demandAllocationRepository;
    private final DemandPlanItemRepository demandPlanItemRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public PurchaseOrderDispatchReadinessService(
        PurchaseOrderRepository purchaseOrderRepository,
        PurchaseOrderItemRepository itemRepository,
        SupplierAccreditationDemandResolver accreditationDemandResolver,
        SupplierAccreditationService supplierAccreditationService,
        SupplierEligibilityService supplierEligibilityService,
        ContractService contractService,
        PurchaseOrderSourceAllocationRepository sourceAllocationRepository,
        DemandPlanItemPurchaseRequestAllocationRepository demandAllocationRepository,
        DemandPlanItemRepository demandPlanItemRepository
    ) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.itemRepository = itemRepository;
        this.accreditationDemandResolver = accreditationDemandResolver;
        this.supplierAccreditationService = supplierAccreditationService;
        this.supplierEligibilityService = supplierEligibilityService;
        this.contractService = contractService;
        this.sourceAllocationRepository = sourceAllocationRepository;
        this.demandAllocationRepository = demandAllocationRepository;
        this.demandPlanItemRepository = demandPlanItemRepository;
    }

    public PurchaseOrderDispatchReadinessService(
        PurchaseOrderRepository purchaseOrderRepository,
        PurchaseOrderItemRepository itemRepository,
        SupplierAccreditationDemandResolver accreditationDemandResolver,
        SupplierAccreditationService supplierAccreditationService,
        ContractService contractService,
        PurchaseOrderSourceAllocationRepository sourceAllocationRepository,
        DemandPlanItemPurchaseRequestAllocationRepository demandAllocationRepository,
        DemandPlanItemRepository demandPlanItemRepository
    ) {
        this(
            purchaseOrderRepository,
            itemRepository,
            accreditationDemandResolver,
            supplierAccreditationService,
            null,
            contractService,
            sourceAllocationRepository,
            demandAllocationRepository,
            demandPlanItemRepository
        );
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public DispatchReadiness evaluate(UUID purchaseOrderId) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + purchaseOrderId));
        List<PurchaseOrderItem> items = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .toList();
        List<ReadinessCheck> checks = new ArrayList<>();
        AccreditationDemand accreditationDemand = accreditationDemand(items);
        List<UUID> materialGroupIds = List.copyOf(accreditationDemand.materialGroupIds());
        List<MaterialGroupEligibility> materialGroupEligibility = List.of();

        checks.add(check(
            "PROCUREMENT_PO_APPROVED",
            PurchaseOrderStatus.APPROVED.name().equals(po.getStatus()),
            true,
            "status", po.getStatus()));
        checks.add(check(
            "PROCUREMENT_COMPANY_SCOPE_VALID",
            AccessScope.canAccessCompany(po.getCompanyId()),
            true,
            "companyId", po.getCompanyId()));
        checks.add(check(
            "PROCUREMENT_SUPPLIER_REQUIRED",
            po.getSupplierId() != null,
            true,
            "supplierId", po.getSupplierId()));

        if (po.getSupplierId() != null) {
            UnifiedSupplierEligibility eligibility = dispatchEligibility(po.getSupplierId(), accreditationDemand);
            String reason = eligibility.summaryReason();
            if (accreditationDemand.materialRequired()
                && dispatchAccreditationRequired(accreditationDemand)) {
                materialGroupEligibility = supplierAccreditationService.materialGroupEligibility(
                    po.getSupplierId(), materialGroupIds, "purchase order dispatch");
            }
            String code = eligibility.hardBlocked()
                ? "PROCUREMENT_SUPPLIER_BLOCKED"
                : "PROCUREMENT_SUPPLIER_NEW_BUSINESS_ALLOWED";
            checks.add(check(code, eligibility.newBusinessAllowed(), true, "reason", reason));
        }
        if (items.stream().anyMatch(item -> item.getLineType() == ProcurementLineType.WORK)) {
            try {
                UUID contractId = contractService.requireWorksContract(
                    po.getContractId(),
                    po.getSupplierId(),
                    normalizedCurrency(po)
                );
                checks.add(check("PROCUREMENT_WORKS_CONTRACT_VALID", true, true, "contractId", contractId));
            } catch (BusinessException exception) {
                checks.add(check(
                    "PROCUREMENT_WORKS_CONTRACT_REQUIRED",
                    false,
                    true,
                    "reason",
                    exception.getMessage()
                ));
            }
        }

        checks.add(check("PROCUREMENT_PO_LINES_PRESENT", !items.isEmpty(), true, "lineCount", items.size()));
        boolean quantitiesValid = !items.isEmpty() && items.stream().allMatch(this::validQuantity);
        checks.add(check("PROCUREMENT_PO_QUANTITIES_VALID", quantitiesValid, true, "lineCount", items.size()));
        checks.add(check(
            "PROCUREMENT_CURRENCY_VALID",
            currencyValid(po),
            true,
            "currency", po.getCurrencyCode()));
        checks.add(check(
            "PROCUREMENT_EXPECTED_DATE_PRESENT",
            po.getExpectedDeliveryDate() != null,
            true,
            "expectedDeliveryDate", po.getExpectedDeliveryDate()));
        DemandPlanOverage demandPlanOverage = demandPlanOverage(po, items);
        if (demandPlanOverage == null) {
            checks.add(check("PROCUREMENT_DEMAND_PLAN_COVERAGE_VALID", true, true, "reason", null));
        } else {
            checks.add(check(demandPlanOverage.code(), false, true, "reason", demandPlanOverage.reason()));
        }


        boolean ready = checks.stream().noneMatch(check -> check.blocking() && !check.passed());
        return new DispatchReadiness(ready, List.copyOf(checks), List.copyOf(materialGroupEligibility));
    }

    private UnifiedSupplierEligibility dispatchEligibility(UUID supplierId, AccreditationDemand demand) {
        if (supplierEligibilityService != null) {
            return supplierEligibilityService.evaluateNewBusiness(supplierId, demand, "PO_DISPATCH");
        }
        String reason = supplierAccreditationService.supplierEligibilityBlockedReasonForDemand(
            supplierId, demand, "purchase order dispatch");
        return new UnifiedSupplierEligibility(
            supplierId,
            java.time.LocalDate.now(),
            "PO_DISPATCH",
            reason == null ? "ELIGIBLE" : "NOT_ELIGIBLE",
            reason == null,
            true,
            reason != null && reason.toLowerCase(Locale.ROOT).contains("block"),
            reason == null ? List.of() : List.of(new com.enterprise.mm.payload.SupplierEligibilityDtos.SupplierEligibilityReason(
                "SUPPLIER_ACCREDITATION_REQUIRED",
                reason
            )),
            List.of()
        );
    }

    private boolean dispatchAccreditationRequired(AccreditationDemand demand) {
        if (supplierEligibilityService != null) {
            return supplierEligibilityService.accreditationRequired("PO_DISPATCH", demand);
        }
        return demand != null && (demand.materialRequired() || demand.serviceRequired() || demand.worksRequired());
    }

    @Transactional(readOnly = true)
    public void requireReady(UUID purchaseOrderId) {
        DispatchReadiness readiness = evaluate(purchaseOrderId);
        readiness.checks().stream()
            .filter(check -> check.blocking() && !check.passed())
            .findFirst()
            .ifPresent(check -> {
                throw new BusinessException(check.code() + ": "
                    + String.valueOf(check.parameters().getOrDefault("reason", "dispatch readiness failed")));
            });
    }

    private ReadinessCheck check(
        String code,
        boolean passed,
        boolean blocking,
        String parameterName,
        Object parameterValue
    ) {
        Map<String, Object> parameters = parameterValue == null
            ? Map.of()
            : Map.of(parameterName, parameterValue);
        return new ReadinessCheck(code, passed, blocking, parameters);
    }

    private boolean validQuantity(PurchaseOrderItem item) {
        return item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0;
    }

    public AccreditationDemand accreditationDemand(List<PurchaseOrderItem> items) {
        return accreditationDemandResolver.forPurchaseOrderItems(items);
    }

    private boolean currencyValid(PurchaseOrder po) {
        String currency = normalizedCurrency(po);
        return "UZS".equals(currency)
            || (po.getExchangeRate() != null && po.getExchangeRate().compareTo(BigDecimal.ZERO) > 0);
    }

    private String normalizedCurrency(PurchaseOrder po) {
        return po.getCurrencyCode() == null || po.getCurrencyCode().isBlank()
            ? "UZS"
            : po.getCurrencyCode().trim().toUpperCase(Locale.ROOT);
    }

    private DemandPlanOverage demandPlanOverage(PurchaseOrder po, List<PurchaseOrderItem> items) {
        List<UUID> currentPoItemIds = items.stream()
            .filter(this::isMaterialLine)
            .map(PurchaseOrderItem::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (currentPoItemIds.isEmpty()) {
            return null;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = po.getCompanyId();
        List<PurchaseOrderSourceAllocation> currentAllocations = sourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                tenantId, companyId, currentPoItemIds);
        List<UUID> seedPurchaseRequestItemIds = purchaseRequestItemIds(currentAllocations);
        if (seedPurchaseRequestItemIds.isEmpty()) {
            return null;
        }
        List<DemandPlanItemPurchaseRequestAllocation> seedDemandAllocations = demandAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                tenantId, companyId, seedPurchaseRequestItemIds);
        List<DemandPlanItem> directDemandItems = demandPlanItemRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalse(
                tenantId, companyId, seedPurchaseRequestItemIds);
        Set<UUID> demandItemIds = new HashSet<>();
        seedDemandAllocations.stream()
            .map(DemandPlanItemPurchaseRequestAllocation::getDemandPlanItemId)
            .filter(Objects::nonNull)
            .forEach(demandItemIds::add);
        directDemandItems.stream()
            .map(DemandPlanItem::getId)
            .filter(Objects::nonNull)
            .forEach(demandItemIds::add);
        if (demandItemIds.isEmpty()) {
            return null;
        }
        List<DemandPlanItemPurchaseRequestAllocation> allDemandAllocations = demandAllocationRepository
            .findByTenantIdAndCompanyIdAndDemandPlanItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                tenantId, companyId, demandItemIds);
        Set<UUID> demandItemsWithPersistedAllocation = allDemandAllocations.stream()
            .map(DemandPlanItemPurchaseRequestAllocation::getDemandPlanItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        List<DemandAllocation> demandAllocations = new ArrayList<>();
        allDemandAllocations.stream()
            .map(allocation -> new DemandAllocation(
                allocation.getDemandPlanItemId(),
                allocation.getPurchaseRequestItemId(),
                nvl(allocation.getAllocatedQuantity())))
            .forEach(demandAllocations::add);
        directDemandItems.stream()
            .filter(item -> item.getId() != null)
            .filter(item -> item.getPurchaseRequestItemId() != null)
            .filter(item -> !demandItemsWithPersistedAllocation.contains(item.getId()))
            .map(item -> new DemandAllocation(item.getId(), item.getPurchaseRequestItemId(), nvl(item.getApprovedQuantity())))
            .forEach(demandAllocations::add);
        if (demandAllocations.isEmpty()) {
            return null;
        }
        List<UUID> purchaseRequestItemIds = demandAllocations.stream()
            .map(DemandAllocation::purchaseRequestItemId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (purchaseRequestItemIds.isEmpty()) {
            return null;
        }
        List<PurchaseOrderSourceAllocation> allSourceAllocations = sourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                tenantId, companyId, purchaseRequestItemIds);
        if (allSourceAllocations.isEmpty()) {
            return null;
        }
        Map<UUID, PurchaseOrderItem> purchaseOrderItems = purchaseOrderItems(items, allSourceAllocations);
        Map<UUID, DemandPlanItem> demandItems = demandPlanItems(demandItemIds, directDemandItems);
        List<DemandPlanItemPurchaseRequestAllocation> allocationShares = demandAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                tenantId, companyId, purchaseRequestItemIds);
        Map<UUID, BigDecimal> totalDemandAllocationByPrItem = allocationShares.stream()
            .collect(Collectors.groupingBy(
                DemandPlanItemPurchaseRequestAllocation::getPurchaseRequestItemId,
                Collectors.mapping(allocation -> nvl(allocation.getAllocatedQuantity()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        Map<UUID, List<PurchaseOrderSourceAllocation>> sourceAllocationsByPrItem = allSourceAllocations.stream()
            .collect(Collectors.groupingBy(PurchaseOrderSourceAllocation::getPurchaseRequestItemId));
        Map<UUID, List<DemandAllocation>> demandAllocationsByItem = demandAllocations.stream()
            .collect(Collectors.groupingBy(DemandAllocation::demandPlanItemId));
        for (Map.Entry<UUID, List<DemandAllocation>> entry : demandAllocationsByItem.entrySet()) {
            DemandPlanItem demandItem = demandItems.get(entry.getKey());
            if (demandItem == null) {
                continue;
            }
            BigDecimal orderedQuantity = BigDecimal.ZERO;
            BigDecimal orderedAmount = BigDecimal.ZERO;
            for (DemandAllocation demandAllocation : entry.getValue()) {
                BigDecimal share = demandShare(demandAllocation, totalDemandAllocationByPrItem);
                for (PurchaseOrderSourceAllocation sourceAllocation : sourceAllocationsByPrItem
                    .getOrDefault(demandAllocation.purchaseRequestItemId(), List.of())) {
                    BigDecimal quantity = nvl(sourceAllocation.getOrderedQuantity()).multiply(share);
                    orderedQuantity = orderedQuantity.add(quantity);
                    PurchaseOrderItem poItem = purchaseOrderItems.get(sourceAllocation.getPurchaseOrderItemId());
                    if (poItem != null) {
                        orderedAmount = orderedAmount.add(quantity.multiply(nvl(poItem.getUnitPrice())));
                    }
                }
            }
            String label = demandItem.getCode() == null || demandItem.getCode().isBlank()
                ? String.valueOf(demandItem.getId())
                : demandItem.getCode();
            if (orderedQuantity.compareTo(nvl(demandItem.getApprovedQuantity())) > 0) {
                return new DemandPlanOverage(
                    "PROCUREMENT_DEMAND_PLAN_QUANTITY_EXCEEDED",
                    "Purchase order quantity exceeds approved demand plan quantity for " + label
                );
            }
            BigDecimal approvedAmount = nvl(demandItem.getEstimatedAmount());
            if (approvedAmount.signum() > 0 && orderedAmount.compareTo(approvedAmount) > 0) {
                return new DemandPlanOverage(
                    "PROCUREMENT_DEMAND_PLAN_AMOUNT_EXCEEDED",
                    "Purchase order amount exceeds approved demand plan amount for " + label
                );
            }
        }
        return null;
    }

    private Map<UUID, PurchaseOrderItem> purchaseOrderItems(
        List<PurchaseOrderItem> currentItems,
        List<PurchaseOrderSourceAllocation> allocations
    ) {
        Map<UUID, PurchaseOrderItem> result = currentItems.stream()
            .filter(item -> item.getId() != null)
            .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity(), (left, right) -> left));
        List<UUID> missingIds = allocations.stream()
            .map(PurchaseOrderSourceAllocation::getPurchaseOrderItemId)
            .filter(Objects::nonNull)
            .filter(id -> !result.containsKey(id))
            .distinct()
            .toList();
        if (!missingIds.isEmpty()) {
            itemRepository.findAllById(missingIds).forEach(item -> {
                if (TenantContext.currentTenant().equals(item.getTenantId())
                    && AccessScope.canAccessCompany(item.getCompanyId())
                    && !item.isDeleted()) {
                    result.put(item.getId(), item);
                }
            });
        }
        return result;
    }

    private Map<UUID, DemandPlanItem> demandPlanItems(Collection<UUID> demandItemIds, List<DemandPlanItem> directDemandItems) {
        Map<UUID, DemandPlanItem> result = directDemandItems.stream()
            .filter(item -> item.getId() != null)
            .collect(Collectors.toMap(DemandPlanItem::getId, Function.identity(), (left, right) -> left));
        List<UUID> missingIds = demandItemIds.stream()
            .filter(id -> !result.containsKey(id))
            .distinct()
            .toList();
        if (!missingIds.isEmpty()) {
            demandPlanItemRepository.findAllById(missingIds).forEach(item -> {
                if (TenantContext.currentTenant().equals(item.getTenantId())
                    && AccessScope.canAccessCompany(item.getCompanyId())
                    && !item.isDeleted()) {
                    result.put(item.getId(), item);
                }
            });
        }
        return result;
    }

    private BigDecimal demandShare(DemandAllocation allocation, Map<UUID, BigDecimal> totalDemandAllocationByPrItem) {
        BigDecimal allocated = nvl(allocation.allocatedQuantity());
        if (allocated.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = nvl(totalDemandAllocationByPrItem.get(allocation.purchaseRequestItemId()));
        if (total.signum() <= 0) {
            return BigDecimal.ONE;
        }
        return allocated.divide(total, 8, java.math.RoundingMode.HALF_UP);
    }

    private List<UUID> purchaseRequestItemIds(List<PurchaseOrderSourceAllocation> allocations) {
        return allocations.stream()
            .map(PurchaseOrderSourceAllocation::getPurchaseRequestItemId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private boolean isMaterialLine(PurchaseOrderItem item) {
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        return lineType == ProcurementLineType.MATERIAL;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record DemandPlanOverage(String code, String reason) {}

    private record DemandAllocation(UUID demandPlanItemId, UUID purchaseRequestItemId, BigDecimal allocatedQuantity) {}
}
