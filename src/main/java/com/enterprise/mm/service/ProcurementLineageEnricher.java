package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.payload.PurchaseOrderItemResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.PurchaseOrderSourceAllocationLineageResponse;
import com.enterprise.mm.payload.PurchaseRequestItemResponse;
import com.enterprise.mm.payload.PurchaseRequestResponse;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementLineageEnricher {
    private final DemandPlanRepository demandPlanRepository;
    private final DemandPlanItemRepository demandPlanItemRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;

    @Transactional(readOnly = true)
    public PurchaseRequestResponse enrichPurchaseRequest(PurchaseRequestResponse response) {
        if (response == null) {
            return null;
        }
        enrichPurchaseRequests(List.of(response));
        return response;
    }

    @Transactional(readOnly = true)
    public void enrichPurchaseRequests(Collection<PurchaseRequestResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return;
        }
        Map<UUID, DemandPlan> plansById = loadDemandPlans(responses.stream()
            .map(PurchaseRequestResponse::getDemandPlanId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));
        for (PurchaseRequestResponse response : responses) {
            applyDemandPlanHeader(response.getDemandPlanId(), plansById, (code, name) -> {
                response.setDemandPlanCode(code);
                response.setDemandPlanName(name);
            });
        }
    }

    @Transactional(readOnly = true)
    public PurchaseRequestItemResponse enrichPurchaseRequestItem(PurchaseRequestItemResponse response) {
        if (response == null) {
            return null;
        }
        enrichPurchaseRequestItems(List.of(response));
        return response;
    }

    @Transactional(readOnly = true)
    public void enrichPurchaseRequestItems(Collection<PurchaseRequestItemResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        Set<UUID> purchaseRequestItemIds = responses.stream()
            .map(PurchaseRequestItemResponse::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (purchaseRequestItemIds.isEmpty()) {
            return;
        }
        Map<UUID, DemandPlanItem> demandItemsByPurchaseRequestItemId =
            demandPlanItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalse(
                    tenantId, companyId, purchaseRequestItemIds)
                .stream()
                .filter(item -> item.getPurchaseRequestItemId() != null)
                .collect(Collectors.toMap(
                    DemandPlanItem::getPurchaseRequestItemId,
                    Function.identity(),
                    (left, right) -> left
                ));
        Map<UUID, DemandPlan> plansById = loadDemandPlans(demandItemsByPurchaseRequestItemId.values().stream()
            .map(DemandPlanItem::getDemandPlanId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));
        for (PurchaseRequestItemResponse response : responses) {
            DemandPlanItem demandItem = demandItemsByPurchaseRequestItemId.get(response.getId());
            if (demandItem == null) {
                continue;
            }
            response.setSourceDemandPlanItemId(demandItem.getId());
            response.setSourceDemandPlanItemCode(demandItem.getCode());
            response.setSourceDemandPlanItemName(displayName(demandItem));
            response.setSourceDemandPlanId(demandItem.getDemandPlanId());
            applyDemandPlanHeader(demandItem.getDemandPlanId(), plansById, (code, name) -> {
                response.setSourceDemandPlanCode(code);
                response.setSourceDemandPlanName(name);
            });
        }
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse enrichPurchaseOrder(PurchaseOrderResponse response) {
        if (response == null) {
            return null;
        }
        enrichPurchaseOrders(List.of(response));
        return response;
    }

    @Transactional(readOnly = true)
    public void enrichPurchaseOrders(Collection<PurchaseOrderResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return;
        }
        List<PurchaseOrderResponse> safeResponses = responses.stream().filter(Objects::nonNull).toList();
        if (safeResponses.isEmpty()) {
            return;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        Set<UUID> purchaseRequestIds = safeResponses.stream()
            .map(PurchaseOrderResponse::getPurchaseRequestId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, PurchaseRequest> purchaseRequestsById = purchaseRequestIds.isEmpty()
            ? Map.of()
            : purchaseRequestRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
                    tenantId, companyId, purchaseRequestIds)
                .stream()
                .collect(Collectors.toMap(PurchaseRequest::getId, Function.identity()));
        Set<UUID> demandPlanIds = new HashSet<>();
        for (PurchaseOrderResponse response : safeResponses) {
            UUID purchaseRequestId = response.getPurchaseRequestId();
            if (purchaseRequestId == null) {
                continue;
            }
            PurchaseRequest purchaseRequest = purchaseRequestsById.get(purchaseRequestId);
            if (purchaseRequest != null && purchaseRequest.getDemandPlanId() != null) {
                demandPlanIds.add(purchaseRequest.getDemandPlanId());
            }
        }
        Map<UUID, DemandPlan> plansById = loadDemandPlans(demandPlanIds);
        for (PurchaseOrderResponse response : safeResponses) {
            UUID purchaseRequestId = response.getPurchaseRequestId();
            if (purchaseRequestId == null) {
                continue;
            }
            PurchaseRequest purchaseRequest = purchaseRequestsById.get(purchaseRequestId);
            if (purchaseRequest == null || purchaseRequest.getDemandPlanId() == null) {
                continue;
            }
            response.setDemandPlanId(purchaseRequest.getDemandPlanId());
            applyDemandPlanHeader(purchaseRequest.getDemandPlanId(), plansById, (code, name) -> {
                response.setDemandPlanCode(code);
                response.setDemandPlanName(name);
            });
        }
    }

    @Transactional(readOnly = true)
    public PurchaseOrderItemResponse enrichPurchaseOrderItem(PurchaseOrderItemResponse response) {
        if (response == null) {
            return null;
        }
        enrichPurchaseOrderItems(List.of(response));
        return response;
    }

    @Transactional(readOnly = true)
    public void enrichPurchaseOrderItems(Collection<PurchaseOrderItemResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        Set<UUID> purchaseOrderItemIds = responses.stream()
            .map(PurchaseOrderItemResponse::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (purchaseOrderItemIds.isEmpty()) {
            return;
        }
        List<PurchaseOrderSourceAllocation> allocations =
            purchaseOrderSourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                tenantId, companyId, new ArrayList<>(purchaseOrderItemIds));
        Map<UUID, List<PurchaseOrderSourceAllocation>> allocationsByPoItemId = allocations.stream()
            .collect(Collectors.groupingBy(PurchaseOrderSourceAllocation::getPurchaseOrderItemId));
        Set<UUID> purchaseRequestItemIds = allocations.stream()
            .map(PurchaseOrderSourceAllocation::getPurchaseRequestItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, DemandPlanItem> demandItemsByPurchaseRequestItemId =
            purchaseRequestItemIds.isEmpty()
                ? Map.of()
                : demandPlanItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalse(
                        tenantId, companyId, purchaseRequestItemIds)
                    .stream()
                    .filter(item -> item.getPurchaseRequestItemId() != null)
                    .collect(Collectors.toMap(
                        DemandPlanItem::getPurchaseRequestItemId,
                        Function.identity(),
                        (left, right) -> left
                    ));
        Map<UUID, PurchaseRequestItem> purchaseRequestItemsById =
            purchaseRequestItemIds.isEmpty()
                ? Map.of()
                : purchaseRequestItemRepository.findWorkspaceLineageItems(
                        tenantId, companyId, purchaseRequestItemIds)
                    .stream()
                    .collect(Collectors.toMap(PurchaseRequestItem::getId, Function.identity()));
        Map<UUID, DemandPlan> plansById = loadDemandPlans(demandItemsByPurchaseRequestItemId.values().stream()
            .map(DemandPlanItem::getDemandPlanId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));
        for (PurchaseOrderItemResponse response : responses) {
            List<PurchaseOrderSourceAllocation> itemAllocations =
                allocationsByPoItemId.getOrDefault(response.getId(), List.of());
            response.setSourceAllocationAvailable(!itemAllocations.isEmpty());
            response.setSourceAllocations(itemAllocations.stream()
                .map(allocation -> toAllocationLineage(
                    allocation,
                    demandItemsByPurchaseRequestItemId,
                    purchaseRequestItemsById,
                    plansById))
                .toList());
        }
    }

    private PurchaseOrderSourceAllocationLineageResponse toAllocationLineage(
        PurchaseOrderSourceAllocation allocation,
        Map<UUID, DemandPlanItem> demandItemsByPurchaseRequestItemId,
        Map<UUID, PurchaseRequestItem> purchaseRequestItemsById,
        Map<UUID, DemandPlan> plansById
    ) {
        PurchaseOrderSourceAllocationLineageResponse lineage = new PurchaseOrderSourceAllocationLineageResponse();
        lineage.setAllocationId(allocation.getId());
        lineage.setPurchaseRequestItemId(allocation.getPurchaseRequestItemId());
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItemsById.get(allocation.getPurchaseRequestItemId());
        if (purchaseRequestItem != null) {
            lineage.setPurchaseRequestItemCode(purchaseRequestItem.getCode());
            lineage.setPurchaseRequestItemName(displayName(purchaseRequestItem));
        }
        lineage.setRfqPurchaseRequestSourceId(allocation.getRfqPurchaseRequestSourceId());
        lineage.setOrderedQuantity(allocation.getOrderedQuantity());
        DemandPlanItem demandItem = demandItemsByPurchaseRequestItemId.get(allocation.getPurchaseRequestItemId());
        if (demandItem != null) {
            lineage.setDemandPlanItemId(demandItem.getId());
            lineage.setDemandPlanItemCode(demandItem.getCode());
            lineage.setDemandPlanItemName(displayName(demandItem));
            lineage.setDemandPlanId(demandItem.getDemandPlanId());
            applyDemandPlanHeader(demandItem.getDemandPlanId(), plansById, (code, name) -> {
                lineage.setDemandPlanCode(code);
                lineage.setDemandPlanName(name);
            });
        }
        return lineage;
    }

    private Map<UUID, DemandPlan> loadDemandPlans(Collection<UUID> demandPlanIds) {
        if (demandPlanIds == null || demandPlanIds.isEmpty()) {
            return Map.of();
        }
        return demandPlanRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                demandPlanIds)
            .stream()
            .collect(Collectors.toMap(DemandPlan::getId, Function.identity()));
    }

    private void applyDemandPlanHeader(
        UUID demandPlanId,
        Map<UUID, DemandPlan> plansById,
        DemandPlanHeaderConsumer consumer
    ) {
        if (demandPlanId == null || consumer == null) {
            return;
        }
        DemandPlan plan = plansById.get(demandPlanId);
        if (plan == null) {
            return;
        }
        consumer.accept(plan.getCode(), displayName(plan));
    }

    private String displayName(DemandPlan plan) {
        if (plan == null) {
            return null;
        }
        return LocalizedText.forCurrentLocale(plan.getName());
    }

    private String displayName(DemandPlanItem item) {
        if (item == null) {
            return null;
        }
        return LocalizedText.forCurrentLocale(item.getName());
    }

    private String displayName(PurchaseRequestItem item) {
        if (item == null) {
            return null;
        }
        return LocalizedText.forCurrentLocale(item.getName());
    }

    @FunctionalInterface
    private interface DemandPlanHeaderConsumer {
        void accept(String code, String name);
    }
}
