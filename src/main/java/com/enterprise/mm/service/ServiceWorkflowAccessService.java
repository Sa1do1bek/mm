package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceFinanceApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceRequestApprovalAdapter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ServiceWorkflowAccessService {
    private static final Set<String> FULL_ACCESS_ROLES = Set.of(
        "ADMIN",
        "MM_MANAGER",
        "AUDITOR",
        "FINANCE_CONTROLLER",
        "BUDGET_CONTROLLER",
        "PROCUREMENT_MANAGER"
    );

    private final RoleDataScopeService roleDataScopeService;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final UserRepository userRepository;
    private final SupplierPortalAccountRepository supplierPortalAccountRepository;

    public boolean hasFullAccess() {
        return currentRoles().stream().anyMatch(FULL_ACCESS_ROLES::contains);
    }

    public boolean canAccessServiceRequest(ServiceRequest request) {
        if (request == null) {
            return false;
        }
        if (hasFullAccess()) {
            return true;
        }
        if (isSupplierManagerSession()) {
            return matchesLinkedSupplier(request.getSupplierId());
        }
        if (canAccessDocumentScope(request.getCreatedBy(), null, request.getDepartmentId())) {
            return true;
        }
        if (matchesCurrentUserId(request.getResponsibleUserId())) {
            return true;
        }
        return hasPendingAssignment(ServiceRequestApprovalAdapter.MODULE_KEY, request.getId());
    }

    public boolean canAccessServiceExecution(ServiceExecution execution, ServiceRequest request) {
        if (execution == null) {
            return false;
        }
        if (hasFullAccess()) {
            return true;
        }
        if (isSupplierManagerSession()) {
            return matchesLinkedSupplier(execution.getServiceRequestId() == null || request == null ? null : request.getSupplierId());
        }
        if (matchesCurrentUserId(execution.getResponsibleUserId())) {
            return true;
        }
        return canAccessServiceRequest(request);
    }

    public boolean canAccessServiceClaim(ServiceClaim claim, ServiceRequest request) {
        return canAccessServiceClaim(claim, request, claim == null ? null : claim.getServiceOrder(), null);
    }

    public boolean canAccessServiceClaim(
        ServiceClaim claim,
        ServiceRequest request,
        ServiceOrder order,
        PurchaseOrder purchaseOrder
    ) {
        if (claim == null) {
            return false;
        }
        if (hasFullAccess()) {
            return true;
        }
        if (isSupplierManagerSession()) {
            if (!matchesLinkedSupplier(claim.getSupplierId())) return false;
            if (request != null) return matchesLinkedSupplier(request.getSupplierId());
            if (order != null) return matchesLinkedSupplier(order.getSupplierId());
            return purchaseOrder != null && matchesLinkedSupplier(purchaseOrder.getSupplierId());
        }
        if (request != null) return canAccessServiceRequest(request);
        if (order != null) return canAccessServiceOrder(order, null);
        return purchaseOrder != null && canAccessPurchaseOrder(purchaseOrder);
    }

    public boolean canAccessServiceOrder(ServiceOrder order, ServiceRequest request) {
        if (order == null) {
            return false;
        }
        if (hasFullAccess()) {
            return true;
        }
        if (isSupplierManagerSession()) {
            return matchesLinkedSupplier(order.getSupplierId());
        }
        if (request != null && canAccessServiceRequest(request)) {
            return true;
        }
        if (order.getPurchaseOrder() != null && order.getPurchaseOrder().getPurchaseRequest() != null) {
            return canAccessDocumentScope(
                order.getPurchaseOrder().getPurchaseRequest().getCreatedBy(),
                order.getPurchaseOrder().getPurchaseRequest().getRequestedBy(),
                order.getPurchaseOrder().getPurchaseRequest().getDepartmentId()
            );
        }
        return false;
    }

    public boolean canAccessServiceAcceptanceAct(ServiceAcceptanceAct act, ServiceRequest request, ServiceOrder order) {
        if (act == null) {
            return false;
        }
        if (hasFullAccess()) {
            return true;
        }
        if (isSupplierManagerSession()) {
            return request != null ? matchesLinkedSupplier(request.getSupplierId())
                : order != null && matchesLinkedSupplier(order.getSupplierId());
        }
        if (request != null && canAccessServiceRequest(request)) {
            return true;
        }
        if (order != null && canAccessServiceOrder(order, request)) {
            return true;
        }
        return hasPendingAssignment(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, act.getId())
            || hasPendingAssignment(ServiceFinanceApprovalAdapter.MODULE_KEY, act.getId());
    }

    public boolean canAccessPurchaseOrder(PurchaseOrder order) {
        if (order == null) {
            return false;
        }
        if (hasFullAccess()) {
            return true;
        }
        if (isSupplierManagerSession()) {
            return matchesLinkedSupplier(order.getSupplierId());
        }
        if (order.getPurchaseRequest() != null) {
            return canAccessDocumentScope(
                order.getPurchaseRequest().getCreatedBy(),
                order.getPurchaseRequest().getRequestedBy(),
                order.getPurchaseRequest().getDepartmentId()
            );
        }
        return canAccessDocumentScope(order.getCreatedBy(), null, null);
    }

    private boolean canAccessDocumentScope(String createdBy, String owner, UUID departmentId) {
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        if (!scope.hasDocumentScope()) {
            return false;
        }
        return roleDataScopeService.canAccessDocument(createdBy, owner, departmentId);
    }

    private boolean matchesCurrentUserId(UUID userId) {
        return userId != null && userId.equals(currentUserId());
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }
        return userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), authentication.getName())
            .map(com.enterprise.mm.entity.User::getId)
            .orElse(null);
    }

    private boolean hasPendingAssignment(String moduleKey, UUID entityId) {
        if (entityId == null) {
            return false;
        }
        ApprovalInstance instance = approvalInstanceRepository.findFirstByTenantIdAndModuleKeyAndEntityIdAndApprovalStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            moduleKey,
            entityId,
            ApprovalInstanceStatus.PENDING
        ).orElse(null);
        if (instance == null || !CompanyContext.currentCompany().equals(instance.getCompanyId())) {
            return false;
        }
        List<ApprovalTask> tasks = approvalTaskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(),
            instance.getId()
        );
        return tasks.stream()
            .filter(task -> task.getTaskStatus() == ApprovalTaskStatus.PENDING)
            .filter(task -> task.getStepOrder() != null && task.getStepOrder().equals(instance.getCurrentStepOrder()))
            .anyMatch(this::matchesCurrentAssignee);
    }

    private boolean matchesCurrentAssignee(ApprovalTask task) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return false;
        }
        if (task.getAssigneeType() == ApprovalAssigneeType.USER) {
            if (task.getAssigneeUserId() != null && task.getAssigneeUserId().equals(currentUserId())) {
                return true;
            }
            return task.getAssigneeUsername() != null && task.getAssigneeUsername().equals(authentication.getName());
        }
        String requiredRole = normalizeRole(task.getAssigneeRole());
        return requiredRole != null && currentRoles().contains(requiredRole);
    }

    private Set<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority != null && authority.getAuthority() != null) {
                roles.add(normalizeRole(authority.getAuthority()));
            }
        }
        return roles;
    }

    private boolean hasRole(String role) {
        return currentRoles().contains(role);
    }

    private boolean matchesLinkedSupplier(UUID supplierId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (supplierId == null || authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return false;
        }
        String loginEmail = supplierPortalLoginEmail(authentication.getName());
        return supplierPortalAccountRepository
            .findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), loginEmail)
            .filter(account -> "ACTIVE".equalsIgnoreCase(account.getStatus()))
            .filter(account -> supplierId.equals(account.getSupplierId()))
            .isPresent();
    }

    private boolean isSupplierManagerSession() {
        return hasRole("SUPPLIER_MANAGER") || isSupplierPortalSession();
    }

    private boolean isSupplierPortalSession() {
        String username = currentUsername();
        return username != null && username.startsWith("supplier:");
    }

    private String supplierPortalLoginEmail(String username) {
        if (username == null) {
            return null;
        }
        return username.startsWith("supplier:") ? username.substring("supplier:".length()) : username;
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        String normalized = role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
        return normalized.toUpperCase(Locale.ROOT);
    }
}
