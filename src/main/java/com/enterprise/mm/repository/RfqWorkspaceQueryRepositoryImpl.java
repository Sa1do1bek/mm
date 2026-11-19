package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceFilter;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceRow;
import com.enterprise.mm.service.RoleDataScopeService.DataScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class RfqWorkspaceQueryRepositoryImpl implements RfqWorkspaceQueryRepository {
    private static final List<String> CURRENT_PROPOSAL_STATUSES = List.of(
        "ACTIVE", "SUBMITTED", "SELECTED", "REJECTED"
    );

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<RfqWorkspaceRow> search(
        String tenantId,
        UUID companyId,
        RfqWorkspaceFilter filter,
        DataScope dataScope,
        LocalDate today,
        Pageable pageable
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<RFQ> rfq = query.from(RFQ.class);
        Subquery<Long> sourceCount = sourceCount(query, cb, rfq);
        Subquery<Long> positionCount = positionCount(query, cb, rfq);
        Subquery<Long> invitationCount = invitationCount(query, cb, rfq);
        Subquery<Long> proposalCount = proposalCount(query, cb, rfq);
        Subquery<Long> failedDeliveryCount = failedDeliveryCount(query, cb, rfq);
        Expression<Long> effectiveSourceCount = cb.<Long>selectCase()
            .when(cb.greaterThan(sourceCount, 0L), sourceCount)
            .when(cb.isNotNull(rfq.get("purchaseRequestId")), 1L)
            .otherwise(0L);

        query.multiselect(
            rfq.get("id").alias("rfqId"),
            rfq.get("code").alias("code"),
            rfq.get("name").alias("title"),
            rfq.get("status").alias("status"),
            rfq.get("responsibleBuyer").alias("responsibleBuyer"),
            effectiveSourceCount.alias("sourceCount"),
            positionCount.alias("positionCount"),
            invitationCount.alias("invitationCount"),
            proposalCount.alias("proposalCount"),
            failedDeliveryCount.alias("failedDeliveryCount"),
            rfq.get("dueDate").alias("dueDate"),
            rfq.get("createdAt").alias("createdAt"),
            rfq.get("publishedAt").alias("publishedAt")
        );
        query.where(predicates(query, cb, rfq, tenantId, companyId, filter, dataScope, today)
            .toArray(Predicate[]::new));
        query.orderBy(
            cb.desc(rfq.get("createdAt")),
            cb.desc(rfq.get("id"))
        );

        var typed = entityManager.createQuery(query);
        if (pageable.isPaged()) {
            typed.setFirstResult(Math.toIntExact(pageable.getOffset()));
            typed.setMaxResults(pageable.getPageSize());
        }
        List<RfqWorkspaceRow> content = typed.getResultList().stream()
            .map(tuple -> row(tuple, today))
            .toList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<RFQ> countRoot = countQuery.from(RFQ.class);
        countQuery.select(cb.countDistinct(countRoot.get("id")));
        countQuery.where(predicates(
            countQuery, cb, countRoot, tenantId, companyId, filter, dataScope, today
        ).toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return pageable.isPaged()
            ? new PageImpl<>(content, pageable, total)
            : new PageImpl<>(content);
    }

    private List<Predicate> predicates(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        String tenantId,
        UUID companyId,
        RfqWorkspaceFilter filter,
        DataScope dataScope,
        LocalDate today
    ) {
        RfqWorkspaceFilter safe = filter == null
            ? new RfqWorkspaceFilter(
                List.of(), null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null
            )
            : filter;
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(rfq.get("tenantId"), tenantId));
        predicates.add(cb.equal(rfq.get("companyId"), companyId));
        predicates.add(cb.isFalse(rfq.get("isDeleted")));
        if (!safe.statuses().isEmpty()) {
            predicates.add(cb.upper(rfq.get("status")).in(upper(safe.statuses())));
        }
        if (safe.dueDateFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(rfq.get("dueDate"), safe.dueDateFrom()));
        }
        if (safe.dueDateTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(rfq.get("dueDate"), safe.dueDateTo()));
        }
        if (Boolean.TRUE.equals(safe.overdue())) {
            predicates.add(cb.lessThan(rfq.get("dueDate"), today));
            predicates.add(cb.not(cb.upper(rfq.get("status")).in(
                "CLOSED", "CANCELLED", "ARCHIVED", "AWARDED"
            )));
        }
        if (text(safe.responsibleBuyer()) != null) {
            predicates.add(cb.equal(
                cb.lower(rfq.get("responsibleBuyer")), text(safe.responsibleBuyer()).toLowerCase(Locale.ROOT)
            ));
        }
        if (safe.purchaseRequestId() != null) {
            predicates.add(cb.or(
                cb.equal(rfq.get("purchaseRequestId"), safe.purchaseRequestId()),
                sourceExists(query, cb, rfq, safe.purchaseRequestId())
            ));
        }
        if (safe.materialId() != null || safe.materialGroupId() != null) {
            predicates.add(materialExists(query, cb, rfq, safe.materialId(), safe.materialGroupId(), null));
        }
        if (safe.supplierId() != null) {
            predicates.add(invitationExists(query, cb, rfq, safe.supplierId(), null));
        }
        if (!safe.deliveryStatuses().isEmpty()) {
            predicates.add(deliveryExists(query, cb, rfq, upper(safe.deliveryStatuses())));
        }
        if (safe.createdAtFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(rfq.get("createdAt"), safe.createdAtFrom()));
        }
        if (safe.createdAtTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(rfq.get("createdAt"), safe.createdAtTo()));
        }
        if (safe.publishedAtFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(rfq.get("publishedAt"), safe.publishedAtFrom()));
        }
        if (safe.publishedAtTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(rfq.get("publishedAt"), safe.publishedAtTo()));
        }
        String search = text(safe.search());
        if (search != null) {
            String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
            predicates.add(cb.or(
                cb.like(cb.lower(rfq.get("code")), pattern),
                cb.like(cb.lower(rfq.get("name")), pattern),
                purchaseRequestSearchExists(query, cb, rfq, pattern),
                materialExists(query, cb, rfq, null, null, pattern),
                invitationExists(query, cb, rfq, null, pattern)
            ));
        }
        addResponseProgress(query, cb, rfq, safe.responseProgress(), predicates);
        addDataScope(query, cb, rfq, dataScope, predicates);
        return predicates;
    }

    private void addResponseProgress(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        String responseProgress,
        List<Predicate> predicates
    ) {
        String progress = text(responseProgress);
        if (progress == null) {
            return;
        }
        Subquery<Long> invitations = invitationCount(query, cb, rfq);
        Subquery<Long> proposals = proposalCount(query, cb, rfq);
        Subquery<Long> declined = declinedCount(query, cb, rfq);
        switch (progress.toUpperCase(Locale.ROOT)) {
            case "NONE" -> predicates.add(cb.and(cb.equal(proposals, 0L), cb.equal(declined, 0L)));
            case "PARTIAL" -> predicates.add(cb.and(
                cb.greaterThan(proposals, 0L), cb.lessThan(proposals, invitations)
            ));
            case "COMPLETE" -> predicates.add(cb.and(
                cb.greaterThan(invitations, 0L),
                cb.greaterThanOrEqualTo(cb.sum(proposals, declined), invitations)
            ));
            case "DECLINED" -> predicates.add(cb.greaterThan(declined, 0L));
            default -> predicates.add(cb.disjunction());
        }
    }

    private void addDataScope(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        DataScope dataScope,
        List<Predicate> predicates
    ) {
        if (dataScope == null || !dataScope.hasDocumentScope()) {
            return;
        }
        Set<String> principals = new LinkedHashSet<>(dataScope.ownPrincipals());
        principals.addAll(dataScope.teamPrincipals());
        Set<UUID> departments = new LinkedHashSet<>(dataScope.managedDepartmentIds());
        if (dataScope.departmentId() != null) {
            departments.add(dataScope.departmentId());
        }
        List<Predicate> access = new ArrayList<>();
        if (!principals.isEmpty()) {
            access.add(cb.lower(rfq.get("createdBy")).in(principals));
            access.add(cb.lower(rfq.get("responsibleBuyer")).in(principals));
        }
        if (!principals.isEmpty() || !departments.isEmpty()) {
            access.add(accessiblePurchaseRequestExists(query, cb, rfq, principals, departments));
        }
        predicates.add(access.isEmpty() ? cb.disjunction() : cb.or(access.toArray(Predicate[]::new)));
    }

    private Subquery<Long> sourceCount(CriteriaQuery<?> query, CriteriaBuilder cb, Root<RFQ> rfq) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<RFQPurchaseRequestSource> source = subquery.from(RFQPurchaseRequestSource.class);
        subquery.select(cb.countDistinct(source.get("purchaseRequestId")));
        subquery.where(
            cb.equal(source.get("rfqId"), rfq.get("id")),
            cb.isFalse(source.get("isDeleted"))
        );
        return subquery;
    }

    private Subquery<Long> positionCount(CriteriaQuery<?> query, CriteriaBuilder cb, Root<RFQ> rfq) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<RFQItem> item = subquery.from(RFQItem.class);
        subquery.select(cb.countDistinct(item.get("id")));
        subquery.where(cb.equal(item.get("rfqId"), rfq.get("id")), cb.isFalse(item.get("isDeleted")));
        return subquery;
    }

    private Subquery<Long> invitationCount(CriteriaQuery<?> query, CriteriaBuilder cb, Root<RFQ> rfq) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<RfqSupplierInvitation> invitation = subquery.from(RfqSupplierInvitation.class);
        subquery.select(cb.countDistinct(invitation.get("supplierId")));
        subquery.where(
            cb.equal(invitation.get("rfqId"), rfq.get("id")),
            cb.equal(invitation.get("status"), "INVITED"),
            cb.isFalse(invitation.get("isDeleted"))
        );
        return subquery;
    }

    private Subquery<Long> proposalCount(CriteriaQuery<?> query, CriteriaBuilder cb, Root<RFQ> rfq) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<SupplierQuotation> proposal = subquery.from(SupplierQuotation.class);
        subquery.select(cb.countDistinct(proposal.get("supplierId")));
        subquery.where(
            cb.equal(proposal.get("rfqId"), rfq.get("id")),
            proposal.get("status").in(CURRENT_PROPOSAL_STATUSES),
            cb.or(cb.isNull(proposal.get("threadId")), cb.isTrue(proposal.get("currentRevision"))),
            cb.isFalse(proposal.get("isDeleted"))
        );
        return subquery;
    }

    private Subquery<Long> failedDeliveryCount(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq
    ) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<RfqInvitationDelivery> delivery = subquery.from(RfqInvitationDelivery.class);
        Root<RfqSupplierInvitation> invitation = subquery.from(RfqSupplierInvitation.class);
        subquery.select(cb.countDistinct(delivery.get("id")));
        subquery.where(
            cb.equal(delivery.get("invitationId"), invitation.get("id")),
            cb.equal(invitation.get("rfqId"), rfq.get("id")),
            cb.equal(delivery.get("status"), "FAILED"),
            cb.isFalse(delivery.get("isDeleted")),
            cb.isFalse(invitation.get("isDeleted"))
        );
        return subquery;
    }

    private Subquery<Long> declinedCount(CriteriaQuery<?> query, CriteriaBuilder cb, Root<RFQ> rfq) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<RfqInvitationDelivery> delivery = subquery.from(RfqInvitationDelivery.class);
        Root<RfqSupplierInvitation> invitation = subquery.from(RfqSupplierInvitation.class);
        subquery.select(cb.countDistinct(delivery.get("supplierId")));
        subquery.where(
            cb.equal(delivery.get("invitationId"), invitation.get("id")),
            cb.equal(invitation.get("rfqId"), rfq.get("id")),
            cb.isNotNull(delivery.get("declinedAt")),
            cb.isFalse(delivery.get("isDeleted")),
            cb.isFalse(invitation.get("isDeleted"))
        );
        return subquery;
    }

    private Predicate sourceExists(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        UUID purchaseRequestId
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<RFQPurchaseRequestSource> source = subquery.from(RFQPurchaseRequestSource.class);
        subquery.select(cb.literal(1));
        subquery.where(
            cb.equal(source.get("rfqId"), rfq.get("id")),
            cb.equal(source.get("purchaseRequestId"), purchaseRequestId),
            cb.isFalse(source.get("isDeleted"))
        );
        return cb.exists(subquery);
    }

    private Predicate materialExists(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        UUID materialId,
        UUID materialGroupId,
        String searchPattern
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<RFQItem> item = subquery.from(RFQItem.class);
        Join<RFQItem, Material> material = item.join("material", JoinType.LEFT);
        List<Predicate> conditions = new ArrayList<>();
        conditions.add(cb.equal(item.get("rfqId"), rfq.get("id")));
        conditions.add(cb.isFalse(item.get("isDeleted")));
        if (materialId != null) {
            conditions.add(cb.equal(item.get("materialId"), materialId));
        }
        if (materialGroupId != null) {
            conditions.add(cb.equal(material.get("materialGroupId"), materialGroupId));
        }
        if (searchPattern != null) {
            conditions.add(cb.or(
                cb.like(cb.lower(item.get("code")), searchPattern),
                cb.like(cb.lower(item.get("name")), searchPattern),
                cb.like(cb.lower(material.get("code")), searchPattern),
                cb.like(cb.lower(material.get("name")), searchPattern)
            ));
        }
        subquery.select(cb.literal(1));
        subquery.where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate invitationExists(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        UUID supplierId,
        String searchPattern
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<RfqSupplierInvitation> invitation = subquery.from(RfqSupplierInvitation.class);
        Join<RfqSupplierInvitation, Supplier> supplier = invitation.join("supplier", JoinType.INNER);
        List<Predicate> conditions = new ArrayList<>();
        conditions.add(cb.equal(invitation.get("rfqId"), rfq.get("id")));
        conditions.add(cb.equal(invitation.get("status"), "INVITED"));
        conditions.add(cb.isFalse(invitation.get("isDeleted")));
        if (supplierId != null) {
            conditions.add(cb.equal(invitation.get("supplierId"), supplierId));
        }
        if (searchPattern != null) {
            conditions.add(cb.or(
                cb.like(cb.lower(supplier.get("code")), searchPattern),
                cb.like(cb.lower(supplier.get("name")), searchPattern),
                cb.like(cb.lower(supplier.get("legalName")), searchPattern)
            ));
        }
        subquery.select(cb.literal(1));
        subquery.where(conditions.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private Predicate deliveryExists(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        List<String> statuses
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<RfqInvitationDelivery> delivery = subquery.from(RfqInvitationDelivery.class);
        Root<RfqSupplierInvitation> invitation = subquery.from(RfqSupplierInvitation.class);
        subquery.select(cb.literal(1));
        subquery.where(
            cb.equal(delivery.get("invitationId"), invitation.get("id")),
            cb.equal(invitation.get("rfqId"), rfq.get("id")),
            cb.upper(delivery.get("status")).in(statuses),
            cb.isFalse(delivery.get("isDeleted")),
            cb.isFalse(invitation.get("isDeleted"))
        );
        return cb.exists(subquery);
    }

    private Predicate purchaseRequestSearchExists(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        String searchPattern
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<PurchaseRequest> request = subquery.from(PurchaseRequest.class);
        Root<RFQPurchaseRequestSource> source = subquery.from(RFQPurchaseRequestSource.class);
        subquery.select(cb.literal(1));
        subquery.where(
            cb.equal(request.get("id"), source.get("purchaseRequestId")),
            cb.equal(source.get("rfqId"), rfq.get("id")),
            cb.isFalse(source.get("isDeleted")),
            cb.or(
                cb.like(cb.lower(request.get("code")), searchPattern),
                cb.like(cb.lower(request.get("name")), searchPattern)
            )
        );
        Subquery<Integer> direct = query.subquery(Integer.class);
        Root<PurchaseRequest> directRequest = direct.from(PurchaseRequest.class);
        direct.select(cb.literal(1));
        direct.where(
            cb.equal(directRequest.get("id"), rfq.get("purchaseRequestId")),
            cb.or(
                cb.like(cb.lower(directRequest.get("code")), searchPattern),
                cb.like(cb.lower(directRequest.get("name")), searchPattern)
            )
        );
        return cb.or(cb.exists(subquery), cb.exists(direct));
    }

    private Predicate accessiblePurchaseRequestExists(
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<RFQ> rfq,
        Set<String> principals,
        Set<UUID> departments
    ) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<PurchaseRequest> request = subquery.from(PurchaseRequest.class);
        Root<RFQPurchaseRequestSource> source = subquery.from(RFQPurchaseRequestSource.class);
        List<Predicate> access = purchaseRequestAccess(cb, request, principals, departments);
        subquery.select(cb.literal(1));
        subquery.where(
            cb.equal(request.get("id"), source.get("purchaseRequestId")),
            cb.equal(source.get("rfqId"), rfq.get("id")),
            cb.isFalse(source.get("isDeleted")),
            cb.or(access.toArray(Predicate[]::new))
        );
        Subquery<Integer> direct = query.subquery(Integer.class);
        Root<PurchaseRequest> directRequest = direct.from(PurchaseRequest.class);
        direct.select(cb.literal(1));
        direct.where(
            cb.equal(directRequest.get("id"), rfq.get("purchaseRequestId")),
            cb.or(purchaseRequestAccess(cb, directRequest, principals, departments).toArray(Predicate[]::new))
        );
        return cb.or(cb.exists(subquery), cb.exists(direct));
    }

    private List<Predicate> purchaseRequestAccess(
        CriteriaBuilder cb,
        Root<PurchaseRequest> request,
        Set<String> principals,
        Set<UUID> departments
    ) {
        List<Predicate> access = new ArrayList<>();
        if (!principals.isEmpty()) {
            access.add(cb.lower(request.get("createdBy")).in(principals));
            access.add(cb.lower(request.get("requestedBy")).in(principals));
        }
        if (!departments.isEmpty()) {
            access.add(request.get("departmentId").in(departments));
        }
        return access;
    }

    private RfqWorkspaceRow row(Tuple tuple, LocalDate today) {
        LocalDate dueDate = tuple.get("dueDate", LocalDate.class);
        return new RfqWorkspaceRow(
            tuple.get("rfqId", UUID.class),
            tuple.get("code", String.class),
            tuple.get("title", String.class),
            tuple.get("title", String.class),
            tuple.get("status", String.class),
            tuple.get("responsibleBuyer", String.class),
            integer(tuple, "sourceCount"),
            integer(tuple, "positionCount"),
            integer(tuple, "invitationCount"),
            integer(tuple, "proposalCount"),
            integer(tuple, "failedDeliveryCount"),
            dueDate,
            dueDate != null && dueDate.isBefore(today)
                && !Set.of("CLOSED", "CANCELLED", "ARCHIVED", "AWARDED")
                    .contains(upper(tuple.get("status", String.class))),
            tuple.get("createdAt", LocalDateTime.class),
            tuple.get("publishedAt", LocalDateTime.class)
        );
    }

    private int integer(Tuple tuple, String alias) {
        Number value = tuple.get(alias, Number.class);
        return value == null ? 0 : value.intValue();
    }

    private List<String> upper(List<String> values) {
        return values.stream().map(this::upper).toList();
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
