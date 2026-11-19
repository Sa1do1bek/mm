package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TransferLegacyReconciliationDtos {
    private TransferLegacyReconciliationDtos() {}
    public record MovementEvidence(UUID id, String code, String name, LocalDate postingDate, String kind, BigDecimal quantity) {}
    public record ReconciliationPreview(String status, List<UUID> sourceMovementIds, List<String> blockers,
        List<MovementEvidence> movements, boolean canConfirm, String ownerRole) {}
    public record ReconciliationCommand(Long expectedVersion, List<UUID> sourceMovementIds, List<UUID> attachmentIds, String reason) {}
}
