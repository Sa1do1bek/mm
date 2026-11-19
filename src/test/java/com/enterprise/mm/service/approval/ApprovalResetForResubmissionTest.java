package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalHistory;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.enums.ApprovalHistoryAction;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApprovalResetForResubmissionTest {

    private static final String MODULE_KEY = "SERVICE_REQUEST";

    @Mock
    private ApprovalFlowRepository flowRepository;
    @Mock
    private ApprovalFlowStepRepository stepRepository;
    @Mock
    private ApprovalInstanceRepository instanceRepository;
    @Mock
    private ApprovalTaskRepository taskRepository;
    @Mock
    private ApprovalHistoryRepository historyRepository;
    @Mock
    private ApprovalModuleRegistry moduleRegistry;

    private ApprovalEngineService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalEngineService(
            flowRepository,
            stepRepository,
            instanceRepository,
            taskRepository,
            historyRepository,
            moduleRegistry
        );
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalInstanceStatus.class, names = {"PENDING", "APPROVED", "REJECTED"})
    void resetsCompletedOrPendingInstanceAndCancelsOnlyPendingTasks(ApprovalInstanceStatus priorStatus) {
        UUID entityId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        ApprovalInstance instance = approvalInstance(instanceId, entityId, priorStatus);
        ApprovalTask pendingTask = approvalTask(instanceId, ApprovalTaskStatus.PENDING);
        ApprovalTask approvedTask = approvalTask(instanceId, ApprovalTaskStatus.APPROVED);

        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), MODULE_KEY, entityId
        )).thenReturn(Optional.of(instance));
        when(taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instanceId
        )).thenReturn(List.of(pendingTask, approvedTask));
        when(instanceRepository.save(instance)).thenReturn(instance);

        invokeReset(MODULE_KEY, entityId, "Returned for correction");

        assertThat(pendingTask.getTaskStatus()).isEqualTo(ApprovalTaskStatus.CANCELLED);
        assertThat(pendingTask.getStatus()).isEqualTo(ApprovalTaskStatus.CANCELLED.name());
        assertThat(approvedTask.getTaskStatus()).isEqualTo(ApprovalTaskStatus.APPROVED);
        verify(taskRepository).save(pendingTask);
        verify(taskRepository, never()).save(approvedTask);

        assertThat(instance.getApprovalStatus()).isEqualTo(ApprovalInstanceStatus.DRAFT);
        assertThat(instance.getStatus()).isEqualTo(ApprovalInstanceStatus.DRAFT.name());
        assertThat(instance.getCurrentStepOrder()).isNull();
        assertThat(instance.getStartedAt()).isNull();
        assertThat(instance.getCompletedAt()).isNull();

        ArgumentCaptor<ApprovalHistory> historyCaptor = ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        ApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getApprovalInstanceId()).isEqualTo(instanceId);
        assertThat(history.getAction()).isEqualTo(ApprovalHistoryAction.CANCELLED);
        assertThat(history.getFromStepOrder()).isEqualTo(2);
        assertThat(history.getToStepOrder()).isNull();
        assertThat(history.getCommentText()).isEqualTo("Returned for correction");
    }

    @Test
    void isSafeWhenTheModuleHasNoApprovalInstanceYet() {
        UUID entityId = UUID.randomUUID();
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), MODULE_KEY, entityId
        )).thenReturn(Optional.empty());

        invokeReset(MODULE_KEY, entityId, "Returned for correction");

        verify(instanceRepository, never()).save(any());
        verifyNoInteractions(taskRepository, historyRepository, moduleRegistry);
    }

    private ApprovalInstance approvalInstance(UUID instanceId, UUID entityId, ApprovalInstanceStatus status) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setTenantId(TenantContext.currentTenant());
        instance.setCompanyId(CompanyContext.currentCompany());
        instance.setModuleKey(MODULE_KEY);
        instance.setEntityId(entityId);
        instance.setApprovalStatus(status);
        instance.setStatus(status.name());
        instance.setCurrentStepOrder(2);
        instance.setStartedAt(LocalDateTime.now().minusDays(2));
        instance.setCompletedAt(LocalDateTime.now().minusDays(1));
        return instance;
    }

    private ApprovalTask approvalTask(UUID instanceId, ApprovalTaskStatus status) {
        ApprovalTask task = new ApprovalTask();
        task.setId(UUID.randomUUID());
        task.setApprovalInstanceId(instanceId);
        task.setTaskStatus(status);
        task.setStatus(status.name());
        return task;
    }

    private void invokeReset(String moduleKey, UUID entityId, String comment) {
        try {
            Method method = ApprovalEngineService.class.getMethod(
                "resetForResubmission",
                String.class,
                UUID.class,
                String.class
            );
            method.invoke(service, moduleKey, entityId, comment);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("ApprovalEngineService.resetForResubmission is missing", ex);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("Approval reset operation is not accessible", ex);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError("Approval reset operation failed", cause);
        }
    }
}
