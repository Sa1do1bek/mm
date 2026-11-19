package com.enterprise.mm.warehouse.reports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public final class WarehouseAccountImportDtos {
    private WarehouseAccountImportDtos() {}
    public record Start(@NotNull UUID importId,@NotNull @Pattern(regexp="[a-f0-9]{64}") String manifestSha256,
        @NotNull @Pattern(regexp="[a-f0-9]{32}") String organizationSourceId,@NotNull LocalDate snapshotDate,
        @NotNull Map<String,@NotNull @PositiveOrZero @Max(20000000) Long> counts,
        @NotNull @Pattern(regexp="[a-f0-9]{64}") String expectedChainSha256) {}
    public record Row(@Positive long seq,@NotNull @Pattern(regexp="[a-f0-9]{64}") String sha256,
        @NotBlank @Size(max=65536) String content) {}
    public record Chunk(@NotEmpty @Size(max=500) List<@NotNull @Valid Row> rows) {}
}
