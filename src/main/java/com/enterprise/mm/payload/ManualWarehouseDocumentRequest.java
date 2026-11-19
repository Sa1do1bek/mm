package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.warehouse.packaging.ManualPackagingReceiptRequest;

public record ManualWarehouseDocumentRequest(
    @NotNull UUID warehouseId,
    @NotNull UUID contractId,
    LocalDate movementDate,
    @Size(max = 240) String source,
    @Size(max = 120) String reference,
    @NotEmpty @Valid List<ManualWarehouseDocumentLineRequest> lines,
    UUID fakturaPowerOfAttorneyId,
    UUID fakturaInvoiceId,
    @Size(max = 500) String missingFakturaReason,
    List<@Valid ManualPackagingReceiptRequest> packaging
) {
    public ManualWarehouseDocumentRequest(UUID warehouseId, UUID contractId, LocalDate movementDate,
        String source, String reference, List<ManualWarehouseDocumentLineRequest> lines,
        UUID fakturaPowerOfAttorneyId, UUID fakturaInvoiceId, String missingFakturaReason) {
        this(warehouseId, contractId, movementDate, source, reference, lines,
            fakturaPowerOfAttorneyId, fakturaInvoiceId, missingFakturaReason, null);
    }
    public ManualWarehouseDocumentRequest(UUID warehouseId, UUID contractId, LocalDate movementDate,
                                          String source, String reference, List<ManualWarehouseDocumentLineRequest> lines) {
        this(warehouseId, contractId, movementDate, source, reference, lines, null, null, null, null);
    }
}
