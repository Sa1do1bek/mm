package com.enterprise.mm.service.inventory;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.CommissionMemberRole;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.RoleDataScopeService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** One authority boundary shared by direct reads, projections and commands. */
@Service
@RequiredArgsConstructor
public class InventoryAccessService {
    private static final Set<String> OVERSIGHT = Set.of("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR");
    private static final Set<CommissionMemberRole> REVIEWERS = Set.of(CommissionMemberRole.CHAIRMAN, CommissionMemberRole.CHECKER, CommissionMemberRole.ACCOUNTANT);
    private final InventoryDocumentRepository documents;
    private final InventoryItemRepository items;
    private final CommissionMemberRepository members;
    private final CommissionRepository commissions;
    private final InventoryCommissionMemberSettingRepository settings;
    private final UserRepository users;
    private final RoleDataScopeService scopes;
    private final ApprovalInstanceRepository approvalInstances;
    private final ApprovalTaskRepository approvalTasks;

    public UUID currentUserId() {
        return users.findByTenantIdAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), username())
            .filter(u -> CompanyContext.currentCompany().equals(u.getCompanyId())).map(User::getId).orElse(new UUID(0, 0));
    }
    public String username() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Authentication is required");
        return auth.getName();
    }
    public boolean hasRole(String... roles) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
            .anyMatch(a -> Arrays.stream(roles).anyMatch(r -> a.getAuthority().equals("ROLE_" + r)));
    }
    public boolean readAll() { return hasRole(OVERSIGHT.toArray(String[]::new)); }
    /** Warehouse authority grants observation, independently of commission participation. */
    public boolean hasWarehouseReadAccess() {
        var scope = scopes.currentScope();
        return scope.warehouseScoped() && !scope.warehouseIds().isEmpty();
    }
    public boolean canPlan(InventoryDocument d) {
        return hasRole("ADMIN", "MM_MANAGER")
            || hasRole("WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD") && member(d).isPresent();
    }
    public void requirePlan(InventoryDocument d) {
        requireRead(d);
        if (!canPlan(d)) throw new AccessDeniedException("Inventory planning commission authority is required");
    }

    public InventoryDocument document(UUID id) {
        var d = documents.findByIdAndIsDeletedFalse(id).orElseThrow(() -> new NotFoundException("Inventory document not found"));
        requireRead(d);
        return d;
    }
    public InventoryItem line(UUID id) {
        var line = items.findByIdAndIsDeletedFalse(id).filter(i -> TenantContext.currentTenant().equals(i.getTenantId()) && CompanyContext.currentCompany().equals(i.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Inventory line not found"));
        requireRead(document(line.getInventoryDocumentId()));
        if (line.getWarehouseId() != null && !scopes.canAccessWarehouse(line.getWarehouseId())) throw new AccessDeniedException("Inventory warehouse authority is required");
        return line;
    }
    public void requireRead(InventoryDocument d) {
        username();
        if (!TenantContext.currentTenant().equals(d.getTenantId()) || !CompanyContext.currentCompany().equals(d.getCompanyId())) throw new NotFoundException("Inventory document not found");
        if (d.isGlobalWarehouse() ? scopes.currentScope().warehouseScoped()
            : d.getScopeMode() == null ? !scopes.canAccessWarehouse(d.getWarehouseId())
            : d.getResolvedWarehouseIds().isEmpty() || d.getResolvedWarehouseIds().stream().anyMatch(id -> !scopes.canAccessWarehouse(id)))
            throw new AccessDeniedException("Inventory warehouse authority is required");
        if (!readAll() && !hasWarehouseReadAccess() && member(d).isEmpty() && !canReadApproval(d)) throw new AccessDeniedException("Inventory commission membership or an assigned approval task is required");
    }
    public Optional<CommissionMember> member(InventoryDocument d) {
        if (d.getCommissionId() == null) return Optional.empty();
        var today = java.time.LocalDate.now();
        if (commissions.findByIdAndIsDeletedFalse(d.getCommissionId()).filter(c -> c.isActive() && d.getTenantId().equals(c.getTenantId()) && d.getCompanyId().equals(c.getCompanyId()) && (c.getValidFrom() == null || !c.getValidFrom().isAfter(today)) && (c.getValidTo() == null || !c.getValidTo().isBefore(today))).isEmpty()) return Optional.empty();
        return members.findFirstByTenantIdAndCommissionIdAndUserIdAndActiveTrueAndIsDeletedFalse(TenantContext.currentTenant(), d.getCommissionId(), currentUserId())
            .filter(m -> d.getCompanyId().equals(m.getCompanyId()));
    }
    public boolean canSeeSystemQuantity(InventoryDocument d) {
        if (canReadApproval(d)) return true;
        var member = member(d);
        if (member.isPresent()) {
            var setting = settings.findFirstByTenantIdAndCommissionIdAndCommissionMemberIdAndIsDeletedFalse(TenantContext.currentTenant(), d.getCommissionId(), member.get().getId());
            if (setting.isPresent()) return setting.get().isShowSystemQuantityToCounter();
            if (!REVIEWERS.contains(member.get().getMemberRole())) return false;
        }
        return readAll() || hasWarehouseReadAccess() || member.map(m -> REVIEWERS.contains(m.getMemberRole())).orElse(false);
    }
    /** A configured approver can inspect their own approval packet without joining the counting commission. */
    public boolean canReadApproval(InventoryDocument d) {
        if (d.getApprovalInstanceId() == null || !TenantContext.currentTenant().equals(d.getTenantId()) || !CompanyContext.currentCompany().equals(d.getCompanyId())) return false;
        var instance = approvalInstances.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(d.getTenantId(), d.getCompanyId(), "INVENTORY_DOCUMENT", d.getId())
            .filter(i -> d.getApprovalInstanceId().equals(i.getId()) && i.getApprovalStatus() != ApprovalInstanceStatus.DRAFT);
        if (instance.isEmpty()) return false;
        return approvalTasks.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(d.getTenantId(), instance.get().getId()).stream()
            .filter(t -> d.getCompanyId().equals(t.getCompanyId()))
            .anyMatch(t -> t.getAssigneeType() == ApprovalAssigneeType.USER
                ? username().equalsIgnoreCase(Objects.toString(t.getAssigneeUsername(), "")) || t.getAssigneeUserId() != null && t.getAssigneeUserId().equals(currentUserId())
                : t.getAssigneeRole() != null && hasRole(t.getAssigneeRole().replace("ROLE_", "").toUpperCase(Locale.ROOT)));
    }
    public boolean canCount(InventoryDocument d) {
        return hasRole("ADMIN") || member(d).map(m -> m.getMemberRole() != CommissionMemberRole.AUDITOR && m.getMemberRole() != CommissionMemberRole.SECRETARY && m.getMemberRole() != CommissionMemberRole.ACCOUNTANT).orElse(false);
    }
    public boolean canReview(InventoryDocument d) {
        return canSeeSystemQuantity(d) && (hasRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT") || member(d).map(m -> REVIEWERS.contains(m.getMemberRole())).orElse(false));
    }
    public boolean canCommissionReview(InventoryDocument d) {
        return canSeeSystemQuantity(d) && member(d).map(m -> REVIEWERS.contains(m.getMemberRole())).orElse(false);
    }
    public void requireCommissionReview(InventoryDocument d) {
        requireRead(d);
        if (!canCommissionReview(d)) throw new AccessDeniedException("An active commission reviewer is required");
    }
    public void requireCount(InventoryDocument d) { requireRead(d); if (!canCount(d)) throw new AccessDeniedException("Inventory counting commission authority is required"); }
    public void requireReview(InventoryDocument d) { requireRead(d); if (!canReview(d)) throw new AccessDeniedException("Inventory review authority is required"); }
    public void requireVisibleExport(InventoryDocument d) {
        requireRead(d);
        if (!canSeeSystemQuantity(d)) throw new AccessDeniedException("Blind counters cannot export or download inventory review documents");
    }
}
