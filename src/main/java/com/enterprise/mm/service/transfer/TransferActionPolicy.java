package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.approval.ApprovedOperationContext;
import com.enterprise.mm.service.approval.TransferApproveOperationHandler;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class TransferActionPolicy {
    public enum Action { EDIT, SUBMIT, APPROVE, DISPATCH, RECEIVE, RETURN_FOR_CORRECTION, CANCEL_REMAINDER, CLOSE, CANCEL }
    private final TransferDocumentRepository repository;
    private final RoleDataScopeService scopes;
    @PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public TransferDocument require(UUID id, Action action) {
        var transfer = lockAuthorized(id, action); requireState(transfer, action); return transfer;
    }
    public TransferDocument lockAuthorized(UUID id, Action action) {
        requireRole(action);
        var transfer = repository.findScopedForUpdate(id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Transfer not found"));
        // Native integrations may update lifecycle state without changing the JPA version.
        if (action == Action.RECEIVE && entityManager != null) entityManager.refresh(transfer);
        requireAuthority(transfer, action); return transfer;
    }
    /** The completed workflow grants approval authority, without granting warehouse operations. */
    public TransferDocument requireApprovedOperation(UUID id) {
        ApprovedOperationContext.require(TransferApproveOperationHandler.MODULE_KEY, id);
        var transfer = repository.findScopedForUpdate(id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Transfer not found"));
        requireIndependentApprover(transfer);
        requireState(transfer, Action.APPROVE);
        return transfer;
    }
    public void requireAuthority(TransferDocument transfer, Action action) {
        requireRole(action);
        UUID warehouse = action == Action.RECEIVE ? transfer.getDestinationWarehouseId() : transfer.getSourceWarehouseId();
        if (!scopes.canAccessWarehouse(warehouse)) throw new AccessDeniedException("This operation requires authority at its " + (action == Action.RECEIVE ? "destination" : "source") + " warehouse");
        if (action == Action.APPROVE) requireIndependentApprover(transfer);
    }
    private void requireIndependentApprover(TransferDocument transfer) {
        if (sameActor(transfer.getCreatedBy()) || sameActor(transfer.getUpdatedBy()))
            throw new AccessDeniedException("A preparer cannot approve their own transfer document");
    }
    public void requireState(TransferDocument transfer, Action action) {
        Set<String> allowed = switch (action) {
            case EDIT, SUBMIT -> Set.of("DRAFT");
            case APPROVE -> Set.of("SUBMITTED");
            case RETURN_FOR_CORRECTION -> Set.of("SUBMITTED", "APPROVED");
            case DISPATCH, CANCEL_REMAINDER -> Set.of("APPROVED", "IN_TRANSIT", "DISCREPANCY_REVIEW");
            case RECEIVE -> Set.of("IN_TRANSIT", "DISCREPANCY_REVIEW");
            case CLOSE -> Set.of("RECEIVED", "DISCREPANCY_REVIEW");
            case CANCEL -> Set.of("DRAFT", "SUBMITTED", "APPROVED");
        };
        if (!allowed.contains(transfer.getStatus())) throw new ConflictException("Transfer " + action + " is unavailable in " + transfer.getStatus());
    }
    public boolean canAct(TransferDocument transfer, Action action) {
        try { requireAuthority(transfer, action); requireState(transfer, action); return true; }
        catch (AccessDeniedException | ConflictException unavailable) { return false; }
    }
    public void requireCreate(UUID warehouseId) {
        requireRole(Action.EDIT);
        if (!scopes.canAccessWarehouse(warehouseId)) throw new AccessDeniedException("Source warehouse authority is required to plan a transfer");
    }
    public static boolean hasRole(String... roles) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
            .anyMatch(a -> Arrays.stream(roles).anyMatch(r -> a.getAuthority().equals("ROLE_" + r)));
    }
    private void requireRole(Action action) {
        boolean operator = action == Action.DISPATCH || action == Action.RECEIVE;
        if (!hasRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD")
            && !(operator && hasRole("WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER")))
            throw new AccessDeniedException("Warehouse authority is required for this transfer operation");
    }
    private boolean sameActor(String other) { return other != null && actor().trim().equalsIgnoreCase(other.trim()); }
    private String actor() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
}
