package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Database-backed read adapter for an approver's worklist. The SLA is intentionally explicit:
 * a pending task is due 48 hours after it is created and enters the due-soon window 24 hours
 * before that deadline. This keeps due-state behavior deterministic until configurable flow SLA
 * fields are introduced.
 */
@Repository
public class ApprovalWorklistQuery {
    public static final int SLA_HOURS = 48;
    public static final int DUE_SOON_HOURS = 24;

    private final EntityManager entityManager;

    public ApprovalWorklistQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<ApprovalTask> search(ActorScope actor, Filter filter, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ApprovalTask> criteria = cb.createQuery(ApprovalTask.class);
        Root<ApprovalTask> root = criteria.from(ApprovalTask.class);
        Join<ApprovalTask, ApprovalInstance> instance = root.join("approvalInstance", JoinType.INNER);
        criteria.select(root).where(predicate(criteria, root, instance, cb, actor, safe(filter)));
        criteria.orderBy(cb.desc(root.get("createdAt")), cb.asc(root.get("id")));

        TypedQuery<ApprovalTask> query = entityManager.createQuery(criteria);
        if (pageable.isPaged()) {
            query.setFirstResult(Math.toIntExact(pageable.getOffset()));
            query.setMaxResults(pageable.getPageSize());
        }
        return new PageImpl<>(query.getResultList(), pageable, count(actor, filter));
    }

    public long count(ActorScope actor, Filter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> criteria = cb.createQuery(Long.class);
        Root<ApprovalTask> root = criteria.from(ApprovalTask.class);
        Join<ApprovalTask, ApprovalInstance> instance = root.join("approvalInstance", JoinType.INNER);
        criteria.select(cb.countDistinct(root)).where(predicate(criteria, root, instance, cb, actor, safe(filter)));
        return entityManager.createQuery(criteria).getSingleResult();
    }

    private Predicate predicate(
        CriteriaQuery<?> criteria,
        Root<ApprovalTask> root,
        Join<ApprovalTask, ApprovalInstance> instance,
        CriteriaBuilder cb,
        ActorScope actor,
        Filter filter
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("tenantId"), actor.tenantId()));
        predicates.add(cb.equal(root.get("companyId"), actor.companyId()));
        predicates.add(cb.isFalse(root.get("isDeleted")));
        predicates.add(actorPredicate(root, cb, actor));

        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            predicates.add(root.get("taskStatus").in(filter.statuses()));
            if (filter.statuses().contains(ApprovalTaskStatus.PENDING)) {
                predicates.add(actionablePendingPredicate(criteria, root, instance, cb));
            }
        }
        if (filter.taskId() != null) {
            predicates.add(cb.equal(root.get("id"), filter.taskId()));
        }
        if (filter.entityId() != null) {
            predicates.add(cb.equal(instance.get("entityId"), filter.entityId()));
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            String term = "%" + filter.search().trim().toLowerCase(Locale.ROOT) + "%";
            predicates.add(cb.or(
                cb.like(cb.lower(root.get("code")), term),
                cb.like(cb.lower(root.get("name")), term),
                cb.like(cb.lower(instance.get("name")), term),
                cb.like(cb.lower(instance.get("moduleKey")), term)
            ));
        }
        if (filter.moduleKey() != null && !filter.moduleKey().isBlank()) {
            predicates.add(cb.equal(
                cb.upper(instance.get("moduleKey")),
                ApprovalModuleRegistry.normalize(filter.moduleKey())
            ));
        }
        if (filter.createdFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.createdTo()));
        }
        if (filter.dueState() != null) {
            predicates.add(duePredicate(root, cb, filter.dueState(), filter.asOf()));
        }
        if (filter.ageBucket() != null) {
            predicates.add(agePredicate(root, cb, filter.ageBucket(), filter.asOf()));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }

    private Predicate actionablePendingPredicate(
        CriteriaQuery<?> criteria,
        Root<ApprovalTask> root,
        Join<ApprovalTask, ApprovalInstance> instance,
        CriteriaBuilder cb
    ) {
        var demandPlan = criteria.subquery(UUID.class);
        Root<DemandPlan> plan = demandPlan.from(DemandPlan.class);
        demandPlan.select(plan.get("id")).where(
            cb.equal(plan.get("id"), instance.get("entityId")),
            cb.equal(plan.get("tenantId"), instance.get("tenantId")),
            cb.equal(plan.get("companyId"), instance.get("companyId")),
            cb.isFalse(plan.get("isDeleted")),
            cb.equal(plan.get("status"), DemandPlanStatus.SUBMITTED.name())
        );
        return cb.or(
            cb.notEqual(cb.upper(instance.get("moduleKey")), DemandPlanApprovalAdapter.MODULE_KEY),
            cb.notEqual(root.get("taskStatus"), ApprovalTaskStatus.PENDING),
            cb.exists(demandPlan)
        );
    }

    private Predicate actorPredicate(Root<ApprovalTask> root, CriteriaBuilder cb, ActorScope actor) {
        List<Predicate> assignments = new ArrayList<>();
        if (actor.username() != null && !actor.username().isBlank()) {
            assignments.add(cb.equal(cb.lower(root.get("assigneeUsername")), actor.username().toLowerCase(Locale.ROOT)));
        }
        if (actor.userId() != null) {
            assignments.add(cb.equal(root.get("assigneeUserId"), actor.userId()));
        }
        Set<String> roles = searchableRoles(actor.roles());
        if (roles.contains("ADMIN") || roles.contains("MM_MANAGER")) {
            return cb.conjunction();
        }
        if (!roles.isEmpty()) {
            assignments.add(cb.upper(root.get("assigneeRole")).in(roles));
        }
        return assignments.isEmpty() ? cb.disjunction() : cb.or(assignments.toArray(Predicate[]::new));
    }

    private static Set<String> searchableRoles(Set<String> rawRoles) {
        if (rawRoles == null || rawRoles.isEmpty()) {
            return Set.of();
        }
        Set<String> roles = new HashSet<>();
        for (String rawRole : rawRoles) {
            if (rawRole == null || rawRole.isBlank()) {
                continue;
            }
            String original = rawRole.trim().toUpperCase(Locale.ROOT);
            String normalized = normalizeRole(rawRole);
            roles.add(original);
            roles.add(normalized);
            roles.add("ROLE_" + normalized);
        }
        return roles;
    }

    private Predicate duePredicate(
        Root<ApprovalTask> root,
        CriteriaBuilder cb,
        DueState state,
        LocalDateTime asOf
    ) {
        LocalDateTime overdueCutoff = asOf.minusHours(SLA_HOURS);
        LocalDateTime dueSoonCutoff = asOf.minusHours(SLA_HOURS - DUE_SOON_HOURS);
        Predicate pending = cb.equal(root.get("taskStatus"), ApprovalTaskStatus.PENDING);
        return switch (state) {
            case OVERDUE -> cb.and(pending, cb.lessThan(root.get("createdAt"), overdueCutoff));
            case DUE_SOON -> cb.and(
                pending,
                cb.greaterThanOrEqualTo(root.get("createdAt"), overdueCutoff),
                cb.lessThanOrEqualTo(root.get("createdAt"), dueSoonCutoff)
            );
            case ON_TRACK -> cb.and(pending, cb.greaterThan(root.get("createdAt"), dueSoonCutoff));
            case COMPLETED -> cb.notEqual(root.get("taskStatus"), ApprovalTaskStatus.PENDING);
        };
    }

    private Predicate agePredicate(
        Root<ApprovalTask> root,
        CriteriaBuilder cb,
        AgeBucket bucket,
        LocalDateTime asOf
    ) {
        return switch (bucket) {
            case LT_1_DAY -> cb.greaterThan(root.get("createdAt"), asOf.minusDays(1));
            case DAYS_1_TO_3 -> cb.and(
                cb.lessThanOrEqualTo(root.get("createdAt"), asOf.minusDays(1)),
                cb.greaterThan(root.get("createdAt"), asOf.minusDays(4))
            );
            case DAYS_4_TO_7 -> cb.and(
                cb.lessThanOrEqualTo(root.get("createdAt"), asOf.minusDays(4)),
                cb.greaterThanOrEqualTo(root.get("createdAt"), asOf.minusDays(7))
            );
            case GT_7_DAYS -> cb.lessThan(root.get("createdAt"), asOf.minusDays(7));
        };
    }

    private Filter safe(Filter filter) {
        if (filter == null) {
            return new Filter(
                null, null, null, null, Set.of(ApprovalTaskStatus.PENDING), null, null, null, null, LocalDateTime.now()
            );
        }
        return filter.asOf() == null
            ? new Filter(
                filter.taskId(), filter.entityId(), filter.search(), filter.moduleKey(), filter.statuses(),
                filter.createdFrom(), filter.createdTo(), filter.dueState(), filter.ageBucket(), LocalDateTime.now()
            )
            : filter;
    }

    private static String normalizeRole(String role) {
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    public record ActorScope(String tenantId, UUID companyId, String username, Set<String> roles, UUID userId) {
        public ActorScope {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        public ActorScope(String tenantId, UUID companyId, String username, Set<String> roles) {
            this(tenantId, companyId, username, roles, null);
        }
    }

    public record Filter(
        UUID taskId,
        UUID entityId,
        String search,
        String moduleKey,
        Set<ApprovalTaskStatus> statuses,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        DueState dueState,
        AgeBucket ageBucket,
        LocalDateTime asOf
    ) {
        public Filter {
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        }
    }

    public enum DueState {
        OVERDUE,
        DUE_SOON,
        ON_TRACK,
        COMPLETED
    }

    public enum AgeBucket {
        LT_1_DAY,
        DAYS_1_TO_3,
        DAYS_4_TO_7,
        GT_7_DAYS
    }
}
