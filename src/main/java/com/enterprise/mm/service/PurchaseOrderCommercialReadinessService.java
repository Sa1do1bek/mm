package com.enterprise.mm.service;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PurchaseOrderCommercialReadinessService {
    private static final Set<PurchaseOrderStatus> STANDALONE_INVOICEABLE_STATUSES = EnumSet.of(
        PurchaseOrderStatus.SENT,
        PurchaseOrderStatus.PARTIALLY_COMPLETED,
        PurchaseOrderStatus.COMPLETED,
        PurchaseOrderStatus.PARTIALLY_RECEIVED,
        PurchaseOrderStatus.RECEIVED,
        PurchaseOrderStatus.CLOSED
    );

    public void requireStandalonePoInvoiceable(
        PurchaseOrder purchaseOrder,
        UUID requestedSupplierId,
        String requestedCurrencyCode
    ) {
        if (purchaseOrder.getContractId() != null) {
            throw new BusinessException("Standalone PO invoice requires a purchase order without contract");
        }
        if (!STANDALONE_INVOICEABLE_STATUSES.contains(status(purchaseOrder))) {
            throw new BusinessException("Standalone PO invoice requires a sent or fulfilled purchase order");
        }
        if (purchaseOrder.getPurchaseRequestId() == null) {
            throw new BusinessException("Standalone PO invoice requires purchase request authority");
        }
        if (purchaseOrder.getSupplierId() == null) {
            throw new BusinessException("Standalone PO invoice requires purchase order supplier");
        }
        if (requestedSupplierId != null && !requestedSupplierId.equals(purchaseOrder.getSupplierId())) {
            throw new BusinessException("Invoice supplier must match standalone purchase order supplier");
        }
        String purchaseOrderCurrency = normalizedCurrency(purchaseOrder.getCurrencyCode());
        if (!isBlank(requestedCurrencyCode) && !purchaseOrderCurrency.equals(normalizedCurrency(requestedCurrencyCode))) {
            throw new BusinessException("Invoice currency must match standalone purchase order currency");
        }
        if (purchaseOrder.getTotalAmount() == null || purchaseOrder.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Standalone PO invoice requires a positive purchase order total");
        }
    }

    public String normalizedCurrency(String value) {
        return isBlank(value) ? "UZS" : value.trim().toUpperCase(Locale.ROOT);
    }

    private PurchaseOrderStatus status(PurchaseOrder purchaseOrder) {
        if (isBlank(purchaseOrder.getStatus())) {
            throw new BusinessException("Standalone PO invoice requires a sent or fulfilled purchase order");
        }
        try {
            return PurchaseOrderStatus.valueOf(purchaseOrder.getStatus().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Standalone PO invoice requires a sent or fulfilled purchase order");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
