package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.WarehouseRoleAuthorities;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.entity.SupplyWorkAssignment;
import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.error.SupplyAssignmentConflictException;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentOwner;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.ClaimRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.EscalationRequest;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import com.enterprise.mm.supply.workspace.repository.SupplyWorkAssignmentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplyWorkAssignmentService {
    private static final Set<String> MANAGER_AUTHORITIES = Set.of(
        "ROLE_ADMIN", "ROLE_MM_MANAGER", "ROLE_PROCUREMENT_MANAGER");
    private static final List<RoleName> OWNER_ROLE_PRECEDENCE = List.of(
        RoleName.PROCUREMENT_SPECIALIST,
        RoleName.PROCUREMENT_MANAGER,
        RoleName.SUPPLIER_MANAGER,
        RoleName.WAREHOUSE_MANAGER,
        RoleName.MM_MANAGER);

    private final SupplyWorkAssignmentRepository repository;
    private final SupplyCommitmentQueryRepository commitmentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SupplyCommitmentKeyCodec keyCodec;
    private final SupplySlaPolicy slaPolicy;
    private final Clock clock;
    private final ZoneId timeZone;

    public SupplyWorkAssignmentService(
        SupplyWorkAssignmentRepository repository,
        SupplyCommitmentQueryRepository commitmentRepository,
        UserRepository userRepository,
        AuditService auditService,
        SupplyCommitmentKeyCodec keyCodec,
        SupplySlaPolicy slaPolicy,
        Clock clock,
        @Value("${app.supply-workspace.time-zone:Asia/Tashkent}") String timeZone
    ) {
        this.repository = repository;
        this.commitmentRepository = commitmentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.keyCodec = keyCodec;
        this.slaPolicy = slaPolicy;
        this.clock = clock;
        this.timeZone = ZoneId.of(timeZone);
    }

    @Transactional
    public AssignmentResponse assign(
        String encodedCommitmentKey,
        SupplyAccessScope scope,
        AssignmentRequest request
    ) {
        ScopedCommitment scoped = scoped(encodedCommitmentKey, scope);
        requireManager();
        if (request == null || request.ownerUserId() == null) {
            throw bad("SUPPLY_ASSIGNMENT_OWNER_REQUIRED", "ownerUserId is required");
        }
        String reason = requireReason(request.reason());
        ResolvedOwner owner = resolveOwner(request.ownerUserId(), scoped);
        SupplyWorkAssignment assignment = current(scoped);
        requireVersion(assignment, request.expectedVersion());
        boolean reassignment = assignment.getOwnerUserId() != null
            || hasText(assignment.getOwnerUsername());
        String before = snapshot(assignment);
        SupplySeverity currentSeverity = effectiveSeverity(assignment, scoped.commitment());
        SupplySeverity severity = request.severity() != null
            ? request.severity()
            : currentSeverity;
        if (isEscalated(assignment) && severity.ordinal() < currentSeverity.ordinal()) {
            throw conflict(
                "SUPPLY_ESCALATION_NOT_MONOTONIC",
                "severity cannot be reduced after escalation");
        }
        SupplySlaPolicy.DueDateDecision dueDate = slaPolicy.resolveDueDate(
            request.dueDate(), scoped.commitment().operationalDate(), severity, operationalDate());

        assignment.setOwnerUserId(owner.user().getId());
        assignment.setOwnerUsername(owner.user().getUsername());
        assignment.setOwnerRole(owner.role());
        assignment.setDueDate(dueDate.dueDate());
        assignment.setDueDateSource(dueDate.source());
        assignment.setSeverity(severity);
        assignment.setReason(reason);
        return saveAndAudit(
            assignment, before, reason, reassignment ? "REASSIGN" : "ASSIGN", encodedCommitmentKey);
    }

    @Transactional
    public AssignmentResponse claim(
        String encodedCommitmentKey,
        SupplyAccessScope scope,
        ClaimRequest request
    ) {
        ScopedCommitment scoped = scoped(encodedCommitmentKey, scope);
        Authentication authentication = requireAuthority("ROLE_PROCUREMENT_SPECIALIST");
        if (request == null) {
            throw bad("SUPPLY_ASSIGNMENT_REQUEST_REQUIRED", "Claim request is required");
        }
        String reason = requireReason(request.reason());
        ResolvedOwner owner = resolveAuthenticatedSpecialist(authentication, scoped);
        SupplyWorkAssignment assignment = current(scoped);
        requireVersion(assignment, request.expectedVersion());
        if (assignment.getOwnerUserId() != null || hasText(assignment.getOwnerUsername())) {
            throw conflict("SUPPLY_ASSIGNMENT_CONFLICT", "commitment is already assigned");
        }
        String before = snapshot(assignment);
        SupplySeverity severity = effectiveSeverity(assignment, scoped.commitment());
        SupplySlaPolicy.DueDateDecision dueDate = slaPolicy.resolveDueDate(
            request.dueDate(), scoped.commitment().operationalDate(), severity, operationalDate());
        assignment.setOwnerUserId(owner.user().getId());
        assignment.setOwnerUsername(owner.user().getUsername());
        assignment.setOwnerRole(owner.role());
        assignment.setDueDate(dueDate.dueDate());
        assignment.setDueDateSource(dueDate.source());
        assignment.setSeverity(severity);
        assignment.setReason(reason);
        return saveAndAudit(assignment, before, reason, "CLAIM", encodedCommitmentKey);
    }

    @Transactional
    public AssignmentResponse escalate(
        String encodedCommitmentKey,
        SupplyAccessScope scope,
        EscalationRequest request
    ) {
        ScopedCommitment scoped = scoped(encodedCommitmentKey, scope);
        requireManager();
        if (request == null) {
            throw bad("SUPPLY_ASSIGNMENT_REQUEST_REQUIRED", "Escalation request is required");
        }
        String reason = requireReason(request.reason());
        SupplyWorkAssignment assignment = repository.findActiveForUpdate(
                scope.tenantId(), scoped.commitment().companyId(), scoped.key().canonical())
            .orElseThrow(() -> conflict(
                "SUPPLY_ASSIGNMENT_CONFLICT", "assignment does not exist"));
        requireVersion(assignment, request.expectedVersion());
        SupplySeverity currentSeverity = effectiveSeverity(assignment, scoped.commitment());
        SupplySeverity requestedSeverity = request.severity() == null
            ? currentSeverity
            : request.severity();
        if (requestedSeverity.ordinal() < currentSeverity.ordinal()) {
            throw conflict(
                "SUPPLY_ESCALATION_NOT_MONOTONIC", "severity cannot be reduced during escalation");
        }
        String before = snapshot(assignment);
        int nextLevel = Math.max(0, assignment.getEscalationLevel()) + 1;
        SupplyEscalationState nextState = stateFor(nextLevel);
        if (nextState.ordinal() < state(assignment).ordinal()) {
            throw conflict(
                "SUPPLY_ESCALATION_NOT_MONOTONIC", "escalation state cannot be reduced");
        }
        assignment.setSeverity(requestedSeverity);
        assignment.setEscalationLevel(nextLevel);
        assignment.setEscalationState(nextState);
        assignment.setReason(reason);
        return saveAndAudit(assignment, before, reason, "ESCALATE", encodedCommitmentKey);
    }

    private ScopedCommitment scoped(String encodedCommitmentKey, SupplyAccessScope scope) {
        if (scope == null) {
            throw new AccessDeniedException("Authenticated supply scope is required");
        }
        SupplyCommitmentKey key;
        try {
            key = keyCodec.decode(encodedCommitmentKey);
        } catch (IllegalArgumentException exception) {
            throw bad("SUPPLY_COMMITMENT_KEY_INVALID", exception.getMessage());
        }
        SupplyCommitmentRecord commitment = commitmentRepository.findScoped(key, scope, clock.instant())
            .filter(record -> scope.allowsCompany(record.companyId()))
            .orElseThrow(() -> new NotFoundException(
                "Supply commitment was not found in authenticated scope",
                "supply.workspace.commitment.notFound",
                key.canonical()));
        return new ScopedCommitment(scope.tenantId(), key, commitment);
    }

    private SupplyWorkAssignment current(ScopedCommitment scoped) {
        return repository.findActiveForUpdate(
                scoped.tenantId(),
                scoped.commitment().companyId(),
                scoped.key().canonical())
            .orElseGet(() -> newAssignment(scoped));
    }

    private SupplyWorkAssignment newAssignment(ScopedCommitment scoped) {
        SupplyWorkAssignment assignment = new SupplyWorkAssignment();
        assignment.setTenantId(scoped.tenantId());
        assignment.setCompanyId(scoped.commitment().companyId());
        assignment.setCommitmentKey(scoped.key().canonical());
        assignment.setCommitmentKind(scoped.key().kind());
        assignment.setCode("SWA-" + scoped.key().id().toString().substring(0, 8));
        assignment.setName("Supply assignment");
        assignment.setStatus("ACTIVE");
        assignment.setEscalationState(SupplyEscalationState.NONE);
        return assignment;
    }

    private ResolvedOwner resolveOwner(UUID ownerUserId, ScopedCommitment scoped) {
        User user = userRepository.findByIdAndIsDeletedFalse(ownerUserId)
            .orElseThrow(() -> new NotFoundException(
                "Assignment owner was not found", "supply.workspace.assignment.ownerNotFound", ownerUserId));
        return resolvedOwner(user, scoped);
    }

    private ResolvedOwner resolveAuthenticatedSpecialist(
        Authentication authentication,
        ScopedCommitment scoped
    ) {
        User user = userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
                scoped.tenantId(),
                scoped.commitment().companyId(),
                authentication.getName())
            .orElseThrow(() -> new AccessDeniedException("Active company specialist was not found"));
        ResolvedOwner owner = resolvedOwner(user, scoped);
        if (owner.role() != SupplyAssignmentRole.PROCUREMENT_SPECIALIST) {
            throw new AccessDeniedException("Only an active procurement specialist can claim work");
        }
        return owner;
    }

    private ResolvedOwner resolvedOwner(User user, ScopedCommitment scoped) {
        String tenant = scoped.tenantId();
        if (!user.isEnabled() || !tenant.equals(user.getTenantId())
            || !scoped.commitment().companyId().equals(user.getCompanyId())) {
            throw new AccessDeniedException("Assignment owner is outside the active tenant/company");
        }
        SupplyAssignmentRole role = OWNER_ROLE_PRECEDENCE.stream()
            .filter(candidate -> WarehouseRoleAuthorities.hasRole(user.getRoles(), candidate))
            .findFirst()
            .map(SupplyWorkAssignmentService::assignmentRole)
            .orElseThrow(() -> new AccessDeniedException("User role cannot own supply work"));
        return new ResolvedOwner(user, role);
    }

    private static SupplyAssignmentRole assignmentRole(RoleName role) {
        return SupplyAssignmentRole.valueOf(role.name());
    }

    private AssignmentResponse saveAndAudit(
        SupplyWorkAssignment assignment,
        String before,
        String reason,
        String action,
        String encodedCommitmentKey
    ) {
        SupplyWorkAssignment saved;
        try {
            saved = repository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException exception) {
            throw conflict("SUPPLY_ASSIGNMENT_CONFLICT", "assignment changed concurrently");
        }
        auditService.logInCurrentTransaction(
            "SupplyWorkAssignment",
            saved.getId(),
            action,
            "before=" + before + ";after=" + snapshot(saved) + ";reason=" + reason, null, null);
        return response(saved, encodedCommitmentKey);
    }

    private AssignmentResponse response(SupplyWorkAssignment assignment, String encodedCommitmentKey) {
        AssignmentOwner owner = assignment.getOwnerUserId() == null && !hasText(assignment.getOwnerUsername())
            ? null
            : new AssignmentOwner(
                assignment.getOwnerUserId(),
                assignment.getOwnerUsername(),
                assignment.getOwnerUsername(),
                assignment.getOwnerRole());
        return new AssignmentResponse(
            assignment.getId(),
            assignment.getCommitmentKey(),
            encodedCommitmentKey,
            assignment.getCommitmentKind(),
            owner,
            assignment.getDueDate(),
            assignment.getDueDateSource(),
            assignment.getSeverity(),
            state(assignment),
            assignment.getEscalationLevel(),
            assignment.getReason(),
            assignment.getVersion());
    }

    private SupplySeverity effectiveSeverity(
        SupplyWorkAssignment assignment,
        SupplyCommitmentRecord commitment
    ) {
        if (assignment != null && assignment.getSeverity() != null) {
            return assignment.getSeverity();
        }
        return commitment.severity() == null ? SupplySeverity.WARNING : commitment.severity();
    }

    private SupplyEscalationState state(SupplyWorkAssignment assignment) {
        return assignment.getEscalationState() == null
            ? SupplyEscalationState.NONE
            : assignment.getEscalationState();
    }

    private boolean isEscalated(SupplyWorkAssignment assignment) {
        return assignment != null
            && (assignment.getEscalationLevel() > 0
                || state(assignment) != SupplyEscalationState.NONE);
    }

    private LocalDate operationalDate() {
        return LocalDate.ofInstant(clock.instant(), timeZone);
    }

    private SupplyEscalationState stateFor(int level) {
        if (level >= 3) return SupplyEscalationState.EXECUTIVE_ATTENTION;
        if (level == 2) return SupplyEscalationState.MANAGEMENT_ATTENTION;
        if (level == 1) return SupplyEscalationState.ESCALATED;
        return SupplyEscalationState.NONE;
    }

    private void requireVersion(SupplyWorkAssignment assignment, Long expectedVersion) {
        if (assignment.getId() == null) {
            if (expectedVersion != null) {
                throw conflict("SUPPLY_ASSIGNMENT_CONFLICT", "assignment does not yet exist");
            }
            return;
        }
        if (expectedVersion == null) {
            throw conflict("SUPPLY_ASSIGNMENT_VERSION_REQUIRED", "expectedVersion is required");
        }
        if (!expectedVersion.equals(assignment.getVersion())) {
            throw conflict(
                "SUPPLY_ASSIGNMENT_CONFLICT",
                "expected version " + assignment.getVersion());
        }
    }

    private void requireManager() {
        Authentication authentication = authentication();
        boolean manager = authentication.getAuthorities().stream()
            .anyMatch(authority -> MANAGER_AUTHORITIES.contains(authority.getAuthority()));
        if (!manager) {
            throw new AccessDeniedException("Only a supply manager can assign or escalate work");
        }
    }

    private Authentication requireAuthority(String authority) {
        Authentication authentication = authentication();
        if (authentication.getAuthorities().stream()
            .noneMatch(value -> authority.equals(value.getAuthority()))) {
            throw new AccessDeniedException("Required authority is missing");
        }
        return authentication;
    }

    private Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }
        return authentication;
    }

    private String requireReason(String reason) {
        if (!hasText(reason)) {
            throw bad("SUPPLY_ASSIGNMENT_REASON_REQUIRED", "A non-blank reason is required");
        }
        return reason.trim();
    }

    private String snapshot(SupplyWorkAssignment assignment) {
        if (assignment == null || assignment.getId() == null) return "null";
        return "owner=" + assignment.getOwnerUsername()
            + ",role=" + assignment.getOwnerRole()
            + ",due=" + assignment.getDueDate()
            + ",dueSource=" + assignment.getDueDateSource()
            + ",severity=" + assignment.getSeverity()
            + ",state=" + state(assignment)
            + ",level=" + assignment.getEscalationLevel()
            + ",version=" + assignment.getVersion();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private BadRequestException bad(String code, String message) {
        return new BadRequestException(
            code + ": " + message,
            "supply.workspace.assignment.invalid",
            code);
    }

    private SupplyAssignmentConflictException conflict(String code, String message) {
        return new SupplyAssignmentConflictException(code, message);
    }

    private record ScopedCommitment(
        String tenantId,
        SupplyCommitmentKey key,
        SupplyCommitmentRecord commitment
    ) {
    }

    private record ResolvedOwner(User user, SupplyAssignmentRole role) {
    }
}
