package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.entity.SupplyWorkAssignment;
import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.error.SupplyAssignmentConflictException;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.ClaimRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.EscalationRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import com.enterprise.mm.supply.workspace.repository.SupplyWorkAssignmentRepository;
import com.enterprise.mm.supply.workspace.service.SupplySlaPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkAssignmentService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceAccessPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceFilterFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SupplyWorkAssignmentServiceTest {
    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD"})
    void bothWarehouseLeadershipNamesCanBeAssignedSupplyWork(RoleName role) {
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdAndIsDeletedFalse(targetId))
            .thenReturn(Optional.of(user(targetId, COMPANY, "warehouse.owner", role)));
        when(repository.findActiveForUpdate(TENANT, COMPANY, KEY.canonical())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 0L));

        var response = service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            targetId, null, null, TODAY.plusDays(3), SupplySeverity.WARNING, "assign warehouse follow-up", null));

        assertThat(response.owner().role()).isEqualTo(SupplyAssignmentRole.WAREHOUSE_MANAGER);
        assertThat(response.owner().userId()).isEqualTo(targetId);
    }

    private static final String TENANT = "tenant-assignment";
    private static final UUID COMPANY = UUID.fromString("98e85f0e-c8cb-482d-aa3a-1f674d951f70");
    private static final UUID OTHER_COMPANY = UUID.fromString("9c2108b8-904b-47fc-b9f6-9621269bd927");
    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private static final SupplyCommitmentKey KEY = new SupplyCommitmentKey(
        SupplyCommitmentKind.PO, UUID.fromString("2516cc31-adc5-4451-895d-8f769f5f91c7"));

    private final SupplyWorkAssignmentRepository repository = mock(SupplyWorkAssignmentRepository.class);
    private final SupplyCommitmentQueryRepository commitmentRepository = mock(SupplyCommitmentQueryRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SupplyCommitmentKeyCodec codec = new SupplyCommitmentKeyCodec();
    private final SupplyAccessScope scope = new SupplyAccessScope(TENANT, Set.of(COMPANY), Set.of(), false, false);
    private final SupplyWorkAssignmentService service = new SupplyWorkAssignmentService(
        repository, commitmentRepository, userRepository, auditService, codec,
        new SupplySlaPolicy(), Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Tashkent");

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        when(commitmentRepository.findScoped(KEY, scope, NOW)).thenReturn(Optional.of(commitment()));
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void managerAssignmentResolvesScopedActiveOwnerAndIgnoresClientIdentityText() {
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        UUID targetId = UUID.fromString("1e431ab4-5226-4bfe-ad35-2fa254fbe771");
        User target = user(targetId, COMPANY, "buyer.real", RoleName.PROCUREMENT_SPECIALIST);
        when(userRepository.findByIdAndIsDeletedFalse(targetId)).thenReturn(Optional.of(target));
        when(repository.findActiveForUpdate(TENANT, COMPANY, KEY.canonical())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 0L));

        var response = service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            targetId, "spoofed.user", "ADMIN", TODAY.plusDays(3), SupplySeverity.WARNING,
            "balance supplier workload", null));

        assertThat(response.owner().userId()).isEqualTo(targetId);
        assertThat(response.owner().username()).isEqualTo("buyer.real");
        assertThat(response.owner().role()).isEqualTo(SupplyAssignmentRole.PROCUREMENT_SPECIALIST);
        assertThat(response.dueDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(response.dueDateSource()).isEqualTo(SupplyDueDateSource.EXPLICIT);
        verify(auditService).logInCurrentTransaction(
            eq("SupplyWorkAssignment"), eq(response.id()), eq("ASSIGN"),
            org.mockito.ArgumentMatchers.argThat(details ->
                details.contains("before=null")
                    && details.contains("buyer.real")
                    && details.contains("balance supplier workload")),
            isNull(), isNull());
    }

    @Test
    void existingAssignmentRequiresMatchingVersionAndCrossScopeKeyIsNotFoundBeforeMutation() {
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        SupplyWorkAssignment existing = assignment(4L, "buyer.old", SupplySeverity.WARNING);
        when(repository.findActiveForUpdate(TENANT, COMPANY, KEY.canonical())).thenReturn(Optional.of(existing));
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdAndIsDeletedFalse(targetId))
            .thenReturn(Optional.of(user(targetId, COMPANY, "buyer.new", RoleName.PROCUREMENT_SPECIALIST)));

        assertThatThrownBy(() -> service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            targetId, null, null, null, null, "reassign", null)))
            .isInstanceOf(SupplyAssignmentConflictException.class)
            .hasMessageContaining("SUPPLY_ASSIGNMENT_VERSION_REQUIRED");
        assertThatThrownBy(() -> service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            targetId, null, null, null, null, "reassign", 3L)))
            .isInstanceOf(SupplyAssignmentConflictException.class)
            .hasMessageContaining("SUPPLY_ASSIGNMENT_CONFLICT");

        SupplyAccessScope otherScope = new SupplyAccessScope(
            TENANT, Set.of(OTHER_COMPANY), Set.of(), false, false);
        assertThatThrownBy(() -> service.assign(codec.encode(KEY), otherScope, new AssignmentRequest(
            targetId, null, null, null, null, "cross scope", 4L)))
            .isInstanceOf(NotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void specialistClaimUsesAuthenticatedDatabaseUserAndRejectsUnsupportedActor() {
        UUID specialistId = UUID.randomUUID();
        User specialist = user(specialistId, COMPANY, "buyer.real", RoleName.PROCUREMENT_SPECIALIST);
        authenticate("buyer.real", "ROLE_PROCUREMENT_SPECIALIST");
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            TENANT, COMPANY, "buyer.real")).thenReturn(Optional.of(specialist));
        when(repository.findActiveForUpdate(TENANT, COMPANY, KEY.canonical())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 0L));

        var response = service.claim(codec.encode(KEY), scope, new ClaimRequest(
            null, "spoofed", "ADMIN", "take ownership", null));

        assertThat(response.owner().userId()).isEqualTo(specialistId);
        assertThat(response.owner().username()).isEqualTo("buyer.real");
        assertThat(response.owner().role()).isEqualTo(SupplyAssignmentRole.PROCUREMENT_SPECIALIST);
        assertThat(response.dueDateSource()).isEqualTo(SupplyDueDateSource.POLICY);

        authenticate("viewer", "ROLE_VIEWER");
        assertThatThrownBy(() -> service.claim(codec.encode(KEY), scope, new ClaimRequest(
            null, null, null, "claim", null)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void escalationIsVersionedAndCannotDecreaseSeverityOrState() {
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        SupplyWorkAssignment existing = assignment(7L, "buyer", SupplySeverity.WARNING);
        existing.setEscalationLevel(1);
        existing.setEscalationState(SupplyEscalationState.ESCALATED);
        when(repository.findActiveForUpdate(TENANT, COMPANY, KEY.canonical())).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 8L));

        assertThatThrownBy(() -> service.escalate(codec.encode(KEY), scope, new EscalationRequest(
            SupplySeverity.INFO, "lower severity", 7L)))
            .isInstanceOf(SupplyAssignmentConflictException.class)
            .hasMessageContaining("SUPPLY_ESCALATION_NOT_MONOTONIC");

        var response = service.escalate(codec.encode(KEY), scope, new EscalationRequest(
            SupplySeverity.BLOCKING, "supplier did not respond", 7L));
        assertThat(response.severity()).isEqualTo(SupplySeverity.BLOCKING);
        assertThat(response.escalationLevel()).isEqualTo(2);
        assertThat(response.escalationState()).isEqualTo(SupplyEscalationState.MANAGEMENT_ATTENTION);
        verify(auditService).logInCurrentTransaction(
            eq("SupplyWorkAssignment"), eq(response.id()), eq("ESCALATE"),
            org.mockito.ArgumentMatchers.argThat(details ->
                details.contains("before=") && details.contains("after=")
                    && details.contains("supplier did not respond")),
            isNull(), isNull());
    }

    @Test
    void reassignmentAuditsReassignInsteadOfAssign() {
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        UUID oldOwnerId = UUID.randomUUID();
        UUID newOwnerId = UUID.randomUUID();
        SupplyWorkAssignment existing = assignment(4L, "buyer.old", SupplySeverity.WARNING);
        existing.setOwnerUserId(oldOwnerId);
        when(repository.findActiveForUpdate(TENANT, COMPANY, KEY.canonical()))
            .thenReturn(Optional.of(existing));
        when(userRepository.findByIdAndIsDeletedFalse(newOwnerId)).thenReturn(Optional.of(
            user(newOwnerId, COMPANY, "buyer.new", RoleName.PROCUREMENT_SPECIALIST)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 5L));

        var response = service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            newOwnerId, null, null, null, null, "move to current buyer", 4L));

        assertThat(response.owner().userId()).isEqualTo(newOwnerId);
        verify(auditService).logInCurrentTransaction(
            eq("SupplyWorkAssignment"), eq(response.id()), eq("REASSIGN"),
            org.mockito.ArgumentMatchers.argThat(details ->
                details.contains("buyer.old") && details.contains("buyer.new")),
            isNull(), isNull());
    }

    @Test
    void escalatedAssignmentCannotBeReassignedWithLowerSeverity() {
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        UUID newOwnerId = UUID.randomUUID();
        SupplyWorkAssignment existing = assignment(4L, "buyer.old", SupplySeverity.BLOCKING);
        existing.setOwnerUserId(UUID.randomUUID());
        existing.setEscalationLevel(1);
        existing.setEscalationState(SupplyEscalationState.ESCALATED);
        when(repository.findActiveForUpdate(TENANT, COMPANY, KEY.canonical()))
            .thenReturn(Optional.of(existing));
        when(userRepository.findByIdAndIsDeletedFalse(newOwnerId)).thenReturn(Optional.of(
            user(newOwnerId, COMPANY, "buyer.new", RoleName.PROCUREMENT_SPECIALIST)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 5L));

        assertThatThrownBy(() -> service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            newOwnerId, null, null, null, SupplySeverity.INFO, "reassign urgent work", 4L)))
            .isInstanceOf(SupplyAssignmentConflictException.class)
            .hasMessageContaining("SUPPLY_ESCALATION_NOT_MONOTONIC");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void policyDueDateUsesConfiguredSupplyTimezoneAtUtcBoundary() {
        Instant boundary = Instant.parse("2026-07-14T20:30:00Z"); // 2026-07-15 01:30 Asia/Tashkent
        SupplyWorkAssignmentService boundaryService = new SupplyWorkAssignmentService(
            repository, commitmentRepository, userRepository, auditService, codec,
            new SupplySlaPolicy(), Clock.fixed(boundary, ZoneOffset.UTC), "Asia/Tashkent");
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        UUID ownerId = UUID.randomUUID();
        when(commitmentRepository.findScoped(KEY, scope, boundary)).thenReturn(Optional.of(commitment()));
        when(userRepository.findByIdAndIsDeletedFalse(ownerId)).thenReturn(Optional.of(
            user(ownerId, COMPANY, "buyer.boundary", RoleName.PROCUREMENT_SPECIALIST)));
        when(repository.findActiveForUpdate(TENANT, COMPANY, KEY.canonical())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 0L));

        var response = boundaryService.assign(codec.encode(KEY), scope, new AssignmentRequest(
            ownerId, null, null, null, SupplySeverity.WARNING, "boundary assignment", null));

        assertThat(response.dueDate()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(response.dueDateSource()).isEqualTo(SupplyDueDateSource.POLICY);
    }

    @Test
    void assignmentRequiresReasonAndRejectsCrossCompanyOrUnsupportedOwner() {
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        UUID targetId = UUID.randomUUID();

        assertThatThrownBy(() -> service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            targetId, null, null, null, null, "  ", null)))
            .isInstanceOf(com.enterprise.mm.error.BadRequestException.class)
            .hasMessageContaining("SUPPLY_ASSIGNMENT_REASON_REQUIRED");

        when(userRepository.findByIdAndIsDeletedFalse(targetId)).thenReturn(Optional.of(
            user(targetId, OTHER_COMPANY, "buyer.other", RoleName.PROCUREMENT_SPECIALIST)));
        assertThatThrownBy(() -> service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            targetId, null, null, null, null, "cross-company owner", null)))
            .isInstanceOf(AccessDeniedException.class);

        when(userRepository.findByIdAndIsDeletedFalse(targetId)).thenReturn(Optional.of(
            user(targetId, COMPANY, "viewer", RoleName.VIEWER)));
        assertThatThrownBy(() -> service.assign(codec.encode(KEY), scope, new AssignmentRequest(
            targetId, null, null, null, null, "unsupported owner", null)))
            .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void accessPolicyBindsMyWorkFromActiveUserAndProtectsManagementLens() {
        UserRepository accessUsers = mock(UserRepository.class);
        SupplyWorkspaceAccessPolicy policy = new SupplyWorkspaceAccessPolicy(accessUsers);
        UUID specialistId = UUID.randomUUID();
        User specialist = user(
            specialistId, COMPANY, "buyer.authenticated", RoleName.PROCUREMENT_SPECIALIST);
        when(accessUsers.findAllByTenantIdAndUsernameAndIsDeletedFalse(
            TENANT, "buyer.authenticated")).thenReturn(List.of(specialist));
        authenticate("buyer.authenticated", "ROLE_PROCUREMENT_SPECIALIST");
        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setAsOf(NOW);
        request.setLens(SupplyLens.MY_WORK);
        request.setOwnerUserId(UUID.randomUUID());
        SupplyWorkspaceQuery query = new SupplyWorkspaceFilterFactory(
            Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Tashkent").normalize(request, scope);

        policy.requireReadable(query.lens());
        SupplyWorkspaceQuery bound = policy.bindAuthenticatedIdentity(query);

        assertThat(query.ownerUserId()).isNull();
        assertThat(bound.authenticatedUserId()).isEqualTo(specialistId);
        assertThat(bound.authenticatedUsername()).isEqualTo("buyer.authenticated");

        authenticate("viewer", "ROLE_VIEWER");
        assertThatThrownBy(() -> policy.requireReadable(SupplyLens.MANAGEMENT))
            .isInstanceOf(AccessDeniedException.class);
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        policy.requireReadable(SupplyLens.MANAGEMENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void myWorkBindsAllOwnerIdsWithNullIdUsernameFallbackAndUnassignedParity() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class)))
            .thenReturn(0L);
        when(jdbc.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(List.of());
        SupplyCommitmentQueryRepository queryRepository = new SupplyCommitmentQueryRepository(jdbc, "Asia/Tashkent");
        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setAsOf(NOW);
        request.setLens(SupplyLens.MY_WORK);
        request.setOwnerUserId(UUID.randomUUID());
        SupplyWorkspaceQuery normalized = new SupplyWorkspaceFilterFactory(
            Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Tashkent").normalize(request, scope);
        UUID firstOwnerId = UUID.randomUUID();
        UUID secondOwnerId = UUID.randomUUID();
        SupplyWorkspaceQuery myWork = normalized.withAuthenticatedPrincipals(
            Set.of(firstOwnerId, secondOwnerId), "buyer.authenticated");

        queryRepository.count(myWork);

        org.mockito.ArgumentCaptor<String> countSql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<MapSqlParameterSource> countParameters =
            org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(countSql.capture(), countParameters.capture(), eq(Long.class));
        assertThat(countSql.getValue())
            .contains("owner_user_id in (:authenticatedUserIds)")
            .contains("owner_user_id is null")
            .contains("owner_username = :authenticatedUsername");
        assertThat(countParameters.getValue().getValue("authenticatedUserIds"))
            .isEqualTo(Set.of(firstOwnerId, secondOwnerId));
        assertThat(countParameters.getValue().getValue("authenticatedUsername"))
            .isEqualTo("buyer.authenticated");
        assertThat(normalized.ownerUserId()).isNull();

        clearInvocations(jdbc);
        SupplyWorkspaceRequest teamRequest = new SupplyWorkspaceRequest();
        teamRequest.setAsOf(NOW);
        teamRequest.setLens(SupplyLens.TEAM);
        SupplyWorkspaceQuery teamUnassigned = new SupplyWorkspaceFilterFactory(
            Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Tashkent")
            .normalize(teamRequest, scope)
            .withQueue(SupplyQueueKey.UNASSIGNED);
        queryRepository.count(teamUnassigned);
        queryRepository.search(teamUnassigned, org.springframework.data.domain.Pageable.unpaged());

        org.mockito.ArgumentCaptor<String> unassignedCountSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(
            unassignedCountSql.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        org.mockito.ArgumentCaptor<String> searchSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(searchSql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertThat(unassignedCountSql.getValue()).contains("commitment.is_unassigned = true");
        assertThat(searchSql.getValue()).contains("commitment.is_unassigned = true");
        assertThat(searchSql.getValue()).doesNotContain("owner_username = :authenticatedUsername");
    }

    private SupplyCommitmentRecord commitment() {
        return new SupplyCommitmentRecord(
            KEY, COMPANY, KEY.id(), "PO-ASSIGN", "Assignment PO", UUID.randomUUID(),
            "SUP", "Supplier", null, TODAY.plusDays(5), "SENT", "USD",
            java.math.BigDecimal.TEN, java.math.BigDecimal.ONE, "EA",
            SupplyDataQuality.AUTHORITATIVE, SupplySeverity.WARNING,
            false, true, false, false, false, true,
            null, null, null, null, null);
    }

    private SupplyWorkAssignment assignment(long version, String username, SupplySeverity severity) {
        SupplyWorkAssignment assignment = new SupplyWorkAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setTenantId(TENANT);
        assignment.setCompanyId(COMPANY);
        assignment.setCommitmentKey(KEY.canonical());
        assignment.setCommitmentKind(KEY.kind());
        assignment.setOwnerUsername(username);
        assignment.setOwnerRole(SupplyAssignmentRole.PROCUREMENT_SPECIALIST);
        assignment.setSeverity(severity);
        assignment.setEscalationState(SupplyEscalationState.NONE);
        assignment.setDueDateSource(SupplyDueDateSource.POLICY);
        assignment.setVersion(version);
        return assignment;
    }

    private User user(UUID id, UUID companyId, String username, RoleName... roles) {
        User user = new User();
        user.setId(id);
        user.setTenantId(TENANT);
        user.setCompanyId(companyId);
        user.setUsername(username);
        user.setFullName("Resolved " + username);
        user.setEnabled(true);
        user.setRoles(new java.util.LinkedHashSet<>(List.of(roles)));
        return user;
    }

    private SupplyWorkAssignment persisted(SupplyWorkAssignment assignment, long version) {
        if (assignment.getId() == null) assignment.setId(UUID.randomUUID());
        assignment.setVersion(version);
        return assignment;
    }

    private void authenticate(String username, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }
}
