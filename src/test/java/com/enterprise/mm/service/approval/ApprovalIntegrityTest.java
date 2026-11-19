package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowParallelAssignee;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.payload.NotificationCreateRequest;
import com.enterprise.mm.repository.ApprovalFlowParallelAssigneeRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import com.enterprise.mm.service.ServiceReviewHistoryService;
import com.enterprise.mm.service.ServiceOrderAcceptanceLifecycleService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ApprovalIntegrityTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectionRequiresCommentWhenApprovalStepRequiresIt() {
        UUID taskId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);

        ApprovalTask task = new ApprovalTask();
        task.setId(taskId);
        task.setTenantId(TenantContext.currentTenant());
        task.setCompanyId(CompanyContext.currentCompany());
        task.setApprovalInstanceId(instanceId);
        task.setApprovalFlowStepId(stepId);
        task.setStepOrder(1);
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setAssigneeType(ApprovalAssigneeType.ROLE);
        task.setAssigneeRole("PROCUREMENT_MANAGER");

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setTenantId(TenantContext.currentTenant());
        instance.setCompanyId(CompanyContext.currentCompany());
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(1);
        instance.setModuleKey("TEST_MODULE");
        instance.setEntityId(entityId);

        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setId(stepId);
        step.setCommentRequired(true);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);

        when(taskRepository.findByIdAndIsDeletedFalse(taskId)).thenReturn(Optional.of(task));
        when(instanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));
        when(stepRepository.findByIdAndIsDeletedFalse(stepId)).thenReturn(Optional.of(step));
        when(registry.adapter("TEST_MODULE")).thenReturn(adapter);
        when(adapter.isApprovalTaskActive(entityId)).thenReturn(true);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "approver",
            "n/a",
            java.util.List.of(new SimpleGrantedAuthority("ROLE_PROCUREMENT_MANAGER"))
        ));

        ApprovalEngineService service = new ApprovalEngineService(
            mock(ApprovalFlowRepository.class),
            stepRepository,
            instanceRepository,
            taskRepository,
            mock(ApprovalHistoryRepository.class),
            registry
        );

        assertThatThrownBy(() -> service.rejectTask(taskId, " "))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Comment is required for this approval step");
    }

    @Test
    void parallelAllApprovalRequiresEveryAssignedUserToApprove() {
        UUID flowId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID firstTaskId = UUID.randomUUID();
        UUID secondTaskId = UUID.randomUUID();

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);

        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(flowId);
        flow.setTenantId(TenantContext.currentTenant());
        flow.setCompanyId(CompanyContext.currentCompany());
        flow.setFlowType(ApprovalFlowType.PARALLEL_ALL);

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setTenantId(TenantContext.currentTenant());
        instance.setCompanyId(CompanyContext.currentCompany());
        instance.setApprovalFlowId(flowId);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(1);
        instance.setModuleKey("TEST_MODULE");
        instance.setEntityId(entityId);

        ApprovalTask firstTask = parallelTask(firstTaskId, instanceId, "parallel.one");
        ApprovalTask secondTask = parallelTask(secondTaskId, instanceId, "parallel.two");

        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));
        when(instanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));
        when(taskRepository.findByIdAndIsDeletedFalse(firstTaskId)).thenReturn(Optional.of(firstTask));
        when(taskRepository.findByIdAndIsDeletedFalse(secondTaskId)).thenReturn(Optional.of(secondTask));
        when(taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instanceId
        )).thenReturn(java.util.List.of(firstTask, secondTask));
        when(registry.adapter("TEST_MODULE")).thenReturn(adapter);
        when(adapter.isApprovalTaskActive(entityId)).thenReturn(true);
        when(adapter.displayName(entityId)).thenReturn("Test approval");
        when(instanceRepository.save(instance)).thenReturn(instance);

        ApprovalEngineService service = new ApprovalEngineService(
            flowRepository,
            mock(ApprovalFlowStepRepository.class),
            instanceRepository,
            taskRepository,
            mock(ApprovalHistoryRepository.class),
            registry
        );

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "parallel.one",
            "n/a",
            java.util.List.of()
        ));
        var afterFirst = service.approveTask(firstTaskId, "first approval");

        assertThat(firstTask.getTaskStatus()).isEqualTo(ApprovalTaskStatus.APPROVED);
        assertThat(secondTask.getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
        assertThat(afterFirst.approvalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        verify(adapter, never()).markApproved(entityId, "first approval");

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "parallel.two",
            "n/a",
            java.util.List.of()
        ));
        var afterSecond = service.approveTask(secondTaskId, "second approval");

        assertThat(secondTask.getTaskStatus()).isEqualTo(ApprovalTaskStatus.APPROVED);
        assertThat(afterSecond.approvalStatus()).isEqualTo(ApprovalInstanceStatus.APPROVED);
        assertThat(instance.getCurrentStepOrder()).isNull();
        verify(adapter).markApproved(entityId, "second approval");
    }

    @Test
    void parallelAgreementCreatesOneFinalApproverTaskBeforeFinalizing() {
        UUID flowId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID finalStepId = UUID.randomUUID();
        UUID firstTaskId = UUID.randomUUID();
        UUID secondTaskId = UUID.randomUUID();

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);

        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(flowId);
        flow.setFlowType(ApprovalFlowType.PARALLEL_THEN_FINAL);

        ApprovalFlowStep finalStep = new ApprovalFlowStep();
        finalStep.setId(finalStepId);
        finalStep.setApprovalFlowId(flowId);
        finalStep.setStepOrder(2);
        finalStep.setApprover(true);
        finalStep.setAssigneeType(ApprovalAssigneeType.USER);
        finalStep.setAssigneeUsername("final.approver");

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setTenantId(TenantContext.currentTenant());
        instance.setCompanyId(CompanyContext.currentCompany());
        instance.setApprovalFlowId(flowId);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(1);
        instance.setModuleKey("TEST_MODULE");
        instance.setEntityId(entityId);

        ApprovalTask firstTask = parallelTask(firstTaskId, instanceId, "agreement.one");
        firstTask.setTaskStatus(ApprovalTaskStatus.APPROVED);
        firstTask.setStatus(ApprovalTaskStatus.APPROVED.name());
        ApprovalTask secondTask = parallelTask(secondTaskId, instanceId, "agreement.two");
        List<ApprovalTask> tasks = new ArrayList<>(List.of(firstTask, secondTask));

        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));
        when(instanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));
        when(instanceRepository.save(any(ApprovalInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findByIdAndIsDeletedFalse(secondTaskId)).thenReturn(Optional.of(secondTask));
        when(taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instanceId)).thenReturn(tasks);
        when(taskRepository.save(any(ApprovalTask.class))).thenAnswer(inv -> {
            ApprovalTask saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            if (!tasks.contains(saved)) tasks.add(saved);
            return saved;
        });
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), flowId)).thenReturn(List.of(finalStep));
        when(stepRepository.findByIdAndIsDeletedFalse(finalStepId)).thenReturn(Optional.of(finalStep));
        when(registry.adapter("TEST_MODULE")).thenReturn(adapter);
        when(adapter.isApprovalTaskActive(entityId)).thenReturn(true);
        when(adapter.displayName(entityId)).thenReturn("Manual warehouse movement");

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "agreement.two", "n/a", List.of()));

        ApprovalEngineService service = new ApprovalEngineService(
            flowRepository, stepRepository, instanceRepository, taskRepository,
            mock(ApprovalHistoryRepository.class), registry);

        ApprovalInstanceResponse response = service.approveTask(secondTaskId, "agreed");

        assertThat(response.approvalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        assertThat(instance.getCurrentStepOrder()).isEqualTo(2);
        assertThat(tasks).hasSize(3);
        ApprovalTask finalTask = tasks.getLast();
        finalTask.setTenantId(TenantContext.currentTenant());
        finalTask.setCompanyId(CompanyContext.currentCompany());
        assertThat(finalTask.getApprovalFlowStepId()).isEqualTo(finalStepId);
        assertThat(finalTask.getAssigneeUsername()).isEqualTo("final.approver");
        assertThat(finalTask.getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
        verify(adapter, never()).markApproved(any(), any());

        when(taskRepository.findByIdAndIsDeletedFalse(finalTask.getId())).thenReturn(Optional.of(finalTask));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "final.approver", "n/a", List.of()));

        ApprovalInstanceResponse finalized = service.approveTask(finalTask.getId(), "finally approved");

        assertThat(finalized.approvalStatus()).isEqualTo(ApprovalInstanceStatus.APPROVED);
        assertThat(instance.getCurrentStepOrder()).isNull();
        verify(adapter).markApproved(entityId, "finally approved");
    }

    @Test
    void activeDemandPlanPendingTaskCannotBeApprovedWithGenericStaleMessage() {
        UUID taskId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalHistoryRepository historyRepository = mock(ApprovalHistoryRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);

        ApprovalTask task = new ApprovalTask();
        task.setId(taskId);
        task.setTenantId(TenantContext.currentTenant());
        task.setCompanyId(CompanyContext.currentCompany());
        task.setApprovalInstanceId(instanceId);
        task.setStepOrder(1);
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setAssigneeType(ApprovalAssigneeType.ROLE);
        task.setAssigneeRole("PROCUREMENT_MANAGER");

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setTenantId(TenantContext.currentTenant());
        instance.setCompanyId(CompanyContext.currentCompany());
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setStatus(ApprovalInstanceStatus.PENDING.name());
        instance.setCurrentStepOrder(1);
        instance.setModuleKey(DemandPlanApprovalAdapter.MODULE_KEY);
        instance.setEntityId(entityId);

        when(taskRepository.findByIdAndIsDeletedFalse(taskId)).thenReturn(Optional.of(task));
        when(instanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));
        when(registry.adapter(DemandPlanApprovalAdapter.MODULE_KEY)).thenReturn(adapter);
        when(adapter.isApprovalTaskActive(entityId)).thenReturn(Boolean.FALSE);
        when(instanceRepository.save(instance)).thenReturn(instance);
        when(taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instanceId
        )).thenReturn(java.util.List.of(task));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "approver",
            "n/a",
            java.util.List.of(new SimpleGrantedAuthority("ROLE_PROCUREMENT_MANAGER"))
        ));

        ApprovalEngineService service = new ApprovalEngineService(
            mock(ApprovalFlowRepository.class),
            mock(ApprovalFlowStepRepository.class),
            instanceRepository,
            taskRepository,
            historyRepository,
            registry
        );

        assertThatThrownBy(() -> service.approveTask(taskId, "approve stale task"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("This approval task is no longer active");

        assertThat(task.getTaskStatus()).isEqualTo(ApprovalTaskStatus.CANCELLED);
        assertThat(instance.getApprovalStatus()).isEqualTo(ApprovalInstanceStatus.CANCELLED);
        verify(adapter, never()).markApproved(entityId, "approve stale task");
        verify(taskRepository).save(task);
        verify(instanceRepository).save(instance);
        verify(historyRepository).save(any());
    }

    @Test
    void parallelAllStartCreatesOnePendingTaskPerAssignee() {
        UUID flowId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID userOneId = UUID.randomUUID();
        UUID userTwoId = UUID.randomUUID();

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowParallelAssigneeRepository parallelAssigneeRepository = mock(ApprovalFlowParallelAssigneeRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalHistoryRepository historyRepository = mock(ApprovalHistoryRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);
        UserRepository userRepository = mock(UserRepository.class);

        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(flowId);
        flow.setTenantId(TenantContext.currentTenant());
        flow.setCompanyId(CompanyContext.currentCompany());
        flow.setModuleKey("TEST_MODULE");
        flow.setFlowType(ApprovalFlowType.PARALLEL_ALL);
        flow.setActive(true);

        ApprovalInstance draft = new ApprovalInstance();
        draft.setId(instanceId);
        draft.setTenantId(TenantContext.currentTenant());
        draft.setCompanyId(CompanyContext.currentCompany());
        draft.setApprovalFlowId(UUID.randomUUID());
        draft.setApprovalStatus(ApprovalInstanceStatus.DRAFT);
        draft.setModuleKey("TEST_MODULE");
        draft.setEntityId(entityId);
        draft.setName("Parallel doc");

        ApprovalFlowParallelAssignee a1 = new ApprovalFlowParallelAssignee();
        a1.setAssigneeUserId(userOneId);
        a1.setAssigneeUsername("stale.display.name");
        ApprovalFlowParallelAssignee a2 = new ApprovalFlowParallelAssignee();
        a2.setAssigneeUserId(userTwoId);
        a2.setAssigneeUsername("also.stale");

        User u1 = new User();
        u1.setId(userOneId);
        u1.setUsername("parallel.one");
        u1.setEnabled(true);
        User u2 = new User();
        u2.setId(userTwoId);
        u2.setUsername("parallel.two");
        u2.setEnabled(true);

        when(adapter.companyId(entityId)).thenReturn(CompanyContext.currentCompany());
        when(adapter.departmentId(entityId)).thenReturn(null);
        when(adapter.usesRequestType()).thenReturn(false);
        when(adapter.displayName(entityId)).thenReturn("Parallel doc");
        when(registry.adapter("TEST_MODULE")).thenReturn(adapter);
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "TEST_MODULE", entityId
        )).thenReturn(Optional.of(draft));
        when(flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "TEST_MODULE"
        )).thenReturn(Optional.of(flow));
        when(instanceRepository.save(any(ApprovalInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parallelAssigneeRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByAssigneeUsername(
            TenantContext.currentTenant(), flowId
        )).thenReturn(java.util.List.of(a1, a2));
        when(taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instanceId
        )).thenReturn(java.util.List.of());
        when(taskRepository.save(any(ApprovalTask.class))).thenAnswer(inv -> {
            ApprovalTask task = inv.getArgument(0);
            if (task.getId() == null) {
                task.setId(UUID.randomUUID());
            }
            return task;
        });
        when(userRepository.findByIdAndIsDeletedFalse(userOneId)).thenReturn(Optional.of(u1));
        when(userRepository.findByIdAndIsDeletedFalse(userTwoId)).thenReturn(Optional.of(u2));
        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));

        ApprovalEngineService service = new ApprovalEngineService(
            flowRepository,
            mock(ApprovalFlowStepRepository.class),
            parallelAssigneeRepository,
            instanceRepository,
            taskRepository,
            historyRepository,
            registry,
            null,
            new ApprovalWorklistDetailAssembler(),
            null,
            userRepository
        );

        ApprovalInstanceResponse response = service.start("TEST_MODULE", entityId);

        assertThat(draft.getApprovalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        assertThat(draft.getApprovalFlowId()).isEqualTo(flowId);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);

        ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository, org.mockito.Mockito.times(2)).save(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues())
            .extracting(ApprovalTask::getAssigneeUsername)
            .containsExactlyInAnyOrder("parallel.one", "parallel.two");
        assertThat(taskCaptor.getAllValues())
            .allMatch(task -> task.getTaskStatus() == ApprovalTaskStatus.PENDING)
            .allMatch(task -> task.getAssigneeType() == ApprovalAssigneeType.USER);
        verify(adapter).markSubmitted(entityId);
    }

    @Test
    void stockMovementSubmitCreatesVisibleRoleTask() {
        UUID flowId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalHistoryRepository historyRepository = mock(ApprovalHistoryRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);

        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(flowId);
        flow.setCompanyId(CompanyContext.currentCompany());
        flow.setFlowType(ApprovalFlowType.SEQUENTIAL);

        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setId(stepId);
        step.setApprovalFlowId(flowId);
        step.setStepOrder(1);
        step.setAssigneeType(ApprovalAssigneeType.ROLE);
        step.setAssigneeRole("ROLE_WAREHOUSE_MANAGER");

        when(registry.adapter("STOCK_MOVEMENT")).thenReturn(adapter);
        when(adapter.companyId(entityId)).thenReturn(CompanyContext.currentCompany());
        when(adapter.displayName(entityId)).thenReturn("Stock movement");
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "STOCK_MOVEMENT", entityId
        )).thenReturn(Optional.empty());
        when(flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "STOCK_MOVEMENT"
        )).thenReturn(Optional.of(flow));
        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), flowId
        )).thenReturn(java.util.List.of(step));
        when(instanceRepository.save(any(ApprovalInstance.class))).thenAnswer(invocation -> {
            ApprovalInstance instance = invocation.getArgument(0);
            if (instance.getId() == null) {
                instance.setId(instanceId);
            }
            return instance;
        });

        ApprovalEngineService service = new ApprovalEngineService(
            flowRepository,
            stepRepository,
            instanceRepository,
            taskRepository,
            historyRepository,
            registry
        );

        service.start("STOCK_MOVEMENT", entityId);

        ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        ApprovalTask task = taskCaptor.getValue();
        assertThat(task.getApprovalInstanceId()).isEqualTo(instanceId);
        assertThat(task.getAssigneeRole()).isEqualTo("WAREHOUSE_MANAGER");
        assertThat(task.getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
        assertThat(LocalizedText.translate(task.getName(), Locale.ENGLISH))
            .isEqualTo("Approval Task - Stock movement");
    }

    @Test
    void pendingStockMovementSubmitRecoversMissingPendingTask() {
        UUID flowId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setTenantId(TenantContext.currentTenant());
        instance.setCompanyId(CompanyContext.currentCompany());
        instance.setApprovalFlowId(flowId);
        instance.setModuleKey("STOCK_MOVEMENT");
        instance.setEntityId(entityId);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setStatus(ApprovalInstanceStatus.PENDING.name());
        instance.setCurrentStepOrder(1);

        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(flowId);
        flow.setCompanyId(CompanyContext.currentCompany());
        flow.setFlowType(ApprovalFlowType.SEQUENTIAL);

        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setApprovalFlowId(flowId);
        step.setStepOrder(1);
        step.setAssigneeType(ApprovalAssigneeType.ROLE);
        step.setAssigneeRole("WAREHOUSE_MANAGER");

        when(registry.adapter("STOCK_MOVEMENT")).thenReturn(adapter);
        when(adapter.companyId(entityId)).thenReturn(CompanyContext.currentCompany());
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "STOCK_MOVEMENT", entityId
        )).thenReturn(Optional.of(instance));
        when(taskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instanceId, ApprovalTaskStatus.PENDING
        )).thenReturn(Optional.empty());
        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), flowId
        )).thenReturn(java.util.List.of(step));
        when(instanceRepository.save(instance)).thenReturn(instance);

        ApprovalEngineService service = new ApprovalEngineService(
            flowRepository,
            stepRepository,
            instanceRepository,
            taskRepository,
            mock(ApprovalHistoryRepository.class),
            registry
        );

        service.start("STOCK_MOVEMENT", entityId);

        ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        ApprovalTask task = taskCaptor.getValue();
        assertThat(task.getApprovalInstanceId()).isEqualTo(instanceId);
        assertThat(task.getAssigneeRole()).isEqualTo("WAREHOUSE_MANAGER");
        assertThat(task.getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
    }

    @Test
    void startCreatesNotificationForAssignedApprovalUser() {
        UUID flowId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID companyId = CompanyContext.currentCompany();

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalHistoryRepository historyRepository = mock(ApprovalHistoryRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);
        NotificationService notificationService = mock(NotificationService.class);

        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(flowId);
        flow.setTenantId(TenantContext.currentTenant());
        flow.setCompanyId(companyId);
        flow.setFlowType(ApprovalFlowType.SEQUENTIAL);

        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setId(stepId);
        step.setTenantId(TenantContext.currentTenant());
        step.setCompanyId(companyId);
        step.setStepOrder(1);
        step.setAssigneeType(ApprovalAssigneeType.USER);
        step.setAssigneeUsername("approver.user");

        when(registry.adapter("PURCHASE_REQUEST")).thenReturn(adapter);
        when(adapter.companyId(entityId)).thenReturn(companyId);
        when(adapter.departmentId(entityId)).thenReturn(null);
        when(adapter.usesRequestType()).thenReturn(false);
        when(adapter.displayName(entityId)).thenReturn("PR-100 - Bearings");
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "PURCHASE_REQUEST", entityId
        )).thenReturn(Optional.empty());
        when(flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, "PURCHASE_REQUEST"
        )).thenReturn(Optional.of(flow));
        when(instanceRepository.save(any(ApprovalInstance.class))).thenAnswer(invocation -> {
            ApprovalInstance instance = invocation.getArgument(0);
            instance.setId(UUID.randomUUID());
            return instance;
        });
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), flowId
        )).thenReturn(java.util.List.of(step));
        when(taskRepository.save(any(ApprovalTask.class))).thenAnswer(invocation -> {
            ApprovalTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(UUID.randomUUID());
            }
            return task;
        });
        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));

        ApprovalEngineService service = new ApprovalEngineService(
            flowRepository,
            stepRepository,
            instanceRepository,
            taskRepository,
            historyRepository,
            registry,
            null,
            new ApprovalWorklistDetailAssembler(),
            notificationService,
            null
        );

        service.start("PURCHASE_REQUEST", entityId);

        ArgumentCaptor<NotificationCreateRequest> captor = ArgumentCaptor.forClass(NotificationCreateRequest.class);
        verify(notificationService).create(captor.capture());
        assertThat(captor.getValue().getRecipientUsername()).isEqualTo("approver.user");
        assertThat(captor.getValue().getTitleKey()).isEqualTo("notification.approval.task.assigned.title");
        assertThat(captor.getValue().getMessageKey()).isEqualTo("notification.approval.task.assigned.message.withStep");
        Object documentName = captor.getValue().getTemplateArgs().get("document");
        assertThat(LocalizedText.translate(String.valueOf(documentName), Locale.ENGLISH))
            .isEqualTo("Purchase Request Approval - PR-100 - Bearings");
        assertThat(captor.getValue().getEntityType()).isEqualTo("APPROVAL_WORKFLOW");
        assertThat(captor.getValue().getEntityId()).isNotNull();
    }

    @Test
    void financeRejectionRequiresFinanceReviewState() {
        UUID actId = UUID.randomUUID();
        ServiceAcceptanceActRepository repository = mock(ServiceAcceptanceActRepository.class);
        AuditService auditService = mock(AuditService.class);
        ServiceReviewHistoryService historyService = mock(ServiceReviewHistoryService.class);

        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(actId);
        act.setTenantId(TenantContext.currentTenant());
        act.setStatus(ServiceAcceptanceActStatus.DRAFT.name());
        when(repository.findByIdAndIsDeletedFalse(actId)).thenReturn(Optional.of(act));

        ServiceFinanceApprovalAdapter adapter = new ServiceFinanceApprovalAdapter(
            repository,
            auditService,
            historyService,
            mock(ServiceOrderAcceptanceLifecycleService.class)
        );

        assertThatThrownBy(() -> adapter.markRejected(actId, "not ready"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FINANCE_REVIEW");
        verifyNoInteractions(auditService, historyService);
    }

    private ApprovalTask parallelTask(UUID taskId, UUID instanceId, String username) {
        ApprovalTask task = new ApprovalTask();
        task.setId(taskId);
        task.setTenantId(TenantContext.currentTenant());
        task.setCompanyId(CompanyContext.currentCompany());
        task.setApprovalInstanceId(instanceId);
        task.setStepOrder(1);
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setAssigneeType(ApprovalAssigneeType.USER);
        task.setAssigneeUsername(username);
        return task;
    }
}
