package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.MaterialType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record WarehouseReport(
    Type type, LocalDate from, LocalDate to, LocalDate coverageStart, LocalDateTime generatedAt,
    String companyName, String currencyCode, UUID warehouseId, String warehouseName, List<String> warnings,
    List<Row> content, int page, int size, long totalElements, List<Total> totals, MaterialSubtype materialSubtype, MaterialType materialType, String accountCode, String sourceType, LocalDate accountSnapshotDate
) {
    public WarehouseReport(Type type, LocalDate from, LocalDate to, LocalDate coverageStart, LocalDateTime generatedAt,
        String companyName, String currencyCode, UUID warehouseId, String warehouseName, List<String> warnings,
        List<Row> content, int page, int size, long totalElements, List<Total> totals, MaterialSubtype materialSubtype, MaterialType materialType) {
        this(type,from,to,coverageStart,generatedAt,companyName,currencyCode,warehouseId,warehouseName,warnings,content,page,size,totalElements,totals,materialSubtype,materialType,null,null,null);
    }

    public enum Type {
        MATERIAL_STATEMENT,
        STOCK_BALANCES,
        RECEIPTS,
        ISSUES,
        TURNOVER,
        GROUP_SUMMARY,
        MATERIALS_IN_USE,
        CUSTODY_STATEMENT,
        INSTALLATION_EQUIPMENT,
        INVENTORY_COMPARISON,
        NONMOVING_STOCK,
        RECONCILIATION
    }

    // Decimal strings preserve the seven-place source quantities in browser clients.
    public record Row(String key, UUID materialId, UUID warehouseId, String warehouseCode, String warehouseName,
        UUID materialGroupId, String groupCode, String materialGroupName, String materialCode, String materialName, String uom, String unitOfMeasureShortName, String unitOfMeasureShortNameRu,
        String unitOfMeasureShortNameUz, String counterparty,
        String correspondingAccount, String price, String openingQuantity, String openingAmount,
        String receiptQuantity, String receiptAmount, String issueQuantity, String issueAmount,
        String closingQuantity, String closingAmount, String accountCode, String accountName, String sourceMaterialType, String stockCategory) {
        public Row(String key, UUID materialId, UUID warehouseId, String warehouseCode, String warehouseName,
            UUID materialGroupId, String groupCode, String materialGroupName, String materialCode, String materialName, String uom,
            String unitOfMeasureShortName, String unitOfMeasureShortNameRu, String unitOfMeasureShortNameUz, String counterparty,
            String correspondingAccount, String price, String openingQuantity, String openingAmount, String receiptQuantity,
            String receiptAmount, String issueQuantity, String issueAmount, String closingQuantity, String closingAmount) {
            this(key,materialId,warehouseId,warehouseCode,warehouseName,materialGroupId,groupCode,materialGroupName,materialCode,materialName,uom,
                unitOfMeasureShortName,unitOfMeasureShortNameRu,unitOfMeasureShortNameUz,counterparty,correspondingAccount,price,
                openingQuantity,openingAmount,receiptQuantity,receiptAmount,issueQuantity,issueAmount,closingQuantity,closingAmount,null,null,null,null);
        }
    }

    // Quantities are totalled separately for each unit; money can be totalled in the company base currency.
    public record Total(String uom, String unitOfMeasureShortName, String unitOfMeasureShortNameRu,
        String unitOfMeasureShortNameUz, String openingQuantity, String openingAmount, String receiptQuantity,
        String receiptAmount, String issueQuantity, String issueAmount, String closingQuantity, String closingAmount, String accountCode, String accountName) {
        public Total(String uom, String unitOfMeasureShortName, String unitOfMeasureShortNameRu, String unitOfMeasureShortNameUz,
            String openingQuantity, String openingAmount, String receiptQuantity, String receiptAmount, String issueQuantity,
            String issueAmount, String closingQuantity, String closingAmount) {
            this(uom,unitOfMeasureShortName,unitOfMeasureShortNameRu,unitOfMeasureShortNameUz,openingQuantity,openingAmount,receiptQuantity,
                receiptAmount,issueQuantity,issueAmount,closingQuantity,closingAmount,null,null);
        }
    }

    public record Filter(Type type, LocalDate from, LocalDate to, UUID warehouseId, UUID materialId,
        UUID materialGroupId, String search, int page, int size, MaterialSubtype materialSubtype, MaterialType materialType, String accountCode, String sourceType, UUID ownerPartyId, java.time.LocalDateTime snapshotTimestamp, List<UUID> warehouseIds) {
        public Filter {
            warehouseIds = warehouseIds == null ? List.of() : List.copyOf(warehouseIds);
        }
        public Filter(Type type, LocalDate from, LocalDate to, UUID warehouseId, UUID materialId,
            UUID materialGroupId, String search, int page, int size, MaterialSubtype materialSubtype, MaterialType materialType, String accountCode, String sourceType, UUID ownerPartyId, java.time.LocalDateTime snapshotTimestamp) {
            this(type,from,to,warehouseId,materialId,materialGroupId,search,page,size,materialSubtype,materialType,accountCode,sourceType,ownerPartyId,snapshotTimestamp,List.of());
        }
        public Filter withWarehouseScope(java.util.Collection<UUID> ids) {
            return new Filter(type,from,to,warehouseId,materialId,materialGroupId,search,page,size,materialSubtype,materialType,accountCode,sourceType,ownerPartyId,snapshotTimestamp,List.copyOf(ids));
        }
        public Filter(Type type, LocalDate from, LocalDate to, UUID warehouseId, UUID materialId,
            UUID materialGroupId, String search, int page, int size, MaterialSubtype materialSubtype, MaterialType materialType, String accountCode, String sourceType, UUID ownerPartyId) {
            this(type,from,to,warehouseId,materialId,materialGroupId,search,page,size,materialSubtype,materialType,accountCode,sourceType,ownerPartyId,null);
        }
        public Filter(Type type, LocalDate from, LocalDate to, UUID warehouseId, UUID materialId,
            UUID materialGroupId, String search, int page, int size, MaterialSubtype materialSubtype, MaterialType materialType, String accountCode, String sourceType) {
            this(type,from,to,warehouseId,materialId,materialGroupId,search,page,size,materialSubtype,materialType,accountCode,sourceType,null,null);
        }
        public Filter(Type type, LocalDate from, LocalDate to, UUID warehouseId, UUID materialId,
            UUID materialGroupId, String search, int page, int size, MaterialSubtype materialSubtype, MaterialType materialType) {
            this(type,from,to,warehouseId,materialId,materialGroupId,search,page,size,materialSubtype,materialType,null,null,null,null);
        }
        public Filter(Type type, LocalDate from, LocalDate to, UUID warehouseId, UUID materialId,
            UUID materialGroupId, String search, int page, int size, MaterialSubtype materialSubtype) {
            this(type,from,to,warehouseId,materialId,materialGroupId,search,page,size,materialSubtype,null,null,null,null,null);
        }
        public Filter(Type type, LocalDate from, LocalDate to, UUID warehouseId, UUID materialId,
            UUID materialGroupId, String search, int page, int size) {
            this(type,from,to,warehouseId,materialId,materialGroupId,search,page,size,null,null,null,null,null,null);
        }
    }

    public record MaterialCard(
        UUID materialId, String materialCode, String materialName, String uom,
        String unitOfMeasureShortName, String unitOfMeasureShortNameRu, String unitOfMeasureShortNameUz,
        UUID warehouseId, String warehouseCode, String warehouseName,
        String accountCode, String accountName,
        LocalDate from, LocalDate to, String currencyCode,
        String openingQuantity, String openingAmount, String openingPrice,
        String totalReceiptQuantity, String totalReceiptAmount,
        String totalIssueQuantity, String totalIssueAmount,
        String closingQuantity, String closingAmount, String closingPrice,
        com.enterprise.mm.payload.PageResponse<MaterialCardEntry> entries
    ) {}

    public record MaterialCardEntry(
        UUID id, LocalDate movementDate, String documentNumber, String documentType,
        UUID referenceId, String operationType, String counterparty, String correspondingAccount,
        String price, String receiptQuantity, String receiptAmount,
        String issueQuantity, String issueAmount,
        String runningQuantity, String runningAmount
    ) {}
}
