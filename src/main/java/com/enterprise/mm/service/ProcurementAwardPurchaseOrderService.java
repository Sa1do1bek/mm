package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcurementAwardPurchaseOrderService {
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderResponse create(
        ProcurementProcedure procedure,
        UUID supplierId,
        BigDecimal totalAmount,
        String currencyCode,
        UUID contractId,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String description
    ) {
        Supplier supplier = requireSupplier(supplierId);
        PurchaseRequest purchaseRequest = requirePurchaseRequest(procedure.getPurchaseRequestId());
        List<PurchaseRequestItem> requestItems = purchaseRequestItemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseRequest.getId())
            .stream()
            .filter(this::scoped)
            .toList();
        if (requestItems.isEmpty()) {
            throw new BusinessException("Purchase request has no lines");
        }
        BigDecimal awardAmount = positive(totalAmount, "awardAmount");
        PurchaseOrderCreateRequest request = new PurchaseOrderCreateRequest();
        request.setCode("PO-" + procedure.getProcedureNumber() + "-" + shortId());
        request.setName(BusinessNameGenerator.workflowDocumentName("PurchaseOrder", purchaseRequest.getName()));
        request.setDescription(description);
        request.setSupplierId(supplier.getId());
        request.setPurchaseRequestId(purchaseRequest.getId());
        request.setContractId(contractId);
        LocalDate effectiveOrderDate = orderDate == null ? LocalDate.now() : orderDate;
        request.setOrderDate(effectiveOrderDate);
        request.setExpectedDeliveryDate(
            expectedDeliveryDate == null ? effectiveOrderDate.plusDays(14) : expectedDeliveryDate
        );
        request.setCurrencyCode(currency(currencyCode, purchaseRequest.getCurrencyCode()));
        request.setItems(lines(purchaseRequest, requestItems, awardAmount));
        return purchaseOrderService.create(request);
    }

    private List<PurchaseOrderItemCreateRequest> lines(
        PurchaseRequest purchaseRequest,
        List<PurchaseRequestItem> items,
        BigDecimal totalAmount
    ) {
        BigDecimal estimatedTotal = items.stream()
            .map(item -> amount(item.getQuantity()).multiply(amount(item.getEstimatedPrice())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalQuantity = items.stream()
            .map(PurchaseRequestItem::getQuantity)
            .map(this::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return items.stream()
            .map(item -> line(purchaseRequest, item, allocatedUnitPrice(item, totalAmount, estimatedTotal, totalQuantity)))
            .toList();
    }

    private BigDecimal allocatedUnitPrice(
        PurchaseRequestItem item,
        BigDecimal totalAmount,
        BigDecimal estimatedTotal,
        BigDecimal totalQuantity
    ) {
        if (estimatedTotal.signum() > 0) {
            BigDecimal multiplier = totalAmount.divide(estimatedTotal, 8, RoundingMode.HALF_UP);
            return amount(item.getEstimatedPrice()).multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
        }
        return totalQuantity.signum() > 0
            ? totalAmount.divide(totalQuantity, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    }

    private PurchaseOrderItemCreateRequest line(
        PurchaseRequest purchaseRequest,
        PurchaseRequestItem item,
        BigDecimal unitPrice
    ) {
        PurchaseOrderItemCreateRequest line = new PurchaseOrderItemCreateRequest();
        line.setName(item.getName());
        line.setPurchaseRequestItemId(item.getId());
        String lineType = resolveLineType(purchaseRequest, item);
        line.setLineType(lineType);
        line.setMaterialTypeSnapshot(item.getMaterialTypeSnapshot());
        if (com.enterprise.mm.enums.ProcurementLineType.MATERIAL.name().equals(lineType)) {
            if (item.getMaterialId() == null) {
                throw new BusinessException(ProcurementLineValidationService.GOODS_PO_ITEM_REQUIRES_MATERIAL);
            }
            line.setMaterialId(item.getMaterialId());
        }
        line.setServiceRequestId(item.getServiceRequestId());
        line.setServiceDescription(firstText(item.getServiceDescription(), item.getName()));
        line.setServiceCategory(item.getServiceCategory());
        line.setUnit(item.getUnit());
        line.setPeriod(item.getPeriod());
        line.setStage(item.getStage());
        line.setBudgetItemId(item.getBudgetItemId());
        line.setQuantity(amount(item.getQuantity()));
        line.setUnitPrice(unitPrice);
        return line;
    }

    private String resolveLineType(PurchaseRequest purchaseRequest, PurchaseRequestItem item) {
        if (purchaseRequest.getRequestType() != null && purchaseRequest.getRequestType().isServiceStyle()) {
            return com.enterprise.mm.enums.ProcurementLineType.SERVICE.name();
        }
        if (item.getLineType() != null) {
            return item.getLineType().name();
        }
        return com.enterprise.mm.enums.ProcurementLineType.MATERIAL.name();
    }


    private String firstText(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private Supplier requireSupplier(UUID id) {
        if (id == null) {
            throw new BusinessException("supplierId is required");
        }
        Supplier supplier = supplierRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException("Supplier not found: " + id));
        if (!Set.of("ACTIVE", "LIMITED").contains(supplier.getStatus())) {
            throw new BusinessException("Awarded supplier must be eligible for existing award fulfillment");
        }
        return supplier;
    }

    private PurchaseRequest requirePurchaseRequest(UUID id) {
        if (id == null) {
            throw new BusinessException("purchaseRequestId is required for purchase order creation");
        }
        PurchaseRequest request = purchaseRequestRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException("Purchase request not found: " + id));
        if (!Set.of(
            PurchaseRequestStatus.APPROVED.name(),
            PurchaseRequestStatus.RFQ_CREATED.name(),
            PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name()
        ).contains(request.getStatus())) {
            throw new BusinessException("Purchase request is not ready for purchase order creation");
        }
        return request;
    }

    public void validateSupplier(UUID id) {
        requireSupplier(id);
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    private BigDecimal positive(BigDecimal value, String field) {
        BigDecimal normalized = amount(value);
        if (normalized.signum() <= 0) {
            throw new BusinessException(field + " must be greater than zero");
        }
        return normalized;
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String currency(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        normalized = normalized == null || normalized.isBlank() ? "UZS" : normalized.trim().toUpperCase();
        if (!normalized.matches("[A-Z]{3}")) {
            throw new BusinessException("Currency code must contain three letters");
        }
        return normalized;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
