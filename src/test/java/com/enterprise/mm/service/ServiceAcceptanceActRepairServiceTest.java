package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalHistory;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalHistoryAction;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ServiceAcceptanceActMapper;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceAcceptanceActRepairServiceTest {
    private static final String TENANT = "tenant-service-act-repair";

    @Mock private ServiceAcceptanceActRepository actRepository;
    @Mock private ApprovalInstanceRepository instanceRepository;
    @Mock private ApprovalTaskRepository taskRepository;
    @Mock private ApprovalFlowRepository flowRepository;
    @Mock private ApprovalFlowStepRepository stepRepository;
    @Mock private ApprovalHistoryRepository historyRepository;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private ServiceOrderAcceptanceLifecycleService orderLifecycleService;
    @Mock private AuditService auditService;

    private UUID companyId;
    private ServiceAcceptanceActRepairService service;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        service = new ServiceAcceptanceActRepairService(
            actRepository,
            instanceRepository,
            taskRepository,
            flowRepository,
            stepRepository,
            historyRepository,
            approvalEngineService,
            orderLifecycleService,
            auditService,
            new ServiceAcceptanceActMapper()
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void missingApprovalInstanceIsLinkedAndStartedWithoutChangingTechnicalReviewStatus() {
        ServiceAcceptanceAct act = technicalReviewAct();
        ApprovalFlow flow = flow();
        ApprovalFlowStep step = step(flow.getId(), 1);
        ApprovalInstance linked = instance(act, flow, ApprovalInstanceStatus.DRAFT);
        when(actRepository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TENANT, companyId, ServiceAcceptanceActApprovalAdapter.MODULE_KEY, act.getId()
        )).thenReturn(Optional.empty(), Optional.of(linked));
        when(taskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
            TENANT, linked.getId(), ApprovalTaskStatus.PENDING
        )).thenReturn(Optional.empty(), Optional.empty());
        when(flowRepository.findByIdAndIsDeletedFalse(flow.getId())).thenReturn(Optional.of(flow));
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(TENANT, flow.getId()))
            .thenReturn(List.of(step));
        when(instanceRepository.save(any(ApprovalInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.repairMissingTechnicalReviewApproval(act.getId());

        verify(approvalEngineService).linkItem(
            ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
            act.getId(),
            companyId,
            act.getCode()
        );
        ArgumentCaptor<ApprovalInstance> instanceCaptor = ArgumentCaptor.forClass(ApprovalInstance.class);
        verify(instanceRepository).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getApprovalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        assertThat(instanceCaptor.getValue().getCurrentStepOrder()).isEqualTo(1);
        ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
        assertThat(taskCaptor.getValue().getApprovalInstanceId()).isEqualTo(linked.getId());
        ArgumentCaptor<ApprovalHistory> historyCaptor = ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getAction()).isEqualTo(ApprovalHistoryAction.STARTED);
        verify(orderLifecycleService).recompute(act.getServiceOrderId());
        verify(auditService).log(
            "ServiceAcceptanceAct",
            act.getId(),
            "SERVICE_ACCEPTANCE_ACT_APPROVAL_REPAIRED",
            act.getCode(),
            null,
            null
        );
        assertThat(act.getStatus()).isEqualTo(ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name());
        assertThat(response.getStatus()).isEqualTo(ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name());
    }

    @Test
    void draftApprovalInstanceIsPromotedAndGetsPendingTask() {
        ServiceAcceptanceAct act = technicalReviewAct();
        ApprovalFlow flow = flow();
        ApprovalFlowStep step = step(flow.getId(), 2);
        ApprovalInstance draft = instance(act, flow, ApprovalInstanceStatus.DRAFT);
        when(actRepository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TENANT, companyId, ServiceAcceptanceActApprovalAdapter.MODULE_KEY, act.getId()
        )).thenReturn(Optional.of(draft));
        when(taskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
            TENANT, draft.getId(), ApprovalTaskStatus.PENDING
        )).thenReturn(Optional.empty(), Optional.empty());
        when(flowRepository.findByIdAndIsDeletedFalse(flow.getId())).thenReturn(Optional.of(flow));
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(TENANT, flow.getId()))
            .thenReturn(List.of(step));
        when(instanceRepository.save(any(ApprovalInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.repairMissingTechnicalReviewApproval(act.getId());

        verify(approvalEngineService, never()).linkItem(any(), any(), any(), any());
        ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        assertThat(draft.getApprovalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        assertThat(draft.getCurrentStepOrder()).isEqualTo(2);
        assertThat(taskCaptor.getValue().getAssigneeRole()).isEqualTo("PROCUREMENT_MANAGER");
    }

    @Test
    void pendingTaskAlreadyPresentDoesNotCreateDuplicate() {
        ServiceAcceptanceAct act = technicalReviewAct();
        ApprovalFlow flow = flow();
        ApprovalInstance pending = instance(act, flow, ApprovalInstanceStatus.PENDING);
        ApprovalTask existing = new ApprovalTask();
        existing.setId(UUID.randomUUID());
        when(actRepository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TENANT, companyId, ServiceAcceptanceActApprovalAdapter.MODULE_KEY, act.getId()
        )).thenReturn(Optional.of(pending));
        when(taskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
            TENANT, pending.getId(), ApprovalTaskStatus.PENDING
        )).thenReturn(Optional.of(existing));

        service.repairMissingTechnicalReviewApproval(act.getId());

        verify(taskRepository, never()).save(any(ApprovalTask.class));
        verify(historyRepository, never()).save(any(ApprovalHistory.class));
        verify(orderLifecycleService).recompute(act.getServiceOrderId());
    }

    @Test
    void nonTechnicalReviewActCannotBeRepaired() {
        ServiceAcceptanceAct act = technicalReviewAct();
        act.setStatus(ServiceAcceptanceActStatus.DOCUMENT_REVIEW.name());
        when(actRepository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));

        assertThatThrownBy(() -> service.repairMissingTechnicalReviewApproval(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only TECHNICAL_REVIEW");

        verify(instanceRepository, never()).findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            any(), any(), any(), any()
        );
        verify(orderLifecycleService, never()).recompute(any());
    }

    private ServiceAcceptanceAct technicalReviewAct() {
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId(TENANT);
        act.setCompanyId(companyId);
        act.setServiceOrderId(UUID.randomUUID());
        act.setCode("SAA-REPAIR");
        act.setName("Stuck service acceptance act");
        act.setStatus(ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name());
        return act;
    }

    private ApprovalFlow flow() {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(UUID.randomUUID());
        flow.setTenantId(TENANT);
        flow.setCompanyId(companyId);
        flow.setModuleKey(ServiceAcceptanceActApprovalAdapter.MODULE_KEY);
        flow.setActive(true);
        return flow;
    }

    private ApprovalFlowStep step(UUID flowId, int order) {
        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setId(UUID.randomUUID());
        step.setTenantId(TENANT);
        step.setCompanyId(companyId);
        step.setApprovalFlowId(flowId);
        step.setStepOrder(order);
        step.setAssigneeType(ApprovalAssigneeType.ROLE);
        step.setAssigneeRole("ROLE_PROCUREMENT_MANAGER");
        return step;
    }

    private ApprovalInstance instance(
        ServiceAcceptanceAct act,
        ApprovalFlow flow,
        ApprovalInstanceStatus status
    ) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(UUID.randomUUID());
        instance.setTenantId(TENANT);
        instance.setCompanyId(companyId);
        instance.setApprovalFlowId(flow.getId());
        instance.setModuleKey(ServiceAcceptanceActApprovalAdapter.MODULE_KEY);
        instance.setEntityId(act.getId());
        instance.setCode("AI-REPAIR");
        instance.setName("Repair approval");
        instance.setStatus(status.name());
        instance.setApprovalStatus(status);
        return instance;
    }
}
