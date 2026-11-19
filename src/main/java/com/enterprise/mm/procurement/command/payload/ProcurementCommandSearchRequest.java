package com.enterprise.mm.procurement.command.payload;

import com.enterprise.mm.procurement.command.enums.ProcurementAllocationMode;
import com.enterprise.mm.procurement.command.enums.ProcurementBlockerSeverity;
import com.enterprise.mm.procurement.command.enums.ProcurementCommandStage;
import com.enterprise.mm.procurement.command.enums.ProcurementQueueKey;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record ProcurementCommandSearchRequest(
    ProcurementQueueKey queue,
    String search,
    UUID ownerUserId,
    UUID departmentId,
    UUID supplierId,
    UUID materialId,
    UUID purchaseOrderId,
    ProcurementCommandStage stage,
    ProcurementBlockerSeverity severity,
    Boolean overdue,
    LocalDate requiredDateFrom,
    LocalDate requiredDateTo,
    LocalDateTime createdFrom,
    LocalDateTime createdTo,
    Boolean integrationImpact,
    ProcurementAllocationMode allocationMode
) {
    public ProcurementCommandSearchRequest withQueue(ProcurementQueueKey nextQueue) {
        return new ProcurementCommandSearchRequest(
            nextQueue, search, ownerUserId, departmentId, supplierId, materialId, purchaseOrderId,
            stage, severity, overdue, requiredDateFrom, requiredDateTo,
            createdFrom, createdTo, integrationImpact, allocationMode);
    }
}
