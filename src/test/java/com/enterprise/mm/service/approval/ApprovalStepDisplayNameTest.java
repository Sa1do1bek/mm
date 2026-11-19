package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ApprovalStepDisplayNameTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void workflowUsesFullNamesForCompletedCurrentAndFutureUserSteps() {
        String tenantId = "approval-display-" + UUID.randomUUID();
        UUID companyId = CompanyContext.DEFAULT_COMPANY_ID;
        UUID flowId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        User firstUser = user(tenantId, companyId, "abduvali", "Abduvali Karimov");
        User secondUser = user(tenantId, companyId, "odilbek", "Odilbek Rakhimov");
        User futureUser = user(tenantId, companyId, "NAVOIYURAN_USER", "Admin Navuran");

        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(flowId);
        flow.setTenantId(tenantId);
        flow.setCompanyId(companyId);
        flow.setFlowType(ApprovalFlowType.SEQUENTIAL);

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setTenantId(tenantId);
        instance.setCompanyId(companyId);
        instance.setApprovalFlowId(flowId);
        instance.setModuleKey("TEST_MODULE");
        instance.setEntityId(entityId);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(2);

        ApprovalFlowStep firstStep = step(tenantId, companyId, flowId, 1, firstUser);
        ApprovalFlowStep secondStep = step(tenantId, companyId, flowId, 2, secondUser);
        ApprovalFlowStep futureStep = step(tenantId, companyId, flowId, 3, futureUser);
        ApprovalTask completedTask = task(tenantId, companyId, instanceId, firstStep, firstUser, ApprovalTaskStatus.APPROVED);
        completedTask.setActedBy(firstUser.getUsername());
        completedTask.setActedAt(LocalDateTime.of(2026, 7, 27, 17, 42));
        ApprovalTask currentTask = task(tenantId, companyId, instanceId, secondStep, secondUser, ApprovalTaskStatus.PENDING);

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalHistoryRepository historyRepository = mock(ApprovalHistoryRepository.class);
        ApprovalModuleRegistry moduleRegistry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter moduleAdapter = mock(ApprovalModuleAdapter.class);
        UserRepository userRepository = mock(UserRepository.class);

        when(taskRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, currentTask.getId()))
            .thenReturn(Optional.of(currentTask));
        when(taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(tenantId, instanceId))
            .thenReturn(List.of(completedTask, currentTask));
        when(instanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));
        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));
        when(stepRepository.findByIdAndIsDeletedFalse(secondStep.getId())).thenReturn(Optional.of(secondStep));
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(tenantId, flowId))
            .thenReturn(List.of(firstStep, secondStep, futureStep));
        when(historyRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByActionAtAsc(tenantId, instanceId))
            .thenReturn(List.of());
        when(moduleRegistry.adapter("TEST_MODULE")).thenReturn(moduleAdapter);
        when(moduleAdapter.displayName(entityId)).thenReturn("Demand plan");
        when(userRepository.findByIdAndIsDeletedFalse(firstUser.getId())).thenReturn(Optional.of(firstUser));
        when(userRepository.findByIdAndIsDeletedFalse(secondUser.getId())).thenReturn(Optional.of(secondUser));
        when(userRepository.findByIdAndIsDeletedFalse(futureUser.getId())).thenReturn(Optional.of(futureUser));
        when(userRepository.resolveActiveUserByUsername(tenantId, companyId, firstUser.getUsername()))
            .thenReturn(Optional.of(firstUser));

        ApprovalWorklistDetailAssembler assembler = new ApprovalWorklistDetailAssembler(
            null,
            null,
            null,
            moduleRegistry
        );
        ApprovalEngineService service = new ApprovalEngineService(
            flowRepository,
            stepRepository,
            instanceRepository,
            taskRepository,
            historyRepository,
            moduleRegistry,
            null,
            assembler,
            null,
            userRepository
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(secondUser.getUsername(), "n/a", List.of())
        );

        var detail = service.myTaskDetail(currentTask.getId());
        List<ApprovalDetailItem> route = detail.workflow().items();

        assertThat(route).extracting(item -> item.values().get("assigneeName"))
            .containsExactly("Abduvali Karimov", "Odilbek Rakhimov", "Admin Navuran");
        assertThat(route.get(0).values()).containsEntry("actedBy", "Abduvali Karimov");
    }

    @Test
    void workflowFallsBackToPendingTaskWhenConfiguredStepsAreUnavailable() {
        String tenantId = "approval-empty-steps-" + UUID.randomUUID();
        UUID companyId = CompanyContext.DEFAULT_COMPANY_ID;
        UUID flowId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        User approver = user(tenantId, companyId, "approval.user", "Approval User");
        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(flowId);
        flow.setTenantId(tenantId);
        flow.setCompanyId(companyId);
        flow.setFlowType(ApprovalFlowType.SEQUENTIAL);

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setTenantId(tenantId);
        instance.setCompanyId(companyId);
        instance.setApprovalFlowId(flowId);
        instance.setModuleKey("TEST_MODULE");
        instance.setEntityId(entityId);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(1);

        ApprovalTask currentTask = new ApprovalTask();
        currentTask.setId(UUID.randomUUID());
        currentTask.setTenantId(tenantId);
        currentTask.setCompanyId(companyId);
        currentTask.setApprovalInstanceId(instanceId);
        currentTask.setApprovalFlowStepId(UUID.randomUUID());
        currentTask.setStepOrder(1);
        currentTask.setAssigneeType(ApprovalAssigneeType.USER);
        currentTask.setAssigneeUserId(approver.getId());
        currentTask.setAssigneeUsername(approver.getUsername());
        currentTask.setTaskStatus(ApprovalTaskStatus.PENDING);
        currentTask.setCreatedAt(LocalDateTime.of(2026, 7, 30, 10, 0));

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalHistoryRepository historyRepository = mock(ApprovalHistoryRepository.class);
        ApprovalModuleRegistry moduleRegistry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter moduleAdapter = mock(ApprovalModuleAdapter.class);
        UserRepository userRepository = mock(UserRepository.class);

        when(taskRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, currentTask.getId()))
            .thenReturn(Optional.of(currentTask));
        when(taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(tenantId, instanceId))
            .thenReturn(List.of(currentTask));
        when(instanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));
        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));
        when(stepRepository.findByIdAndIsDeletedFalse(currentTask.getApprovalFlowStepId())).thenReturn(Optional.empty());
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(tenantId, flowId))
            .thenReturn(List.of());
        when(historyRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByActionAtAsc(tenantId, instanceId))
            .thenReturn(List.of());
        when(moduleRegistry.adapter("TEST_MODULE")).thenReturn(moduleAdapter);
        when(moduleAdapter.displayName(entityId)).thenReturn("Approval source");
        when(userRepository.findByIdAndIsDeletedFalse(approver.getId())).thenReturn(Optional.of(approver));

        ApprovalWorklistDetailAssembler assembler = new ApprovalWorklistDetailAssembler(
            null,
            null,
            null,
            moduleRegistry
        );
        ApprovalEngineService service = new ApprovalEngineService(
            flowRepository,
            stepRepository,
            instanceRepository,
            taskRepository,
            historyRepository,
            moduleRegistry,
            null,
            assembler,
            null,
            userRepository
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(approver.getUsername(), "n/a", List.of())
        );

        var detail = service.myTaskDetail(currentTask.getId());

        assertThat(detail.workflow().status()).isEqualTo("READY");
        assertThat(detail.workflow().message()).isNull();
        assertThat(detail.workflow().items()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("Step 1");
            assertThat(item.values()).containsEntry("status", ApprovalTaskStatus.PENDING);
            assertThat(item.values()).containsEntry("assigneeName", "Approval User");
        });
    }

    private User user(String tenantId, UUID companyId, String username, String fullName) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenantId);
        user.setCompanyId(companyId);
        user.setCode("USR-" + UUID.randomUUID());
        user.setName(fullName);
        user.setStatus("ACTIVE");
        user.setUsername(username);
        user.setFullName(fullName);
        user.setPasswordHash("test");
        user.setEnabled(true);
        return user;
    }

    private ApprovalFlowStep step(
        String tenantId,
        UUID companyId,
        UUID flowId,
        int order,
        User assignee
    ) {
        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setId(UUID.randomUUID());
        step.setTenantId(tenantId);
        step.setCompanyId(companyId);
        step.setApprovalFlowId(flowId);
        step.setStepOrder(order);
        step.setAssigneeType(ApprovalAssigneeType.USER);
        step.setAssigneeUserId(assignee.getId());
        step.setAssigneeUsername(null);
        return step;
    }

    private ApprovalTask task(
        String tenantId,
        UUID companyId,
        UUID instanceId,
        ApprovalFlowStep step,
        User assignee,
        ApprovalTaskStatus status
    ) {
        ApprovalTask task = new ApprovalTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(tenantId);
        task.setCompanyId(companyId);
        task.setApprovalInstanceId(instanceId);
        task.setApprovalFlowStepId(step.getId());
        task.setStepOrder(step.getStepOrder());
        task.setAssigneeType(ApprovalAssigneeType.USER);
        task.setAssigneeUserId(assignee.getId());
        task.setAssigneeUsername(assignee.getUsername());
        task.setTaskStatus(status);
        task.setCreatedAt(LocalDateTime.of(2026, 7, 27, 17, 39).plusMinutes(step.getStepOrder()));
        return task;
    }
}
