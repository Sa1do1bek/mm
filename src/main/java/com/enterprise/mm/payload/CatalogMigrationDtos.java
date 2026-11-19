package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Bounded 1C Catalog54 material import commands; source and prepared digests are distinct. */
public final class CatalogMigrationDtos {
    private CatalogMigrationDtos() {
    }

    public record MaterialCatalogChunkRequest(
        @NotNull UUID importId,
        @NotNull UUID chunkId,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sourceManifestSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String preparedManifestSha256,
        @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid MaterialCatalogRow> rows
    ) {
    }

    public record MaterialCatalogRow(
        @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String sourceId,
        @NotNull @Valid MaterialCreateRequest payload,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Pattern(regexp = "Catalog54|Catalog55|Catalog5876") String sourceEntityType
    ) {
        public MaterialCatalogRow(String sourceId, MaterialCreateRequest payload) { this(sourceId,payload,null); }
        public String effectiveSourceEntityType() { return sourceEntityType == null ? "Catalog54" : sourceEntityType; }
    }

    public record MaterialCatalogRowResponse(String sourceId, UUID materialId, String code, boolean created) {
    }

    public record MaterialCatalogChunkResponse(
        UUID importId,
        UUID chunkId,
        String sourceManifestSha256,
        String preparedManifestSha256,
        String tenantId,
        UUID companyId,
        List<MaterialCatalogRowResponse> rows
    ) {
    }
}
