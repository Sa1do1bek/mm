package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseRequestCreateRequest {
    private UUID departmentId;
    private UUID demandPlanId;
    /**
     * TODO item 4 ("Создать заявку на закупку" / P0 "Demand scope"): when set, this
     * purchase request's demand is scoped to a single warehouse, which lets
     * InventoryAvailabilityQueryRepository's warehouse_scope_mode computation report
     * WAREHOUSE_SPECIFIC instead of always falling back to MIXED/COMPANY_LEVEL_DEMAND.
     */
    private UUID warehouseId;
    @Size(max = 120)
    private String requestedBy;
    @Size(max = 20)
    private String requestType;
    private LocalDate requiredDate;
    @PositiveOrZero
    private BigDecimal totalAmount;
    @Size(max = 3)
    private String currencyCode;
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @Valid
    private List<PurchaseRequestItemCreateRequest> items;
}
