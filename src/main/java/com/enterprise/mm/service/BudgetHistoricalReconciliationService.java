package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.BudgetTransaction;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.payload.BudgetHistoricalReconciliationDtos.Classification;
import com.enterprise.mm.payload.BudgetHistoricalReconciliationDtos.Finding;
import com.enterprise.mm.payload.BudgetHistoricalReconciliationDtos.Report;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.BudgetTransactionRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only historical control. It deliberately identifies deterministic gaps
 * and ambiguous history without silently inventing ledger transactions.
 */
@Service
@RequiredArgsConstructor
public class BudgetHistoricalReconciliationService {
    private final BudgetItemRepository budgetItemRepository;
    private final BudgetTransactionRepository transactionRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ContractInvoiceRepository contractInvoiceRepository;

    @Transactional(readOnly = true)
    public Report reconcile() {
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        List<BudgetTransaction> transactions = transactionRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenant, company);
        Map<String, BudgetTransaction> byOperation = transactions.stream().collect(Collectors.toMap(
            transaction -> transaction.getSourceKey() + "\u0000" + transaction.getActionKey(),
            Function.identity(),
            (left, right) -> left
        ));
        List<Finding> findings = new ArrayList<>();
        List<BudgetItem> budgets = budgetItemRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenant, company);
        for (BudgetItem budget : budgets) {
            BigDecimal reserved = nvl(budget.getReservedAmount());
            BigDecimal committed = nvl(budget.getCommittedAmount());
            BigDecimal used = nvl(budget.getUsedAmount());
            BigDecimal ledgerReserved = transactions.stream().filter(t -> budget.getId().equals(t.getBudgetItemId())).map(t -> nvl(t.getReservedDelta())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ledgerCommitted = transactions.stream().filter(t -> budget.getId().equals(t.getBudgetItemId())).map(t -> nvl(t.getCommittedDelta())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ledgerUsed = transactions.stream().filter(t -> budget.getId().equals(t.getBudgetItemId())).map(t -> nvl(t.getUsedDelta())).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (reserved.compareTo(ledgerReserved) != 0 || committed.compareTo(ledgerCommitted) != 0 || used.compareTo(ledgerUsed) != 0) {
                findings.add(new Finding("BudgetItem", budget.getId(), budget.getStatus(), Classification.AGGREGATE_MISMATCH,
                    "BudgetItem aggregate columns differ from active ledger totals"));
            }
        }

        List<PurchaseRequest> requests = purchaseRequestRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenant, company);
        List<UUID> requestIds = requests.stream().map(PurchaseRequest::getId).toList();
        List<PurchaseRequestItem> requestItems = requestIds.isEmpty() ? List.of()
            : purchaseRequestItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(tenant, company, requestIds);
        for (PurchaseRequestItem item : requestItems) {
            PurchaseRequest request = requests.stream().filter(candidate -> candidate.getId().equals(item.getPurchaseRequestId())).findFirst().orElse(null);
            if (request == null || item.getBudgetItemId() == null || !isReservationRelevant(request.getStatus())) {
                continue;
            }
            String sourceKey = "PurchaseRequestItem:" + item.getId();
            boolean reservation = byOperation.containsKey(sourceKey + "\u0000RESERVE_APPROVED");
            boolean release = byOperation.containsKey(sourceKey + "\u0000RELEASE_CANCELLED");
            boolean terminalCancellation = "CANCELLED".equalsIgnoreCase(request.getStatus());
            if (!reservation || terminalCancellation && !release) {
                findings.add(new Finding("PurchaseRequestItem", item.getId(), request.getStatus(),
                    terminalCancellation ? Classification.REQUIRES_MANUAL_REVIEW : Classification.MISSING_LEDGER,
                    terminalCancellation && !reservation ? "Cancelled request has no deterministic reservation history" : "Approved request reservation ledger entry is missing"));
            } else {
                findings.add(new Finding("PurchaseRequestItem", item.getId(), request.getStatus(), Classification.MATCHED, "Reservation and cancellation state are represented"));
            }
        }

        List<PurchaseOrder> orders = purchaseOrderRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenant, company);
        List<UUID> orderIds = orders.stream().map(PurchaseOrder::getId).toList();
        List<PurchaseOrderItem> orderItems = orderIds.isEmpty() ? List.of()
            : purchaseOrderItemRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdInAndIsDeletedFalse(tenant, company, orderIds);
        for (PurchaseOrderItem item : orderItems) {
            PurchaseOrder order = orders.stream().filter(candidate -> candidate.getId().equals(item.getPurchaseOrderId())).findFirst().orElse(null);
            if (order == null || item.getBudgetItemId() == null || !isCommitmentRelevant(order.getStatus())) {
                continue;
            }
            String sourceKey = "PurchaseOrderItem:" + item.getId();
            boolean commitment = byOperation.containsKey(sourceKey + "\u0000COMMIT_SENT");
            boolean reversal = byOperation.containsKey(sourceKey + "\u0000REVERSE_CANCELLED");
            if (!commitment || "CANCELLED".equalsIgnoreCase(order.getStatus()) && !reversal) {
                findings.add(new Finding("PurchaseOrderItem", item.getId(), order.getStatus(),
                    "CANCELLED".equalsIgnoreCase(order.getStatus()) ? Classification.REQUIRES_MANUAL_REVIEW : Classification.MISSING_LEDGER,
                    "Sent/cancelled purchase order commitment history is incomplete"));
            } else {
                findings.add(new Finding("PurchaseOrderItem", item.getId(), order.getStatus(), Classification.MATCHED, "Commitment and cancellation state are represented"));
            }
        }

        for (ContractInvoice invoice : contractInvoiceRepository.findAll()) {
            if (!tenant.equals(invoice.getTenantId()) || !company.equals(invoice.getCompanyId()) || invoice.isDeleted() || invoice.getPurchaseOrderId() == null || !isInvoiceRelevant(invoice.getStatus())) {
                continue;
            }
            String prefix = "ContractInvoice:" + invoice.getId() + ":PurchaseOrderItem:";
            boolean consumption = transactions.stream().anyMatch(t -> "CONSUMPTION".equals(t.getTransactionType()) && t.getSourceKey().startsWith(prefix));
            boolean reversal = transactions.stream().anyMatch(t -> "CONSUMPTION_REVERSAL".equals(t.getTransactionType()) && t.getSourceKey().startsWith(prefix));
            if (!consumption || isCancelled(invoice.getStatus()) && !reversal) {
                findings.add(new Finding("ContractInvoice", invoice.getId(), invoice.getStatus(),
                    isCancelled(invoice.getStatus()) ? Classification.REQUIRES_MANUAL_REVIEW : Classification.MISSING_LEDGER,
                    "Issued/cancelled invoice consumption history is incomplete"));
            } else {
                findings.add(new Finding("ContractInvoice", invoice.getId(), invoice.getStatus(), Classification.MATCHED, "Consumption and cancellation state are represented"));
            }
        }
        return report(tenant, company, findings);
    }

    private Report report(String tenant, UUID company, List<Finding> findings) {
        return new Report(tenant, company,
            count(findings, Classification.MATCHED), count(findings, Classification.MISSING_LEDGER),
            count(findings, Classification.AGGREGATE_MISMATCH), count(findings, Classification.AMBIGUOUS_HISTORY),
            count(findings, Classification.REQUIRES_MANUAL_REVIEW), List.copyOf(findings));
    }

    private int count(List<Finding> findings, Classification classification) {
        return (int) findings.stream().filter(finding -> finding.classification() == classification).count();
    }
    private boolean isReservationRelevant(String status) { return "APPROVED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status); }
    private boolean isCommitmentRelevant(String status) { return "SENT".equalsIgnoreCase(status) || "RECEIVED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status); }
    private boolean isInvoiceRelevant(String status) { return "ISSUED".equalsIgnoreCase(status) || "PARTIALLY_PAID".equalsIgnoreCase(status) || "PAID".equalsIgnoreCase(status) || isCancelled(status); }
    private boolean isCancelled(String status) { return "CANCELLED".equalsIgnoreCase(status) || "VOID".equalsIgnoreCase(status); }
    private BigDecimal nvl(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
