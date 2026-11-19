package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ManagementOpportunityType;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityPolicyResolveRequest {
    @NotNull
    private ManagementOpportunityType opportunityType;

    @NotNull
    private LocalDate asOfDate;

    private UUID warehouseId;
    private UUID materialGroupId;
    private UUID supplierId;
}
