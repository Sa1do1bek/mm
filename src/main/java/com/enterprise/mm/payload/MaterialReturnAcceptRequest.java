package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ReturnStockStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialReturnAcceptRequest {
    @NotNull
    private Long expectedVersion;

    @Valid
    @NotEmpty
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        private UUID returnLineId;
        @DecimalMin(value = "0.0", inclusive = true)
        private BigDecimal acceptedQuantity;
        @DecimalMin(value = "0.0", inclusive = true)
        private BigDecimal rejectedQuantity;
        private ReturnStockStatus targetStockStatus;
        private String inspectionNote;
        private List<UUID> materialUnitIds;
    }
}
