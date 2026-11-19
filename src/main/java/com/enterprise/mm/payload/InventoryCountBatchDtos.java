package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class InventoryCountBatchDtos {
    private InventoryCountBatchDtos() {}
    public record RowCommand(UUID lineId, Long expectedLineVersion, BigDecimal countedQuantity, boolean clearCount, String comment) {}
    public record BatchCommand(Long expectedDocumentVersion, List<RowCommand> rows) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RowResult(UUID lineId, String outcome, InventoryItemResponse saved, InventoryItemResponse current, String errorCode, String message) {}
    public record BatchResult(long documentVersion, long countRevision, List<RowResult> rows) {}
    public record DocumentRevision(long version, long countRevision) {}
}
