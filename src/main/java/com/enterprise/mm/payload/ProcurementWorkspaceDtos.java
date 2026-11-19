package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ProcurementWorkspaceDtos {
    private ProcurementWorkspaceDtos() {
    }

    public record ProcurementFlowResponse(
        UUID purchaseOrderId,
        String currentStage,
        String nextAction,
        List<FlowStep> steps,
        FlowDocument demandPlan,
        FlowDocument purchaseRequest,
        FlowDocument purchaseOrder,
        List<FlowDocument> advanceShippingNotices,
        List<FlowDocument> goodsReceipts,
        List<FlowDocument> serviceWorkCompletions,
        List<FlowDocument> acceptanceActs,
        QualitySummary quality,
        ProcurementPayablesDtos.PayablesReadinessResponse payablesReadiness,
        ProcurementCloseoutDtos.Readiness closeoutReadiness,
        FlowDocument closeout,
        List<FlowDocument> invoices,
        List<FlowDocument> payments
    ) {
    }

    public record FlowStep(
        String key,
        String label,
        String status,
        boolean complete,
        boolean active,
        String action
    ) {
    }

    public record FlowDocument(
        UUID id,
        String type,
        String code,
        String name,
        String status,
        BigDecimal amount,
        String currencyCode,
        LocalDate documentDate,
        LocalDateTime eventAt
    ) {
    }

    public record QualitySummary(
        boolean required,
        boolean released,
        BigDecimal receivedQuantity,
        BigDecimal releasedQuantity,
        BigDecimal rejectedQuantity,
        List<FlowDocument> inspections
    ) {
    }

    public record DraftInvoiceFromPurchaseOrderRequest(
        UUID purchaseOrderId,
        BigDecimal amount,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate
    ) {
    }

    public record DraftPaymentFromInvoiceRequest(
        UUID contractInvoiceId,
        BigDecimal amount,
        String paymentReference,
        LocalDate paymentDate
    ) {
    }
}
