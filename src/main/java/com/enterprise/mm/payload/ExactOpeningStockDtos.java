package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ExactOpeningStockDtos {
    private ExactOpeningStockDtos() {}
    public record StartRequest(
        @NotNull UUID importId,
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sourceManifestSha256,
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") String preparedManifestSha256,
        @NotBlank @Pattern(regexp="[a-f0-9]{32}") String sourceOrganizationId,
        @NotBlank @Size(max=4000) String sourceEvidence,
        @NotNull LocalDate postingDate,
        @Min(1) @Max(1000000) int expectedRows,
        @NotBlank @Pattern(regexp="[0-9]{1,15}(\\.[0-9]{1,7})?") String expectedQuantityChecksum,
        @NotBlank @Pattern(regexp="[0-9]{1,15}(\\.[0-9]{1,2})?") String expectedValueUzs
    ) {}
    public record Row(
        @NotBlank @Size(max=220) String rowId,
        @NotBlank @Pattern(regexp="[a-f0-9]{32}") String accountSourceId,
        @NotBlank @Pattern(regexp="10[0-9]{2}") String accountCode,
        @NotBlank @Pattern(regexp="[a-f0-9]{32}") String materialSourceId,
        @NotBlank @Pattern(regexp="[a-f0-9]{32}") String warehouseSourceId,
        @NotNull UUID materialId, @NotNull UUID warehouseId, @NotNull UUID storageLocationId, UUID storageBinId,
        @NotBlank String unit,
        @NotBlank @Pattern(regexp="[0-9]{1,15}(\\.[0-9]{1,7})?") String quantity,
        @NotBlank @Pattern(regexp="[0-9]{1,15}(\\.[0-9]{1,2})?") String totalValueUzs
    ) {}
    public record ChunkRequest(@NotNull UUID chunkId, @NotNull @Size(min=1,max=500) List<@NotNull @Valid Row> rows) {}
    public record RowResult(String rowId, UUID movementId, UUID stockBatchId, BigDecimal quantity, BigDecimal totalValueUzs) {}
    public record ChunkResult(UUID importId, UUID chunkId, List<RowResult> rows) {}
    /** Quantity is a checksum across unlike units, not a physical aggregate. */
    public record Status(UUID importId, String tenantId, UUID companyId, String status,
        int expectedRows, int acceptedRows, BigDecimal expectedQuantityChecksum, BigDecimal acceptedQuantityChecksum,
        BigDecimal expectedValueUzs, BigDecimal acceptedValueUzs, String sourceManifestSha256, String preparedManifestSha256) {}
}
