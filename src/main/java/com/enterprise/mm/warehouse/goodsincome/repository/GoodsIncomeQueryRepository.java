package com.enterprise.mm.warehouse.goodsincome.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class GoodsIncomeQueryRepository {
    private static final List<String> RECEIVING_STATUSES = List.of(
        PurchaseOrderStatus.SENT.name(),
        PurchaseOrderStatus.PARTIALLY_RECEIVED.name()
    );

    private final EntityManager entityManager;

    public GoodsIncomeQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<PurchaseOrder> search(GoodsIncomeQueueFilter filter, Pageable pageable) {
        return search(filter, pageable, false);
    }

    public Page<PurchaseOrder> searchForCompany(GoodsIncomeQueueFilter filter, Pageable pageable) {
        return search(filter, pageable, true);
    }

    private Page<PurchaseOrder> search(
        GoodsIncomeQueueFilter filter,
        Pageable pageable,
        boolean forceCompanyScope
    ) {
        GoodsIncomeQueueFilter normalized = safe(filter);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<PurchaseOrder> query = cb.createQuery(PurchaseOrder.class);
        Root<PurchaseOrder> root = query.from(PurchaseOrder.class);
        query.select(root).distinct(true).where(toPredicate(query, root, cb, normalized, forceCompanyScope));
        applyOrder(query, root, cb, pageable);
        TypedQuery<PurchaseOrder> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(typedQuery.getResultList(), pageable, count(normalized, forceCompanyScope));
    }

    public long count(GoodsIncomeQueueFilter filter) {
        return count(filter, false);
    }

    public long countForCompany(GoodsIncomeQueueFilter filter) {
        return count(filter, true);
    }

    private long count(GoodsIncomeQueueFilter filter, boolean forceCompanyScope) {
        GoodsIncomeQueueFilter normalized = safe(filter);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<PurchaseOrder> root = query.from(PurchaseOrder.class);
        query.select(cb.countDistinct(root)).where(toPredicate(query, root, cb, normalized, forceCompanyScope));
        return entityManager.createQuery(query).getSingleResult();
    }

    private void applyOrder(
        CriteriaQuery<PurchaseOrder> query,
        Root<PurchaseOrder> root,
        CriteriaBuilder cb,
        Pageable pageable
    ) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            query.orderBy(cb.desc(root.get("createdAt")), cb.asc(root.get("id")));
            return;
        }
        List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            var path = root.get(order.getProperty());
            orders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
        }
        orders.add(cb.asc(root.get("id")));
        query.orderBy(orders);
    }

    private Predicate toPredicate(
        CriteriaQuery<?> query,
        Root<PurchaseOrder> root,
        CriteriaBuilder cb,
        GoodsIncomeQueueFilter filter,
        boolean forceCompanyScope
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
        predicates.add(cb.isFalse(root.get("isDeleted")));
        predicates.add(cb.equal(root.get("requestType"), RequestType.GOODS));
        var openTmbItem = query.subquery(PurchaseOrderItem.class);
        Root<PurchaseOrderItem> item = openTmbItem.from(PurchaseOrderItem.class);
        openTmbItem.select(item).where(
            cb.equal(item.get("tenantId"), TenantContext.currentTenant()),
            cb.isFalse(item.get("isDeleted")),
            cb.equal(item.get("purchaseOrderId"), root.get("id")),
            cb.equal(item.get("lineType"), ProcurementLineType.MATERIAL),
            cb.equal(item.get("materialTypeSnapshot"), MaterialType.TMB),
            cb.greaterThan(
                cb.diff(
                    cb.coalesce(item.get("quantity"), java.math.BigDecimal.ZERO),
                    cb.coalesce(item.get("receivedQuantity"), java.math.BigDecimal.ZERO)
                ),
                java.math.BigDecimal.ZERO
            )
        );
        predicates.add(cb.exists(openTmbItem));
        if (forceCompanyScope || AccessScope.companyScoped(true)) {
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
        }
        List<String> requestedStatuses = filter.status().isEmpty()
            ? RECEIVING_STATUSES
            : filter.status().stream().filter(RECEIVING_STATUSES::contains).toList();
        predicates.add(requestedStatuses.isEmpty()
            ? cb.disjunction()
            : root.get("status").in(requestedStatuses));
        if (filter.search() != null) {
            String term = "%" + filter.search() + "%";
            var supplier = root.<PurchaseOrder, Supplier>join("supplier", JoinType.LEFT);
            predicates.add(cb.or(
                cb.like(cb.lower(root.get("code")), term),
                cb.like(cb.lower(root.get("name")), term),
                cb.like(cb.lower(supplier.get("code")), term),
                cb.like(cb.lower(supplier.get("name")), term),
                cb.like(cb.lower(supplier.get("legalName")), term)
            ));
        }
        if (filter.supplierId() != null) {
            predicates.add(cb.equal(root.get("supplierId"), filter.supplierId()));
        }
        if (filter.contractId() != null) {
            predicates.add(cb.equal(root.get("contractId"), filter.contractId()));
        }
        if (Boolean.TRUE.equals(filter.overdue())) {
            predicates.add(cb.isNotNull(root.get("expectedDeliveryDate")));
            predicates.add(cb.lessThan(root.get("expectedDeliveryDate"), LocalDate.now()));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }

    private GoodsIncomeQueueFilter safe(GoodsIncomeQueueFilter filter) {
        return (filter == null ? new GoodsIncomeQueueFilter(null, List.of(), null, null) : filter).normalized();
    }
}
