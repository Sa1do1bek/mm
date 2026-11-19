package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ApprovalTaskFocusFilterTest {
    @Mock ApprovalFlowRepository flowRepository;
    @Mock ApprovalFlowStepRepository stepRepository;
    @Mock ApprovalInstanceRepository instanceRepository;
    @Mock ApprovalTaskRepository taskRepository;
    @Mock ApprovalHistoryRepository historyRepository;
    @Mock ApprovalModuleRegistry moduleRegistry;
    @Mock ApprovalModuleAdapter moduleAdapter;

    private ApprovalEngineService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        service = new ApprovalEngineService(
            flowRepository, stepRepository, instanceRepository, taskRepository, historyRepository, moduleRegistry
        );
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-approval-focus");
        CompanyContext.setCompany(companyId);
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
    void focusedInboxReturnsOnlyTheRequestedTaskFromTheUsersVisibleTasks() {
        ApprovalTask requested = task(UUID.randomUUID());
        ApprovalTask other = task(UUID.randomUUID());
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(requested.getApprovalInstanceId());
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setModuleKey("TEST_MODULE");
        instance.setEntityId(UUID.randomUUID());
        when(taskRepository.findByTenantIdAndCompanyIdAndTaskStatusAndAssigneeUsernameAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, ApprovalTaskStatus.PENDING, "approver"
        )).thenReturn(List.of(requested, other));
        when(instanceRepository.findByIdAndIsDeletedFalse(requested.getApprovalInstanceId())).thenReturn(Optional.of(instance));
        when(stepRepository.findByIdAndIsDeletedFalse(requested.getApprovalFlowStepId())).thenReturn(Optional.empty());
        when(moduleRegistry.adapter("TEST_MODULE")).thenReturn(moduleAdapter);
        when(moduleAdapter.isApprovalTaskActive(instance.getEntityId())).thenReturn(true);

        var page = service.myTasks(PageRequest.of(0, 20), "PENDING", requested.getId());

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(task ->
            assertThat(task.id()).isEqualTo(requested.getId())
        );
    }

    private ApprovalTask task(UUID id) {
        ApprovalTask task = new ApprovalTask();
        task.setId(id);
        task.setApprovalInstanceId(UUID.randomUUID());
        task.setApprovalFlowStepId(UUID.randomUUID());
        task.setStepOrder(1);
        task.setAssigneeUsername("approver");
        task.setAssigneeType(com.enterprise.mm.enums.ApprovalAssigneeType.USER);
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        return task;
    }
}
