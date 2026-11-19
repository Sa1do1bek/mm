package com.enterprise.mm.payload;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.warehouse.packaging.ManualPackagingReceiptRequest;

/** Physical warehouse facts; manual receipt and issue commands require a contract specification. */
public record ManualWarehouseOperationRequest(
    @NotNull UUID warehouseId,
    @NotNull UUID materialId,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
    @DecimalMin("0") BigDecimal unitCost,
    UUID stockPositionId,
    LocalDate movementDate,
    @Size(max = 240) String source,
    @Size(max = 120) String reference,
    @Pattern(regexp = "UZS") String currencyCode,
    @Size(max = 1000) List<@NotBlank @Size(max = 120) String> serialNumbers,
    @Size(max = 1000) List<UUID> materialUnitIds,
    UUID contractId,
    UUID contractSpecificationId,
    UUID fakturaPowerOfAttorneyId,
    UUID fakturaInvoiceId,
    @Size(max = 500) String missingFakturaReason,
    List<@Valid ManualPackagingReceiptRequest> packaging
) {
    public ManualWarehouseOperationRequest(UUID warehouseId, UUID materialId, BigDecimal quantity,
        BigDecimal unitCost, UUID stockPositionId, LocalDate movementDate, String source, String reference,
        String currencyCode, List<String> serialNumbers, List<UUID> materialUnitIds,
        UUID contractId, UUID contractSpecificationId, UUID fakturaPowerOfAttorneyId,
        UUID fakturaInvoiceId, String missingFakturaReason) {
        this(warehouseId, materialId, quantity, unitCost, stockPositionId, movementDate, source, reference,
            currencyCode, serialNumbers, materialUnitIds, contractId, contractSpecificationId,
            fakturaPowerOfAttorneyId, fakturaInvoiceId, missingFakturaReason, null);
    }
    public ManualWarehouseOperationRequest(UUID warehouseId, UUID materialId, BigDecimal quantity,
        BigDecimal unitCost, UUID stockPositionId, LocalDate movementDate, String source, String reference,
        String currencyCode, List<String> serialNumbers, List<UUID> materialUnitIds,
        UUID contractId, UUID contractSpecificationId) {
        this(warehouseId, materialId, quantity, unitCost, stockPositionId, movementDate, source, reference,
            currencyCode, serialNumbers, materialUnitIds, contractId, contractSpecificationId, null, null, null);
    }
    public ManualWarehouseOperationRequest(
        UUID warehouseId,
        UUID materialId,
        BigDecimal quantity,
        BigDecimal unitCost,
        UUID stockPositionId,
        LocalDate movementDate,
        String source,
        String reference,
        String currencyCode,
        List<String> serialNumbers,
        List<UUID> materialUnitIds
    ) {
        this(warehouseId, materialId, quantity, unitCost, stockPositionId, movementDate,
            source, reference, currencyCode, serialNumbers, materialUnitIds, null, null);
    }
}
