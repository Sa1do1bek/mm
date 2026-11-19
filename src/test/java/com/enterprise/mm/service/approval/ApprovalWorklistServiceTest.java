package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalWorklistFilter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ApprovalWorklistServiceTest {
    private RecordingWorklistQuery query;
    private ApprovalEngineService service;

    @BeforeEach
    void setUp() {
        query = new RecordingWorklistQuery();
        service = new ApprovalEngineService(
            null, null, null, null, null, null, query, new ApprovalWorklistDetailAssembler()
        );
        TenantContext.setTenant("tenant-worklist-service");
        CompanyContext.setCompany(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("approver", "n/a", List.of())
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void summaryCountsPendingOverdueDueSoonAndEveryAgeBucketFromTheSameScope() {
        query.defaultCount = 10;
        query.overdueCount = 2;
        query.dueSoonCount = 3;
        query.ageCounts = List.of(4L, 3L, 2L, 1L);

        var summary = service.myTaskSummary(new ApprovalWorklistFilter(
            null, null, "bearing", "PURCHASE_REQUEST", null, null, null, null, null
        ));

        assertThat(summary.pendingCount()).isEqualTo(10);
        assertThat(summary.overdueCount()).isEqualTo(2);
        assertThat(summary.dueSoonCount()).isEqualTo(3);
        assertThat(summary.ageBuckets()).extracting(bucket -> bucket.count())
            .containsExactly(4L, 3L, 2L, 1L);
        assertThat(summary.slaHours()).isEqualTo(48);
        assertThat(query.seen).allSatisfy(filter -> {
            assertThat(filter.search()).isEqualTo("bearing");
            assertThat(filter.moduleKey()).isEqualTo("PURCHASE_REQUEST");
        });
    }

    @Test
    void myTasksPassesEntityIdToTheWorklistQuery() {
        UUID decisionId = UUID.randomUUID();

        service.myTasks(Pageable.ofSize(20), new ApprovalWorklistFilter(
            null, decisionId, null, "PROCUREMENT_METHOD_DECISION", "PENDING", null, null, null, null
        ));

        assertThat(query.seen).singleElement().satisfies(filter -> {
            assertThat(filter.entityId()).isEqualTo(decisionId);
            assertThat(filter.moduleKey()).isEqualTo("PROCUREMENT_METHOD_DECISION");
            assertThat(filter.statuses()).containsExactly(ApprovalTaskStatus.PENDING);
        });
    }

    @Test
    void invalidCreatedRangeIsRejectedBeforeAnyRepositoryQuery() {
        LocalDateTime from = LocalDateTime.of(2026, 7, 15, 12, 0);
        LocalDateTime to = from.minusDays(1);

        assertThatThrownBy(() -> service.myTasks(
            Pageable.ofSize(20),
            new ApprovalWorklistFilter(null, null, null, null, "PENDING", from, to, null, null)
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("createdFrom");
        assertThat(query.seen).isEmpty();
    }

    @Test
    void myTasksReturnsAssigneeFullnameInsteadOfUsername() {
        UUID instanceId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID assigneeUserId = UUID.randomUUID();
        ApprovalTask task = new ApprovalTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(TenantContext.currentTenant());
        task.setCompanyId(CompanyContext.currentCompany());
        task.setName("Approval task");
        task.setApprovalInstanceId(instanceId);
        task.setStepOrder(1);
        task.setAssigneeType(ApprovalAssigneeType.USER);
        task.setAssigneeUserId(assigneeUserId);
        task.setAssigneeUsername("approval.user");
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setCreatedAt(LocalDateTime.of(2026, 7, 15, 12, 0));
        query.searchResults = List.of(task);

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setModuleKey("PURCHASE_REQUEST");
        instance.setEntityId(entityId);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);

        User assignee = new User();
        assignee.setId(assigneeUserId);
        assignee.setUsername("approval.user");
        assignee.setFullName("Alice Smith");

        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalModuleRegistry moduleRegistry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(instanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));
        when(taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instanceId
        )).thenReturn(List.of(task));
        when(moduleRegistry.adapter("PURCHASE_REQUEST")).thenReturn(adapter);
        when(adapter.displayName(entityId)).thenReturn("PR-001");
        when(userRepository.findByIdAndIsDeletedFalse(assigneeUserId)).thenReturn(Optional.of(assignee));
        service = new ApprovalEngineService(
            null, stepRepository, instanceRepository, taskRepository, null,
            moduleRegistry, query, new ApprovalWorklistDetailAssembler(), null, userRepository
        );

        var result = service.myTasks(Pageable.ofSize(20), new ApprovalWorklistFilter(
            null, null, null, null, "PENDING", null, null, null, null
        ));

        assertThat(result.getContent()).singleElement()
            .extracting(response -> response.assigneeFullname())
            .isEqualTo("Alice Smith");
    }

    private static final class RecordingWorklistQuery extends ApprovalWorklistQuery {
        private final List<Filter> seen = new ArrayList<>();
        private List<ApprovalTask> searchResults = List.of();
        private long defaultCount;
        private long overdueCount;
        private long dueSoonCount;
        private List<Long> ageCounts = List.of(0L, 0L, 0L, 0L);

        private RecordingWorklistQuery() {
            super(null);
        }

        @Override
        public Page<ApprovalTask> search(ActorScope actor, Filter filter, Pageable pageable) {
            seen.add(filter);
            return new PageImpl<>(searchResults, pageable, searchResults.size());
        }

        @Override
        public long count(ActorScope actor, Filter filter) {
            seen.add(filter);
            if (filter.dueState() == DueState.OVERDUE) {
                return overdueCount;
            }
            if (filter.dueState() == DueState.DUE_SOON) {
                return dueSoonCount;
            }
            if (filter.ageBucket() != null) {
                return ageCounts.get(filter.ageBucket().ordinal());
            }
            return defaultCount;
        }
    }
}
