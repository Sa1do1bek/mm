package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ContractPaymentResponse;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PayablesReadinessResponse;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentMarkPaidRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentSubmitRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.ReadinessCheck;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementPayablesService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ContractPaymentRepository contractPaymentRepository;
    private final ContractPaymentService contractPaymentService;
    private final ProcurementInvoiceEvidenceService evidenceService;
    private final ProcurementPaymentReadinessService paymentReadinessService;

    @Transactional(readOnly = true)
    public PayablesReadinessResponse purchaseOrderReadiness(UUID purchaseOrderId) {
        PurchaseOrder purchaseOrder = loadPurchaseOrder(purchaseOrderId);
        ProcurementInvoiceEvidenceService.EvidenceSummary summary = evidenceService.summarize(purchaseOrder);
        boolean hasDraftInvoice = contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .anyMatch(invoice -> ContractInvoiceStatus.DRAFT.name().equals(normalizedStatus(invoice.getStatus())));
        List<ReadinessCheck> checks = new ArrayList<>();
        checks.add(new ReadinessCheck(
            "EVIDENCE",
            summary.evidenceAvailable() ? "INFO" : "BLOCKER",
            summary.evidenceAvailable(),
            "Receipt or completion evidence is required before invoicing"
        ));
        boolean invoiceHeadroomPassed = !summary.evidenceAvailable()
            || summary.remainingInvoiceableAmount().signum() > 0
            || summary.invoicedAmount().signum() > 0
            || hasDraftInvoice;
        checks.add(new ReadinessCheck(
            "INVOICE_HEADROOM",
            invoiceHeadroomPassed ? "INFO" : "BLOCKER",
            invoiceHeadroomPassed,
            "Remaining invoiceable amount must be greater than zero"
        ));
        checks.add(new ReadinessCheck(
            "OUTSTANDING_PAYABLE",
            summary.outstandingPayableAmount().signum() >= 0 ? "INFO" : "BLOCKER",
            true,
            "Outstanding payable amount is derived from issued invoices minus paid amount"
        ));
        return new PayablesReadinessResponse(
            purchaseOrder.getId(),
            summary.requestType().name(),
            summary.evidenceAvailable(),
            summary.evidenceAmount(),
            summary.invoicedAmount(),
            summary.remainingInvoiceableAmount(),
            summary.paidAmount(),
            summary.outstandingPayableAmount(),
            summary.primarySourceDocumentType() == null ? null : summary.primarySourceDocumentType().name(),
            summary.primarySourceDocumentId(),
            checks
        );
    }

    private String normalizedStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public ContractInvoice requirePayableInvoice(UUID purchaseOrderId, BigDecimal requiredAmount) {
        if (requiredAmount == null || requiredAmount.signum() <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }
        return contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .filter(invoice -> {
                String status = invoice.getStatus() == null ? "" : invoice.getStatus().trim().toUpperCase(Locale.ROOT);
                return ContractInvoiceStatus.ISSUED.name().equals(status)
                    || ContractInvoiceStatus.PARTIALLY_PAID.name().equals(status);
            })
            .filter(invoice -> paymentReadinessService.remainingInvoiceAmount(invoice).compareTo(requiredAmount) >= 0)
            .findFirst()
            .orElseThrow(() -> new BusinessException(
                "Issued invoice with sufficient remaining amount is required before payment"));
    }

    @Transactional
    public ContractPaymentResponse submitSourceDocumentPayment(
        UUID purchaseOrderId,
        UUID sourceDocumentId,
        ProcurementSourceDocumentType sourceDocumentType,
        BigDecimal amount,
        String paymentReference,
        String comment
    ) {
        if (purchaseOrderId == null) {
            throw new BusinessException("Purchase order is required before payment");
        }
        if (sourceDocumentId == null || sourceDocumentType == null) {
            throw new BusinessException("Source document is required before payment");
        }
        List<ContractPayment> existing = contractPaymentRepository
            .findByTenantIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), sourceDocumentType.name(), sourceDocumentId);
        for (ContractPayment payment : existing) {
            if (payment.getPurchaseOrderId() != null && !purchaseOrderId.equals(payment.getPurchaseOrderId())) {
                continue;
            }
            String status = normalized(payment.getStatus());
            if (ContractPaymentStatus.SENT_TO_TREASURY.name().equals(status)
                || ContractPaymentStatus.PAID.name().equals(status)) {
                return contractPaymentService.get(payment.getId());
            }
            if (ContractPaymentStatus.DRAFT.name().equals(status)) {
                return contractPaymentService.submitToTreasury(
                    payment.getId(),
                    new PaymentSubmitRequest(paymentReference, comment)
                );
            }
        }
        ContractInvoice invoice = requirePayableInvoice(purchaseOrderId, amount);
        ContractPaymentResponse created = contractPaymentService.createDraftForSourceDocument(
            invoice.getId(),
            sourceDocumentId,
            sourceDocumentType,
            amount,
            paymentReference
        );
        return contractPaymentService.submitToTreasury(
            created.getId(),
            new PaymentSubmitRequest(paymentReference, comment)
        );
    }

    @Transactional
    public ContractPaymentResponse markSourceDocumentPaymentPaid(
        UUID sourceDocumentId,
        ProcurementSourceDocumentType sourceDocumentType,
        String paymentReference,
        String comment
    ) {
        if (sourceDocumentId == null || sourceDocumentType == null) {
            throw new BusinessException("Source document is required before marking payment paid");
        }
        ContractPayment payment = contractPaymentRepository
            .findByTenantIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), sourceDocumentType.name(), sourceDocumentId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(
                "Contract payment linked to invoice is required before marking paid"));
        if (ContractPaymentStatus.PAID.name().equals(normalized(payment.getStatus()))) {
            return contractPaymentService.get(payment.getId());
        }
        return contractPaymentService.markPaid(
            payment.getId(),
            new PaymentMarkPaidRequest(paymentReference, comment)
        );
    }

    private PurchaseOrder loadPurchaseOrder(UUID purchaseOrderId) {
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
    }

    private String normalized(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }
}
