package com.enterprise.mm.payload;

import com.enterprise.mm.enums.IssueRequestCostObjectType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssueRequestCreateRequest {
    @Size(max = 80)
    private String code;

    @NotNull
    @Size(max = 240)
    private String name;

    private String description;

    @NotNull
    private UUID warehouseId;

    @NotNull
    private UUID requesterUserId;

    @NotNull
    private UUID receiverUserId;

    @NotNull
    private IssueRequestCostObjectType costObjectType;

    @NotNull
    private UUID costObjectId;

    private LocalDate requiredDate;

    private String purpose;

    @Valid
    @NotEmpty
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        @NotNull
        private UUID materialId;

        @NotNull
        @Positive
        private BigDecimal requestedQuantity;

        private BigDecimal unitCost;

        private UUID sourceDemandPlanItemId;

        private String comment;
    }
}
