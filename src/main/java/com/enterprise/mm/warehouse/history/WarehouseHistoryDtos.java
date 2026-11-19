package com.enterprise.mm.warehouse.history;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class WarehouseHistoryDtos {
    private WarehouseHistoryDtos() {}
    public record Start(@NotNull UUID importId, @Pattern(regexp="[a-f0-9]{64}") @NotNull String manifestSha256,
        @Pattern(regexp="[a-f0-9]{32}") @NotNull String organizationSourceId,
        @NotNull @Size(min=1,max=4) Map<String,@NotNull @PositiveOrZero @Max(20000000) Long> counts,
        @Pattern(regexp="[a-f0-9]{64}") @NotNull String expectedChainSha256) {}
    public record Row(@Positive long seq, @NotNull @Pattern(regexp="[a-f0-9]{64}") String sha256,
        @NotBlank @Size(max=262144) String content) {}
    public record Chunk(@NotEmpty @Size(max=500) List<@Valid Row> rows) {}
    public record ChunkResult(long firstSeq,int acceptedRows,String payloadSha256,String chainSha256) {}
    public record Run(UUID importId,String companyId,String manifestSha256,String status,long acceptedRecords,
        long expectedRecords,String chainSha256,String expectedChainSha256,Map<String,Long> counts) {}
}
