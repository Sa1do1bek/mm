package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowParallelAssignee;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalHistory;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalHistoryAction;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalHistoryResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowPreviewResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalAgeBucketCount;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalStepProgressView;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDetailResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskSummaryResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalWorklistFilter;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewResponse;
import com.enterprise.mm.payload.NotificationCreateRequest;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowParallelAssigneeRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.service.NotificationService;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ApprovalEngineService {
    private static final String STALE_TASK_MESSAGE = "This approval task is no longer active";

    private final ApprovalFlowRepository flowRepository;
    private final ApprovalFlowStepRepository stepRepository;
    private final ApprovalFlowParallelAssigneeRepository parallelAssigneeRepository;
    private final ApprovalInstanceRepository instanceRepository;
    private final ApprovalTaskRepository taskRepository;
    private final ApprovalHistoryRepository historyRepository;
    private final ApprovalModuleRegistry moduleRegistry;
    private final ApprovalWorklistQuery worklistQuery;
    private final ApprovalWorklistDetailAssembler detailAssembler;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @Autowired
    public ApprovalEngineService(
        ApprovalFlowRepository flowRepository,
        ApprovalFlowStepRepository stepRepository,
        ApprovalFlowParallelAssigneeRepository parallelAssigneeRepository,
        ApprovalInstanceRepository instanceRepository,
        ApprovalTaskRepository taskRepository,
        ApprovalHistoryRepository historyRepository,
        ApprovalModuleRegistry moduleRegistry,
        ApprovalWorklistQuery worklistQuery,
        ApprovalWorklistDetailAssembler detailAssembler,
        NotificationService notificationService,
        UserRepository userRepository
    ) {
        this.flowRepository = flowRepository;
        this.stepRepository = stepRepository;
        this.parallelAssigneeRepository = parallelAssigneeRepository;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.historyRepository = historyRepository;
        this.moduleRegistry = moduleRegistry;
        this.worklistQuery = worklistQuery;
        this.detailAssembler = detailAssembler;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    ApprovalEngineService(
        ApprovalFlowRepository flowRepository,
        ApprovalFlowStepRepository stepRepository,
        ApprovalInstanceRepository instanceRepository,
        ApprovalTaskRepository taskRepository,
        ApprovalHistoryRepository historyRepository,
        ApprovalModuleRegistry moduleRegistry
    ) {
        this(
            flowRepository,
            stepRepository,
            instanceRepository,
            taskRepository,
            historyRepository,
            moduleRegistry,
            null,
            new ApprovalWorklistDetailAssembler(),
            null,
            null
        );
    }

    ApprovalEngineService(
        ApprovalFlowRepository flowRepository,
        ApprovalFlowStepRepository stepRepository,
        ApprovalInstanceRepository instanceRepository,
        ApprovalTaskRepository taskRepository,
        ApprovalHistoryRepository historyRepository,
        ApprovalModuleRegistry moduleRegistry,
        ApprovalWorklistQuery worklistQuery,
        ApprovalWorklistDetailAssembler detailAssembler
    ) {
        this(
            flowRepository, stepRepository, null, instanceRepository, taskRepository, historyRepository,
            moduleRegistry, worklistQuery, detailAssembler, null, null
        );
    }

    ApprovalEngineService(
        ApprovalFlowRepository flowRepository,
        ApprovalFlowStepRepository stepRepository,
        ApprovalInstanceRepository instanceRepository,
        ApprovalTaskRepository taskRepository,
        ApprovalHistoryRepository historyRepository,
        ApprovalModuleRegistry moduleRegistry,
        ApprovalWorklistQuery worklistQuery,
        ApprovalWorklistDetailAssembler detailAssembler,
        NotificationService notificationService,
        UserRepository userRepository
    ) {
        this(
            flowRepository, stepRepository, null, instanceRepository, taskRepository, historyRepository,
            moduleRegistry, worklistQuery, detailAssembler, notificationService, userRepository
        );
    }

    @Transactional
    public ApprovalInstanceResponse linkItem(String moduleKey, UUID entityId, UUID companyId, String displayName) {
        String normalizedModule = ApprovalModuleRegistry.normalize(moduleKey);
        return instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(), companyId, normalizedModule, entityId)
            .map(this::toInstanceResponse)
            .orElseGet(() -> {
                ApprovalModuleAdapter adapter = moduleRegistry.adapter(normalizedModule);
                boolean usesRequestType = adapter.usesRequestType();
                RequestType requestType = usesRequestType ? adapter.requestType(entityId) : null;
                ApprovalFlow flow = latestFlow(normalizedModule, companyId, adapter.departmentId(entityId), usesRequestType, requestType);
                ApprovalInstance instance = new ApprovalInstance();
                instance.setCompanyId(companyId);
                instance.setCode("AI-" + UUID.randomUUID().toString().substring(0, 8));
                instance.setName(approvalInstanceName(normalizedModule, adapter, entityId, displayName));
                instance.setStatus("DRAFT");
                instance.setApprovalStatus(ApprovalInstanceStatus.DRAFT);
                instance.setApprovalFlowId(flow.getId());
                instance.setModuleKey(normalizedModule);
                instance.setEntityId(entityId);
                ApprovalInstance saved = instanceRepository.save(instance);
                record(saved, ApprovalHistoryAction.LINKED, null, null, "Approval flow linked");
                return toInstanceResponse(saved);
            });
    }

    @Transactional
    public ApprovalInstanceResponse start(String moduleKey, UUID entityId) {
        String normalizedModule = ApprovalModuleRegistry.normalize(moduleKey);
        ApprovalModuleAdapter adapter = moduleRegistry.adapter(normalizedModule);
        adapter.validateCanSubmit(entityId);
        UUID companyId = adapter.companyId(entityId);
        UUID departmentId = adapter.departmentId(entityId);
        boolean usesRequestType = adapter.usesRequestType();
        RequestType requestType = usesRequestType ? adapter.requestType(entityId) : null;
        ApprovalInstance instance = instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(), companyId, normalizedModule, entityId)
            .orElseGet(() -> {
                ApprovalFlow flow = latestFlow(normalizedModule, companyId, departmentId, usesRequestType, requestType);
                ApprovalInstance created = new ApprovalInstance();
                created.setCompanyId(companyId);
                created.setCode("AI-" + UUID.randomUUID().toString().substring(0, 8));
                created.setName(approvalInstanceName(normalizedModule, adapter, entityId, null));
                created.setStatus("DRAFT");
                created.setApprovalStatus(ApprovalInstanceStatus.DRAFT);
                created.setApprovalFlowId(flow.getId());
                created.setModuleKey(normalizedModule);
                created.setEntityId(entityId);
                ApprovalInstance saved = instanceRepository.save(created);
                record(saved, ApprovalHistoryAction.LINKED, null, null, "Approval flow linked");
                return saved;
            });
        if (instance.getApprovalStatus() == ApprovalInstanceStatus.APPROVED || instance.getApprovalStatus() == ApprovalInstanceStatus.REJECTED) {
            throw new BusinessException("Approval instance is already completed");
        }
        if (instance.getApprovalStatus() == ApprovalInstanceStatus.PENDING) {
            ensurePendingTasks(instance);
            syncSubmittedState(normalizedModule, entityId);
            return toInstanceResponse(instance);
        }
        // DRAFT: always bind to the latest active flow so newly configured parallel routing is used.
        ApprovalFlow flow = latestFlow(normalizedModule, companyId, departmentId, usesRequestType, requestType);
        if (instance.getApprovalFlowId() == null || !flow.getId().equals(instance.getApprovalFlowId())) {
            instance.setApprovalFlowId(flow.getId());
            instance = instanceRepository.save(instance);
            record(instance, ApprovalHistoryAction.LINKED, null, null, "Approval flow refreshed to latest active flow");
        }
        if (usesParallelAgreement(flow.getFlowType())) {
            instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
            instance.setStatus(ApprovalInstanceStatus.PENDING.name());
            instance.setCurrentStepOrder(1);
            instance.setStartedAt(LocalDateTime.now());
            ApprovalInstance saved = instanceRepository.save(instance);
            createParallelTasks(saved, flow);
            adapter.markSubmitted(entityId);
            record(saved, ApprovalHistoryAction.STARTED, null, 1, "Parallel approval started");
            return toInstanceResponse(saved);
        }
        boolean resubmission = instance.getApprovalStatus() == ApprovalInstanceStatus.RETURNED;
        Integer resumedStepOrder = resubmission ? instance.getCurrentStepOrder() : null;
        ApprovalFlowStep firstStep = orderedSteps(instance).stream()
            .filter(step -> resumedStepOrder == null || step.getStepOrder().equals(resumedStepOrder))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Approval flow has no steps"));
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setStatus(ApprovalInstanceStatus.PENDING.name());
        instance.setCurrentStepOrder(firstStep.getStepOrder());
        instance.setStartedAt(LocalDateTime.now());
        ApprovalInstance saved = instanceRepository.save(instance);
        createTask(saved, firstStep);
        adapter.markSubmitted(entityId);
        record(saved, ApprovalHistoryAction.STARTED, null, firstStep.getStepOrder(), "Approval started");
        return toInstanceResponse(saved);
    }

    private String approvalInstanceName(
        String normalizedModule,
        ApprovalModuleAdapter adapter,
        UUID entityId,
        String fallbackDisplayName
    ) {
        String parentName = adapter.displayName(entityId);
        if (!hasText(parentName)) {
            parentName = fallbackDisplayName;
        }
        return BusinessNameGenerator.workflowDocumentName(approvalDocumentType(normalizedModule), parentName);
    }

    private String approvalDocumentType(String normalizedModule) {
        return switch (normalizedModule) {
            case DemandPlanApprovalAdapter.MODULE_KEY -> "PlanApproval";
            case PurchaseRequestApprovalAdapter.MODULE_KEY -> "PurchaseRequestApproval";
            case PurchaseOrderApprovalAdapter.MODULE_KEY -> "PurchaseOrderApproval";
            case ServiceAcceptanceActApprovalAdapter.MODULE_KEY -> "ServiceAcceptanceActApproval";
            case ServiceFinanceApprovalAdapter.MODULE_KEY -> "ServiceFinanceApproval";
            case WorkCompletionActApprovalAdapter.MODULE_KEY -> "WorkCompletionActApproval";
            case ProcurementMethodDecisionApprovalAdapter.MODULE_KEY -> "ProcurementMethodDecisionApproval";
            case StockMovementApprovalAdapter.MODULE_KEY -> "StockMovementApproval";
            case InventoryDocumentApprovalAdapter.MODULE_KEY -> "InventoryDocumentApproval";
            default -> moduleDocumentType(normalizedModule) + "Approval";
        };
    }

    private String moduleDocumentType(String normalizedModule) {
        if (!hasText(normalizedModule)) {
            return "Approval";
        }
        StringBuilder type = new StringBuilder();
        for (String part : normalizedModule.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part.isBlank()) {
                continue;
            }
            type.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return type.isEmpty() ? "Approval" : type.toString();
    }

    @Transactional(noRollbackFor = StaleApprovalTaskException.class)
    public ApprovalInstanceResponse approveTask(UUID taskId, String comment) {
        return approveTask(taskId, new ApprovalTaskDecisionRequest(comment, null));
    }

    @Transactional(noRollbackFor = StaleApprovalTaskException.class)
    public ApprovalInstanceResponse approveTask(UUID taskId, ApprovalTaskDecisionRequest request) {
        String comment = request == null ? null : request.comment();
        ApprovalTask task = currentTask(taskId);
        assertCanAct(task);
        ApprovalInstance instance = findInstance(task.getApprovalInstanceId());
        ensureApprovalTaskActive(task, instance);
        if (isParallelAgreementTask(instance, task)) {
            var parallelStep = stepRepository.findByIdAndIsDeletedFalse(task.getApprovalFlowStepId());
            if (parallelStep != null && parallelStep.isPresent()) {
                moduleRegistry.adapter(instance.getModuleKey()).beforeApproveTask(instance.getEntityId(), parallelStep.get(), request);
            }
            task.setTaskStatus(ApprovalTaskStatus.APPROVED);
            task.setStatus(ApprovalTaskStatus.APPROVED.name());
            task.setActedAt(LocalDateTime.now());
            task.setActedBy(currentActor());
            task.setCommentText(comment);
            taskRepository.save(task);
            record(instance, ApprovalHistoryAction.APPROVED, task.getStepOrder(), task.getStepOrder(), comment);
            boolean allApproved = taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
                    TenantContext.currentTenant(), instance.getId())
                .stream()
                .filter(candidate -> candidate.getApprovalFlowStepId() == null)
                .allMatch(candidate -> candidate.getTaskStatus() == ApprovalTaskStatus.APPROVED);
            if (!allApproved) {
                return toInstanceResponse(instance);
            }
            if (isParallelThenFinal(instance)) {
                ApprovalFlowStep finalStep = orderedSteps(instance).stream()
                    .filter(ApprovalFlowStep::isApprover)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("Parallel agreement has no final approver"));
                instance.setCurrentStepOrder(finalStep.getStepOrder());
                ApprovalInstance saved = instanceRepository.save(instance);
                createTask(saved, finalStep);
                record(saved, ApprovalHistoryAction.APPROVED, 1, finalStep.getStepOrder(),
                    "All agreement participants approved");
                return toInstanceResponse(saved);
            }
            instance.setApprovalStatus(ApprovalInstanceStatus.APPROVED);
            instance.setStatus(ApprovalInstanceStatus.APPROVED.name());
            instance.setCompletedAt(LocalDateTime.now());
            instance.setCurrentStepOrder(null);
            ApprovalInstance saved = instanceRepository.save(instance);
            moduleRegistry.adapter(instance.getModuleKey()).markApproved(instance.getEntityId(), comment);
            record(saved, ApprovalHistoryAction.FINALIZED, task.getStepOrder(), null, "Parallel approval finalized");
            return toInstanceResponse(saved);
        }
        ApprovalFlowStep step = stepRepository.findByIdAndIsDeletedFalse(task.getApprovalFlowStepId())
            .orElseThrow(() -> new NotFoundException("Approval step not found: " + task.getApprovalFlowStepId()));
        if (step.isCommentRequired() && (comment == null || comment.isBlank())) {
            throw new BusinessException("Comment is required for this approval step");
        }
        moduleRegistry.adapter(instance.getModuleKey()).beforeApproveTask(instance.getEntityId(), step, request);
        task.setTaskStatus(ApprovalTaskStatus.APPROVED);
        task.setStatus(ApprovalTaskStatus.APPROVED.name());
        task.setActedAt(LocalDateTime.now());
        task.setActedBy(currentActor());
        task.setCommentText(comment);
        taskRepository.save(task);
        List<ApprovalFlowStep> steps = orderedSteps(instance);
        ApprovalFlowStep next = steps.stream()
            .filter(candidate -> candidate.getStepOrder() > task.getStepOrder())
            .min(Comparator.comparing(ApprovalFlowStep::getStepOrder))
            .orElse(null);
        if (next == null) {
            instance.setApprovalStatus(ApprovalInstanceStatus.APPROVED);
            instance.setStatus(ApprovalInstanceStatus.APPROVED.name());
            instance.setCompletedAt(LocalDateTime.now());
            instance.setCurrentStepOrder(null);
            ApprovalInstance saved = instanceRepository.save(instance);
            moduleRegistry.adapter(instance.getModuleKey()).markApproved(instance.getEntityId(), comment);
            record(saved, ApprovalHistoryAction.APPROVED, task.getStepOrder(), null, comment);
            record(saved, ApprovalHistoryAction.FINALIZED, task.getStepOrder(), null, "Approval finalized");
            return toInstanceResponse(saved);
        }
        instance.setCurrentStepOrder(next.getStepOrder());
        ApprovalInstance saved = instanceRepository.save(instance);
        createTask(saved, next);
        syncSubmittedState(saved.getModuleKey(), saved.getEntityId());
        record(saved, ApprovalHistoryAction.APPROVED, task.getStepOrder(), next.getStepOrder(), comment);
        return toInstanceResponse(saved);
    }

    @Transactional(noRollbackFor = StaleApprovalTaskException.class)
    public ApprovalInstanceResponse rejectTask(UUID taskId, String comment) {
        ApprovalTask task = currentTask(taskId);
        assertCanAct(task);
        ApprovalInstance instance = findInstance(task.getApprovalInstanceId());
        ensureApprovalTaskActive(task, instance);
        if (isParallelAgreementTask(instance, task)) {
            task.setTaskStatus(ApprovalTaskStatus.REJECTED);
            task.setStatus(ApprovalTaskStatus.REJECTED.name());
            task.setActedAt(LocalDateTime.now());
            task.setActedBy(currentActor());
            task.setCommentText(comment);
            taskRepository.save(task);
            cancelPendingTasks(instance.getId());
            instance.setApprovalStatus(ApprovalInstanceStatus.REJECTED);
            instance.setStatus(ApprovalInstanceStatus.REJECTED.name());
            instance.setCompletedAt(LocalDateTime.now());
            instance.setCurrentStepOrder(null);
            ApprovalInstance saved = instanceRepository.save(instance);
            moduleRegistry.adapter(instance.getModuleKey()).markRejected(instance.getEntityId(), comment);
            record(saved, ApprovalHistoryAction.REJECTED, task.getStepOrder(), null, comment);
            return toInstanceResponse(saved);
        }
        ApprovalFlowStep step = stepRepository.findByIdAndIsDeletedFalse(task.getApprovalFlowStepId())
            .orElseThrow(() -> new NotFoundException("Approval step not found: " + task.getApprovalFlowStepId()));
        if (step.isCommentRequired() && (comment == null || comment.isBlank())) {
            throw new BusinessException("Comment is required for this approval step");
        }
        task.setTaskStatus(ApprovalTaskStatus.REJECTED);
        task.setStatus(ApprovalTaskStatus.REJECTED.name());
        task.setActedAt(LocalDateTime.now());
        task.setActedBy(currentActor());
        task.setCommentText(comment);
        taskRepository.save(task);
        cancelPendingTasks(instance.getId());
        instance.setApprovalStatus(ApprovalInstanceStatus.REJECTED);
        instance.setStatus(ApprovalInstanceStatus.REJECTED.name());
        instance.setCompletedAt(LocalDateTime.now());
        instance.setCurrentStepOrder(null);
        ApprovalInstance saved = instanceRepository.save(instance);
        moduleRegistry.adapter(instance.getModuleKey()).markRejected(instance.getEntityId(), comment);
        record(saved, ApprovalHistoryAction.REJECTED, task.getStepOrder(), null, comment);
        return toInstanceResponse(saved);
    }

    @Transactional
    public ApprovalInstanceResponse returnTask(UUID taskId, Integer returnToStepOrder, String comment) {
        ApprovalTask task = currentTask(taskId);
        assertCanAct(task);
        ApprovalInstance instance = findInstance(task.getApprovalInstanceId());
        moduleRegistry.adapter(instance.getModuleKey()).lockApprovalRevision(instance.getEntityId(), instance.getId(), task.getId());
        if (isParallelAgreementTask(instance, task)) {
            throw new BusinessException("Parallel approval tasks cannot be returned");
        }
        ApprovalFlowStep currentStep = stepRepository.findByIdAndIsDeletedFalse(task.getApprovalFlowStepId())
            .orElseThrow(() -> new NotFoundException("Approval step not found: " + task.getApprovalFlowStepId()));
        if (!currentStep.isCanReturn()) {
            throw new BusinessException("This approval step cannot return to earlier steps");
        }
        if (returnToStepOrder == null || returnToStepOrder >= task.getStepOrder()) {
            throw new BusinessException("Return target must be an earlier step");
        }
        if (comment == null || comment.isBlank()) {
            throw new BusinessException("Comment is required when returning an approval task");
        }
        ApprovalFlowStep targetStep = orderedSteps(instance).stream()
            .filter(step -> step.getStepOrder().equals(returnToStepOrder))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Return target step does not exist"));
        task.setTaskStatus(ApprovalTaskStatus.RETURNED);
        task.setStatus(ApprovalTaskStatus.RETURNED.name());
        task.setActedAt(LocalDateTime.now());
        task.setActedBy(currentActor());
        task.setCommentText(comment);
        taskRepository.save(task);
        cancelPendingTasks(instance.getId());
        instance.setCurrentStepOrder(targetStep.getStepOrder());
        instance.setApprovalStatus(ApprovalInstanceStatus.RETURNED);
        instance.setStatus(ApprovalInstanceStatus.RETURNED.name());
        ApprovalInstance saved = instanceRepository.save(instance);
        moduleRegistry.adapter(instance.getModuleKey()).onReturned(instance.getEntityId(), comment);
        record(saved, ApprovalHistoryAction.RETURNED, task.getStepOrder(), targetStep.getStepOrder(), comment);
        return toInstanceResponse(saved);
    }

    @Transactional
    public ApprovalInstanceResponse cancelOperation(String moduleKey, UUID entityId) {
        String normalizedModule = ApprovalModuleRegistry.normalize(moduleKey);
        ApprovalModuleAdapter adapter = moduleRegistry.adapter(normalizedModule);
        String requester = adapter.requesterUsername(entityId);
        if (requester == null || !requester.equalsIgnoreCase(currentActor())) {
            throw new BusinessException("Only the requester can cancel this operation");
        }
        ApprovalInstance instance = instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), adapter.companyId(entityId), normalizedModule, entityId
        ).orElseThrow(() -> new NotFoundException("Approval instance not found for operation"));
        if (instance.getApprovalStatus() != ApprovalInstanceStatus.PENDING
            && instance.getApprovalStatus() != ApprovalInstanceStatus.RETURNED
            && instance.getApprovalStatus() != ApprovalInstanceStatus.DRAFT) {
            throw new BusinessException("Only a pending or returned operation can be cancelled");
        }
        cancelPendingTasks(instance.getId());
        instance.setApprovalStatus(ApprovalInstanceStatus.CANCELLED);
        instance.setStatus(ApprovalInstanceStatus.CANCELLED.name());
        instance.setCompletedAt(LocalDateTime.now());
        instance.setCurrentStepOrder(null);
        ApprovalInstance saved = instanceRepository.save(instance);
        adapter.onCancelled(entityId, "Cancelled by requester");
        record(saved, ApprovalHistoryAction.CANCELLED, null, null, "Cancelled by requester");
        return toInstanceResponse(saved);
    }

    @Transactional(readOnly = true)
    public ApprovalInstanceResponse currentInstance(String moduleKey, UUID entityId) {
        String normalizedModule = ApprovalModuleRegistry.normalize(moduleKey);
        return instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), normalizedModule, entityId)
            .map(this::toInstanceResponse)
            .orElseThrow(() -> new NotFoundException("Approval instance not found"));
    }

    /** Preview the route submission would select, without creating an instance or tasks. */
    @Transactional(readOnly = true)
    public ApprovalFlowPreviewResponse previewFlow(String moduleKey, UUID entityId) {
        String normalizedModule = ApprovalModuleRegistry.normalize(moduleKey);
        ApprovalModuleAdapter adapter = moduleRegistry.adapter(normalizedModule);
        if (entityId == null && !StockMovementApprovalAdapter.MODULE_KEY.equals(normalizedModule)
            && !TransferApproveOperationHandler.MODULE_KEY.equals(normalizedModule)) {
            throw new BusinessException("Document ID is required to preview this approval flow");
        }
        UUID companyId = entityId == null ? CompanyContext.currentCompany() : adapter.companyId(entityId);
        if (!AccessScope.canAccessCompany(companyId)) {
            throw new NotFoundException("Document not found");
        }
        if (entityId != null) {
            adapter.requireDetailRead(entityId);
        }
        UUID departmentId = entityId == null ? null : adapter.departmentId(entityId);
        boolean usesRequestType = adapter.usesRequestType();
        RequestType requestType = usesRequestType ? adapter.requestType(entityId) : null;
        ApprovalFlow flow = latestFlow(normalizedModule, companyId, departmentId, usesRequestType, requestType);
        List<ApprovalStepProgressView> steps;
        if (usesParallelAgreement(flow.getFlowType())) {
            if (parallelAssigneeRepository == null) {
                throw new BusinessException("Parallel approval assignee repository is unavailable");
            }
            steps = new ArrayList<>(parallelAssigneeRepository
                .findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByAssigneeUsername(TenantContext.currentTenant(), flow.getId())
                .stream()
                .map(assignee -> new ApprovalStepProgressView(
                    1, null, ApprovalAssigneeType.USER, assignee.getAssigneeUsername(),
                    approvalUserDisplayName(assignee.getAssigneeUserId(), assignee.getAssigneeUsername()),
                    null, null, null, null, null, flow.getFlowType() == ApprovalFlowType.PARALLEL_ALL))
                .toList());
            if (flow.getFlowType() == ApprovalFlowType.PARALLEL_THEN_FINAL) {
                stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(TenantContext.currentTenant(), flow.getId())
                    .stream().filter(ApprovalFlowStep::isApprover).findFirst()
                    .ifPresent(step -> steps.add(previewStep(step)));
            }
        } else {
            steps = stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(TenantContext.currentTenant(), flow.getId())
                .stream().map(this::previewStep).toList();
        }
        return new ApprovalFlowPreviewResponse(flow.getName(), steps);
    }

    private ApprovalStepProgressView previewStep(ApprovalFlowStep step) {
        String username = step.getAssigneeUsername();
        if (!hasText(username)) {
            username = approvalUsername(step.getAssigneeUserId());
        }
        return new ApprovalStepProgressView(
            step.getStepOrder(), null, step.getAssigneeType(), username,
            approvalUserDisplayName(step.getAssigneeUserId(), username), step.getAssigneeRole(),
            null, null, null, null, step.isApprover());
    }

    @Transactional
    public void resetForResubmission(String moduleKey, UUID entityId, String comment) {
        String normalizedModule = ApprovalModuleRegistry.normalize(moduleKey);
        ApprovalInstance instance = instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), normalizedModule, entityId)
            .orElse(null);
        if (instance == null || instance.getApprovalStatus() == ApprovalInstanceStatus.DRAFT) {
            return;
        }
        ApprovalInstanceStatus currentStatus = instance.getApprovalStatus();
        if (currentStatus != ApprovalInstanceStatus.PENDING
            && currentStatus != ApprovalInstanceStatus.APPROVED
            && currentStatus != ApprovalInstanceStatus.REJECTED) {
            throw new BusinessException("Approval instance cannot be reset from status " + currentStatus);
        }

        Integer previousStepOrder = instance.getCurrentStepOrder();
        cancelPendingTasks(instance.getId());
        instance.setApprovalStatus(ApprovalInstanceStatus.DRAFT);
        instance.setStatus(ApprovalInstanceStatus.DRAFT.name());
        instance.setCurrentStepOrder(null);
        instance.setStartedAt(null);
        instance.setCompletedAt(null);
        ApprovalInstance saved = instanceRepository.save(instance);
        record(
            saved,
            ApprovalHistoryAction.CANCELLED,
            previousStepOrder,
            null,
            comment == null || comment.isBlank() ? "Approval reset for resubmission" : comment
        );
    }

    @Transactional(readOnly = true)
    public List<ApprovalHistoryResponse> history(UUID instanceId) {
        findInstance(instanceId);
        return historyRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByActionAtAsc(TenantContext.currentTenant(), instanceId)
            .stream()
            .map(this::toHistoryResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ApprovalTaskResponse> myTasks() {
        return myTasks(ApprovalTaskStatus.PENDING.name());
    }

    @Transactional(readOnly = true)
    public List<ApprovalTaskResponse> myTasks(String status) {
        return findMyTasks(parseTaskStatusFilter(status)).stream()
            .map(this::toTaskResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public Page<ApprovalTaskResponse> myTasks(Pageable pageable) {
        return myTasks(pageable, ApprovalTaskStatus.PENDING.name());
    }

    @Transactional(readOnly = true)
    public Page<ApprovalTaskResponse> myTasks(Pageable pageable, String status) {
        return myTasks(pageable, status, null);
    }

    @Transactional(readOnly = true)
    public Page<ApprovalTaskResponse> myTasks(Pageable pageable, String status, UUID taskId) {
        if (worklistQuery != null) {
            return myTasks(
                pageable,
                new ApprovalWorklistFilter(taskId, null, null, null, status, null, null, null, null)
            );
        }
        List<ApprovalTask> tasks = findMyTasks(parseTaskStatusFilter(status)).stream()
            .filter(task -> taskId == null || taskId.equals(task.getId()))
            .filter(task -> parseTaskStatusFilter(status) != ApprovalTaskStatus.PENDING || isPendingTaskVisible(task))
            .toList();
        int start = Math.toIntExact(Math.min(pageable.getOffset(), tasks.size()));
        int end = Math.min(start + pageable.getPageSize(), tasks.size());
        List<ApprovalTaskResponse> content = tasks.subList(start, end).stream()
            .map(this::toTaskResponse)
            .toList();
        return new PageImpl<>(content, pageable, tasks.size());
    }

    @Transactional(readOnly = true)
    public Page<ApprovalTaskResponse> myTasks(Pageable pageable, ApprovalWorklistFilter filter) {
        if (worklistQuery == null) {
            String status = filter == null ? ApprovalTaskStatus.PENDING.name() : filter.status();
            UUID taskId = filter == null ? null : filter.taskId();
            return myTasks(pageable, status, taskId);
        }
        LocalDateTime asOf = LocalDateTime.now();
        ApprovalWorklistQuery.Filter queryFilter = toQueryFilter(filter, asOf);
        return worklistQuery.search(currentActorScope(), queryFilter, pageable).map(this::toTaskResponse);
    }

    @Transactional(readOnly = true)
    public ApprovalTaskSummaryResponse myTaskSummary(ApprovalWorklistFilter filter) {
        LocalDateTime asOf = LocalDateTime.now();
        if (worklistQuery == null) {
            long pending = findMyTasks(ApprovalTaskStatus.PENDING).stream()
                .filter(this::isPendingTaskVisible)
                .count();
            return new ApprovalTaskSummaryResponse(pending, 0, 0, List.of(), asOf, ApprovalWorklistQuery.SLA_HOURS);
        }
        ApprovalWorklistQuery.ActorScope actor = currentActorScope();
        ApprovalWorklistQuery.Filter requested = toQueryFilter(filter, asOf);
        ApprovalWorklistQuery.Filter pending = new ApprovalWorklistQuery.Filter(
            requested.taskId(), requested.entityId(), requested.search(), requested.moduleKey(), Set.of(ApprovalTaskStatus.PENDING),
            requested.createdFrom(), requested.createdTo(), null, null, asOf
        );
        long pendingCount = worklistQuery.count(actor, pending);
        long overdueCount = worklistQuery.count(actor, withDueState(pending, ApprovalWorklistQuery.DueState.OVERDUE));
        long dueSoonCount = worklistQuery.count(actor, withDueState(pending, ApprovalWorklistQuery.DueState.DUE_SOON));
        List<ApprovalAgeBucketCount> ageBuckets = List.of(
            ageBucket(actor, pending, ApprovalWorklistQuery.AgeBucket.LT_1_DAY, "Less than 1 day"),
            ageBucket(actor, pending, ApprovalWorklistQuery.AgeBucket.DAYS_1_TO_3, "1–3 days"),
            ageBucket(actor, pending, ApprovalWorklistQuery.AgeBucket.DAYS_4_TO_7, "4–7 days"),
            ageBucket(actor, pending, ApprovalWorklistQuery.AgeBucket.GT_7_DAYS, "More than 7 days")
        );
        return new ApprovalTaskSummaryResponse(
            pendingCount, overdueCount, dueSoonCount, ageBuckets, asOf, ApprovalWorklistQuery.SLA_HOURS
        );
    }

    @Transactional(readOnly = true)
    public ApprovalTaskDetailResponse myTaskDetail(UUID taskId) {
        ApprovalTask task;
        if (worklistQuery == null) {
            task = taskRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), taskId
                )
                .filter(this::isAssignedToCurrentActor)
                .orElseThrow(() -> new NotFoundException("Approval task not found: " + taskId));
        } else {
            ApprovalWorklistQuery.Filter filter = new ApprovalWorklistQuery.Filter(
                taskId, null, null, null, Set.of(), null, null, null, null, LocalDateTime.now()
            );
            task = worklistQuery.search(currentActorScope(), filter, Pageable.ofSize(1)).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Approval task not found: " + taskId));
        }
        ApprovalTaskResponse response = toTaskResponse(task);
        List<ApprovalHistoryResponse> history = historyRepository
            .findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByActionAtAsc(
                TenantContext.currentTenant(), task.getApprovalInstanceId()
            )
            .stream()
            .map(this::toHistoryResponse)
            .toList();
        return detailAssembler.assemble(response, history);
    }

    private ApprovalAgeBucketCount ageBucket(
        ApprovalWorklistQuery.ActorScope actor,
        ApprovalWorklistQuery.Filter base,
        ApprovalWorklistQuery.AgeBucket bucket,
        String label
    ) {
        ApprovalWorklistQuery.Filter filter = new ApprovalWorklistQuery.Filter(
            base.taskId(), base.entityId(), base.search(), base.moduleKey(), base.statuses(), base.createdFrom(), base.createdTo(),
            null, bucket, base.asOf()
        );
        return new ApprovalAgeBucketCount(bucket.name(), label, worklistQuery.count(actor, filter));
    }

    private ApprovalWorklistQuery.Filter withDueState(
        ApprovalWorklistQuery.Filter base,
        ApprovalWorklistQuery.DueState dueState
    ) {
        return new ApprovalWorklistQuery.Filter(
            base.taskId(), base.entityId(), base.search(), base.moduleKey(), base.statuses(), base.createdFrom(), base.createdTo(),
            dueState, null, base.asOf()
        );
    }

    private List<ApprovalTask> findMyTasks(ApprovalTaskStatus status) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return List.of();
        }
        Set<ApprovalTask> tasks = new HashSet<>();
        if (status == null) {
            for (ApprovalTaskStatus taskStatus : ApprovalTaskStatus.values()) {
                taskRepository.findByTenantIdAndCompanyIdAndTaskStatusAndAssigneeUsernameAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), taskStatus, authentication.getName()
                ).forEach(tasks::add);
            }
        } else {
            taskRepository.findByTenantIdAndCompanyIdAndTaskStatusAndAssigneeUsernameAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), status, authentication.getName()
            ).forEach(tasks::add);
        }
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(this::normalizeRole)
            .forEach(role -> {
                if (status == null) {
                    for (ApprovalTaskStatus taskStatus : ApprovalTaskStatus.values()) {
                        taskRepository.findByTenantIdAndCompanyIdAndTaskStatusAndAssigneeRoleAndIsDeletedFalseOrderByCreatedAtDesc(
                            TenantContext.currentTenant(), CompanyContext.currentCompany(), taskStatus, role
                        ).forEach(tasks::add);
                    }
                } else {
                    taskRepository.findByTenantIdAndCompanyIdAndTaskStatusAndAssigneeRoleAndIsDeletedFalseOrderByCreatedAtDesc(
                        TenantContext.currentTenant(), CompanyContext.currentCompany(), status, role
                    ).forEach(tasks::add);
                }
            });
        return tasks.stream()
            .sorted(Comparator.comparing(ApprovalTask::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private boolean isPendingTaskVisible(ApprovalTask task) {
        if (task.getTaskStatus() != ApprovalTaskStatus.PENDING) {
            return true;
        }
        ApprovalInstance instance = instanceRepository.findByIdAndIsDeletedFalse(task.getApprovalInstanceId()).orElse(null);
        return isApprovalTaskActive(instance);
    }

    private ApprovalTaskStatus parseTaskStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return ApprovalTaskStatus.PENDING;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return null;
        }
        try {
            return ApprovalTaskStatus.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unsupported approval task status filter: " + status);
        }
    }

    private ApprovalWorklistQuery.Filter toQueryFilter(ApprovalWorklistFilter filter, LocalDateTime asOf) {
        ApprovalWorklistFilter safe = filter == null
            ? new ApprovalWorklistFilter(null, null, null, null, ApprovalTaskStatus.PENDING.name(), null, null, null, null)
            : filter;
        if (safe.createdFrom() != null && safe.createdTo() != null && safe.createdFrom().isAfter(safe.createdTo())) {
            throw new BusinessException("Approval task createdFrom must not be after createdTo");
        }
        ApprovalTaskStatus parsedStatus = parseTaskStatusFilter(safe.status());
        Set<ApprovalTaskStatus> statuses = parsedStatus == null ? Set.of() : Set.of(parsedStatus);
        return new ApprovalWorklistQuery.Filter(
            safe.taskId(),
            safe.entityId(),
            normalizeOptional(safe.search()),
            normalizeOptional(safe.moduleKey()),
            statuses,
            safe.createdFrom(),
            safe.createdTo(),
            parseEnum(safe.dueState(), ApprovalWorklistQuery.DueState.class, "due state"),
            parseEnum(safe.ageBucket(), ApprovalWorklistQuery.AgeBucket.class, "age bucket"),
            asOf
        );
    }

    private <E extends Enum<E>> E parseEnum(String raw, Class<E> enumType, String label) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unsupported approval task " + label + " filter: " + raw);
        }
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private ApprovalWorklistQuery.ActorScope currentActorScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return new ApprovalWorklistQuery.ActorScope(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), null, Set.of(), null
            );
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(this::normalizeRole)
            .filter(role -> role != null && !role.isBlank())
            .collect(java.util.stream.Collectors.toSet());
        UUID userId = null;
        if (userRepository != null) {
            userId = userRepository.resolveActiveUserByUsername(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    authentication.getName())
                .map(User::getId)
                .orElse(null);
        }
        return new ApprovalWorklistQuery.ActorScope(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), authentication.getName(), roles, userId
        );
    }

    @Transactional(readOnly = true)
    public boolean hasActiveFlow(String moduleKey, UUID companyId, UUID departmentId) {
        return hasActiveFlow(moduleKey, companyId, departmentId, null);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveFlow(String moduleKey, UUID companyId, UUID departmentId, RequestType requestType) {
        String normalizedModule = ApprovalModuleRegistry.normalize(moduleKey);
        ApprovalModuleAdapter adapter = moduleRegistry.adapter(normalizedModule);
        try {
            latestFlow(normalizedModule, companyId, departmentId, adapter.usesRequestType(), adapter.usesRequestType() ? requestType : null);
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    private ApprovalFlow latestFlow(String moduleKey, UUID companyId, UUID departmentId, boolean usesRequestType, RequestType requestType) {
        if (usesRequestType) {
            if (requestType == null) {
                throw new BusinessException("Request type is required for approval module " + moduleKey);
            }
            ApprovalRequestType flowRequestType = ApprovalRequestType.valueOf(requestType.name());
            if (departmentId != null) {
                var departmentFlow = flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdAndRequestTypeAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), companyId, moduleKey, departmentId, flowRequestType
                );
                if (departmentFlow.isPresent()) {
                    return departmentFlow.get();
                }
                var departmentBothFlow = flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdAndRequestTypeAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), companyId, moduleKey, departmentId, ApprovalRequestType.BOTH
                );
                if (departmentBothFlow.isPresent()) {
                    return departmentBothFlow.get();
                }
                var departmentGenericFlow = flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), companyId, moduleKey, departmentId
                );
                if (departmentGenericFlow.isPresent()) {
                    return departmentGenericFlow.get();
                }
            }
            var specificFlow = flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, moduleKey, flowRequestType
            );
            if (specificFlow.isPresent()) {
                return specificFlow.get();
            }
            var bothFlow = flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, moduleKey, ApprovalRequestType.BOTH
            );
            if (bothFlow.isPresent()) {
                return bothFlow.get();
            }
            return flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, moduleKey
            ).orElseThrow(() -> new BusinessException("No active approval flow configured for module " + moduleKey + " and request type " + requestType));
        }
        if (departmentId != null) {
            var departmentFlow = flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, moduleKey, departmentId
            );
            if (departmentFlow.isPresent()) {
                return departmentFlow.get();
            }
        }
        return flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, moduleKey
        ).orElseThrow(() -> new BusinessException("No active approval flow configured for module " + moduleKey));
    }

    private ApprovalTask currentTask(UUID taskId) {
        ApprovalTask task = taskRepository.findByIdAndIsDeletedFalse(taskId)
            .orElseThrow(() -> new NotFoundException("Approval task not found: " + taskId));
        if (!TenantContext.currentTenant().equals(task.getTenantId()) || !AccessScope.canAccessCompany(task.getCompanyId())) {
            throw new NotFoundException("Approval task not found: " + taskId);
        }
        if (task.getTaskStatus() != ApprovalTaskStatus.PENDING) {
            throw new BusinessException("Approval task is not pending");
        }
        ApprovalInstance instance = findInstance(task.getApprovalInstanceId());
        if (instance.getApprovalStatus() != ApprovalInstanceStatus.PENDING || !task.getStepOrder().equals(instance.getCurrentStepOrder())) {
            throw new BusinessException("Approval task is not the current task");
        }
        return task;
    }

    private void ensureApprovalTaskActive(ApprovalTask task, ApprovalInstance instance) {
        ApprovalModuleAdapter adapter = moduleRegistry.adapter(instance.getModuleKey());
        adapter.lockApprovalRevision(instance.getEntityId(), instance.getId(), task.getId());
        if (!Boolean.FALSE.equals(adapter.isApprovalTaskActive(instance.getEntityId()))) {
            return;
        }
        task.setTaskStatus(ApprovalTaskStatus.CANCELLED);
        task.setStatus(ApprovalTaskStatus.CANCELLED.name());
        task.setActedAt(LocalDateTime.now());
        task.setActedBy(currentActor());
        task.setCommentText(STALE_TASK_MESSAGE);
        taskRepository.save(task);
        cancelPendingTasks(instance.getId());
        instance.setApprovalStatus(ApprovalInstanceStatus.CANCELLED);
        instance.setStatus(ApprovalInstanceStatus.CANCELLED.name());
        instance.setCompletedAt(LocalDateTime.now());
        instance.setCurrentStepOrder(null);
        ApprovalInstance saved = instanceRepository.save(instance);
        record(saved, ApprovalHistoryAction.CANCELLED, task.getStepOrder(), null, STALE_TASK_MESSAGE);
        throw new StaleApprovalTaskException();
    }

    private static class StaleApprovalTaskException extends BusinessException {
        private StaleApprovalTaskException() {
            super(STALE_TASK_MESSAGE);
        }
    }

    private ApprovalInstance findInstance(UUID id) {
        ApprovalInstance instance = instanceRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Approval instance not found: " + id));
        if (!TenantContext.currentTenant().equals(instance.getTenantId()) || !AccessScope.canAccessCompany(instance.getCompanyId())) {
            throw new NotFoundException("Approval instance not found: " + id);
        }
        return instance;
    }

    private List<ApprovalFlowStep> orderedSteps(ApprovalInstance instance) {
        return stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(TenantContext.currentTenant(), instance.getApprovalFlowId());
    }

    private ApprovalFlow findFlowForInstance(ApprovalInstance instance) {
        return flowRepository.findByIdAndIsDeletedFalse(instance.getApprovalFlowId())
            .orElseThrow(() -> new NotFoundException("Approval flow not found: " + instance.getApprovalFlowId()));
    }

    private boolean isParallelAll(ApprovalInstance instance) {
        if (instance == null || instance.getApprovalFlowId() == null || flowRepository == null) {
            return false;
        }
        return flowRepository.findByIdAndIsDeletedFalse(instance.getApprovalFlowId())
            .map(flow -> flow.getFlowType() == ApprovalFlowType.PARALLEL_ALL)
            .orElse(false);
    }

    private boolean isParallelThenFinal(ApprovalInstance instance) {
        return flowType(instance) == ApprovalFlowType.PARALLEL_THEN_FINAL;
    }

    private ApprovalFlowType flowType(ApprovalInstance instance) {
        if (instance == null || instance.getApprovalFlowId() == null || flowRepository == null) {
            return ApprovalFlowType.SEQUENTIAL;
        }
        return flowRepository.findByIdAndIsDeletedFalse(instance.getApprovalFlowId())
            .map(ApprovalFlow::getFlowType)
            .orElse(ApprovalFlowType.SEQUENTIAL);
    }

    private boolean usesParallelAgreement(ApprovalFlowType type) {
        return type == ApprovalFlowType.PARALLEL_ALL || type == ApprovalFlowType.PARALLEL_THEN_FINAL;
    }

    private boolean isParallelAgreementTask(ApprovalInstance instance, ApprovalTask task) {
        ApprovalFlowType type = flowType(instance);
        return type == ApprovalFlowType.PARALLEL_ALL
            || (type == ApprovalFlowType.PARALLEL_THEN_FINAL && task != null
                && task.getApprovalFlowStepId() == null);
    }

    private void syncSubmittedState(String moduleKey, UUID entityId) {
        if (DemandPlanApprovalAdapter.MODULE_KEY.equals(moduleKey)) {
            moduleRegistry.adapter(moduleKey).markSubmitted(entityId);
        }
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
        if (step.getAssigneeType() == ApprovalAssigneeType.USER) {
            String resolvedUsername = resolveUserAssigneeUsername(task);
            if (!hasText(resolvedUsername)) {
                throw new BusinessException("Assignee username could not be resolved for user approval step "
                    + step.getStepOrder());
            }
            task.setAssigneeUsername(resolvedUsername.trim());
            if (task.getAssigneeUserId() == null && userRepository != null) {
                userRepository.resolveActiveUserByUsername(
                        TenantContext.currentTenant(),
                        instance.getCompanyId() != null ? instance.getCompanyId() : CompanyContext.currentCompany(),
                        resolvedUsername)
                    .ifPresent(user -> task.setAssigneeUserId(user.getId()));
            }
        }
        ApprovalTask saved = taskRepository.save(task);
        notifyTaskAssigned(instance, saved);
    }

    private void ensurePendingTasks(ApprovalInstance instance) {
        boolean hasPendingTask = taskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instance.getId(), ApprovalTaskStatus.PENDING
        ).isPresent();
        if (hasPendingTask) {
            return;
        }
        ApprovalFlow flow = findFlowForInstance(instance);
        if (usesParallelAgreement(flow.getFlowType()) && Integer.valueOf(1).equals(instance.getCurrentStepOrder())) {
            createParallelTasks(instance, flow);
            record(instance, ApprovalHistoryAction.STARTED, null, 1, "Pending approval tasks recovered");
            return;
        }
        Integer currentStepOrder = instance.getCurrentStepOrder();
        ApprovalFlowStep currentStep = orderedSteps(instance).stream()
            .filter(step -> currentStepOrder == null || currentStepOrder.equals(step.getStepOrder()))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Approval flow has no current step"));
        instance.setCurrentStepOrder(currentStep.getStepOrder());
        ApprovalInstance saved = instanceRepository.save(instance);
        createTask(saved, currentStep);
        record(saved, ApprovalHistoryAction.STARTED, null, currentStep.getStepOrder(), "Pending approval task recovered");
    }

    private void createParallelTasks(ApprovalInstance instance, ApprovalFlow flow) {
        if (parallelAssigneeRepository == null) {
            throw new BusinessException("Parallel approval assignee repository is unavailable");
        }
        List<ApprovalFlowParallelAssignee> assignees =
            parallelAssigneeRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByAssigneeUsername(
                TenantContext.currentTenant(), flow.getId());
        if (assignees.isEmpty()) {
            throw new BusinessException("Parallel approval flow has no assignees");
        }
        List<ApprovalTask> existingTasks = taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instance.getId());
        Set<String> existingPendingOrApprovedUsernames = new HashSet<>();
        Set<UUID> existingPendingOrApprovedUserIds = new HashSet<>();
        for (ApprovalTask existing : existingTasks) {
            if (existing.getTaskStatus() != ApprovalTaskStatus.PENDING
                    && existing.getTaskStatus() != ApprovalTaskStatus.APPROVED) {
                continue;
            }
            if (hasText(existing.getAssigneeUsername())) {
                existingPendingOrApprovedUsernames.add(existing.getAssigneeUsername().trim().toLowerCase(Locale.ROOT));
            }
            if (existing.getAssigneeUserId() != null) {
                existingPendingOrApprovedUserIds.add(existing.getAssigneeUserId());
            }
        }
        int created = 0;
        int skipped = 0;
        for (ApprovalFlowParallelAssignee assignee : assignees) {
            ResolvedUserAssignee resolved;
            try {
                resolved = resolveParallelAssigneeIdentity(instance.getCompanyId(), assignee);
            } catch (BusinessException ex) {
                skipped++;
                log.warn("Skipping parallel assignee {} for instance {}: {}",
                    assignee.getAssigneeUsername(), instance.getId(), ex.getMessage());
                continue;
            }
            String usernameKey = resolved.username().toLowerCase(Locale.ROOT);
            if (existingPendingOrApprovedUsernames.contains(usernameKey)
                    || (resolved.userId() != null && existingPendingOrApprovedUserIds.contains(resolved.userId()))) {
                continue;
            }
            ApprovalTask task = new ApprovalTask();
            task.setCompanyId(instance.getCompanyId());
            task.setCode("AT-" + UUID.randomUUID().toString().substring(0, 8));
            task.setName(BusinessNameGenerator.workflowDocumentName("ApprovalTask", instance.getName()));
            task.setStatus(ApprovalTaskStatus.PENDING.name());
            task.setTaskStatus(ApprovalTaskStatus.PENDING);
            task.setApprovalInstanceId(instance.getId());
            task.setApprovalFlowStepId(null);
            // Keep stepOrder=1 for all parallel tasks so allowedActions/currentStepOrder checks stay valid.
            // Progress UI should treat each list item as one required approver, not an ordered step.
            task.setStepOrder(1);
            task.setAssigneeType(ApprovalAssigneeType.USER);
            task.setAssigneeUserId(resolved.userId());
            task.setAssigneeUsername(resolved.username());
            ApprovalTask saved = taskRepository.save(task);
            existingPendingOrApprovedUsernames.add(usernameKey);
            if (resolved.userId() != null) {
                existingPendingOrApprovedUserIds.add(resolved.userId());
            }
            notifyTaskAssigned(instance, saved);
            created++;
        }
        if (created == 0 && existingPendingOrApprovedUsernames.isEmpty()) {
            throw new BusinessException(skipped > 0
                ? "Parallel approval flow has no enabled assignees to create tasks for"
                : "Parallel approval flow has no assignees to create tasks for");
        }
    }

    private ResolvedUserAssignee resolveParallelAssigneeIdentity(UUID companyId, ApprovalFlowParallelAssignee assignee) {
        if (assignee == null) {
            throw new BusinessException("Parallel approval assignee is required");
        }
        if (userRepository != null && assignee.getAssigneeUserId() != null) {
            return userRepository.findByIdAndIsDeletedFalse(assignee.getAssigneeUserId())
                .filter(User::isEnabled)
                .map(user -> new ResolvedUserAssignee(user.getId(), user.getUsername().trim()))
                .orElseThrow(() -> new BusinessException(
                    "Parallel approval assignee user not found: " + assignee.getAssigneeUserId()));
        }
        if (userRepository != null && hasText(assignee.getAssigneeUsername())) {
            return userRepository.resolveActiveUserByUsername(
                    TenantContext.currentTenant(),
                    companyId != null ? companyId : CompanyContext.currentCompany(),
                    assignee.getAssigneeUsername().trim())
                .filter(User::isEnabled)
                .map(user -> new ResolvedUserAssignee(user.getId(), user.getUsername().trim()))
                .orElseGet(() -> new ResolvedUserAssignee(
                    assignee.getAssigneeUserId(),
                    assignee.getAssigneeUsername().trim()));
        }
        if (!hasText(assignee.getAssigneeUsername())) {
            throw new BusinessException("Parallel approval assignee username is required");
        }
        return new ResolvedUserAssignee(assignee.getAssigneeUserId(), assignee.getAssigneeUsername().trim());
    }

    private record ResolvedUserAssignee(UUID userId, String username) {
    }

    private void notifyTaskAssigned(ApprovalInstance instance, ApprovalTask task) {
        if (notificationService == null || task == null) {
            return;
        }
        try {
            if (task.getAssigneeType() == ApprovalAssigneeType.USER) {
                String username = resolveUserAssigneeUsername(task);
                if (hasText(username)) {
                    createAssignmentNotification(username, instance, task);
                } else {
                    log.warn("Approval task {} USER assignee has no resolvable username/userId; push skipped",
                        task.getId());
                }
                return;
            }
            if (task.getAssigneeType() == ApprovalAssigneeType.ROLE && hasText(task.getAssigneeRole())) {
                notifyRoleAssignees(instance, task);
            }
        } catch (RuntimeException ex) {
            log.warn("Approval task notification failed for task {}: {}", task.getId(), ex.getMessage());
        }
    }

    private String resolveUserAssigneeUsername(ApprovalTask task) {
        if (task.getAssigneeUserId() != null && userRepository != null) {
            return userRepository.findByIdAndIsDeletedFalse(task.getAssigneeUserId())
                .filter(User::isEnabled)
                .map(User::getUsername)
                .filter(this::hasText)
                .map(String::trim)
                .orElseGet(() -> {
                    if (userRepository.findByIdAndIsDeletedFalse(task.getAssigneeUserId()).isPresent()) {
                        log.warn("Approval USER assignee {} is disabled; falling back to stored username",
                            task.getAssigneeUserId());
                    }
                    return hasText(task.getAssigneeUsername()) ? task.getAssigneeUsername().trim() : null;
                });
        }
        if (hasText(task.getAssigneeUsername())) {
            return task.getAssigneeUsername().trim();
        }
        return null;
    }

    private void notifyRoleAssignees(ApprovalInstance instance, ApprovalTask task) {
        RoleName role = parseRole(task.getAssigneeRole());
        if (role == null || userRepository == null) {
            log.warn("Approval task {} ROLE assignee {} could not be expanded to users; push skipped",
                task.getId(), task.getAssigneeRole());
            return;
        }
        List<User> users = userRepository.findActiveCompanyUsersByRole(
            TenantContext.currentTenant(),
            task.getCompanyId() != null ? task.getCompanyId() : CompanyContext.currentCompany(),
            role
        );
        if (users.isEmpty()) {
            log.warn("Approval task {} ROLE {} has no active company users; push skipped",
                task.getId(), role);
            return;
        }
        users.stream()
            .map(User::getUsername)
            .filter(this::hasText)
            .distinct()
            .forEach(username -> createAssignmentNotification(username, instance, task));
    }

    private RoleName parseRole(String value) {
        String normalized = normalizeRole(value);
        if (!hasText(normalized)) {
            return null;
        }
        try {
            return RoleName.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void createAssignmentNotification(String recipientUsername, ApprovalInstance instance, ApprovalTask task) {
        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setRecipientUsername(recipientUsername);
        request.setTitleKey("notification.approval.task.assigned.title");
        request.setMessageKey(task.getStepOrder() == null
            ? "notification.approval.task.assigned.message.noStep"
            : "notification.approval.task.assigned.message.withStep");
        request.setTemplateArgs(Map.of(
            "document", approvalTaskDocument(instance),
            "step", task.getStepOrder() == null ? "" : task.getStepOrder()
        ));
        request.setEntityType(NotificationEntityType.APPROVAL_WORKFLOW.name());
        request.setEntityId(task.getId());
        notificationService.create(request);
    }

    private String approvalTaskDocument(ApprovalInstance instance) {
        if (hasText(instance.getName())) {
            return instance.getName();
        }
        return hasText(instance.getModuleKey()) ? instance.getModuleKey() : "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void cancelPendingTasks(UUID instanceId) {
        taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(TenantContext.currentTenant(), instanceId).stream()
            .filter(task -> task.getTaskStatus() == ApprovalTaskStatus.PENDING)
            .forEach(task -> {
                task.setTaskStatus(ApprovalTaskStatus.CANCELLED);
                task.setStatus(ApprovalTaskStatus.CANCELLED.name());
                taskRepository.save(task);
            });
    }

    void assertCanAct(ApprovalTask task) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("Authentication is required to approve task");
        }
        ApprovalInstance instance = findInstance(task.getApprovalInstanceId());
        String requester = moduleRegistry.adapter(instance.getModuleKey()).requesterUsername(instance.getEntityId());
        if (requester != null && requester.equalsIgnoreCase(authentication.getName())) {
            throw new BusinessException("Requester cannot approve their own operation");
        }
        if (task.getAssigneeType() == ApprovalAssigneeType.USER) {
            if (task.getAssigneeUsername() != null && task.getAssigneeUsername().equalsIgnoreCase(authentication.getName())) {
                return;
            }
            if (matchesCurrentUserId(task.getAssigneeUserId())) {
                return;
            }
            throw new BusinessException("Current user is not assigned to this approval task");
        }
        boolean hasRole = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(this::normalizeRole)
            .anyMatch(role -> role != null && role.equalsIgnoreCase(normalizeRole(task.getAssigneeRole())));
        if (!hasRole) {
            throw new BusinessException("Current user does not have required approval role: " + task.getAssigneeRole());
        }
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        return role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
    }

    private void record(ApprovalInstance instance, ApprovalHistoryAction action, Integer fromStep, Integer toStep, String comment) {
        ApprovalHistory history = new ApprovalHistory();
        history.setCompanyId(instance.getCompanyId());
        history.setCode("AH-" + UUID.randomUUID().toString().substring(0, 8));
        history.setName(BusinessNameGenerator.workflowDocumentName("ApprovalHistory", instance.getName()));
        history.setStatus(action.name());
        history.setApprovalInstanceId(instance.getId());
        history.setAction(action);
        history.setActor(currentActor());
        history.setFromStepOrder(fromStep);
        history.setToStepOrder(toStep);
        history.setCommentText(comment);
        history.setActionAt(LocalDateTime.now());
        historyRepository.save(history);
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    private ApprovalInstanceResponse toInstanceResponse(ApprovalInstance instance) {
        ApprovalFlow flow = flowRepository.findByIdAndIsDeletedFalse(instance.getApprovalFlowId()).orElse(null);
        List<ApprovalTask> taskEntities = taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instance.getId());
        List<ApprovalStepProgressView> progress = stepProgress(instance, taskEntities);
        List<ApprovalTaskResponse> tasks = taskEntities.stream()
            .map(task -> toTaskResponse(task, instance, progress))
            .toList();
        return new ApprovalInstanceResponse(
            instance.getId(),
            instance.getApprovalFlowId(),
            flow == null ? null : flow.getName(),
            instance.getModuleKey(),
            instance.getEntityId(),
            moduleRegistry.adapter(instance.getModuleKey()).displayName(instance.getEntityId()),
            instance.getApprovalStatus(),
            instance.getCurrentStepOrder(),
            instance.getStartedAt(),
            instance.getCompletedAt(),
            tasks
        );
    }

    private ApprovalTaskResponse toTaskResponse(ApprovalTask task) {
        ApprovalInstance instance = instanceRepository.findByIdAndIsDeletedFalse(task.getApprovalInstanceId()).orElse(null);
        List<ApprovalStepProgressView> progress = instance == null
            ? List.of()
            : stepProgress(instance, taskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(), instance.getId()));
        return toTaskResponse(task, instance, progress);
    }

    private ApprovalTaskResponse toTaskResponse(ApprovalTask task, ApprovalInstance instance, List<ApprovalStepProgressView> progress) {
        String entityName = instance == null ? null : moduleRegistry.adapter(instance.getModuleKey()).displayName(instance.getEntityId());
        ApprovalFlowStep step = task.getApprovalFlowStepId() == null
            ? null
            : stepRepository.findByIdAndIsDeletedFalse(task.getApprovalFlowStepId()).orElse(null);
        boolean canReturn = step != null && step.isCanReturn();
        DemandStockReviewResponse demandStockReview = instance == null || step == null
            ? null
            : moduleRegistry.adapter(instance.getModuleKey()).demandStockReview(instance.getEntityId(), step);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dueAt = task.getCreatedAt() == null
            ? null
            : task.getCreatedAt().plusHours(ApprovalWorklistQuery.SLA_HOURS);
        long ageHours = task.getCreatedAt() == null
            ? 0
            : Math.max(0, Duration.between(task.getCreatedAt(), now).toHours());
        String dueState = taskDueState(task, now);
        List<String> allowedActions = allowedActions(task, instance, canReturn);
        int completedSteps = progress == null
            ? 0
            : (int) progress.stream().filter(item -> item.status() == ApprovalTaskStatus.APPROVED).count();
        boolean isApprover = instance != null && instance.getApprovalStatus() == ApprovalInstanceStatus.APPROVED
            ? progress != null && progress.stream().anyMatch(item -> item.isApprover()
                && task.getStepOrder().equals(item.stepOrder()))
            : step == null ? !isParallelThenFinal(instance) : step.isApprover();
        return new ApprovalTaskResponse(
            task.getId(),
            task.getApprovalInstanceId(),
            instance == null ? null : instance.getModuleKey(),
            instance == null ? null : instance.getEntityId(),
            entityName,
            task.getStepOrder(),
            step == null ? null : step.getStepType(),
            demandStockReview,
            task.getAssigneeType(),
            task.getAssigneeRole(),
            task.getAssigneeUserId(),
            approvalUserDisplayName(task.getAssigneeUserId(), task.getAssigneeUsername()),
            task.getTaskStatus(),
            task.getActedBy(),
            task.getActedAt(),
            task.getCommentText(),
            task.getCreatedAt(),
            progress == null ? null : progress.size(),
            progress,
            step != null && step.isCommentRequired(),
            canReturn,
            isApprover,
            dueAt,
            ageHours,
            taskAgeBucket(task, now),
            dueState,
            severity(dueState),
            null,
            null,
            task.getTaskStatus() == ApprovalTaskStatus.PENDING
                ? (allowedActions.isEmpty() ? "WAIT_FOR_CURRENT_STEP" : (isApprover ? "REVIEW_AND_DECIDE" : "COMPLETE_EXECUTION"))
                : "VIEW_DECISION",
            allowedActions,
            completedSteps,
            instance == null || entityName == null ? "PARTIAL" : "READY"
        );
    }


    List<String> allowedActions(ApprovalTask task, ApprovalInstance instance, boolean canReturn) {
        if (task.getTaskStatus() != ApprovalTaskStatus.PENDING
            || instance == null
            || instance.getApprovalStatus() != ApprovalInstanceStatus.PENDING
            || !isAssignedToCurrentActor(task)) {
            return List.of();
        }
        boolean parallel = isParallelAgreementTask(instance, task);
        if (!parallel && !task.getStepOrder().equals(instance.getCurrentStepOrder())) {
            return List.of();
        }
        if (!isApprovalTaskActive(instance)) {
            return List.of();
        }
        if (!parallel && canReturn && task.getStepOrder() != null && task.getStepOrder() > 1) {
            return List.of("APPROVE", "REJECT", "RETURN");
        }
        return List.of("APPROVE", "REJECT");
    }

    private boolean isApprovalTaskActive(ApprovalInstance instance) {
        return instance != null
            && !Boolean.FALSE.equals(moduleRegistry.adapter(instance.getModuleKey()).isApprovalTaskActive(instance.getEntityId()));
    }

    private boolean isAssignedToCurrentActor(ApprovalTask task) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return false;
        }
        if (task.getAssigneeType() == ApprovalAssigneeType.USER) {
            if (task.getAssigneeUsername() != null
                    && task.getAssigneeUsername().equalsIgnoreCase(authentication.getName())) {
                return true;
            }
            return matchesCurrentUserId(task.getAssigneeUserId());
        }
        String requiredRole = normalizeRole(task.getAssigneeRole());
        return requiredRole != null && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(this::normalizeRole)
            .anyMatch(requiredRole::equalsIgnoreCase);
    }

    private boolean matchesCurrentUserId(UUID assigneeUserId) {
        if (assigneeUserId == null || userRepository == null) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return false;
        }
        return userRepository.resolveActiveUserByUsername(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                authentication.getName())
            .map(User::getId)
            .filter(assigneeUserId::equals)
            .isPresent();
    }

    private String taskDueState(ApprovalTask task, LocalDateTime asOf) {
        if (task.getTaskStatus() != ApprovalTaskStatus.PENDING) {
            return ApprovalWorklistQuery.DueState.COMPLETED.name();
        }
        if (task.getCreatedAt() == null) {
            return "UNAVAILABLE";
        }
        LocalDateTime dueAt = task.getCreatedAt().plusHours(ApprovalWorklistQuery.SLA_HOURS);
        if (dueAt.isBefore(asOf)) {
            return ApprovalWorklistQuery.DueState.OVERDUE.name();
        }
        if (!dueAt.isAfter(asOf.plusHours(ApprovalWorklistQuery.DUE_SOON_HOURS))) {
            return ApprovalWorklistQuery.DueState.DUE_SOON.name();
        }
        return ApprovalWorklistQuery.DueState.ON_TRACK.name();
    }

    private String taskAgeBucket(ApprovalTask task, LocalDateTime asOf) {
        if (task.getCreatedAt() == null) {
            return "UNAVAILABLE";
        }
        long hours = Math.max(0, Duration.between(task.getCreatedAt(), asOf).toHours());
        if (hours < 24) {
            return ApprovalWorklistQuery.AgeBucket.LT_1_DAY.name();
        }
        if (hours < 96) {
            return ApprovalWorklistQuery.AgeBucket.DAYS_1_TO_3.name();
        }
        if (hours <= 168) {
            return ApprovalWorklistQuery.AgeBucket.DAYS_4_TO_7.name();
        }
        return ApprovalWorklistQuery.AgeBucket.GT_7_DAYS.name();
    }

    private String severity(String dueState) {
        return switch (dueState) {
            case "OVERDUE" -> "CRITICAL";
            case "DUE_SOON" -> "WARNING";
            case "ON_TRACK" -> "INFO";
            default -> "NONE";
        };
    }

    /**
     * Builds an ordered view of every step in the instance's flow together with the state of the
     * latest task at that step (approved / pending / rejected / returned / cancelled), so the UI can
     * render a step-by-step progress tracker. Steps not yet reached carry a {@code null} status.
     */
    private List<ApprovalStepProgressView> stepProgress(ApprovalInstance instance, List<ApprovalTask> tasks) {
        if (instance.getApprovalStatus() == ApprovalInstanceStatus.APPROVED) {
            return approvedTaskProgress(tasks);
        }
        if (isParallelThenFinal(instance)) {
            List<ApprovalStepProgressView> progress = new ArrayList<>();
            tasks.stream()
                .filter(task -> task.getApprovalFlowStepId() == null)
                .sorted(Comparator
                    .comparing(ApprovalTask::getAssigneeUsername, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(ApprovalTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(task -> new ApprovalStepProgressView(
                    1,
                    task.getTaskStatus(),
                    task.getAssigneeType() == null ? ApprovalAssigneeType.USER : task.getAssigneeType(),
                    task.getAssigneeUsername(),
                    approvalUserDisplayName(task.getAssigneeUserId(), task.getAssigneeUsername()),
                    task.getAssigneeRole(),
                    task.getActedBy(),
                    approvalUserDisplayName(null, task.getActedBy()),
                    task.getActedAt(),
                    task.getCommentText(),
                    false
                ))
                .forEach(progress::add);
            ApprovalFlowStep finalStep = orderedSteps(instance).stream()
                .filter(ApprovalFlowStep::isApprover)
                .findFirst()
                .orElse(null);
            if (finalStep != null) {
                ApprovalTask finalTask = tasks.stream()
                    .filter(task -> finalStep.getId().equals(task.getApprovalFlowStepId()))
                    .max(Comparator.comparing(ApprovalTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
                UUID assigneeUserId = finalTask != null && finalTask.getAssigneeUserId() != null
                    ? finalTask.getAssigneeUserId() : finalStep.getAssigneeUserId();
                String assigneeUsername = finalTask != null && hasText(finalTask.getAssigneeUsername())
                    ? finalTask.getAssigneeUsername() : finalStep.getAssigneeUsername();
                progress.add(new ApprovalStepProgressView(
                    finalStep.getStepOrder(),
                    finalTask == null ? null : finalTask.getTaskStatus(),
                    finalTask == null ? finalStep.getAssigneeType() : finalTask.getAssigneeType(),
                    assigneeUsername,
                    approvalUserDisplayName(assigneeUserId, assigneeUsername),
                    finalTask != null && hasText(finalTask.getAssigneeRole())
                        ? finalTask.getAssigneeRole() : finalStep.getAssigneeRole(),
                    finalTask == null ? null : finalTask.getActedBy(),
                    finalTask == null ? null : approvalUserDisplayName(null, finalTask.getActedBy()),
                    finalTask == null ? null : finalTask.getActedAt(),
                    finalTask == null ? null : finalTask.getCommentText(),
                    true
                ));
            }
            return progress;
        }
        if (isParallelAll(instance)) {
            return tasks.stream()
                .sorted(Comparator
                    .comparing(ApprovalTask::getAssigneeUsername, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(ApprovalTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(task -> new ApprovalStepProgressView(
                    task.getStepOrder(),
                    task.getTaskStatus(),
                    task.getAssigneeType() == null ? ApprovalAssigneeType.USER : task.getAssigneeType(),
                    task.getAssigneeUsername(),
                    approvalUserDisplayName(task.getAssigneeUserId(), task.getAssigneeUsername()),
                    task.getAssigneeRole(),
                    task.getActedBy(),
                    approvalUserDisplayName(null, task.getActedBy()),
                    task.getActedAt(),
                    task.getCommentText(),
                    true
                ))
                .toList();
        }
        Map<Integer, ApprovalTask> latestByStep = new HashMap<>();
        for (ApprovalTask task : tasks) {
            Integer order = task.getStepOrder();
            if (order == null) {
                continue;
            }
            ApprovalTask existing = latestByStep.get(order);
            if (existing == null || isLaterTask(task, existing)) {
                latestByStep.put(order, task);
            }
        }
        List<ApprovalFlowStep> steps = orderedSteps(instance);
        if (steps.isEmpty() && !tasks.isEmpty()) {
            return tasks.stream()
                .filter(task -> task.getStepOrder() != null)
                .sorted(Comparator
                    .comparing(ApprovalTask::getStepOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ApprovalTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(task -> new ApprovalStepProgressView(
                    task.getStepOrder(),
                    task.getTaskStatus(),
                    task.getAssigneeType(),
                    task.getAssigneeUsername(),
                    approvalUserDisplayName(task.getAssigneeUserId(), task.getAssigneeUsername()),
                    task.getAssigneeRole(),
                    task.getActedBy(),
                    approvalUserDisplayName(null, task.getActedBy()),
                    task.getActedAt(),
                    task.getCommentText(),
                    true
                ))
                .toList();
        }
        return steps.stream()
            .map(step -> {
                ApprovalTask task = latestByStep.get(step.getStepOrder());
                UUID assigneeUserId = task != null && task.getAssigneeUserId() != null
                    ? task.getAssigneeUserId()
                    : step.getAssigneeUserId();
                String assigneeUsername = task != null && task.getAssigneeUsername() != null
                    ? task.getAssigneeUsername()
                    : step.getAssigneeUsername();
                if (!hasText(assigneeUsername)) {
                    assigneeUsername = approvalUsername(assigneeUserId);
                }
                String actedBy = task == null ? null : task.getActedBy();
                return new ApprovalStepProgressView(
                    step.getStepOrder(),
                    task == null ? null : task.getTaskStatus(),
                    task != null && task.getAssigneeType() != null
                        ? task.getAssigneeType()
                        : step.getAssigneeType(),
                    assigneeUsername,
                    approvalUserDisplayName(assigneeUserId, assigneeUsername),
                    task != null && task.getAssigneeRole() != null
                        ? task.getAssigneeRole()
                        : step.getAssigneeRole(),
                    actedBy,
                    approvalUserDisplayName(null, actedBy),
                    task == null ? null : task.getActedAt(),
                    task == null ? null : task.getCommentText(),
                    step.isApprover()
                );
            })
            .toList();

    }

    private List<ApprovalStepProgressView> approvedTaskProgress(List<ApprovalTask> tasks) {
        Map<String, ApprovalTask> latestByIdentity = new HashMap<>();
        for (ApprovalTask task : tasks) {
            if (task.getTaskStatus() != ApprovalTaskStatus.APPROVED) continue;
            String identity = task.getApprovalFlowStepId() != null
                ? "step:" + task.getApprovalFlowStepId()
                : "participant:" + (task.getAssigneeUserId() != null
                    ? task.getAssigneeUserId()
                    : hasText(task.getAssigneeUsername()) ? task.getAssigneeUsername() : task.getId());
            ApprovalTask existing = latestByIdentity.get(identity);
            if (existing == null || isLaterTask(task, existing)) {
                latestByIdentity.put(identity, task);
            }
        }
        List<ApprovalTask> approved = latestByIdentity.values().stream()
            .sorted(Comparator.comparing(ApprovalTask::getStepOrder)
                .thenComparing(ApprovalTask::getAssigneeUsername,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(ApprovalTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        boolean parallelAll = approved.stream().allMatch(task -> task.getApprovalFlowStepId() == null);
        boolean hybrid = approved.stream().anyMatch(task -> task.getApprovalFlowStepId() == null)
            && !parallelAll;
        ApprovalTask last = approved.isEmpty() ? null : approved.getLast();
        return approved.stream()
            .map(task -> new ApprovalStepProgressView(
                task.getStepOrder(), task.getTaskStatus(), task.getAssigneeType(),
                task.getAssigneeUsername(),
                approvalUserDisplayName(task.getAssigneeUserId(), task.getAssigneeUsername()),
                task.getAssigneeRole(), task.getActedBy(),
                approvalUserDisplayName(null, task.getActedBy()), task.getActedAt(),
                task.getCommentText(),
                parallelAll || (task.getApprovalFlowStepId() != null && (hybrid || task == last))
            ))
            .toList();
    }

    private String approvalUsername(UUID userId) {
        if (userId == null || userRepository == null) {
            return null;
        }
        return userRepository.findByIdAndIsDeletedFalse(userId)
            .filter(User::isEnabled)
            .map(User::getUsername)
            .filter(this::hasText)
            .map(String::trim)
            .orElse(null);
    }

    private String approvalUserDisplayName(UUID userId, String username) {
        if (userRepository == null) {
            return hasText(username) ? username.trim() : null;
        }
        User user = userId == null
            ? null
            : userRepository.findByIdAndIsDeletedFalse(userId).orElse(null);
        if (user == null && hasText(username)) {
            user = userRepository.resolveActiveUserByUsername(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    username.trim())
                .orElse(null);
        }
        if (user == null) {
            return hasText(username) ? username.trim() : null;
        }
        if (hasText(user.getFullName())) {
            return user.getFullName().trim();
        }
        if (hasText(user.getName())) {
            return user.getName().trim();
        }
        return hasText(user.getUsername()) ? user.getUsername().trim() : null;
    }

    private boolean isLaterTask(ApprovalTask candidate, ApprovalTask existing) {
        LocalDateTime candidateAt = candidate.getCreatedAt();
        LocalDateTime existingAt = existing.getCreatedAt();
        if (candidateAt != null && existingAt != null && !candidateAt.isEqual(existingAt)) {
            return candidateAt.isAfter(existingAt);
        }
        // Fall back to preferring a still-pending task so a re-issued step reflects the live state.
        return candidate.getTaskStatus() == ApprovalTaskStatus.PENDING
            && existing.getTaskStatus() != ApprovalTaskStatus.PENDING;
    }

    private ApprovalHistoryResponse toHistoryResponse(ApprovalHistory history) {
        return new ApprovalHistoryResponse(
            history.getId(),
            history.getApprovalInstanceId(),
            history.getAction(),
            history.getActor(),
            history.getFromStepOrder(),
            history.getToStepOrder(),
            history.getCommentText(),
            history.getActionAt()
        );
    }
}
