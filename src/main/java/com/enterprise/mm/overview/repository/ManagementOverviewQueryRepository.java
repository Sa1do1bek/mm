package com.enterprise.mm.overview.repository;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.overview.service.ManagementOrderCohort;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ManagementOverviewQueryRepository {
    private final EntityManager entityManager;

    public List<PurchaseOrder> purchaseOrders(
        String tenantId,
        UUID companyId,
        LocalDate from,
        LocalDate to,
        String currencyCode
    ) {
        return entityManager.createQuery("""
                select purchaseOrder
                from PurchaseOrder purchaseOrder
                where purchaseOrder.isDeleted = false
                  and purchaseOrder.tenantId = :tenantId
                  and purchaseOrder.companyId = :companyId
                  and purchaseOrder.orderDate between :from and :to
                  and upper(coalesce(purchaseOrder.currencyCode, '')) = :currencyCode
                  and purchaseOrder.status <> 'CANCELLED'
                order by purchaseOrder.orderDate asc, purchaseOrder.createdAt asc
                """, PurchaseOrder.class)
            .setParameter("tenantId", tenantId)
            .setParameter("companyId", companyId)
            .setParameter("from", from)
            .setParameter("to", to)
            .setParameter("currencyCode", currencyCode)
            .getResultList();
    }

    public List<PurchaseOrder> openPurchaseOrders(
        String tenantId,
        UUID companyId,
        String currencyCode
    ) {
        return entityManager.createQuery("""
                select purchaseOrder
                from PurchaseOrder purchaseOrder
                where purchaseOrder.isDeleted = false
                  and purchaseOrder.tenantId = :tenantId
                  and purchaseOrder.companyId = :companyId
                  and upper(coalesce(purchaseOrder.currencyCode, '')) = :currencyCode
                  and purchaseOrder.status in :statuses
                order by purchaseOrder.expectedDeliveryDate asc, purchaseOrder.createdAt asc
                """, PurchaseOrder.class)
            .setParameter("tenantId", tenantId)
            .setParameter("companyId", companyId)
            .setParameter("currencyCode", currencyCode)
            .setParameter("statuses", ManagementOrderCohort.openStatuses())
            .getResultList();
    }

    public Map<UUID, BigDecimal> remainingValueByOrder(
        String tenantId,
        UUID companyId,
        List<UUID> purchaseOrderIds
    ) {
        if (purchaseOrderIds == null || purchaseOrderIds.isEmpty()) {
            return Map.of();
        }
        return entityManager.createQuery("""
                select item.purchaseOrderId,
                  coalesce(sum(case
                    when coalesce(item.quantity, 0) > coalesce(item.receivedQuantity, 0)
                    then (coalesce(item.quantity, 0) - coalesce(item.receivedQuantity, 0))
                      * coalesce(item.unitPrice, 0)
                    else 0
                  end), 0)
                from PurchaseOrderItem item
                where item.isDeleted = false
                  and item.tenantId = :tenantId
                  and item.companyId = :companyId
                  and item.purchaseOrderId in :purchaseOrderIds
                group by item.purchaseOrderId
                """, Object[].class)
            .setParameter("tenantId", tenantId)
            .setParameter("companyId", companyId)
            .setParameter("purchaseOrderIds", purchaseOrderIds)
            .getResultList().stream()
            .collect(Collectors.toMap(
                row -> (UUID) row[0],
                row -> row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]
            ));
    }
}
