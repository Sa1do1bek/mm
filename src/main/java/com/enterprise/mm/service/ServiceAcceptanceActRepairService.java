package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalHistory;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.enums.ApprovalHistoryAction;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ServiceAcceptanceActMapper;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {})
public class ServiceAcceptanceActRepairService {
    private static final String REPAIR_COMMENT = "Service acceptance act technical review approval repaired";

    private final ServiceAcceptanceActRepository actRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final ApprovalTaskRepository taskRepository;
    private final ApprovalFlowRepository flowRepository;
    private final ApprovalFlowStepRepository stepRepository;
    private final ApprovalHistoryRepository historyRepository;
    private final ApprovalEngineService approvalEngineService;
    private final ServiceOrderAcceptanceLifecycleService orderLifecycleService;
    private final AuditService auditService;
    private final ServiceAcceptanceActMapper mapper;

    @Transactional
    public ServiceAcceptanceActResponse repairMissingTechnicalReviewApproval(UUID actId) {
        ServiceAcceptanceAct act = loadAct(actId);
        if (!ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name().equals(act.getStatus())) {
            throw new BusinessException("Only TECHNICAL_REVIEW service acceptance act approval can be repaired");
        }
        if (act.getServiceOrderId() == null) {
            throw new BusinessException("Service acceptance act must be linked to a service order before approval repair");
        }

        ApprovalInstance instance = instanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                act.getCompanyId(),
                ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
                act.getId()
            )
            .orElseGet(() -> linkApprovalInstance(act));

        boolean pendingTaskExists = hasPendingTask(instance);
        if (instance.getApprovalStatus() == ApprovalInstanceStatus.PENDING && pendingTaskExists) {
            recomputeAndAudit(act);
            return mapper.toResponse(actRepository.findByIdAndIsDeletedFalse(act.getId()).orElse(act));
        }
        if (instance.getApprovalStatus() == ApprovalInstanceStatus.APPROVED
            || instance.getApprovalStatus() == ApprovalInstanceStatus.REJECTED
            || instance.getApprovalStatus() == ApprovalInstanceStatus.CANCELLED) {
            throw new BusinessException("Approval instance is already completed and cannot be repaired");
        }

        ApprovalFlowStep step = currentOrFirstStep(instance);
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setStatus(ApprovalInstanceStatus.PENDING.name());
        instance.setCurrentStepOrder(step.getStepOrder());
        if (instance.getStartedAt() == null) {
            instance.setStartedAt(LocalDateTime.now());
        }
        ApprovalInstance saved = instanceRepository.save(instance);
        if (!pendingTaskExists && !hasPendingTask(saved)) {
            createTask(saved, step);
            record(saved, ApprovalHistoryAction.STARTED, null, step.getStepOrder(), REPAIR_COMMENT);
        } else if (pendingTaskExists) {
            record(saved, ApprovalHistoryAction.STARTED, null, step.getStepOrder(), REPAIR_COMMENT);
        }
        recomputeAndAudit(act);
        return mapper.toResponse(actRepository.findByIdAndIsDeletedFalse(act.getId()).orElse(act));
    }

    private ServiceAcceptanceAct loadAct(UUID actId) {
        ServiceAcceptanceAct act = actRepository.findByIdAndIsDeletedFalse(actId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> CompanyContext.currentCompany().equals(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ServiceAcceptanceAct not found: " + actId));
        return act;
    }

    private ApprovalInstance linkApprovalInstance(ServiceAcceptanceAct act) {
        approvalEngineService.linkItem(
            ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
            act.getId(),
            act.getCompanyId(),
            act.getCode()
        );
        return instanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                act.getCompanyId(),
                ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
                act.getId()
            )
            .orElseThrow(() -> new BusinessException("Service acceptance act approval instance could not be linked"));
    }

    private boolean hasPendingTask(ApprovalInstance instance) {
        if (instance == null || instance.getId() == null) {
            return false;
        }
        return taskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(),
            instance.getId(),
            ApprovalTaskStatus.PENDING
        ).isPresent();
    }

    private ApprovalFlowStep currentOrFirstStep(ApprovalInstance instance) {
        ApprovalFlow flow = flow(instance);
        return stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(), flow.getId())
            .stream()
            .filter(step -> instance.getCurrentStepOrder() == null || instance.getCurrentStepOrder().equals(step.getStepOrder()))
            .min(Comparator.comparing(ApprovalFlowStep::getStepOrder))
            .or(() -> stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
                    TenantContext.currentTenant(), flow.getId())
                .stream()
                .min(Comparator.comparing(ApprovalFlowStep::getStepOrder)))
            .orElseThrow(() -> new BusinessException("Approval flow has no steps"));
    }

    private ApprovalFlow flow(ApprovalInstance instance) {
        if (instance.getApprovalFlowId() == null) {
            ApprovalFlow latest = flowRepository
                .findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), instance.getCompanyId(), ServiceAcceptanceActApprovalAdapter.MODULE_KEY)
                .orElseThrow(() -> new BusinessException("Active SERVICE_ACCEPTANCE_ACT approval flow not found"));
            instance.setApprovalFlowId(latest.getId());
            return latest;
        }
        return flowRepository.findByIdAndIsDeletedFalse(instance.getApprovalFlowId())
            .orElseThrow(() -> new BusinessException("Approval flow not found for repair"));
    }

    private void createTask(ApprovalInstance instance, ApprovalFlowStep step) {
        ApprovalTask task = new ApprovalTask();
        task.setCompanyId(instance.getCompanyId());
        task.setCode("AT-" + UUID.randomUUID().toString().substring(0, 8));
        task.setName(BusinessNameGenerator.workflowDocumentName("ApprovalTask", instance.getName()));
        task.setStatus(ApprovalTaskStatus.PENDING.name());
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setApprovalInstanceId(instance.getId());
        task.setApprovalFlowStepId(step.getId());
        task.setStepOrder(step.getStepOrder());
        task.setAssigneeType(step.getAssigneeType());
        task.setAssigneeRole(normalizeRole(step.getAssigneeRole()));
        task.setAssigneeUserId(step.getAssigneeUserId());
        task.setAssigneeUsername(step.getAssigneeUsername());
        taskRepository.save(task);
    }

    private void record(ApprovalInstance instance, ApprovalHistoryAction action, Integer fromStep, Integer toStep, String comment) {
        ApprovalHistory history = new ApprovalHistory();
        history.setCompanyId(instance.getCompanyId());
        history.setCode("AH-" + UUID.randomUUID().toString().substring(0, 8));
        history.setName(BusinessNameGenerator.workflowDocumentName("ApprovalHistory", instance.getName()));
        history.setStatus(action.name());
        history.setApprovalInstanceId(instance.getId());
        history.setAction(action);
        history.setActor("approval-repair");
        history.setFromStepOrder(fromStep);
        history.setToStepOrder(toStep);
        history.setCommentText(comment);
        history.setActionAt(LocalDateTime.now());
        historyRepository.save(history);
    }

    private void recomputeAndAudit(ServiceAcceptanceAct act) {
        orderLifecycleService.recompute(act.getServiceOrderId());
        auditService.log(
            "ServiceAcceptanceAct",
            act.getId(),
            "SERVICE_ACCEPTANCE_ACT_APPROVAL_REPAIRED",
            act.getCode(),
            null,
            null
        );
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        String normalized = role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
        return normalized.toUpperCase(Locale.ROOT);
    }
}
