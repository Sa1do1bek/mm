package com.enterprise.mm.procurement.command;

import com.enterprise.mm.procurement.command.entity.ProcurementWorkAssignment;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AssignmentRequest;
import com.enterprise.mm.procurement.command.repository.ProcurementWorkAssignmentRepository;
import com.enterprise.mm.procurement.command.service.ProcurementWorkAssignmentService;
import com.enterprise.mm.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcurementWorkAssignmentServiceTest {

    private final ProcurementWorkAssignmentRepository repository = mock(ProcurementWorkAssignmentRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ProcurementWorkAssignmentService service = new ProcurementWorkAssignmentService(repository, auditService);

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void specialistCannotReassignAnotherBuyerCase() {
        UUID prId = UUID.randomUUID();
        ProcurementWorkAssignment existing = assignment(prId, 3L);
        existing.setOwnerUsername("buyer.one");
        when(repository.findActiveForUpdate("default", UUID.fromString("00000000-0000-0000-0000-000000000001"), prId))
            .thenReturn(Optional.of(existing));
        authenticate("buyer.two", "ROLE_PROCUREMENT_SPECIALIST");

        assertThatThrownBy(() -> service.assign(prId, new AssignmentRequest(
            UUID.randomUUID(), "buyer.two", "PROCUREMENT_SPECIALIST", LocalDate.now().plusDays(2),
            "HIGH", false, "reassign", 3L)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staleAssignmentVersionReturnsConflict() {
        UUID prId = UUID.randomUUID();
        when(repository.findActiveForUpdate("default", UUID.fromString("00000000-0000-0000-0000-000000000001"), prId))
            .thenReturn(Optional.of(assignment(prId, 3L)));
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");

        assertThatThrownBy(() -> service.assign(prId, new AssignmentRequest(
            UUID.randomUUID(), "buyer", "PROCUREMENT_SPECIALIST", LocalDate.now().plusDays(2),
            "HIGH", false, "balance workload", 2L)))
            .isInstanceOf(OptimisticLockingFailureException.class)
            .hasMessageContaining("PROCUREMENT_ASSIGNMENT_CONFLICT");
    }

    private ProcurementWorkAssignment assignment(UUID prId, long version) {
        ProcurementWorkAssignment assignment = new ProcurementWorkAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setTenantId("default");
        assignment.setCompanyId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assignment.setPurchaseRequestId(prId);
        assignment.setVersion(version);
        return assignment;
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of(new SimpleGrantedAuthority(role))));
    }
}
