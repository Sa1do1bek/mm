package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import java.math.BigDecimal;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseOrderServiceOrderLinkService {
    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final String DEFAULT_CURRENCY = "UZS";

    private final ServiceOrderRepository serviceOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final AuditService auditService;

    @Transactional
    public Optional<ServiceOrder> ensureForPurchaseOrder(PurchaseOrder purchaseOrder, String auditAction) {
        if (purchaseOrder == null || purchaseOrder.getRequestType() != RequestType.SERVICE) {
            return Optional.empty();
        }
        if (purchaseOrder.getId() == null) {
            throw new BusinessException("Purchase order must be saved before service order creation");
        }
        if (purchaseOrder.getSupplierId() == null) {
            throw new BusinessException("Supplier is required before service order creation");
        }

        String tenantId = effectiveTenant(purchaseOrder);
        UUID companyId = effectiveCompany(purchaseOrder);
        Optional<ServiceOrder> existing = serviceOrderRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId,
                companyId,
                purchaseOrder.getId(),
                purchaseOrder.getSupplierId()
            )
            .stream()
            .findFirst();
        if (existing.isPresent()) {
            return existing;
        }

        ServiceOrder order = new ServiceOrder();
        order.setTenantId(tenantId);
        order.setCompanyId(companyId);
        order.setCode(serviceOrderCode(purchaseOrder));
        order.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            ServiceOrder.class.getSimpleName(),
            null,
            order.getCode(),
            purchaseOrder.getName()
        ));
        order.setStatus(DEFAULT_STATUS);
        order.setSupplierId(purchaseOrder.getSupplierId());
        order.setContractId(purchaseOrder.getContractId());
        order.setPurchaseOrderId(purchaseOrder.getId());
        order.setServiceRequestId(serviceRequestId(purchaseOrder));
        order.setTotalAmount(Objects.requireNonNullElse(purchaseOrder.getTotalAmount(), BigDecimal.ZERO));
        order.setCurrencyCode(currency(purchaseOrder));
        order.setServiceType(RequestType.SERVICE.name());

        ServiceOrder saved = serviceOrderRepository.save(order);
        auditService.log(
            ServiceOrder.class.getSimpleName(),
            saved.getId(),
            auditAction == null || auditAction.isBlank() ? "ENSURE_FROM_SERVICE_PURCHASE_ORDER" : auditAction,
            saved.getCode(),
            null,
            saved
        );

        List<PurchaseOrderItem> itemsToUpdate = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, purchaseOrder.getId())
            .stream()
            .filter(i -> i.getServiceOrderId() == null)
            .peek(i -> i.setServiceOrderId(saved.getId()))
            .toList();
        if (!itemsToUpdate.isEmpty()) {
            purchaseOrderItemRepository.saveAll(itemsToUpdate);
            itemsToUpdate.forEach(i -> auditService.log(
                "PurchaseOrderItem", i.getId(), "LINK_SERVICE_ORDER", saved.getId().toString(), null, i));
        }

        return Optional.of(saved);
    }

    private String effectiveTenant(PurchaseOrder purchaseOrder) {
        return purchaseOrder.getTenantId() == null || purchaseOrder.getTenantId().isBlank()
            ? TenantContext.currentTenant()
            : purchaseOrder.getTenantId();
    }

    private UUID effectiveCompany(PurchaseOrder purchaseOrder) {
        return purchaseOrder.getCompanyId() == null
            ? CompanyContext.currentCompany()
            : purchaseOrder.getCompanyId();
    }

    private String currency(PurchaseOrder purchaseOrder) {
        String currency = purchaseOrder.getCurrencyCode();
        return currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency.trim().toUpperCase(Locale.ROOT);
    }

    private String serviceOrderCode(PurchaseOrder purchaseOrder) {
        String source = purchaseOrder.getCode();
        if (source == null || source.isBlank()) {
            source = purchaseOrder.getId().toString();
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-");
        if (normalized.isBlank()) {
            normalized = purchaseOrder.getId().toString().replace("-", "").toUpperCase(Locale.ROOT);
        }
        String code = "SO-" + normalized;
        return code.length() <= 80 ? code : code.substring(0, 80);
    }

    private UUID serviceRequestId(PurchaseOrder purchaseOrder) {
        return purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(effectiveTenant(purchaseOrder), purchaseOrder.getId())
            .stream()
            .filter(item -> item.getLineType() == ProcurementLineType.SERVICE)
            .map(PurchaseOrderItem::getServiceRequestId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }
}
