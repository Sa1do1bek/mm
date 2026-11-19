package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.SourcingCoverageSnapshot;

import java.util.UUID;

public interface SourcingCoverageReader {
    SourcingCoverageSnapshot read(UUID purchaseRequestId);
}
