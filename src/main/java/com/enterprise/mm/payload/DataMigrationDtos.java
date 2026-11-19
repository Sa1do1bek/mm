package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class DataMigrationDtos {
    private DataMigrationDtos() {
    }

    public record MigrationTemplateDto(
        String code,
        String name,
        String description,
        String downloadUrl
    ) {
    }

    public record MigrationValidationResponse(
        UUID runId,
        String migrationType,
        String sourceSystem,
        String status,
        String fileName,
        int totalRows,
        int validRows,
        int warningRows,
        int errorRows,
        LocalDateTime finishedAt,
        List<MigrationValidationRowDto> rows
    ) {
    }

    public record MigrationValidationRowDto(
        int rowNumber,
        String rowStatus,
        String sourceKey,
        String companyCode,
        String warehouseCode,
        String storageLocationCode,
        String materialCode,
        String materialName,
        String article,
        String uomCode,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalValue,
        String currencyCode,
        String accountCode,
        String ikpuCode,
        String tnvedCode,
        LocalDate balanceDate,
        List<String> errors,
        List<String> warnings
    ) {
    }
}