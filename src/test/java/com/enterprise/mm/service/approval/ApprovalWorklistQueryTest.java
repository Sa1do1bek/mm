package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.service.approval.ApprovalWorklistQuery.ActorScope;
import com.enterprise.mm.service.approval.ApprovalWorklistQuery.AgeBucket;
import com.enterprise.mm.service.approval.ApprovalWorklistQuery.DueState;
import com.enterprise.mm.service.approval.ApprovalWorklistQuery.Filter;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class ApprovalWorklistQueryTest {
    private static final String TENANT = "tenant-approval-worklist";
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 15, 12, 0);

    @Autowired private ApprovalWorklistQuery query;
    @Autowired private EntityManager entityManager;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void filtersByAssignedUserOrRoleAndModuleBeforeApplyingDatabasePagination() {
        ApprovalTask direct = task("AT-NEEDLE-USER", "PURCHASE_REQUEST", AS_OF.minusHours(80), "approver", null, companyId, TENANT);
        ApprovalTask byRole = task("AT-NEEDLE-ROLE", "PURCHASE_REQUEST", AS_OF.minusHours(70), null, "WAREHOUSE_HEAD", companyId, TENANT);
        task("AT-NEEDLE-OTHER-USER", "PURCHASE_REQUEST", AS_OF.minusHours(60), "someone-else", null, companyId, TENANT);
        task("AT-NEEDLE-OTHER-MODULE", "INVENTORY_DOCUMENT", AS_OF.minusHours(50), "approver", null, companyId, TENANT);
        task("AT-NONMATCH", "PURCHASE_REQUEST", AS_OF.minusHours(40), "approver", null, companyId, TENANT);
        entityManager.flush();

        ActorScope actor = new ActorScope(TENANT, companyId, "approver", Set.of("WAREHOUSE_HEAD"));
        Filter filter = new Filter(
            null, null, "  needle  ", "purchase-request", Set.of(ApprovalTaskStatus.PENDING),
            AS_OF.minusDays(10), AS_OF, null, null, AS_OF
        );

        var firstPage = query.search(actor, filter, PageRequest.of(0, 1));
        var secondPage = query.search(actor, filter, PageRequest.of(1, 1));

        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(ApprovalTask::getId).containsExactly(byRole.getId());
        assertThat(secondPage.getContent()).extracting(ApprovalTask::getId).containsExactly(direct.getId());
    }

    @Test
    void dueAndAgeFiltersUseTheExplicitFortyEightHourSla() {
        ApprovalTask overdueFourDays = task("AT-OVERDUE-4D", "PURCHASE_REQUEST", AS_OF.minusDays(4), "approver", null, companyId, TENANT);
        task("AT-OVERDUE-8D", "PURCHASE_REQUEST", AS_OF.minusDays(8), "approver", null, companyId, TENANT);
        task("AT-DUE-SOON", "PURCHASE_REQUEST", AS_OF.minusHours(30), "approver", null, companyId, TENANT);
        task("AT-ON-TRACK", "PURCHASE_REQUEST", AS_OF.minusHours(12), "approver", null, companyId, TENANT);
        entityManager.flush();

        ActorScope actor = new ActorScope(TENANT, companyId, "approver", Set.of());
        Filter filter = new Filter(
            null, null, null, null, Set.of(ApprovalTaskStatus.PENDING), null, null,
            DueState.OVERDUE, AgeBucket.DAYS_4_TO_7, AS_OF
        );

        var result = query.search(actor, filter, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(ApprovalTask::getId).containsExactly(overdueFourDays.getId());
        assertThat(query.count(actor, filter)).isEqualTo(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findsStockMovementTaskWhenStoredRoleHasSpringRolePrefix() {
        ApprovalTask task = task("AT-STOCK-MOVEMENT", "STOCK_MOVEMENT", AS_OF.minusHours(1), null, "ROLE_WAREHOUSE_MANAGER", companyId, TENANT);
        entityManager.flush();

        ActorScope actor = new ActorScope(TENANT, companyId, "warehouse.user", Set.of("WAREHOUSE_MANAGER"));
        Filter filter = new Filter(
            null, null, null, "STOCK_MOVEMENT", Set.of(ApprovalTaskStatus.PENDING),
            null, null, null, null, AS_OF
        );

        var result = query.search(actor, filter, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(ApprovalTask::getId).containsExactly(task.getId());
        assertThat(query.count(actor, filter)).isEqualTo(1);
    }

    @Test
    void adminCanSeeStockMovementTaskEvenWhenNotAssignedDirectly() {
        ApprovalTask task = task("AT-STOCK-ADMIN", "STOCK_MOVEMENT", AS_OF.minusHours(1), null, "WAREHOUSE_MANAGER", companyId, TENANT);
        entityManager.flush();

        ActorScope actor = new ActorScope(TENANT, companyId, "admin", Set.of("ROLE_ADMIN"));
        Filter filter = new Filter(
            null, null, null, "STOCK_MOVEMENT", Set.of(ApprovalTaskStatus.PENDING),
            null, null, null, null, AS_OF
        );

        var result = query.search(actor, filter, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(ApprovalTask::getId).containsExactly(task.getId());
        assertThat(query.count(actor, filter)).isEqualTo(1);
    }

    @Test
    void filtersByApprovalInstanceEntityId() {
        UUID decisionId = UUID.randomUUID();
        ApprovalTask matching = task("AT-PMD-MATCH", "PROCUREMENT_METHOD_DECISION", decisionId, AS_OF.minusHours(1), "approver", null, companyId, TENANT);
        task("AT-PMD-OTHER", "PROCUREMENT_METHOD_DECISION", UUID.randomUUID(), AS_OF.minusHours(2), "approver", null, companyId, TENANT);
        entityManager.flush();

        ActorScope actor = new ActorScope(TENANT, companyId, "approver", Set.of());
        Filter filter = new Filter(
            null, decisionId, null, "PROCUREMENT_METHOD_DECISION", Set.of(ApprovalTaskStatus.PENDING),
            null, null, null, null, AS_OF
        );

        var result = query.search(actor, filter, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(ApprovalTask::getId).containsExactly(matching.getId());
        assertThat(query.count(actor, filter)).isEqualTo(1);
    }

    @Test
    void activeDemandPlanTaskHiddenFromPendingQueue() {
        DemandPlan activePlan = demandPlan("DP-ACTIVE", DemandPlanStatus.ACTIVE);
        DemandPlan submittedPlan = demandPlan("DP-SUBMITTED", DemandPlanStatus.SUBMITTED);
        ApprovalTask staleTask = task("AT-DP-ACTIVE", DemandPlanApprovalAdapter.MODULE_KEY, activePlan.getId(), AS_OF.minusHours(1), "approver", null, companyId, TENANT);
        ApprovalTask activeTask = task("AT-DP-SUBMITTED", DemandPlanApprovalAdapter.MODULE_KEY, submittedPlan.getId(), AS_OF.minusHours(2), "approver", null, companyId, TENANT);
        entityManager.flush();

        ActorScope actor = new ActorScope(TENANT, companyId, "approver", Set.of());
        Filter filter = new Filter(
            null, null, null, DemandPlanApprovalAdapter.MODULE_KEY, Set.of(ApprovalTaskStatus.PENDING),
            null, null, null, null, AS_OF
        );

        var result = query.search(actor, filter, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(ApprovalTask::getId).containsExactly(activeTask.getId());
        assertThat(result.getContent()).extracting(ApprovalTask::getId).doesNotContain(staleTask.getId());
        assertThat(query.count(actor, filter)).isEqualTo(1);
    }

    private ApprovalTask task(
        String code,
        String moduleKey,
        LocalDateTime createdAt,
        String username,
        String role,
        UUID taskCompanyId,
        String tenant
    ) {
        ApprovalFlow flow = base(new ApprovalFlow(), "AF-" + code, moduleKey + " flow", taskCompanyId, tenant);
        flow.setModuleKey(moduleKey);
        entityManager.persist(flow);

        ApprovalFlowStep step = base(new ApprovalFlowStep(), "AFS-" + code, "Review", taskCompanyId, tenant);
        step.setApprovalFlowId(flow.getId());
        step.setStepOrder(1);
        step.setAssigneeType(username == null ? ApprovalAssigneeType.ROLE : ApprovalAssigneeType.USER);
        step.setAssigneeUsername(username);
        step.setAssigneeRole(role);
        entityManager.persist(step);

        ApprovalInstance instance = base(new ApprovalInstance(), "AI-" + code, code + " source", taskCompanyId, tenant);
        instance.setApprovalFlowId(flow.getId());
        instance.setModuleKey(moduleKey);
        instance.setEntityId(UUID.randomUUID());
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(1);
        instance.setStartedAt(createdAt);
        entityManager.persist(instance);

        ApprovalTask task = base(new ApprovalTask(), code, code + " approval", taskCompanyId, tenant);
        task.setApprovalInstanceId(instance.getId());
        task.setApprovalFlowStepId(step.getId());
        task.setStepOrder(1);
        task.setAssigneeType(username == null ? ApprovalAssigneeType.ROLE : ApprovalAssigneeType.USER);
        task.setAssigneeUsername(username);
        task.setAssigneeRole(role);
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        entityManager.persist(task);
        entityManager.flush();
        entityManager.createNativeQuery("update approval_tasks set created_at = :createdAt where id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", task.getId())
            .executeUpdate();
        return task;
    }

    private ApprovalTask task(
        String code,
        String moduleKey,
        UUID entityId,
        LocalDateTime createdAt,
        String username,
        String role,
        UUID taskCompanyId,
        String tenant
    ) {
        ApprovalFlow flow = base(new ApprovalFlow(), "AF-" + code, moduleKey + " flow", taskCompanyId, tenant);
        flow.setModuleKey(moduleKey);
        entityManager.persist(flow);

        ApprovalFlowStep step = base(new ApprovalFlowStep(), "AFS-" + code, "Review", taskCompanyId, tenant);
        step.setApprovalFlowId(flow.getId());
        step.setStepOrder(1);
        step.setAssigneeType(username == null ? ApprovalAssigneeType.ROLE : ApprovalAssigneeType.USER);
        step.setAssigneeUsername(username);
        step.setAssigneeRole(role);
        entityManager.persist(step);

        ApprovalInstance instance = base(new ApprovalInstance(), "AI-" + code, code + " source", taskCompanyId, tenant);
        instance.setApprovalFlowId(flow.getId());
        instance.setModuleKey(moduleKey);
        instance.setEntityId(entityId);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(1);
        entityManager.persist(instance);

        ApprovalTask task = base(new ApprovalTask(), code, code + " approval", taskCompanyId, tenant);
        task.setApprovalInstanceId(instance.getId());
        task.setApprovalFlowStepId(step.getId());
        task.setStepOrder(1);
        task.setAssigneeType(username == null ? ApprovalAssigneeType.ROLE : ApprovalAssigneeType.USER);
        task.setAssigneeUsername(username);
        task.setAssigneeRole(role);
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        entityManager.persist(task);
        entityManager.flush();
        entityManager.createNativeQuery("update approval_tasks set created_at = :createdAt where id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", task.getId())
            .executeUpdate();
        return task;
    }

    private DemandPlan demandPlan(String code, DemandPlanStatus status) {
        DemandPlan plan = base(new DemandPlan(), code, code + " plan", companyId, TENANT);
        plan.setStatus(status.name());
        plan.setRequestType(RequestType.GOODS);
        plan.setPlanYear(2026);
        plan.setPeriodType("ANNUAL");
        plan.setPeriodStart(LocalDate.of(2026, 1, 1));
        plan.setPeriodEnd(LocalDate.of(2026, 12, 31));
        entityManager.persist(plan);
        return plan;
    }

    private <T extends BusinessEntity> T base(T entity, String code, String name, UUID entityCompanyId, String tenant) {
        entity.setTenantId(tenant);
        entity.setCompanyId(entityCompanyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus("ACTIVE");
        entity.setVersion(0L);
        return entity;
    }
}
