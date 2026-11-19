package com.enterprise.mm.service;

import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app (+ push) signals for the payables handoff gaps:
 * PO evidence ready → create invoice; invoice issued → create payment.
 * Integration events alone are not visible on the user dashboard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcurementPayablesNotificationService {
    private final NotificationService notificationService;

    @Transactional
    public void notifyInvoiceRequired(PurchaseOrder purchaseOrder, BigDecimal amount, String currency) {
        if (purchaseOrder == null || purchaseOrder.getId() == null) {
            return;
        }
        Map<String, Object> args = baseArgs(purchaseOrder.getCode(), amount, currency);
        args.put("purchaseOrderId", purchaseOrder.getId());
        int created = notifyRole(
            RoleName.FINANCE_CONTROLLER,
            "notification.payables.invoiceRequired.title",
            "notification.payables.invoiceRequired.message",
            args,
            "Invoice required " + safe(purchaseOrder.getCode()),
            NotificationEntityType.PURCHASE_ORDER.name(),
            purchaseOrder.getId()
        );
        created += notifyRole(
            RoleName.ACCOUNTANT,
            "notification.payables.invoiceRequired.title",
            "notification.payables.invoiceRequired.message",
            args,
            "Invoice required " + safe(purchaseOrder.getCode()),
            NotificationEntityType.PURCHASE_ORDER.name(),
            purchaseOrder.getId()
        );
        log.info(
            "Invoice-required notifications created={} for PO {}",
            created,
            purchaseOrder.getCode()
        );
    }

    @Transactional
    public void notifyPaymentRequired(ContractInvoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            return;
        }
        Map<String, Object> args = baseArgs(invoice.getCode(), invoice.getAmount(), invoice.getCurrencyCode());
        args.put("invoiceId", invoice.getId());
        if (invoice.getPurchaseOrderId() != null) {
            args.put("purchaseOrderId", invoice.getPurchaseOrderId());
        }
        int created = notifyRole(
            RoleName.FINANCE_CONTROLLER,
            "notification.payables.paymentRequired.title",
            "notification.payables.paymentRequired.message",
            args,
            "Payment required " + safe(invoice.getCode()),
            NotificationEntityType.CONTRACT_INVOICE.name(),
            invoice.getId()
        );
        created += notifyRole(
            RoleName.ACCOUNTANT,
            "notification.payables.paymentRequired.title",
            "notification.payables.paymentRequired.message",
            args,
            "Payment required " + safe(invoice.getCode()),
            NotificationEntityType.CONTRACT_INVOICE.name(),
            invoice.getId()
        );
        log.info(
            "Payment-required notifications created={} for invoice {}",
            created,
            invoice.getCode()
        );
    }

    private int notifyRole(
        RoleName role,
        String titleKey,
        String messageKey,
        Map<String, Object> args,
        String name,
        String entityType,
        UUID entityId
    ) {
        try {
            return notificationService.createForRole(
                role, titleKey, messageKey, args, name, entityType, entityId
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Failed to create payables notification for role {}: {}",
                role,
                ex.getMessage()
            );
            return 0;
        }
    }

    private Map<String, Object> baseArgs(String reference, BigDecimal amount, String currency) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("reference", safe(reference));
        args.put("amount", amount == null ? BigDecimal.ZERO : amount);
        args.put("currency", currency == null || currency.isBlank() ? "" : currency.trim());
        return args;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
