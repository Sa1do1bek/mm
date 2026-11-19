package com.enterprise.mm.service.approval;
import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowParallelAssignee;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalStepType;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowParallelAssigneeRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowParallelAssigneeResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowHierarchyTemplateResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepResponse;
import com.enterprise.mm.payload.ApprovalFlowSearchRequest;
import com.enterprise.mm.repository.ApprovalFlowParallelAssigneeRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.security.AccessScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalFlowAdminService {
    private final ApprovalFlowRepository flowRepository;
    private final ApprovalFlowStepRepository stepRepository;
    private final ApprovalFlowParallelAssigneeRepository parallelAssigneeRepository;
    private final ApprovalModuleRegistry moduleRegistry;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    @Transactional(readOnly = true)
    public Page<ApprovalFlowResponse> search(ApprovalFlowSearchRequest request, Pageable pageable) {
        String normalizedModule = request.moduleKey() == null || request.moduleKey().isBlank() ? null : ApprovalModuleRegistry.normalize(request.moduleKey());
        String normalizedSearch = request.search() == null || request.search().isBlank() ? "" : request.search().trim().toLowerCase(Locale.ROOT);
        List<ApprovalRequestType> normalizedRequestTypes = parseRequestType(request.requestType());
        return flowRepository.search(
            TenantContext.currentTenant(),
            AccessScope.companyScoped(true) ? CompanyContext.currentCompany() : null,
            normalizedModule,
            request.departmentId(),
            normalizedRequestTypes,
            normalizedRequestTypes == null || normalizedRequestTypes.isEmpty(),
            request.active(),
            request.includeDeleted(),
            request.fromDateTime(),
            request.toDateTime(),
            !normalizedSearch.isBlank(),
            "%" + normalizedSearch + "%",
            pageable
        ).map(this::toFlowResponse);
    }

    @Transactional(readOnly = true)
    public ApprovalFlowResponse get(UUID id) {
        return toFlowResponse(findFlow(id));
    }

    @Transactional
    public ApprovalFlowResponse create(ApprovalFlowRequest request) {
        ApprovalFlow flow = new ApprovalFlow();
        apply(flow, request, true);
        if (flow.getCode() == null || flow.getCode().isBlank()) {
            flow.setCode("AF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        ApprovalFlow saved = flowRepository.save(flow);
        if (request.steps() != null) {
            request.steps().forEach(stepRequest -> saveStep(saved, stepRequest));
        }
        if (usesParallelAssignees(flow.getFlowType()) && request.parallelAssignees() != null) {
            request.parallelAssignees().forEach(assigneeRequest -> saveParallelAssignee(saved, assigneeRequest));
        }
        validateSequentialSteps(saved);
        auditService.log("ApprovalFlow", saved.getId(), "CREATE", saved.getCode(), null, null);
        return toFlowResponse(saved);
    }

    @Transactional
    public ApprovalFlowResponse update(UUID id, ApprovalFlowRequest request) {
        ApprovalFlow flow = findFlow(id);
        ApprovalFlowType previousType = flow.getFlowType() == null ? ApprovalFlowType.SEQUENTIAL : flow.getFlowType();
        apply(flow, request, false);
        ApprovalFlow saved = flowRepository.save(flow);
        reconcileFlowSides(saved, previousType, request);
        validateSequentialSteps(saved);
        auditService.log("ApprovalFlow", saved.getId(), "UPDATE", saved.getCode(), null, null);
        return toFlowResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ApprovalFlow flow = findFlow(id);
        flow.softDelete();
        flow.setActive(false);
        flowRepository.save(flow);
        auditService.log("ApprovalFlow", flow.getId(), "DELETE", flow.getCode(), null, null);
    }

    @Transactional
    public ApprovalFlowStepResponse createStep(UUID flowId, ApprovalFlowStepRequest request) {
        ApprovalFlow flow = findFlow(flowId);
        if (flow.getFlowType() == ApprovalFlowType.SEQUENTIAL) {
            archiveDeletedStepOrders(flow);
            List<ApprovalFlowStep> existing = orderedSteps(flow);
            if (hasStepOrderGaps(existing)) {
                persistStepSequence(existing);
            }
        }
        if (flow.getFlowType() == ApprovalFlowType.SEQUENTIAL && Boolean.TRUE.equals(request.isApprover())) {
            List<ApprovalFlowStep> existing = orderedSteps(flow);
            existing.forEach(step -> {
                step.setApprover(false);
                step.setCanReturn(false);
            });
            stepRepository.saveAllAndFlush(existing);
        }
        ApprovalFlowStep savedStep = saveStep(flow, request);
        validateSequentialSteps(flow);
        return toStepResponse(savedStep);
    }


    private ApprovalFlowStep saveStep(ApprovalFlow flow, ApprovalFlowStepRequest request) {
        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setApprovalFlowId(flow.getId());
        step.setCompanyId(flow.getCompanyId());
        apply(step, flow, request);
        if (flow.getFlowType() == ApprovalFlowType.SEQUENTIAL && step.isApprover()) {
            step.setStepOrder(orderedSteps(flow).stream()
                .mapToInt(ApprovalFlowStep::getStepOrder).max().orElse(0) + 1);
        }
        if (step.getCode() == null || step.getCode().isBlank()) {
            step.setCode("AFS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        step.setStatus("ACTIVE");
        ApprovalFlowStep saved = stepRepository.save(step);
        auditService.log("ApprovalFlowStep", saved.getId(), "CREATE", saved.getCode(), null, null);
        return saved;
    }

    private ApprovalFlowParallelAssignee saveParallelAssignee(ApprovalFlow flow, ApprovalFlowParallelAssigneeRequest request) {
        if (parallelAssigneeRepository == null) {
            throw new BusinessException("Parallel approval assignee repository is unavailable");
        }
        ResolvedParallelAssignee resolved = resolveParallelAssignee(flow.getCompanyId(), request);
        ApprovalFlowParallelAssignee assignee = new ApprovalFlowParallelAssignee();
        assignee.setApprovalFlowId(flow.getId());
        assignee.setCompanyId(flow.getCompanyId());
        assignee.setAssigneeUserId(resolved.userId());
        assignee.setAssigneeUsername(resolved.username());
        assignee.setCode("AFPA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        assignee.setName(resolved.username());
        assignee.setStatus("ACTIVE");
        ApprovalFlowParallelAssignee saved = parallelAssigneeRepository.save(assignee);
        auditService.log("ApprovalFlowParallelAssignee", saved.getId(), "CREATE", saved.getCode(), null, null);
        return saved;
    }

    private void replaceParallelAssignees(ApprovalFlow flow, List<ApprovalFlowParallelAssigneeRequest> requests) {
        if (parallelAssigneeRepository == null) {
            throw new BusinessException("Parallel approval assignee repository is unavailable");
        }
        List<ApprovalFlowParallelAssignee> existing = parallelAssigneeRepository
            .findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByAssigneeUsername(
                TenantContext.currentTenant(), flow.getId());
        Map<String, ApprovalFlowParallelAssignee> byUsername = new HashMap<>();
        for (ApprovalFlowParallelAssignee assignee : existing) {
            byUsername.put(assignee.getAssigneeUsername().trim().toLowerCase(Locale.ROOT), assignee);
        }
        Set<String> keep = new HashSet<>();
        for (ApprovalFlowParallelAssigneeRequest request : requests) {
            ResolvedParallelAssignee resolved = resolveParallelAssignee(flow.getCompanyId(), request);
            String key = resolved.username().toLowerCase(Locale.ROOT);
            keep.add(key);
            ApprovalFlowParallelAssignee current = byUsername.get(key);
            if (current != null) {
                current.setAssigneeUserId(resolved.userId());
                current.setAssigneeUsername(resolved.username());
                current.setName(resolved.username());
                current.setStatus("ACTIVE");
                parallelAssigneeRepository.save(current);
                auditService.log("ApprovalFlowParallelAssignee", current.getId(), "UPDATE", current.getCode(), null, null);
            } else {
                saveParallelAssignee(flow, request);
            }
        }
        for (ApprovalFlowParallelAssignee assignee : existing) {
            String key = assignee.getAssigneeUsername().trim().toLowerCase(Locale.ROOT);
            if (!keep.contains(key)) {
                assignee.softDelete();
                parallelAssigneeRepository.save(assignee);
                auditService.log("ApprovalFlowParallelAssignee", assignee.getId(), "DELETE", assignee.getCode(), null, null);
            }
        }
    }

    private void softDeleteParallelAssignees(ApprovalFlow flow) {
        if (parallelAssigneeRepository == null) {
            return;
        }
        parallelAssigneeRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByAssigneeUsername(
                TenantContext.currentTenant(), flow.getId())
            .forEach(existing -> {
                existing.softDelete();
                parallelAssigneeRepository.save(existing);
                auditService.log("ApprovalFlowParallelAssignee", existing.getId(), "DELETE", existing.getCode(), null, null);
            });
    }

    private void softDeleteSteps(ApprovalFlow flow) {
        stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(), flow.getId())
            .forEach(step -> {
                step.softDelete();
                stepRepository.save(step);
                auditService.log("ApprovalFlowStep", step.getId(), "DELETE", step.getCode(), null, null);
            });
    }

    private void reconcileFlowSides(ApprovalFlow flow, ApprovalFlowType previousType, ApprovalFlowRequest request) {
        ApprovalFlowType currentType = flow.getFlowType();
        if (!usesOrderedSteps(currentType) && usesOrderedSteps(previousType)) {
            softDeleteSteps(flow);
        }
        if (!usesParallelAssignees(currentType) && usesParallelAssignees(previousType)) {
            softDeleteParallelAssignees(flow);
        }
        if (usesParallelAssignees(currentType)) {
            if (!usesParallelAssignees(previousType)) {
                if (request.parallelAssignees() == null || request.parallelAssignees().isEmpty()) {
                    throw new BusinessException("Parallel approval flows require at least one assignee");
                }
            }
            if (request.parallelAssignees() != null) {
                replaceParallelAssignees(flow, request.parallelAssignees());
            }
        }
        if (currentType == ApprovalFlowType.PARALLEL_ALL) {
            if (previousType != ApprovalFlowType.PARALLEL_ALL) {
                softDeleteSteps(flow);
            }
        }
    }

    @Transactional
    public ApprovalFlowStepResponse updateStep(UUID id, ApprovalFlowStepRequest request) {
        ApprovalFlowStep step = findStep(id);
        ApprovalFlow flow = findFlow(step.getApprovalFlowId());
        List<ApprovalFlowStep> ordered = flow.getFlowType() == ApprovalFlowType.SEQUENTIAL
            ? orderedSteps(flow) : List.of();
        boolean selectingNewFinal = flow.getFlowType() == ApprovalFlowType.SEQUENTIAL
            && Boolean.TRUE.equals(request.isApprover())
            && !ordered.getLast().getId().equals(id);
        if (selectingNewFinal) {
            archiveDeletedStepOrders(flow);
        }
        apply(step, flow, request);
        ApprovalFlowStep saved;
        if (selectingNewFinal) {
            ordered.removeIf(item -> item.getId().equals(id));
            ordered.add(step);
            persistStepSequence(ordered);
            saved = step;
        } else {
            saved = stepRepository.save(step);
        }
        validateSequentialSteps(flow);
        auditService.log("ApprovalFlowStep", saved.getId(), "UPDATE", saved.getCode(), null, null);
        return toStepResponse(saved);
    }

    @Transactional
    public List<ApprovalFlowStepResponse> reorderSteps(UUID flowId, List<UUID> stepIds) {
        ApprovalFlow flow = findFlow(flowId);
        if (flow.getFlowType() != ApprovalFlowType.SEQUENTIAL) {
            throw new BusinessException("Only sequential approval steps can be reordered");
        }
        List<ApprovalFlowStep> existing = orderedSteps(flow);
        if (stepIds == null || stepIds.size() != existing.size()
                || new HashSet<>(stepIds).size() != existing.size()) {
            throw new BusinessException("Step order must include each approval step exactly once");
        }
        Map<UUID, ApprovalFlowStep> byId = new HashMap<>();
        existing.forEach(step -> byId.put(step.getId(), step));
        List<ApprovalFlowStep> ordered = new ArrayList<>();
        for (UUID stepId : stepIds) {
            ApprovalFlowStep step = byId.get(stepId);
            if (step == null) {
                throw new BusinessException("Step order contains a step outside this approval flow");
            }
            ordered.add(step);
        }
        archiveDeletedStepOrders(flow);
        persistStepSequence(ordered);
        validateSequentialSteps(flow);
        ordered.forEach(step -> auditService.log("ApprovalFlowStep", step.getId(), "UPDATE", step.getCode(), null, null));
        return ordered.stream().map(this::toStepResponse).toList();
    }

    private List<ApprovalFlowStep> orderedSteps(ApprovalFlow flow) {
        return stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), flow.getId());
    }

    private boolean hasStepOrderGaps(List<ApprovalFlowStep> steps) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).getStepOrder() != index + 1) return true;
        }
        return false;
    }

    private void archiveDeletedStepOrders(ApprovalFlow flow) {
        List<ApprovalFlowStep> allSteps = stepRepository.findByTenantIdAndApprovalFlowIdOrderByStepOrder(
            TenantContext.currentTenant(), flow.getId());
        int archivedOrder = Math.min(0, allSteps.stream()
            .mapToInt(ApprovalFlowStep::getStepOrder).min().orElse(0)) - 1;
        List<ApprovalFlowStep> toArchive = new ArrayList<>();
        for (ApprovalFlowStep step : allSteps) {
            if (step.isDeleted() && step.getStepOrder() > 0) {
                step.setStepOrder(archivedOrder--);
                toArchive.add(step);
            }
        }
        if (!toArchive.isEmpty()) stepRepository.saveAllAndFlush(toArchive);
    }

    private void persistStepSequence(List<ApprovalFlowStep> ordered) {
        int temporaryBase = ordered.stream().mapToInt(ApprovalFlowStep::getStepOrder).max().orElse(0) + 10000;
        for (int index = 0; index < ordered.size(); index++) {
            ordered.get(index).setStepOrder(temporaryBase + index);
        }
        stepRepository.saveAllAndFlush(ordered);
        for (int index = 0; index < ordered.size(); index++) {
            ApprovalFlowStep step = ordered.get(index);
            boolean finalStep = index == ordered.size() - 1;
            step.setStepOrder(index + 1);
            step.setApprover(finalStep);
            if (!finalStep) step.setCanReturn(false);
        }
        stepRepository.saveAllAndFlush(ordered);
    }

    @Transactional
    public void deleteStep(UUID id) {
        ApprovalFlowStep step = findStep(id);
        ApprovalFlow flow = findFlow(step.getApprovalFlowId());
        step.softDelete();
        stepRepository.saveAndFlush(step);
        archiveDeletedStepOrders(flow);
        if (flow.getFlowType() == ApprovalFlowType.SEQUENTIAL) {
            persistStepSequence(orderedSteps(flow));
        }
        validateSequentialSteps(flow);
        auditService.log("ApprovalFlowStep", step.getId(), "DELETE", step.getCode(), null, null);
    }

    private void validateSequentialSteps(ApprovalFlow flow) {
        if (flow.getFlowType() == ApprovalFlowType.PARALLEL_ALL) {
            return;
        }
        List<ApprovalFlowStep> activeSteps = stepRepository
            .findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(), flow.getId());
        if (activeSteps.isEmpty() && flow.getFlowType() == ApprovalFlowType.PARALLEL_THEN_FINAL) {
            throw new BusinessException("Parallel-then-final approval flows require one final approver");
        }
        if (activeSteps.isEmpty()) {
            return;
        }
        if (flow.getFlowType() == ApprovalFlowType.PARALLEL_THEN_FINAL) {
            if (activeSteps.size() != 1 || activeSteps.getFirst().getStepOrder() != 2
                    || !activeSteps.getFirst().isApprover()) {
                throw new BusinessException(
                    "Parallel-then-final approval flows require exactly one final approver at step 2");
            }
            if (activeSteps.getFirst().isCanReturn()) {
                throw new BusinessException("The final approver cannot return a parallel agreement stage");
            }
            return;
        }
        if (activeSteps.size() < 2) {
            throw new BusinessException("Sequential approval flows require at least 2 steps");
        }
        ApprovalFlowStep lastStep = activeSteps.get(activeSteps.size() - 1);
        if (!lastStep.isApprover()) {
            throw new BusinessException("The last step of a sequential approval flow must be the final approver");
        }
        for (int i = 0; i < activeSteps.size() - 1; i++) {
            if (activeSteps.get(i).isApprover()) {
                throw new BusinessException("Only the last step of a sequential approval flow can be the final approver");
            }
        }
    }


    @Transactional(readOnly = true)
    public List<ApprovalFlowStepResponse> steps(UUID flowId) {
        ApprovalFlow flow = findFlow(flowId);
        return stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(TenantContext.currentTenant(), flow.getId())
            .stream()
            .map(this::toStepResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public ApprovalFlowHierarchyTemplateResponse buildHierarchyTemplate(UUID departmentId, String moduleKey, ApprovalRequestType requestType) {
        if (departmentId == null) {
            throw new BusinessException("Department is required");
        }
        Department baseDepartment = departmentRepository.findByIdAndIsDeletedFalse(departmentId)
            .orElseThrow(() -> new NotFoundException("Department not found: " + departmentId));
        if (!TenantContext.currentTenant().equals(baseDepartment.getTenantId())
                || !AccessScope.canAccessCompany(baseDepartment.getCompanyId())) {
            throw new NotFoundException("Department not found: " + departmentId);
        }

        List<ApprovalFlowStepRequest> steps = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        Department current = baseDepartment;
        int order = 1;
        while (current != null) {
            UUID currentId = current.getId();
            if (currentId == null || !visited.add(currentId)) {
                throw new BusinessException("Department hierarchy cycle detected");
            }
            if (visited.size() > 50) {
                throw new BusinessException("Department hierarchy is too deep");
            }
            steps.add(hierarchyStep(
                order++,
                currentId,
                "Department head - " + current.getName(),
                RoleName.DEPARTMENT_HEAD,
                false
            ));
            current = current.getParentDepartment();
        }

        RoleName finalRole = resolveHierarchyFinalRole(moduleKey, requestType);
        steps.add(hierarchyStep(
            order,
            null,
            hierarchyFinalStepName(finalRole),
            finalRole,
            true
        ));
        return new ApprovalFlowHierarchyTemplateResponse(baseDepartment.getId(), steps);
    }

    private ApprovalFlowStepRequest hierarchyStep(
        int order,
        UUID departmentId,
        String name,
        RoleName role,
        boolean finalApprover
    ) {
        return new ApprovalFlowStepRequest(
            null,
            name,
            order,
            ApprovalStepType.STANDARD,
            null,
            departmentId,
            ApprovalAssigneeType.ROLE,
            role.name(),
            null,
            null,
            finalApprover,
            finalApprover,
            true,
            null
        );
    }

    private RoleName resolveHierarchyFinalRole(String moduleKey, ApprovalRequestType requestType) {
        String normalizedModule = moduleKey == null || moduleKey.isBlank()
            ? PurchaseRequestApprovalAdapter.MODULE_KEY
            : ApprovalModuleRegistry.normalize(moduleKey);
        if (DemandPlanApprovalAdapter.MODULE_KEY.equals(normalizedModule)) {
            return RoleName.BUDGET_CONTROLLER;
        }
        if (PurchaseRequestApprovalAdapter.MODULE_KEY.equals(normalizedModule)
                || PurchaseOrderApprovalAdapter.MODULE_KEY.equals(normalizedModule)
                || "SERVICE_REQUEST".equals(normalizedModule)
                || requestType == ApprovalRequestType.SERVICE) {
            return RoleName.PROCUREMENT_MANAGER;
        }
        return RoleName.MM_MANAGER;
    }

    private String hierarchyFinalStepName(RoleName role) {
        return switch (role) {
            case BUDGET_CONTROLLER -> "Budget controller review";
            case PROCUREMENT_MANAGER -> "Procurement manager review";
            case MM_MANAGER -> "Management review";
            default -> "Final approval";
        };
    }

    private void apply(ApprovalFlow flow, ApprovalFlowRequest request, boolean creating) {
        if (request.companyId() != null) {
            if (!AccessScope.canAccessCompany(request.companyId())) {
                throw new BusinessException("Company is outside the current access scope");
            }
            flow.setCompanyId(request.companyId());
        }
        flow.setCode(request.code());
        flow.setName(LocalizedText.ensureComplete(BusinessDisplayNamePolicy.requireHumanName(
            "Approval flow",
            request.name(),
            request.code()
        )));
        if (request.flowType() != null) {
            flow.setFlowType(request.flowType());
        } else if (creating || flow.getFlowType() == null) {
            flow.setFlowType(ApprovalFlowType.SEQUENTIAL);
        }
        String moduleKey = ApprovalModuleRegistry.normalize(request.moduleKey());
        flow.setModuleKey(moduleKey);
        ApprovalModuleAdapter adapter = moduleRegistry.adapter(moduleKey);
        flow.setDepartmentId("ASSET_ACCEPTANCE".equals(moduleKey) ? null : request.departmentId());
        flow.setRequestType(adapter.usesRequestType() ? request.requestType() : null);
        String status = request.status() == null || request.status().isBlank()
            ? (Boolean.FALSE.equals(request.active()) ? "INACTIVE" : "ACTIVE")
            : request.status().trim().toUpperCase(Locale.ROOT);
        flow.setActive(request.active() == null ? !"INACTIVE".equals(status) : request.active());
        flow.setStatus(status);
        flow.setDescription(request.description());
        validateFlowRequest(flow, request, creating);
    }

    private List<ApprovalRequestType> parseRequestType(List<String> requestTypeValues) {
        if (requestTypeValues == null || requestTypeValues.isEmpty()) {
            return null;
        }
        List<ApprovalRequestType> result = new ArrayList<>();
        for (String requestType : requestTypeValues) {
            if (requestType == null || requestType.isBlank()) {
                continue;
            }
            try {
                result.add(ApprovalRequestType.valueOf(requestType.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException ignored) {
                // Ignore unsupported or obsolete request types (e.g. WORKS)
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        return result.stream().distinct().toList();
    }

    private void apply(ApprovalFlowStep step, ApprovalFlow flow, ApprovalFlowStepRequest request) {
        step.setCode(request.code());
        step.setName(request.name() == null || request.name().isBlank() ? "Approval Step" : request.name());
        step.setStepOrder(request.stepOrder());
        ApprovalStepType stepType = request.stepType() == null ? ApprovalStepType.STANDARD : request.stepType();
        step.setStepType(stepType);
        step.setWarehouseId(stepType == ApprovalStepType.DEMAND_STOCK_CHECK ? request.warehouseId() : null);
        step.setDepartmentId(request.departmentId());
        step.setAssigneeType(request.assigneeType());
        step.setAssigneeRole(request.assigneeType() == ApprovalAssigneeType.ROLE ? normalizeRole(request.assigneeRole()) : null);
        step.setAssigneeUserId(request.assigneeType() == ApprovalAssigneeType.USER ? request.assigneeUserId() : null);
        step.setAssigneeUsername(request.assigneeType() == ApprovalAssigneeType.USER
            ? resolveStepAssigneeUsername(flow, request)
            : null);
        boolean isApprover = Boolean.TRUE.equals(request.isApprover());
        step.setApprover(isApprover);
        step.setCanReturn(Boolean.TRUE.equals(request.canReturn()));
        if (!isApprover && step.isCanReturn()) {
            throw new BusinessException("Only the final approver step can return approval flow");
        }
        step.setCommentRequired(request.commentRequired() == null || Boolean.TRUE.equals(request.commentRequired()));
        step.setDescription(request.description());
        validateStep(step, flow);
    }


    private String resolveStepAssigneeUsername(ApprovalFlow flow, ApprovalFlowStepRequest request) {
        if (request.assigneeUserId() == null) {
            return request.assigneeUsername() == null ? null : request.assigneeUsername().trim();
        }
        User user = userRepository.findByIdAndIsDeletedFalse(request.assigneeUserId())
            .filter(candidate -> candidate.isEnabled()
                && TenantContext.currentTenant().equals(candidate.getTenantId())
                && (candidate.getCompanyId() == null || candidate.getCompanyId().equals(flow.getCompanyId())))
            .orElseThrow(() -> new BusinessException(
                "Approval step assignee user not found: " + request.assigneeUserId()));
        return user.getUsername().trim();
    }

    private void validateStep(ApprovalFlowStep step, ApprovalFlow flow) {
        if (flow.getFlowType() == ApprovalFlowType.PARALLEL_ALL) {
            throw new BusinessException("Parallel approval flows cannot contain ordered steps");
        }
        if (step.getStepOrder() == null || step.getStepOrder() < 1) {
            throw new BusinessException("Step order must be greater than or equal to 1");
        }
        if (flow.getFlowType() == ApprovalFlowType.PARALLEL_THEN_FINAL
                && (step.getStepOrder() != 2 || !step.isApprover() || step.isCanReturn())) {
            throw new BusinessException(
                "Parallel-then-final approval requires one non-returnable final approver at step 2");
        }
        if (step.getStepType() == ApprovalStepType.DEMAND_STOCK_CHECK
                && !DemandPlanApprovalAdapter.MODULE_KEY.equals(flow.getModuleKey())) {
            throw new BusinessException("Demand stock check steps are only supported for demand plan approval flows");
        }
        if (step.getAssigneeType() == ApprovalAssigneeType.ROLE) {
            if (step.getAssigneeRole() == null || step.getAssigneeRole().isBlank()) {
                throw new BusinessException("Assignee role is required for role approval steps");
            }
            try {
                RoleName.valueOf(step.getAssigneeRole().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("Unsupported approval assignee role: " + step.getAssigneeRole());
            }
        }
        if (step.getAssigneeType() == ApprovalAssigneeType.USER && step.getAssigneeUserId() == null
                && (step.getAssigneeUsername() == null || step.getAssigneeUsername().isBlank())) {
            throw new BusinessException("Assignee user is required for user approval steps");
        }
    }

    private void validateFlowRequest(ApprovalFlow flow, ApprovalFlowRequest request, boolean creating) {
        if (!usesParallelAssignees(flow.getFlowType())) {
            if (request.parallelAssignees() != null && !request.parallelAssignees().isEmpty()) {
                throw new BusinessException("Sequential approval flows cannot contain parallel assignees");
            }
            return;
        }
        if (flow.getFlowType() == ApprovalFlowType.PARALLEL_ALL
                && request.steps() != null && !request.steps().isEmpty()) {
            throw new BusinessException("Parallel approval flows cannot contain ordered steps");
        }
        if (flow.getFlowType() == ApprovalFlowType.PARALLEL_THEN_FINAL && creating) {
            List<ApprovalFlowStepRequest> steps = request.steps();
            if (steps == null || steps.size() != 1 || steps.getFirst().stepOrder() != 2
                    || !Boolean.TRUE.equals(steps.getFirst().isApprover())
                    || Boolean.TRUE.equals(steps.getFirst().canReturn())) {
                throw new BusinessException(
                    "Parallel-then-final approval flows require exactly one non-returnable final approver at step 2");
            }
        }
        List<ApprovalFlowParallelAssigneeRequest> assignees = request.parallelAssignees();
        if (assignees == null || assignees.isEmpty()) {
            if (creating) {
                throw new BusinessException("Parallel approval flows require at least one assignee");
            }
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ApprovalFlowParallelAssigneeRequest assignee : assignees) {
            ResolvedParallelAssignee resolved = resolveParallelAssignee(flow.getCompanyId(), assignee);
            if (!seen.add(resolved.username().toLowerCase(Locale.ROOT))) {
                throw new BusinessException("Parallel approval assignee is duplicated: " + resolved.username());
            }
        }
    }

    private boolean usesParallelAssignees(ApprovalFlowType type) {
        return type == ApprovalFlowType.PARALLEL_ALL || type == ApprovalFlowType.PARALLEL_THEN_FINAL;
    }

    private boolean usesOrderedSteps(ApprovalFlowType type) {
        return type != ApprovalFlowType.PARALLEL_ALL;
    }

    private ResolvedParallelAssignee resolveParallelAssignee(UUID companyId, ApprovalFlowParallelAssigneeRequest request) {
        if (request == null) {
            throw new BusinessException("Parallel approval assignee is required");
        }
        if (userRepository == null) {
            String username = request.assigneeUsername() == null ? null : request.assigneeUsername().trim();
            if (username == null || username.isBlank()) {
                throw new BusinessException("Assignee username is required for parallel approval flows");
            }
            return new ResolvedParallelAssignee(request.assigneeUserId(), username);
        }
        UUID companyScope = companyId != null ? companyId : CompanyContext.currentCompany();
        if (request.assigneeUserId() != null) {
            User user = userRepository.findByIdAndIsDeletedFalse(request.assigneeUserId())
                .orElseThrow(() -> new BusinessException("Parallel approval assignee user not found: " + request.assigneeUserId()));
            if (!user.isEnabled()) {
                throw new BusinessException("Parallel approval assignee user is disabled: " + user.getUsername());
            }
            return new ResolvedParallelAssignee(user.getId(), user.getUsername().trim());
        }
        String username = request.assigneeUsername() == null ? null : request.assigneeUsername().trim();
        if (username == null || username.isBlank()) {
            throw new BusinessException("Parallel approval assignee requires assigneeUserId or assigneeUsername");
        }
        return userRepository.resolveActiveUserByUsername(TenantContext.currentTenant(), companyScope, username)
            .filter(User::isEnabled)
            .map(user -> new ResolvedParallelAssignee(user.getId(), user.getUsername().trim()))
            .orElseGet(() -> new ResolvedParallelAssignee(null, username));
    }

    private record ResolvedParallelAssignee(UUID userId, String username) {
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    private ApprovalFlow findFlow(UUID id) {
        ApprovalFlow flow = flowRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Approval flow not found: " + id));
        if (!TenantContext.currentTenant().equals(flow.getTenantId()) || !AccessScope.canAccessCompany(flow.getCompanyId())) {
            throw new NotFoundException("Approval flow not found: " + id);
        }
        return flow;
    }

    private ApprovalFlowStep findStep(UUID id) {
        ApprovalFlowStep step = stepRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Approval flow step not found: " + id));
        if (!TenantContext.currentTenant().equals(step.getTenantId()) || !AccessScope.canAccessCompany(step.getCompanyId())) {
            throw new NotFoundException("Approval flow step not found: " + id);
        }
        return step;
    }

    private ApprovalFlowResponse toFlowResponse(ApprovalFlow flow) {
        Map<String, String> names = LocalizedText.parse(flow.getName());
        boolean multilingual = LocalizedText.isMultilingual(flow.getName());
        List<ApprovalFlowStepResponse> steps = stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(), flow.getId())
            .stream()
            .map(this::toStepResponse)
            .toList();
        List<ApprovalFlowParallelAssigneeResponse> parallelAssignees = parallelAssigneeRepository == null
            ? List.of()
            : parallelAssigneeRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByAssigneeUsername(
                    TenantContext.currentTenant(), flow.getId())
                .stream()
                .map(this::toParallelAssigneeResponse)
                .toList();
        return new ApprovalFlowResponse(
            flow.getId(),
            flow.getCompanyId(),
            flow.getCode(),
            flow.getName(),
            multilingual ? names.get("uz") : flow.getName(),
            multilingual ? names.get("ru") : flow.getName(),
            multilingual ? names.get("en") : flow.getName(),
            flow.getModuleKey(),
            flow.getFlowType(),
            flow.getDepartmentId(),
            flow.getRequestType(),
            flow.isActive(),
            flow.getStatus(),
            flow.getDescription(),
            flow.getCreatedAt(),
            flow.getUpdatedAt(),
            steps,
            parallelAssignees
        );
    }

    private ApprovalFlowParallelAssigneeResponse toParallelAssigneeResponse(ApprovalFlowParallelAssignee assignee) {
        return new ApprovalFlowParallelAssigneeResponse(
            assignee.getId(),
            assignee.getApprovalFlowId(),
            assignee.getAssigneeUserId(),
            assignee.getAssigneeUsername(),
            assignee.getCode(),
            assignee.getName()
        );
    }

    private ApprovalFlowStepResponse toStepResponse(ApprovalFlowStep step) {
        return new ApprovalFlowStepResponse(
            step.getId(),
            step.getApprovalFlowId(),
            step.getStepOrder(),
            step.getStepType(),
            step.getWarehouseId(),
            step.getDepartmentId(),
            step.getAssigneeType(),
            step.getAssigneeRole(),
            step.getAssigneeUserId(),
            step.getAssigneeUsername(),
            step.isCanReturn(),
            step.isApprover(),
            step.isCommentRequired(),
            step.getCode(),
            step.getName(),
            step.getDescription()
        );
    }

}
