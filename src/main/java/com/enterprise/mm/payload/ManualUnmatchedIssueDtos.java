package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ManualUnmatchedIssueDtos {
    private ManualUnmatchedIssueDtos() {}

    /** The entry context grants access; no physical source warehouse is asserted. */
    public record Create(
        @NotNull UUID entryContextWarehouseId,
        @NotNull @PastOrPresent LocalDate issueDate,
        @NotBlank @Size(max = 240) String issuedTo,
        @Size(max = 120) String reference,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid LineInput> lines
    ) {}

    public record LineInput(
        @NotNull UUID materialId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity
    ) {}

    public record Line(
        UUID id, UUID materialId, String materialCode, String materialName,
        String unitCode, BigDecimal quantity
    ) {}

    public record Response(
        UUID id, String code, String status, LocalDate issueDate, String issuedTo,
        String reference, UUID entryContextWarehouseId, UUID sourceWarehouseId,
        LocalDateTime recordedAt, String recordedBy, List<Line> lines
    ) {}
}
