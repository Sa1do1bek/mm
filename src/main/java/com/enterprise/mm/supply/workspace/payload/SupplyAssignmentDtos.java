package com.enterprise.mm.supply.workspace.payload;

import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import java.time.LocalDate;
import java.util.UUID;

public final class SupplyAssignmentDtos {
    private SupplyAssignmentDtos() {
    }

    /** ownerUsername and ownerRole are compatibility fields and are never trusted. */
    public record AssignmentRequest(
        UUID ownerUserId,
        String ownerUsername,
        String ownerRole,
        LocalDate dueDate,
        SupplySeverity severity,
        String reason,
        Long expectedVersion
    ) {
    }

    /** ownerUsername and ownerRole are compatibility fields and are never trusted. */
    public record ClaimRequest(
        LocalDate dueDate,
        String ownerUsername,
        String ownerRole,
        String reason,
        Long expectedVersion
    ) {
    }

    public record EscalationRequest(
        SupplySeverity severity,
        String reason,
        Long expectedVersion
    ) {
    }

    public record AssignmentOwner(
        UUID userId,
        String username,
        String displayName,
        SupplyAssignmentRole role
    ) {
    }

    public record AssignmentResponse(
        UUID id,
        String commitmentKey,
        String encodedCommitmentKey,
        SupplyCommitmentKind commitmentKind,
        AssignmentOwner owner,
        LocalDate dueDate,
        SupplyDueDateSource dueDateSource,
        SupplySeverity severity,
        SupplyEscalationState escalationState,
        int escalationLevel,
        String reason,
        Long version
    ) {
    }
}
