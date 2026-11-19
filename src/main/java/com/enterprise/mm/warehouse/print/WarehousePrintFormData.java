package com.enterprise.mm.warehouse.print;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WarehousePrintFormData(
    WarehousePrintFormType formType,
    String formCode,
    String formTitle,
    String organizationName,
    String organizationInn,
    String structuralDivision,
    String documentNumber,
    LocalDate documentDate,
    String operationType,
    UUID sourceWarehouseId,
    String sourceWarehouseName,
    String sourceDepartmentName,
    String sourcePersonName,
    UUID targetWarehouseId,
    String targetWarehouseName,
    String targetDepartmentName,
    String targetPersonName,
    String supplierName,
    String supplierInn,
    String contractNumber,
    LocalDate contractDate,
    String invoiceNumber,
    LocalDate invoiceDate,
    String currencyCode,
    List<WarehousePrintFormLine> lines,
    BigDecimal totalQuantity,
    BigDecimal totalAmount,
    String totalAmountInWords,
    String releasedByName,
    String releasedByRole,
    String receivedByName,
    String receivedByRole,
    String approvedByName,
    String approvedByRole,
    String accountantName,
    String notes
) {
    public WarehousePrintFormData withLines(List<WarehousePrintFormLine> lines) {
        return new WarehousePrintFormData(formType, formCode, formTitle, organizationName, organizationInn, structuralDivision,
            documentNumber, documentDate, operationType, sourceWarehouseId, sourceWarehouseName, sourceDepartmentName,
            sourcePersonName, targetWarehouseId, targetWarehouseName, targetDepartmentName, targetPersonName, supplierName,
            supplierInn, contractNumber, contractDate, invoiceNumber, invoiceDate, currencyCode, lines, totalQuantity,
            totalAmount, totalAmountInWords, releasedByName, releasedByRole, receivedByName, receivedByRole, approvedByName,
            approvedByRole, accountantName, notes);
    }
}
