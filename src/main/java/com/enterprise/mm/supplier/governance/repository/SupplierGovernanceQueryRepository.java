package com.enterprise.mm.supplier.governance.repository;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierActivationRecord;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierKycCheck;
import com.enterprise.mm.entity.SupplierLicense;
import com.enterprise.mm.entity.SupplierUnblockRequest;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class SupplierGovernanceQueryRepository {
    private static final List<String> REVIEW_STATUSES = List.of(
        "SUBMITTED", "UNDER_REVIEW", "NEEDS_CLARIFICATION", "COMPLIANCE_CHECK", "COMMITTEE_REVIEW");
    private static final List<String> OPEN_PO_STATUSES = List.of("APPROVED", "SENT", "PARTIALLY_RECEIVED");

    private final EntityManager entityManager;

    public SupplierGovernanceQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<Supplier> search(SupplierGovernanceFilter filter, Pageable pageable) {
        return search(filter, pageable, false);
    }

    public Page<Supplier> searchForCompany(SupplierGovernanceFilter filter, Pageable pageable) {
        return search(filter, pageable, true);
    }

    private Page<Supplier> search(
        SupplierGovernanceFilter filter,
        Pageable pageable,
        boolean forceCompanyScope
    ) {
        SupplierGovernanceFilter normalized = filter.normalized();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Supplier> query = cb.createQuery(Supplier.class);
        Root<Supplier> root = query.from(Supplier.class);
        query.select(root).where(
            predicate(root, query, cb, normalized, forceCompanyScope));
        applyOrder(query, root, cb, pageable);
        TypedQuery<Supplier> typed = entityManager.createQuery(query);
        typed.setFirstResult((int) pageable.getOffset());
        typed.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(typed.getResultList(), pageable, count(normalized, forceCompanyScope));
    }

    public List<Supplier> findAll(SupplierGovernanceFilter filter) {
        return findAll(filter, false);
    }

    public List<Supplier> findAllForCompany(SupplierGovernanceFilter filter) {
        return findAll(filter, true);
    }

    private List<Supplier> findAll(SupplierGovernanceFilter filter, boolean forceCompanyScope) {
        SupplierGovernanceFilter normalized = filter.normalized();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Supplier> query = cb.createQuery(Supplier.class);
        Root<Supplier> root = query.from(Supplier.class);
        query.select(root).where(
            predicate(root, query, cb, normalized, forceCompanyScope));
        query.orderBy(cb.desc(root.get("createdAt")), cb.asc(root.get("id")));
        return entityManager.createQuery(query).getResultList();
    }

    private void applyOrder(
        CriteriaQuery<Supplier> query,
        Root<Supplier> root,
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

    public long count(SupplierGovernanceFilter filter) {
        return count(filter, false);
    }

    public long countForCompany(SupplierGovernanceFilter filter) {
        return count(filter, true);
    }

    private long count(SupplierGovernanceFilter filter, boolean forceCompanyScope) {
        SupplierGovernanceFilter normalized = filter.normalized();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Supplier> root = query.from(Supplier.class);
        query.select(cb.countDistinct(root)).where(
            predicate(root, query, cb, normalized, forceCompanyScope));
        return entityManager.createQuery(query).getSingleResult();
    }

    private Predicate predicate(
        Root<Supplier> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        SupplierGovernanceFilter filter,
        boolean forceCompanyScope
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
        predicates.add(cb.isFalse(root.get("isDeleted")));
        if (forceCompanyScope || AccessScope.companyScoped(true)) {
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
        }
        if (filter.search() != null) {
            String term = "%" + filter.search() + "%";
            predicates.add(cb.or(
                cb.like(cb.lower(root.get("code")), term),
                cb.like(cb.lower(root.get("name")), term),
                cb.like(cb.lower(root.get("legalName")), term),
                cb.like(cb.lower(root.get("shortName")), term)));
        }
        predicates.add(queuePredicate(root, query, cb, filter));
        if (filter.purchaseOrderId() != null) {
            predicates.add(hasPurchaseOrder(root, query, cb, List.of(), filter.purchaseOrderId()));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }

    private Predicate queuePredicate(
        Root<Supplier> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        SupplierGovernanceFilter filter
    ) {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(filter.horizonDays());
        return switch (filter.queue()) {
            case ALL -> cb.conjunction();
            case NEEDS_ACTION, READY_TO_ACTIVATE -> cb.conjunction();
            case ACTIVE -> cb.and(
                cb.equal(cb.upper(root.get("status")), "ACTIVE"),
                hasActivation(root, query, cb));
            case BLOCKED -> cb.or(
                cb.equal(cb.upper(root.get("status")), "BLOCKED"),
                cb.and(cb.isNotNull(root.get("blockedReason")),
                    cb.notEqual(cb.trim(root.get("blockedReason")), "")));
            case IN_ACCREDITATION -> hasApplication(root, query, cb,
                List.of("DRAFT", "SUBMITTED", "UNDER_REVIEW", "NEEDS_CLARIFICATION",
                    "COMPLIANCE_CHECK", "COMMITTEE_REVIEW"), null, null);
            case EXPIRING -> cb.or(
                hasApplication(root, query, cb, List.of("APPROVED", "CONDITIONALLY_APPROVED"), today, horizon),
                hasExpiringAccreditationDocument(root, query, cb, today, horizon),
                hasLicenseRisk(root, query, cb, today, horizon));
            case ACCREDITATION_REVIEW -> hasApplication(root, query, cb, REVIEW_STATUSES, null, null);
            case EXPIRING_EVIDENCE -> cb.or(
                hasApplication(root, query, cb, List.of("APPROVED", "CONDITIONALLY_APPROVED"), today, horizon),
                hasExpiringAccreditationDocument(root, query, cb, today, horizon));
            case KYC_COMPLIANCE -> hasKycRisk(root, query, cb, today, horizon);
            case LICENSE_RISK -> hasLicenseRisk(root, query, cb, today, horizon);
            case BLACKLIST_UNBLOCK -> cb.or(
                cb.equal(cb.upper(root.get("status")), "BLOCKED"),
                cb.and(cb.isNotNull(root.get("blockedReason")), cb.notEqual(cb.trim(root.get("blockedReason")), "")),
                hasPendingUnblock(root, query, cb));
            case OPEN_CAPA -> hasOpenCapa(root, query, cb);
            case RECEIVING_BLOCKED -> hasPurchaseOrder(root, query, cb, OPEN_PO_STATUSES, null);
        };
    }

    private Predicate hasApplication(
        Root<Supplier> supplier,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        List<String> statuses,
        LocalDate validFrom,
        LocalDate validTo
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<SupplierAccreditationApplication> application = subquery.from(SupplierAccreditationApplication.class);
        List<Predicate> conditions = baseSupplierRelation(supplier, application, cb);
        if (!statuses.isEmpty()) conditions.add(application.get("status").in(statuses));
        if (validFrom != null) conditions.add(cb.greaterThanOrEqualTo(application.get("validTo"), validFrom));
        if (validTo != null) conditions.add(cb.lessThanOrEqualTo(application.get("validTo"), validTo));
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate hasActivation(
        Root<Supplier> supplier,
        CriteriaQuery<?> query,
        CriteriaBuilder cb
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<SupplierActivationRecord> activation = subquery.from(SupplierActivationRecord.class);
        subquery.select(cb.literal(1)).where(
            cb.equal(activation.get("tenantId"), supplier.get("tenantId")),
            cb.equal(activation.get("companyId"), supplier.get("companyId")),
            cb.equal(activation.get("supplierId"), supplier.get("id")),
            cb.equal(activation.get("status"), "RECORDED"),
            cb.isFalse(activation.get("isDeleted")));
        return cb.exists(subquery);
    }

    private Predicate hasExpiringAccreditationDocument(
        Root<Supplier> supplier,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        LocalDate from,
        LocalDate to
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<SupplierAccreditationDocument> document = subquery.from(SupplierAccreditationDocument.class);
        Root<SupplierAccreditationApplication> application = subquery.from(SupplierAccreditationApplication.class);
        subquery.select(cb.literal(1)).where(
            cb.equal(document.get("tenantId"), supplier.get("tenantId")),
            cb.equal(document.get("companyId"), supplier.get("companyId")),
            cb.isFalse(document.get("isDeleted")),
            cb.equal(application.get("id"), document.get("applicationId")),
            cb.equal(application.get("supplierId"), supplier.get("id")),
            cb.equal(application.get("tenantId"), supplier.get("tenantId")),
            cb.equal(application.get("companyId"), supplier.get("companyId")),
            cb.isFalse(application.get("isDeleted")),
            cb.greaterThanOrEqualTo(document.get("validTo"), from),
            cb.lessThanOrEqualTo(document.get("validTo"), to));
        return cb.exists(subquery);
    }

    private Predicate hasLicenseRisk(
        Root<Supplier> supplier,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        LocalDate today,
        LocalDate horizon
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<SupplierLicense> license = subquery.from(SupplierLicense.class);
        List<Predicate> conditions = baseSupplierRelation(supplier, license, cb);
        conditions.add(cb.or(
            cb.isTrue(license.get("renewalRequired")),
            cb.isNotNull(license.get("revokedAt")),
            cb.lessThanOrEqualTo(license.get("validTo"), horizon),
            cb.lessThan(license.get("validTo"), today)));
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate hasKycRisk(
        Root<Supplier> supplier,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        LocalDate today,
        LocalDate horizon
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<SupplierKycCheck> check = subquery.from(SupplierKycCheck.class);
        List<Predicate> conditions = baseSupplierRelation(supplier, check, cb);
        conditions.add(cb.or(
            cb.isTrue(check.get("remediationRequired")),
            cb.upper(check.get("riskLevel")).in(List.of("HIGH", "CRITICAL")),
            cb.upper(check.get("status")).in(List.of("FAILED", "BLOCKED")),
            cb.lessThan(check.get("validUntil"), today),
            cb.lessThanOrEqualTo(check.get("nextReviewDate"), horizon)));
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate hasOpenCapa(Root<Supplier> supplier, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<SupplierCorrectiveAction> action = subquery.from(SupplierCorrectiveAction.class);
        List<Predicate> conditions = baseSupplierRelation(supplier, action, cb);
        conditions.add(cb.and(
            cb.isNull(action.get("closedAt")),
            cb.or(cb.isNull(action.get("status")), cb.not(action.get("status").in(List.of("CLOSED", "COMPLETED", "CANCELLED"))))));
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate hasPendingUnblock(Root<Supplier> supplier, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<SupplierUnblockRequest> request = subquery.from(SupplierUnblockRequest.class);
        List<Predicate> conditions = baseSupplierRelation(supplier, request, cb);
        conditions.add(request.get("status").in(List.of("PENDING", "UNDER_REVIEW")));
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate hasPurchaseOrder(
        Root<Supplier> supplier,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        List<String> statuses,
        java.util.UUID purchaseOrderId
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<PurchaseOrder> po = subquery.from(PurchaseOrder.class);
        List<Predicate> conditions = baseSupplierRelation(supplier, po, cb);
        conditions.add(cb.equal(po.get("requestType"), RequestType.GOODS));
        if (!statuses.isEmpty()) conditions.add(po.get("status").in(statuses));
        if (purchaseOrderId != null) conditions.add(cb.equal(po.get("id"), purchaseOrderId));
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private List<Predicate> baseSupplierRelation(
        Root<Supplier> supplier,
        Root<?> related,
        CriteriaBuilder cb
    ) {
        return new ArrayList<>(List.of(
            cb.equal(related.get("supplierId"), supplier.get("id")),
            cb.equal(related.get("tenantId"), supplier.get("tenantId")),
            cb.equal(related.get("companyId"), supplier.get("companyId")),
            cb.isFalse(related.get("isDeleted"))));
    }
}
