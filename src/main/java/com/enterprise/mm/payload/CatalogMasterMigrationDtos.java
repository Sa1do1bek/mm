package com.enterprise.mm.payload;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** Bounded, source-bound master chunks. Payloads use the normal create contracts. */
public final class CatalogMasterMigrationDtos {
    private CatalogMasterMigrationDtos() {}

    public record MasterChunkRequest(
        @NotNull UUID importId, @NotNull UUID chunkId,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sourceManifestSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String preparedManifestSha256,
        @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid MasterRow> rows
    ) {}

    public record MasterRow(
        @NotBlank @Pattern(regexp = "[a-f0-9]{32}|source-not-specified") String sourceId,
        @NotBlank String sourceEntityType,
        UUID expectedMasterId,
        @NotNull JsonNode payload
    ) {}

    public record MasterRowResponse(String sourceId, UUID masterId, String code, boolean created) {}
    public record SupplierNameRepairRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String sourceId,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String preparedManifestSha256,
        @NotBlank @Size(max = 240) String sourceName,
        @NotNull @PositiveOrZero Long expectedVersion
    ) {}
    public record MasterChunkResponse(
        UUID importId, UUID chunkId, String sourceManifestSha256, String preparedManifestSha256,
        String tenantId, UUID companyId, String kind, List<MasterRowResponse> rows
    ) {}
}
