package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementAwardOutcomeLine;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeLineRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementAwardOutcomeLineService {
    private final ProcurementAwardOutcomeLineRepository lineRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    private final ContractSpecificationRepository contractSpecificationRepository;
    private final RFQPurchaseRequestSourceRepository rfqSourceRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;

    @Transactional
    public void recordLines(ProcurementAwardOutcome outcome) {
        if (outcome == null || outcome.getId() == null) {
            return;
        }
        Set<UUID> affectedPurchaseRequestIds = new HashSet<>();
        if (outcome.getPurchaseOrderId() != null) {
            recordPurchaseOrderLines(outcome, affectedPurchaseRequestIds);
        } else if (outcome.getContractId() != null) {
            recordContractLines(outcome, affectedPurchaseRequestIds);
        }
        recalculatePurchaseRequestCoverage(affectedPurchaseRequestIds);
    }

    private void recordPurchaseOrderLines(
        ProcurementAwardOutcome outcome,
        Set<UUID> affectedPurchaseRequestIds
    ) {
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), outcome.getPurchaseOrderId())
            .stream()
            .filter(this::scoped)
            .toList();
        if (items.isEmpty()) {
            return;
        }
        Map<UUID, PurchaseOrderItem> itemsById = items.stream()
            .filter(item -> item.getId() != null)
            .collect(Collectors.toMap(PurchaseOrderItem::getId, item -> item, (left, right) -> left));
        List<PurchaseOrderSourceAllocation> allocations = sourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                itemsById.keySet().stream().toList()
            );
        for (PurchaseOrderSourceAllocation allocation : allocations) {
            PurchaseOrderItem item = itemsById.get(allocation.getPurchaseOrderItemId());
            if (item == null) {
                continue;
            }
            PurchaseRequestItem requestItem = purchaseRequestItem(allocation.getPurchaseRequestItemId());
            if (requestItem == null) {
                continue;
            }
            saveLine(
                outcome,
                requestItem.getPurchaseRequestId(),
                requestItem.getId(),
                null,
                null,
                allocation.getRfqPurchaseRequestSourceId(),
                item.getPurchaseOrderId(),
                item.getId(),
                null,
                null,
                amount(allocation.getOrderedQuantity()),
                amount(item.getUnitPrice()),
                "PO-ALLOCATION:" + allocation.getId()
            );
            affectedPurchaseRequestIds.add(requestItem.getPurchaseRequestId());
        }
    }

    private void recordContractLines(
        ProcurementAwardOutcome outcome,
        Set<UUID> affectedPurchaseRequestIds
    ) {
        List<ContractSpecification> specifications = contractSpecificationRepository
            .findByTenantIdAndContractIdAndIsDeletedFalse(TenantContext.currentTenant(), outcome.getContractId())
            .stream()
            .filter(this::scoped)
            .toList();
        for (ContractSpecification specification : specifications) {
            if (specification.getSourceRfqItemId() != null) {
                recordContractRfqSourceLines(outcome, specification, affectedPurchaseRequestIds);
                continue;
            }
            if (specification.getSourcePurchaseRequestItemId() != null) {
                PurchaseRequestItem requestItem = purchaseRequestItem(specification.getSourcePurchaseRequestItemId());
                if (requestItem == null) {
                    continue;
                }
                saveLine(
                    outcome,
                    requestItem.getPurchaseRequestId(),
                    requestItem.getId(),
                    null,
                    specification.getSourceQuotationItemId(),
                    null,
                    null,
                    null,
                    specification.getContractId(),
                    specification.getId(),
                    amount(specification.getQuantityLimit()),
                    amount(specification.getPrice()),
                    "CONTRACT-PR-ITEM:" + specification.getId() + ":" + requestItem.getId()
                );
                affectedPurchaseRequestIds.add(requestItem.getPurchaseRequestId());
            }
        }
    }

    private void recordContractRfqSourceLines(
        ProcurementAwardOutcome outcome,
        ContractSpecification specification,
        Set<UUID> affectedPurchaseRequestIds
    ) {
        List<RFQPurchaseRequestSource> sources = rfqSourceRepository
            .findByTenantIdAndCompanyIdAndRfqItemIdAndStatusAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                specification.getSourceRfqItemId(),
                "ACTIVE"
            );
        for (RFQPurchaseRequestSource source : sources) {
            saveLine(
                outcome,
                source.getPurchaseRequestId(),
                source.getPurchaseRequestItemId(),
                specification.getSourceRfqItemId(),
                specification.getSourceQuotationItemId(),
                source.getId(),
                null,
                null,
                specification.getContractId(),
                specification.getId(),
                amount(source.getAllocatedQuantity()),
                amount(specification.getPrice()),
                "CONTRACT-RFQ-SOURCE:" + specification.getId() + ":" + source.getId()
            );
            affectedPurchaseRequestIds.add(source.getPurchaseRequestId());
        }
    }

    private void saveLine(
        ProcurementAwardOutcome outcome,
        UUID purchaseRequestId,
        UUID purchaseRequestItemId,
        UUID rfqItemId,
        UUID supplierQuotationItemId,
        UUID rfqPurchaseRequestSourceId,
        UUID purchaseOrderId,
        UUID purchaseOrderItemId,
        UUID contractId,
        UUID contractSpecificationId,
        BigDecimal awardedQuantity,
        BigDecimal unitPrice,
        String idempotencyKey
    ) {
        if (purchaseRequestId == null || purchaseRequestItemId == null || idempotencyKey == null) {
            return;
        }
        boolean exists = lineRepository
            .findByTenantIdAndCompanyIdAndProcurementAwardOutcomeIdAndIdempotencyKeyAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                outcome.getId(),
                idempotencyKey
            ).isPresent();
        if (exists) {
            return;
        }
        BigDecimal quantity = amount(awardedQuantity);
        BigDecimal price = amount(unitPrice);
        ProcurementAwardOutcomeLine line = new ProcurementAwardOutcomeLine();
        line.setCode("PAOL-" + shortId());
        line.setName(BusinessNameGenerator.workflowDocumentName("ProcurementAwardOutcomeLine", outcome.getName()));
        line.setStatus("FINALIZED");
        line.setProcurementAwardOutcomeId(outcome.getId());
        line.setProcurementProcedureId(outcome.getProcurementProcedureId());
        line.setSourceType(outcome.getSourceType());
        line.setPurchaseRequestId(purchaseRequestId);
        line.setPurchaseRequestItemId(purchaseRequestItemId);
        line.setRfqItemId(rfqItemId);
        line.setSupplierQuotationItemId(supplierQuotationItemId);
        line.setRfqPurchaseRequestSourceId(rfqPurchaseRequestSourceId);
        line.setPurchaseOrderId(purchaseOrderId);
        line.setPurchaseOrderItemId(purchaseOrderItemId);
        line.setContractId(contractId);
        line.setContractSpecificationId(contractSpecificationId);
        line.setAwardedQuantity(quantity);
        line.setUnitPrice(price);
        line.setAwardedAmount(quantity.multiply(price));
        line.setCurrencyCode(currency(outcome.getCurrencyCode()));
        line.setIdempotencyKey(idempotencyKey);
        lineRepository.save(line);
    }

    private void recalculatePurchaseRequestCoverage(Collection<UUID> purchaseRequestIds) {
        if (purchaseRequestIds == null || purchaseRequestIds.isEmpty()) {
            return;
        }
        List<PurchaseRequest> requests = purchaseRequestRepository.findAllByIdInForUpdate(purchaseRequestIds)
            .stream()
            .filter(this::scoped)
            .toList();
        if (requests.isEmpty()) {
            return;
        }
        Set<UUID> scopedRequestIds = requests.stream().map(PurchaseRequest::getId).collect(Collectors.toSet());
        List<PurchaseRequestItem> requestItems = purchaseRequestItemRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                scopedRequestIds
            );
        Map<UUID, List<PurchaseRequestItem>> itemsByRequest = requestItems.stream()
            .collect(Collectors.groupingBy(PurchaseRequestItem::getPurchaseRequestId));
        List<ProcurementAwardOutcomeLine> lines = lineRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                scopedRequestIds
            );
        Map<UUID, BigDecimal> poBackedQuantityByItem = lines.stream()
            .filter(line -> line.getPurchaseOrderId() != null)
            .collect(Collectors.groupingBy(
                ProcurementAwardOutcomeLine::getPurchaseRequestItemId,
                Collectors.mapping(line -> amount(line.getAwardedQuantity()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        for (PurchaseRequest request : requests) {
            List<PurchaseRequestItem> items = itemsByRequest.getOrDefault(request.getId(), List.of());
            if (items.isEmpty() || shouldPreserveRequestStatus(request.getStatus())) {
                continue;
            }
            boolean fullyPoBacked = items.stream().allMatch(item ->
                amount(poBackedQuantityByItem.get(item.getId())).compareTo(amount(item.getQuantity())) >= 0);
            if (fullyPoBacked) {
                request.setStatus(PurchaseRequestStatus.PO_CREATED.name());
                purchaseRequestRepository.save(request);
            }
        }
    }

    private boolean shouldPreserveRequestStatus(String status) {
        return List.of(
            PurchaseRequestStatus.PO_CREATED.name(),
            PurchaseRequestStatus.CONVERTED_TO_PO.name(),
            PurchaseRequestStatus.CANCELLED.name(),
            PurchaseRequestStatus.REJECTED.name()
        ).contains(status);
    }

    private PurchaseRequestItem purchaseRequestItem(UUID id) {
        if (id == null) {
            return null;
        }
        return purchaseRequestItemRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElse(null);
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && Objects.equals(TenantContext.currentTenant(), entity.getTenantId())
            && Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId());
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String currency(String value) {
        String normalized = value == null || value.isBlank()
            ? "UZS"
            : value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : "UZS";
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
