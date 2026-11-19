package com.enterprise.mm.supply.workspace.payload;

import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplyWorkspaceRequest {
    private Instant asOf;
    private LocalDate from;
    private LocalDate to;
    private SupplyLens lens;
    private UUID ownerUserId;
    private UUID companyId;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private UUID contractId;
    private UUID warehouseId;
    private String transportMode;
    private String currencyCode;
    private String status;
    private SupplyRiskKey risk;
    private SupplyQueueKey queue;
    private String search;
    private SupplyCommitmentSort sort;
}
