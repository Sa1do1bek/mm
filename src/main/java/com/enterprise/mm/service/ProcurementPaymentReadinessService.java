package com.enterprise.mm.service;

import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ProcurementPayablesDtos.ReadinessCheck;
import com.enterprise.mm.repository.ContractPaymentRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementPaymentReadinessService {
    private final ProcurementInvoiceEvidenceService evidenceService;
    private final ContractPaymentRepository contractPaymentRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.treasury.TreasuryTaskService treasuryTaskService;

    public record PaymentReadiness(
        boolean ready,
        List<ReadinessCheck> checks
    ) {
    }

    @Transactional(readOnly = true)
    public PaymentReadiness evaluateInvoiceIssue(PurchaseOrder purchaseOrder, BigDecimal invoiceAmount) {
        List<ReadinessCheck> checks = new ArrayList<>();
        ProcurementInvoiceEvidenceService.EvidenceSummary summary = evidenceService.summarize(purchaseOrder);
        checks.add(check("EVIDENCE", summary.evidenceAvailable(),
            "Receipt or completion evidence is required before invoice issuance"));
        if (invoiceAmount != null && invoiceAmount.signum() > 0) {
            checks.add(check("INVOICE_AMOUNT", invoiceAmount.compareTo(summary.remainingInvoiceableAmount()) <= 0,
                "Invoice amount exceeds remaining invoiceable amount"));
        }
        boolean ready = checks.stream().allMatch(ReadinessCheck::passed);
        return new PaymentReadiness(ready, checks);
    }

    @Transactional(readOnly = true)
    public BigDecimal remainingInvoiceAmount(ContractInvoice invoice) {
        return remainingInvoiceAmount(invoice, null);
    }

    @Transactional(readOnly = true)
    public BigDecimal remainingInvoiceAmount(ContractInvoice invoice, UUID excludePaymentId) {
        BigDecimal amount = invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount();
        BigDecimal paid = invoice.getPaidAmount() == null ? BigDecimal.ZERO : invoice.getPaidAmount();
        BigDecimal activeSubmitted = contractPaymentRepository
            .findByTenantIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(invoice.getTenantId(), invoice.getId())
            .stream()
            .filter(payment -> excludePaymentId == null || !Objects.equals(excludePaymentId, payment.getId()))
            .filter(payment -> !ContractPaymentStatus.PAID.name().equalsIgnoreCase(normalized(payment.getStatus())))
            .filter(payment -> !ContractPaymentStatus.CANCELLED.name().equalsIgnoreCase(normalized(payment.getStatus())))
            .map(payment -> {
                BigDecimal requested=payment.getAmount()==null?BigDecimal.ZERO:payment.getAmount();
                return ContractPaymentStatus.PARTIALLY_PAID.name().equalsIgnoreCase(normalized(payment.getStatus()))&&treasuryTaskService!=null
                    ?treasuryTaskService.reservedAmount(payment.getId(),requested):requested;
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = amount.subtract(paid).subtract(activeSubmitted);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    @Transactional(readOnly = true)
    public void requirePaymentSubmit(ContractInvoice invoice, BigDecimal paymentAmount) {
        requirePaymentSubmit(invoice, paymentAmount, null);
    }

    @Transactional(readOnly = true)
    public void requirePaymentSubmit(ContractInvoice invoice, BigDecimal paymentAmount, UUID excludePaymentId) {
        PaymentReadiness readiness = evaluatePaymentSubmit(invoice, paymentAmount, excludePaymentId);
        if (!readiness.ready()) {
            throw new BusinessException(firstFailure(readiness.checks()));
        }
    }

    @Transactional(readOnly = true)
    public PaymentReadiness evaluatePaymentSubmit(ContractInvoice invoice, BigDecimal paymentAmount) {
        return evaluatePaymentSubmit(invoice, paymentAmount, null);
    }

    @Transactional(readOnly = true)
    public PaymentReadiness evaluatePaymentSubmit(
        ContractInvoice invoice,
        BigDecimal paymentAmount,
        UUID excludePaymentId
    ) {
        List<ReadinessCheck> checks = new ArrayList<>();
        checks.add(check("INVOICE_STATUS", isPayableInvoiceStatus(invoice.getStatus()),
            "Invoice must be ISSUED or PARTIALLY_PAID before payment submission"));
        BigDecimal remaining = remainingInvoiceAmount(invoice, excludePaymentId);
        checks.add(check("PAYMENT_AMOUNT", paymentAmount != null && paymentAmount.signum() > 0 && paymentAmount.compareTo(remaining) <= 0,
            "Payment amount must be greater than zero and not exceed invoice remaining amount"));
        boolean ready = checks.stream().allMatch(ReadinessCheck::passed);
        return new PaymentReadiness(ready, checks);
    }

    private boolean isPayableInvoiceStatus(String status) {
        String normalized = normalized(status);
        return ContractInvoiceStatus.ISSUED.name().equals(normalized)
            || ContractInvoiceStatus.PARTIALLY_PAID.name().equals(normalized);
    }

    private ReadinessCheck check(String code, boolean passed, String message) {
        return new ReadinessCheck(code, passed ? "INFO" : "BLOCKER", passed, message);
    }

    private String firstFailure(List<ReadinessCheck> checks) {
        return checks.stream()
            .filter(check -> !check.passed())
            .map(ReadinessCheck::message)
            .findFirst()
            .orElse("Payment is not ready");
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
