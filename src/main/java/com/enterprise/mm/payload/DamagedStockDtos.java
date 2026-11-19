package com.enterprise.mm.payload;

import com.enterprise.mm.entity.DamagedStockCase.Condition;
import com.enterprise.mm.entity.DamagedStockCase.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class DamagedStockDtos {
    private DamagedStockDtos() {}

    public record Report(@NotNull UUID balanceId, @NotNull Condition condition, @NotNull BigDecimal quantity,
                         @NotBlank @Size(max = 2000) String reason) {}
    public record Release(@NotBlank @Size(max = 2000) String reason) {}
    public record Post(@NotNull @PastOrPresent LocalDate postingDate) {}

    public record CaseView(UUID id, String code, Status status, Condition condition, BigDecimal quantity, String unit,
                           String reason, UUID balanceId, String materialCode, String materialName,
                           UUID warehouseId, String warehouseName, String locationName, String binName, String batchNumber,
                           String reportedBy, LocalDateTime reportedAt, UUID approvalInstanceId, UUID movementId,
                           String resolutionNote, List<String> actions) {}
}
