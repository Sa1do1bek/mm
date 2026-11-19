package com.enterprise.mm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementPayablesNotificationServiceTest {
    @Mock private NotificationService notificationService;
    @InjectMocks private ProcurementPayablesNotificationService service;

    @Test
    void notifyInvoiceRequiredTargetsFinanceRolesWithPurchaseOrderLink() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(UUID.randomUUID());
        po.setCode("PO-1");
        when(notificationService.createForRole(
            any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);

        service.notifyInvoiceRequired(po, new BigDecimal("100.0000"), "UZS");

        verify(notificationService).createForRole(
            eq(RoleName.FINANCE_CONTROLLER),
            eq("notification.payables.invoiceRequired.title"),
            eq("notification.payables.invoiceRequired.message"),
            any(Map.class),
            eq("Invoice required PO-1"),
            eq(NotificationEntityType.PURCHASE_ORDER.name()),
            eq(po.getId())
        );
        verify(notificationService).createForRole(
            eq(RoleName.ACCOUNTANT),
            eq("notification.payables.invoiceRequired.title"),
            eq("notification.payables.invoiceRequired.message"),
            any(Map.class),
            eq("Invoice required PO-1"),
            eq(NotificationEntityType.PURCHASE_ORDER.name()),
            eq(po.getId())
        );
    }

    @Test
    void notifyPaymentRequiredTargetsFinanceRolesWithInvoiceLink() {
        ContractInvoice invoice = new ContractInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setCode("INV-1");
        invoice.setAmount(new BigDecimal("50.0000"));
        invoice.setCurrencyCode("UZS");
        invoice.setPurchaseOrderId(UUID.randomUUID());
        when(notificationService.createForRole(
            any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(2);

        service.notifyPaymentRequired(invoice);

        verify(notificationService).createForRole(
            eq(RoleName.FINANCE_CONTROLLER),
            eq("notification.payables.paymentRequired.title"),
            eq("notification.payables.paymentRequired.message"),
            any(Map.class),
            eq("Payment required INV-1"),
            eq(NotificationEntityType.CONTRACT_INVOICE.name()),
            eq(invoice.getId())
        );
        verify(notificationService).createForRole(
            eq(RoleName.ACCOUNTANT),
            eq("notification.payables.paymentRequired.title"),
            eq("notification.payables.paymentRequired.message"),
            any(Map.class),
            eq("Payment required INV-1"),
            eq(NotificationEntityType.CONTRACT_INVOICE.name()),
            eq(invoice.getId())
        );
    }

    @Test
    void notifyInvoiceRequiredIgnoresNullPurchaseOrder() {
        service.notifyInvoiceRequired(null, BigDecimal.TEN, "UZS");
        verify(notificationService, org.mockito.Mockito.never()).createForRole(
            any(), any(), any(), any(), any(), any(), any()
        );
        verify(notificationService, org.mockito.Mockito.never()).createForRole(
            any(), any(), any(), any(), any()
        );
    }
}
