package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalyticsSummaryService {
    private static final List<String> OPEN_PR_STATUSES = List.of(
        PurchaseRequestStatus.DRAFT.name(),
        PurchaseRequestStatus.APPROVAL_PENDING.name(),
        PurchaseRequestStatus.APPROVED.name(),
        PurchaseRequestStatus.RFQ_CREATED.name(),
        PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
        PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name()
    );
    private static final List<String> OPEN_PO_STATUSES = List.of(
        PurchaseOrderStatus.DRAFT.name(),
        PurchaseOrderStatus.APPROVAL_PENDING.name(),
        PurchaseOrderStatus.APPROVED.name(),
        PurchaseOrderStatus.SENT.name(),
        PurchaseOrderStatus.PARTIALLY_RECEIVED.name()
    );

    private final MaterialRepository materialRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final NonMovingStockRepository nonMovingStockRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        return summary(false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> companySummary() {
        return summary(true);
    }

    private Map<String, Object> summary(boolean scoped) {
        String tenantId = TenantContext.currentTenant();
        var companyId = com.enterprise.mm.common.CompanyContext.currentCompany();
        var stockRows = scoped ? stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId)
            : stockBalanceRepository.findByTenantIdAndIsDeletedFalse(tenantId);
        BigDecimal totalStockValue = stockRows.stream()
            .map(balance -> balance.getTotalValue() == null ? BigDecimal.ZERO : balance.getTotalValue())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("materials", (scoped ? materialRepository.countByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(tenantId, companyId, MaterialStatus.ACTIVE.name()) : materialRepository.countByTenantIdAndStatusAndIsDeletedFalse(tenantId, MaterialStatus.ACTIVE.name())));
        metrics.put("materialsAmount", totalStockValue);
        metrics.put("suppliers", (scoped ? supplierRepository.countByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId) : supplierRepository.countByTenantIdAndIsDeletedFalse(tenantId)));
        metrics.put("suppliersAmount", BigDecimal.ZERO);
        metrics.put("openPurchaseRequests", (scoped ? purchaseRequestRepository.countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(tenantId, companyId, OPEN_PR_STATUSES) : purchaseRequestRepository.countByTenantIdAndStatusInAndIsDeletedFalse(tenantId, OPEN_PR_STATUSES)));
        metrics.put("openPurchaseRequestsAmount", (scoped ? purchaseRequestRepository.sumTotalAmountByTenantAndCompanyAndStatusIn(tenantId, companyId, OPEN_PR_STATUSES) : purchaseRequestRepository.sumTotalAmountByTenantAndStatusIn(tenantId, OPEN_PR_STATUSES)));
        metrics.put("openPurchaseOrders", (scoped ? purchaseOrderRepository.countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(tenantId, companyId, OPEN_PO_STATUSES) : purchaseOrderRepository.countByTenantIdAndStatusInAndIsDeletedFalse(tenantId, OPEN_PO_STATUSES)));
        metrics.put("openPurchaseOrdersAmount", (scoped ? purchaseOrderRepository.sumTotalAmountByTenantAndCompanyAndStatusIn(tenantId, companyId, OPEN_PO_STATUSES) : purchaseOrderRepository.sumTotalAmountByTenantAndStatusIn(tenantId, OPEN_PO_STATUSES)));
        metrics.put("blockedSuppliers", (scoped ? supplierRepository.countByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(tenantId, companyId, "BLOCKED") : supplierRepository.countByTenantIdAndStatusAndIsDeletedFalse(tenantId, "BLOCKED")));
        metrics.put("blockedSuppliersAmount", BigDecimal.ZERO);
        metrics.put("nonMovingPositions", (scoped ? nonMovingStockRepository.countByTenantIdAndCompanyIdAndStatusNotAndIsDeletedFalse(tenantId, companyId, "CLOSED") : nonMovingStockRepository.countByTenantIdAndStatusNotAndIsDeletedFalse(tenantId, "CLOSED")));
        metrics.put("nonMovingStockValue", (scoped ? stockBalanceRepository.sumOpenNonMovingStockValueByTenantAndCompany(tenantId, companyId) : stockBalanceRepository.sumOpenNonMovingStockValueByTenant(tenantId)));
        List<String> overdueStatuses = List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        LocalDate overdueBefore = LocalDate.now();
        metrics.put("overdueDeliveries", (scoped ? purchaseOrderRepository.countByTenantIdAndCompanyIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(tenantId, companyId, overdueStatuses, overdueBefore)
            : purchaseOrderRepository.countByTenantIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(tenantId, overdueStatuses, overdueBefore)));
        metrics.put("overdueDeliveriesAmount", (scoped ? purchaseOrderRepository.sumTotalAmountByTenantAndCompanyAndStatusInAndExpectedDeliveryDateBefore(tenantId, companyId, overdueStatuses, overdueBefore)
            : purchaseOrderRepository.sumTotalAmountByTenantAndStatusInAndExpectedDeliveryDateBefore(tenantId, overdueStatuses, overdueBefore)));
        metrics.put("totalStockValue", totalStockValue);
        metrics.put("stockRows", stockRows.size());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("domain", "MM");
        payload.put("metrics", metrics);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metrics", metrics);
        response.put("payload", payload);
        response.put("items", List.of(payload));
        return response;
    }
}
