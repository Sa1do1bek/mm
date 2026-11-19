package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DemandPlanCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    // GOODS (default), SERVICE, or WORKS; WORKS remains distinct while using the SERVICE catalog mapping.
    @Size(max = 20)
    private String requestType;
    private UUID planningCycleId;
    private Integer planYear;
    private String periodType;
    private Integer quarter;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private UUID departmentId;
    @Setter(AccessLevel.NONE)
    private UUID budgetItemId;
    @Setter(AccessLevel.NONE)
    private String budgetItemCode;
    private String ownerUsername;
    private String currencyCode;
    private BigDecimal totalPlannedAmount;
    private BigDecimal totalApprovedAmount;
    private BigDecimal totalReservedQuantity;
    private LocalDateTime consolidatedAt;
    private LocalDateTime approvedAt;
    @Valid
    private List<DemandPlanItemCreateRequest> items;

    @JsonProperty("budgetItemId")
    public void setBudgetItemId(Object budgetItemId) {
        if (budgetItemId instanceof UUID uuid) {
            this.budgetItemId = uuid;
            this.budgetItemCode = uuid.toString();
            return;
        }
        this.budgetItemCode = budgetItemId == null || budgetItemId.toString().isBlank() ? null : budgetItemId.toString().trim();
        try {
            this.budgetItemId = this.budgetItemCode == null ? null : UUID.fromString(this.budgetItemCode);
        } catch (IllegalArgumentException ignored) {
            this.budgetItemId = null;
        }
    }
}
