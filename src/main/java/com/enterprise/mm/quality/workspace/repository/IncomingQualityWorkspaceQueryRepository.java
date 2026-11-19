package com.enterprise.mm.quality.workspace.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.enums.QualityInspectionType;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueFilter;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueKey;
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
public class IncomingQualityWorkspaceQueryRepository {
    private static final List<String> OPEN_STATUSES = List.of(
        QualityInspectionStatus.DRAFT.name(),
        QualityInspectionStatus.IN_QUARANTINE.name(),
        QualityInspectionStatus.SAMPLING.name(),
        QualityInspectionStatus.LAB_TESTING.name(),
        QualityInspectionStatus.FAILED.name(),
        QualityInspectionStatus.PASSED.name()
    );
    private static final List<String> COMPLETED_STATUSES = List.of(
        QualityInspectionStatus.RELEASED.name(),
        QualityInspectionStatus.REJECTED.name(),
        QualityInspectionStatus.CANCELLED.name()
    );

    private final EntityManager entityManager;

    public IncomingQualityWorkspaceQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<QualityInspection> search(QueueFilter filter, Pageable pageable) {
        QueueFilter normalized = safe(filter);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<QualityInspection> query = cb.createQuery(QualityInspection.class);
        Root<QualityInspection> root = query.from(QualityInspection.class);
        query.select(root).where(toPredicate(root, cb, normalized));
        applyOrder(query, root, cb, pageable);
        TypedQuery<QualityInspection> typedQuery = entityManager.createQuery(query);
        if (pageable.isPaged()) {
            typedQuery.setFirstResult(Math.toIntExact(pageable.getOffset()));
            typedQuery.setMaxResults(pageable.getPageSize());
        }
        return new PageImpl<>(typedQuery.getResultList(), pageable, count(normalized));
    }

    public long count(QueueFilter filter) {
        QueueFilter normalized = safe(filter);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<QualityInspection> root = query.from(QualityInspection.class);
        query.select(cb.countDistinct(root)).where(toPredicate(root, cb, normalized));
        return entityManager.createQuery(query).getSingleResult();
    }

    private void applyOrder(
        CriteriaQuery<QualityInspection> query,
        Root<QualityInspection> root,
        CriteriaBuilder cb,
        Pageable pageable
    ) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            query.orderBy(
                cb.asc(cb.selectCase().when(cb.isNull(root.get("dueDate")), 1).otherwise(0)),
                cb.asc(root.get("dueDate")),
                cb.desc(root.get("createdAt")),
                cb.asc(root.get("id"))
            );
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
        Root<QualityInspection> root,
        CriteriaBuilder cb,
        QueueFilter filter
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
        predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
        predicates.add(cb.isFalse(root.get("isDeleted")));
        predicates.add(root.get("inspectionType").in(QualityInspectionType.INCOMING.name(), QualityInspectionType.PRODUCTION.name()));
        predicates.add(queuePredicate(root, cb, filter.queue()));
        if (filter.warehouseId() != null) {
            predicates.add(cb.equal(root.get("warehouseId"), filter.warehouseId()));
        }
        if (filter.supplierId() != null) {
            predicates.add(cb.equal(root.get("supplierId"), filter.supplierId()));
        }
        if (filter.search() != null) {
            String term = "%" + filter.search() + "%";
            var supplier = root.join("supplier", JoinType.LEFT);
            var material = root.join("material", JoinType.LEFT);
            var purchaseOrder = root.join("purchaseOrder", JoinType.LEFT);
            predicates.add(cb.or(
                cb.like(cb.lower(root.get("inspectionNumber")), term),
                cb.like(cb.lower(root.get("code")), term),
                cb.like(cb.lower(root.get("name")), term),
                cb.like(cb.lower(supplier.get("code")), term),
                cb.like(cb.lower(supplier.get("name")), term),
                cb.like(cb.lower(material.get("code")), term),
                cb.like(cb.lower(material.get("name")), term),
                cb.like(cb.lower(purchaseOrder.get("code")), term),
                cb.like(cb.lower(purchaseOrder.get("name")), term)
            ));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }

    private Predicate queuePredicate(
        Root<QualityInspection> root,
        CriteriaBuilder cb,
        QueueKey queue
    ) {
        return switch (queue) {
            case OPEN -> root.get("status").in(OPEN_STATUSES);
            case AWAITING_QUARANTINE -> cb.equal(
                root.get("status"), QualityInspectionStatus.DRAFT.name());
            case AWAITING_SAMPLING -> cb.equal(
                root.get("status"), QualityInspectionStatus.IN_QUARANTINE.name());
            case LAB_PENDING -> root.get("status").in(List.of(
                QualityInspectionStatus.SAMPLING.name(),
                QualityInspectionStatus.LAB_TESTING.name()
            ));
            case DECISION_REQUIRED -> root.get("status").in(List.of(
                QualityInspectionStatus.FAILED.name(),
                QualityInspectionStatus.PASSED.name()
            ));
            case OVERDUE -> cb.and(
                root.get("status").in(OPEN_STATUSES),
                cb.lessThan(root.get("dueDate"), LocalDate.now())
            );
            case COMPLETED -> root.get("status").in(COMPLETED_STATUSES);
        };
    }

    private QueueFilter safe(QueueFilter filter) {
        return (filter == null ? new QueueFilter(null, null, null, null) : filter).normalized();
    }
}
