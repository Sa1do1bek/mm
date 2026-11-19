package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class EnterpriseIntegrationDtos {
    private EnterpriseIntegrationDtos() {
    }

    public record EnterpriseIntegrationRequest(
        UUID aggregateId,
        String externalDocumentId,
        String documentNumber,
        LocalDate documentDate,
        String documentType,
        String status,
        BigDecimal amount,
        String currencyCode,
        String supplierTin,
        String supplierName,
        String contractNumber,
        String purchaseOrderNumber,
        String budgetCode,
        String costCenter,
        String warehouseCode,
        String storageLocationCode,
        String materialCode,
        BigDecimal quantity,
        String unitCode,
        String targetPath,
        Object payload,
        String idempotencyKey,
        Boolean dispatchNow
    ) {
    }

    public record EnterpriseIntegrationBatchRequest(
        @NotEmpty List<@Valid EnterpriseIntegrationRequest> items,
        String idempotencyKey,
        Boolean dispatchNow
    ) {
    }

    public record EnterpriseIntegrationPushResult(
        String system,
        String eventType,
        int queued,
        List<UUID> eventIds
    ) {
    }
}