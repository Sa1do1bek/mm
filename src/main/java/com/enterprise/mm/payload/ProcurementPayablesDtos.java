package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class ProcurementPayablesDtos {
    private ProcurementPayablesDtos() {
    }

    public record PayablesReadinessResponse(
        UUID purchaseOrderId,
        String requestType,
        boolean evidenceAvailable,
        BigDecimal evidenceAmount,
        BigDecimal invoicedAmount,
        BigDecimal remainingInvoiceableAmount,
        BigDecimal paidAmount,
        BigDecimal outstandingPayableAmount,
        String primarySourceDocumentType,
        UUID primarySourceDocumentId,
        List<ReadinessCheck> checks
    ) {
    }

    public record ReadinessCheck(
        String code,
        String level,
        boolean passed,
        String message
    ) {
    }

    public record PaymentSubmitRequest(
        String paymentReference,
        String comment
    ) {
    }

    public record PaymentMarkPaidRequest(
        String paymentReference,
        String comment
    ) {
    }
}
