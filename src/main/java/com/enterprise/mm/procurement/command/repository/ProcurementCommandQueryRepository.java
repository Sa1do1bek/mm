package com.enterprise.mm.procurement.command.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.procurement.command.entity.ProcurementWorkAssignment;
import com.enterprise.mm.procurement.command.enums.ProcurementCommandStage;
import com.enterprise.mm.procurement.command.enums.ProcurementBlockerSeverity;
import com.enterprise.mm.procurement.command.enums.ProcurementAllocationMode;
import com.enterprise.mm.procurement.command.enums.ProcurementQueueKey;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.security.AccessScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ProcurementCommandQueryRepository {

    private final EntityManager entityManager;

    public ProcurementCommandQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<PurchaseRequest> search(ProcurementCommandSearchRequest request, Pageable pageable) {
        return search(request, pageable, false);
    }

    public Page<PurchaseRequest> searchForCompany(ProcurementCommandSearchRequest request, Pageable pageable) {
        return search(request, pageable, true);
    }

    private Page<PurchaseRequest> search(
        ProcurementCommandSearchRequest request,
        Pageable pageable,
        boolean forceCompanyScope
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<PurchaseRequest> query = cb.createQuery(PurchaseRequest.class);
        Root<PurchaseRequest> root = query.from(PurchaseRequest.class);
        query.select(root).where(toPredicate(root, query, cb, safe(request), forceCompanyScope));
        query.orderBy(cb.asc(root.get("requiredDate")), cb.desc(root.get("createdAt")));
        TypedQuery<PurchaseRequest> typed = entityManager.createQuery(query);
        typed.setFirstResult((int) pageable.getOffset());
        typed.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(typed.getResultList(), pageable, count(request, forceCompanyScope));
    }

    public long count(ProcurementCommandSearchRequest request) {
        return count(request, false);
    }

    public long countForCompany(ProcurementCommandSearchRequest request) {
        return count(request, true);
    }

    private long count(ProcurementCommandSearchRequest request, boolean forceCompanyScope) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<PurchaseRequest> root = query.from(PurchaseRequest.class);
        query.select(cb.countDistinct(root)).where(
            toPredicate(root, query, cb, safe(request), forceCompanyScope));
        return entityManager.createQuery(query).getSingleResult();
    }

    private Predicate toPredicate(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        ProcurementCommandSearchRequest request,
        boolean forceCompanyScope
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
        predicates.add(cb.isFalse(root.get("isDeleted")));
        if (forceCompanyScope || AccessScope.companyScoped(true)) {
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
        }
        if (request.search() != null && !request.search().isBlank()) {
            String term = "%" + request.search().trim().toLowerCase() + "%";
            predicates.add(cb.or(
                cb.like(cb.lower(root.get("code")), term),
                cb.like(cb.lower(root.get("name")), term),
                cb.like(cb.lower(root.get("requestedBy")), term)));
        }
        if (request.departmentId() != null) {
            predicates.add(cb.equal(root.get("departmentId"), request.departmentId()));
        }
        if (request.ownerUserId() != null) {
            predicates.add(hasAssignment(root, query, cb, request.ownerUserId(), null));
        }
        if (request.requiredDateFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("requiredDate"), request.requiredDateFrom()));
        }
        if (request.requiredDateTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("requiredDate"), request.requiredDateTo()));
        }
        if (request.createdFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), request.createdFrom()));
        }
        if (request.createdTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), request.createdTo()));
        }
        if (request.materialId() != null) {
            predicates.add(hasItemMaterial(root, query, cb, request));
        }
        if (request.purchaseOrderId() != null) {
            predicates.add(hasPurchaseOrderId(root, query, cb, request));
        }
        if (request.supplierId() != null) {
            predicates.add(hasPurchaseOrder(root, query, cb, List.of(), request.supplierId()));
        }
        if (Boolean.TRUE.equals(request.overdue())) {
            predicates.add(cb.lessThan(root.get("requiredDate"), LocalDate.now()));
        }
        if (Boolean.TRUE.equals(request.integrationImpact())) {
            predicates.add(hasIntegrationRisk(root, query, cb));
        }
        if (request.queue() != null) {
            predicates.add(queuePredicate(request.queue(), root, query, cb));
        }
        if (request.stage() != null) {
            predicates.add(stagePredicate(request.stage(), root, query, cb));
        }
        if (request.severity() != null) {
            predicates.add(severityPredicate(request.severity(), root, query, cb));
        }
        if (request.allocationMode() != null && request.allocationMode() != ProcurementAllocationMode.AUTHORITATIVE) {
            predicates.add(cb.disjunction());
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }

    private Predicate stagePredicate(
        ProcurementCommandStage stage,
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb
    ) {
        return switch (stage) {
            case PR_PREPARATION -> statusIs(root, cb, "DRAFT");
            case PR_APPROVAL -> statusIs(root, cb, "APPROVAL_PENDING");
            case SOURCING_SETUP -> cb.and(
                statusIs(root, cb, "APPROVED"),
                cb.not(hasRfq(root, query, cb, List.of())),
                cb.not(hasPurchaseOrder(root, query, cb, List.of(), null)));
            case RFQ_IN_PROGRESS -> hasRfq(root, query, cb, List.of("DRAFT", "SENT", "OPEN"));
            case QUOTATION_EVALUATION -> hasRfq(root, query, cb, List.of("EVALUATED", "QUOTATIONS_RECEIVED"));
            case AWARD_DECISION -> hasRfq(root, query, cb, List.of("AWARDED"));
            case PO_PREPARATION -> hasPurchaseOrder(root, query, cb, List.of("DRAFT", "RETURNED"), null);
            case PO_APPROVAL -> hasPurchaseOrder(root, query, cb, List.of("APPROVAL_PENDING"), null);
            case PO_READY_TO_DISPATCH -> hasPurchaseOrder(root, query, cb, List.of("APPROVED"), null);
            case PO_DISPATCHED -> hasPurchaseOrder(root, query, cb, List.of(
                "SENT", "PARTIALLY_RECEIVED", "RECEIVED", "PARTIALLY_COMPLETED", "COMPLETED", "CLOSED"), null);
            case CANCELLED -> statusIs(root, cb, "CANCELLED");
            case BLOCKED -> cb.or(
                statusIs(root, cb, "REJECTED"),
                hasRfq(root, query, cb, List.of("BLOCKED")),
                hasPurchaseOrder(root, query, cb, List.of("BLOCKED", "APPROVAL_REJECTED", "REJECTED"), null));
        };
    }

    private Predicate severityPredicate(
        ProcurementBlockerSeverity severity,
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb
    ) {
        Predicate assigned = hasAssignment(root, query, cb, null, severity.name());
        return switch (severity) {
            case INFO -> assigned;
            case WARNING -> cb.or(assigned, cb.lessThan(root.get("requiredDate"), LocalDate.now()));
            case BLOCKING -> cb.or(
                assigned,
                statusIs(root, cb, "REJECTED"),
                hasRfq(root, query, cb, List.of("BLOCKED")),
                hasPurchaseOrder(root, query, cb, List.of("BLOCKED", "APPROVAL_REJECTED", "REJECTED"), null));
        };
    }

    private Predicate hasAssignment(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        java.util.UUID ownerUserId,
        String severity
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<ProcurementWorkAssignment> assignment = subquery.from(ProcurementWorkAssignment.class);
        List<Predicate> conditions = new ArrayList<>(List.of(
            cb.equal(assignment.get("purchaseRequestId"), root.get("id")),
            cb.equal(assignment.get("tenantId"), root.get("tenantId")),
            cb.equal(assignment.get("companyId"), root.get("companyId")),
            cb.isFalse(assignment.get("isDeleted"))));
        if (ownerUserId != null) conditions.add(cb.equal(assignment.get("ownerUserId"), ownerUserId));
        if (severity != null) conditions.add(cb.equal(cb.upper(assignment.get("severity")), severity));
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate queuePredicate(
        ProcurementQueueKey key,
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb
    ) {
        return switch (key) {
            case BUYER_PREPARATION -> statusIs(root, cb, "DRAFT");
            case AWAITING_APPROVAL -> statusIs(root, cb, "APPROVAL_PENDING");
            case APPROVED_UNSOURCED -> cb.and(
                statusIs(root, cb, "APPROVED"),
                cb.not(hasRfq(root, query, cb, List.of())),
                cb.not(hasPurchaseOrder(root, query, cb, List.of(), null)));
            case PARTIALLY_ALLOCATED -> statusIs(root, cb, "PARTIALLY_SENT_TO_TENDER");
            case RFQ_AWAITING_QUOTES -> hasRfq(root, query, cb, List.of("DRAFT", "SENT", "OPEN"));
            case TENDER_BLOCKED -> hasRfq(root, query, cb, List.of("BLOCKED"));
            case QUOTATION_DECISION -> hasRfq(root, query, cb, List.of("EVALUATED", "QUOTATIONS_RECEIVED"));
            case PO_PREPARATION -> hasPurchaseOrder(root, query, cb, List.of("DRAFT"), null);
            case PO_DISPATCH_BLOCKED -> hasPurchaseOrder(root, query, cb, List.of("BLOCKED", "APPROVAL_REJECTED"), null);
            case PO_READY_TO_DISPATCH -> hasPurchaseOrder(root, query, cb, List.of("APPROVED"), null);
            case OVERDUE_WORK -> cb.and(
                cb.lessThan(root.get("requiredDate"), LocalDate.now()),
                root.get("status").in(List.of(
                    "DRAFT", "APPROVAL_PENDING", "APPROVED", "RFQ_CREATED",
                    "PARTIALLY_SENT_TO_TENDER", "FULLY_SENT_TO_TENDER")));
            case INTEGRATION_RISK -> hasIntegrationRisk(root, query, cb);
        };
    }

    private Predicate statusIs(Root<PurchaseRequest> root, CriteriaBuilder cb, String status) {
        return cb.equal(root.get("status"), status);
    }

    private Predicate hasRfq(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        List<String> statuses
    ) {
        return cb.or(hasLegacyRfq(root, query, cb, statuses), hasSourceRfq(root, query, cb, statuses));
    }

    private Predicate hasLegacyRfq(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        List<String> statuses
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<RFQ> rfq = subquery.from(RFQ.class);
        List<Predicate> conditions = new ArrayList<>(List.of(
            cb.equal(rfq.get("purchaseRequestId"), root.get("id")),
            cb.equal(rfq.get("tenantId"), root.get("tenantId")),
            cb.equal(rfq.get("companyId"), root.get("companyId")),
            cb.isFalse(rfq.get("isDeleted"))));
        if (!statuses.isEmpty()) {
            conditions.add(rfq.get("status").in(statuses));
        }
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate hasSourceRfq(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        List<String> statuses
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<RFQPurchaseRequestSource> source = subquery.from(RFQPurchaseRequestSource.class);
        Root<RFQ> rfq = subquery.from(RFQ.class);
        List<Predicate> conditions = new ArrayList<>(List.of(
            cb.equal(source.get("purchaseRequestId"), root.get("id")),
            cb.equal(source.get("tenantId"), root.get("tenantId")),
            cb.equal(source.get("companyId"), root.get("companyId")),
            cb.equal(source.get("status"), "ACTIVE"),
            cb.isFalse(source.get("isDeleted")),
            cb.equal(rfq.get("id"), source.get("rfqId")),
            cb.equal(rfq.get("tenantId"), source.get("tenantId")),
            cb.equal(rfq.get("companyId"), source.get("companyId")),
            cb.isFalse(rfq.get("isDeleted"))));
        if (!statuses.isEmpty()) {
            conditions.add(rfq.get("status").in(statuses));
        }
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate hasPurchaseOrder(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        List<String> statuses,
        java.util.UUID supplierId
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<PurchaseOrder> po = subquery.from(PurchaseOrder.class);
        List<Predicate> conditions = new ArrayList<>(List.of(
            cb.equal(po.get("purchaseRequestId"), root.get("id")),
            cb.equal(po.get("tenantId"), root.get("tenantId")),
            cb.equal(po.get("companyId"), root.get("companyId")),
            cb.isFalse(po.get("isDeleted"))));
        if (!statuses.isEmpty()) {
            conditions.add(po.get("status").in(statuses));
        }
        if (supplierId != null) {
            conditions.add(cb.equal(po.get("supplierId"), supplierId));
        }
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate hasPurchaseOrderId(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        ProcurementCommandSearchRequest request
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<PurchaseOrder> po = subquery.from(PurchaseOrder.class);
        subquery.select(cb.literal(1)).where(
            cb.equal(po.get("id"), request.purchaseOrderId()),
            cb.equal(po.get("purchaseRequestId"), root.get("id")),
            cb.equal(po.get("tenantId"), root.get("tenantId")),
            cb.equal(po.get("companyId"), root.get("companyId")),
            cb.isFalse(po.get("isDeleted")));
        return cb.exists(subquery);
    }

    private Predicate hasItemMaterial(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        ProcurementCommandSearchRequest request
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<PurchaseRequestItem> item = subquery.from(PurchaseRequestItem.class);
        subquery.select(cb.literal(1)).where(
            cb.equal(item.get("purchaseRequestId"), root.get("id")),
            cb.equal(item.get("tenantId"), root.get("tenantId")),
            cb.equal(item.get("companyId"), root.get("companyId")),
            cb.equal(item.get("materialId"), request.materialId()),
            cb.isFalse(item.get("isDeleted")));
        return cb.exists(subquery);
    }

    private Predicate hasIntegrationRisk(
        Root<PurchaseRequest> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<IntegrationEvent> event = subquery.from(IntegrationEvent.class);
        subquery.select(cb.literal(1)).where(
            cb.equal(event.get("aggregateType"), "PURCHASE_REQUEST"),
            cb.equal(event.get("aggregateId"), root.get("id")),
            cb.equal(event.get("tenantId"), root.get("tenantId")),
            cb.equal(event.get("companyId"), root.get("companyId")),
            event.get("status").in(List.of("FAILED", "RETRYING")),
            cb.isFalse(event.get("isDeleted")));
        return cb.exists(subquery);
    }

    private ProcurementCommandSearchRequest safe(ProcurementCommandSearchRequest request) {
        return request == null ? ProcurementCommandSearchRequest.builder().build() : request;
    }
}
