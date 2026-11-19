package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.procurement.command.entity.ProcurementWorkAssignment;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AssignmentRequest;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AssignmentResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ClaimRequest;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.EscalationRequest;
import com.enterprise.mm.procurement.command.repository.ProcurementWorkAssignmentRepository;
import com.enterprise.mm.service.AuditService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class ProcurementWorkAssignmentService {
    private static final Set<String> MANAGER_ROLES = Set.of(
        "ROLE_ADMIN", "ROLE_MM_MANAGER", "ROLE_PROCUREMENT_MANAGER");

    private final ProcurementWorkAssignmentRepository repository;
    private final AuditService auditService;

    public ProcurementWorkAssignmentService(
        ProcurementWorkAssignmentRepository repository,
        AuditService auditService
    ) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public AssignmentResponse assign(UUID purchaseRequestId, AssignmentRequest request) {
        requireManager();
        ProcurementWorkAssignment assignment = current(purchaseRequestId)
            .orElseGet(() -> newAssignment(purchaseRequestId));
        requireVersion(assignment, request.version());
        String before = snapshot(assignment);
        assignment.setOwnerUserId(request.ownerUserId());
        assignment.setOwnerUsername(request.ownerUsername());
        assignment.setOwnerRole(request.ownerRole());
        assignment.setDueDate(request.dueDate());
        assignment.setSeverity(request.severity());
        assignment.setEscalated(request.escalated());
        assignment.setReason(requireReason(request.reason()));
        return saveAndAudit(assignment, before, "ASSIGN");
    }

    @Transactional
    public AssignmentResponse claim(UUID purchaseRequestId, ClaimRequest request) {
        Authentication authentication = authentication();
        ProcurementWorkAssignment assignment = current(purchaseRequestId)
            .orElseGet(() -> newAssignment(purchaseRequestId));
        requireVersion(assignment, request == null ? null : request.version());
        if (assignment.getOwnerUsername() != null && !assignment.getOwnerUsername().isBlank()) {
            throw new OptimisticLockingFailureException("PROCUREMENT_ASSIGNMENT_CONFLICT: case is already assigned");
        }
        String before = snapshot(assignment);
        assignment.setOwnerUsername(authentication.getName());
        assignment.setOwnerRole("PROCUREMENT_SPECIALIST");
        assignment.setDueDate(request == null ? null : request.dueDate());
        assignment.setReason(request == null ? "Claimed" : requireReason(request.reason()));
        return saveAndAudit(assignment, before, "CLAIM");
    }

    @Transactional
    public AssignmentResponse escalate(UUID purchaseRequestId, EscalationRequest request) {
        requireManager();
        ProcurementWorkAssignment assignment = current(purchaseRequestId)
            .orElseThrow(() -> new OptimisticLockingFailureException(
                "PROCUREMENT_ASSIGNMENT_CONFLICT: assignment does not exist"));
        requireVersion(assignment, request.version());
        String before = snapshot(assignment);
        assignment.setEscalated(true);
        assignment.setEscalationLevel(assignment.getEscalationLevel() + 1);
        assignment.setSeverity(request.severity());
        assignment.setReason(requireReason(request.reason()));
        return saveAndAudit(assignment, before, "ESCALATE");
    }

    private java.util.Optional<ProcurementWorkAssignment> current(UUID purchaseRequestId) {
        return repository.findActiveForUpdate(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestId);
    }

    private ProcurementWorkAssignment newAssignment(UUID purchaseRequestId) {
        ProcurementWorkAssignment assignment = new ProcurementWorkAssignment();
        assignment.setPurchaseRequestId(purchaseRequestId);
        assignment.setCode("PWA-" + purchaseRequestId.toString().substring(0, 8));
        assignment.setName("Procurement assignment");
        assignment.setStatus("ACTIVE");
        assignment.setTenantId(TenantContext.currentTenant());
        assignment.setCompanyId(CompanyContext.currentCompany());
        return assignment;
    }

    private AssignmentResponse saveAndAudit(
        ProcurementWorkAssignment assignment,
        String before,
        String action
    ) {
        ProcurementWorkAssignment saved = repository.save(assignment);
        auditService.log("ProcurementWorkAssignment", saved.getId(), action,
            "before=" + before + ";after=" + snapshot(saved), null, null);
        return response(saved);
    }

    private void requireVersion(ProcurementWorkAssignment assignment, Long version) {
        if (assignment.getId() != null && (version == null || !version.equals(assignment.getVersion()))) {
            throw new OptimisticLockingFailureException(
                "PROCUREMENT_ASSIGNMENT_CONFLICT: expected version " + assignment.getVersion());
        }
    }

    private void requireManager() {
        boolean allowed = authentication().getAuthorities().stream()
            .anyMatch(authority -> MANAGER_ROLES.contains(authority.getAuthority()));
        if (!allowed) throw new AccessDeniedException("Only procurement managers can reassign work");
    }

    private Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Authentication required");
        return auth;
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Assignment reason is required");
        return reason.trim();
    }

    private String snapshot(ProcurementWorkAssignment a) {
        return "owner=" + a.getOwnerUsername() + ",role=" + a.getOwnerRole()
            + ",due=" + a.getDueDate() + ",severity=" + a.getSeverity()
            + ",escalated=" + a.isEscalated() + ",level=" + a.getEscalationLevel();
    }

    private AssignmentResponse response(ProcurementWorkAssignment a) {
        return new AssignmentResponse(
            a.getId(), a.getPurchaseRequestId(), a.getOwnerUserId(), a.getOwnerUsername(),
            a.getOwnerRole(), a.getDueDate(), a.getSeverity(), a.isEscalated(),
            a.getEscalationLevel(), a.getReason(), a.getVersion());
    }
}
