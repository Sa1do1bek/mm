package com.enterprise.mm.warehouse.installation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class InstallationCommands {

    public enum InstallationPositionStatus {
        DISPATCHED,
        ACCEPTED,
        PARTIALLY_CAPITALIZED,
        FULLY_CAPITALIZED,
        RETURNED
    }

    public record DispatchInstallationCommand(
        String commandId,
        UUID projectId,
        UUID materialId,
        UUID warehouseId,
        BigDecimal quantity,
        String baseUnitCode,
        LocalDate dispatchDate,
        String sourceReference
    ) {}

    public record AcceptInstallationCommand(
        String commandId,
        UUID positionId,
        BigDecimal quantity,
        LocalDate acceptanceDate
    ) {}

    public record ReturnInstallationCommand(
        String commandId,
        UUID positionId,
        BigDecimal quantity,
        UUID returnWarehouseId,
        LocalDate returnDate,
        String reason,
        BigDecimal unitCost
    ) {
        public ReturnInstallationCommand(
            String commandId,
            UUID positionId,
            BigDecimal quantity,
            UUID returnWarehouseId,
            LocalDate returnDate,
            String reason
        ) {
            this(commandId, positionId, quantity, returnWarehouseId, returnDate, reason, null);
        }
    }

    public record InstallationProjectBalance(
        UUID projectId,
        UUID materialId,
        BigDecimal dispatchedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal capitalizedQuantity,
        BigDecimal returnedQuantity,
        BigDecimal remainingAtProjectQuantity,
        List<String> allowedActions
    ) {
        public InstallationProjectBalance(
            UUID projectId,
            UUID materialId,
            BigDecimal dispatchedQuantity,
            BigDecimal acceptedQuantity,
            BigDecimal capitalizedQuantity,
            BigDecimal returnedQuantity,
            BigDecimal remainingAtProjectQuantity
        ) {
            this(projectId, materialId, dispatchedQuantity, acceptedQuantity, capitalizedQuantity, returnedQuantity, remainingAtProjectQuantity,
                remainingAtProjectQuantity != null && remainingAtProjectQuantity.signum() > 0 ? List.of("CAPITALIZE", "RETURN_UNUSED") : List.of());
        }
    }

    public record CapitalizeEquipmentCommand(
        String commandId,
        UUID positionId,
        BigDecimal quantity,
        BigDecimal totalCost,
        List<UUID> fixedAssetIds,
        LocalDate capitalizationDate,
        String sourceReference
    ) {}

    public record CapitalizationResult(
        String commandId,
        UUID positionId,
        BigDecimal capitalizedQuantity,
        BigDecimal capitalizedAmount,
        BigDecimal remainingQuantity,
        List<UUID> fixedAssetIds,
        List<String> allowedActions
    ) {
        public CapitalizationResult(
            String commandId,
            UUID positionId,
            BigDecimal capitalizedQuantity,
            BigDecimal capitalizedAmount,
            BigDecimal remainingQuantity,
            List<UUID> fixedAssetIds
        ) {
            this(commandId, positionId, capitalizedQuantity, capitalizedAmount, remainingQuantity, fixedAssetIds,
                remainingQuantity != null && remainingQuantity.signum() > 0 ? List.of("CAPITALIZE", "RETURN_UNUSED") : List.of());
        }
    }
}
