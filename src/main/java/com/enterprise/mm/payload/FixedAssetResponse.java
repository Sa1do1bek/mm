package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FixedAssetResponse extends BaseRecordDto {
    private UUID materialId;
    private String materialName;
    private String materialMxikName;
    private UUID equipmentId;
    private UUID sourceAcceptanceUnitId;
    private String openingSourceType;
    private UUID openingSourceId;
    private String assetCode;
    private String inventoryNumber;
    private String serialNumber;
    private String lifecycleStatus;
    private String custodyKind;
    private UUID custodianEmployeeId;
    private String custodianEmployeeName;
    private UUID departmentId;
    private String departmentName;
    private UUID warehouseId;
    private String warehouseName;
    private UUID storageLocationId;
    private String operatingLocation;
    private UUID activeTransferId;
    private BigDecimal acquisitionValue;
    private String acquisitionCurrencyCode;
    private BigDecimal acquisitionExchangeRate;
    private String acquisitionEvidenceType;
    private UUID acquisitionEvidenceId;
    private LocalDate firstCommissionedOn;
    private LocalDateTime firstCommissionedAt;
    private String firstCommissionedBy;
    private LocalDateTime disposedAt;
    private String category;
    private String depreciationMethod;
    private Integer usefulLifeMonths;
    private BigDecimal salvageValue;
    private BigDecimal accumulatedDepreciation;
    private BigDecimal currentBookValue;
    private LocalDate depreciationStartDate;
    private LocalDate lastDepreciationDate;
    private BigDecimal depreciationRate;
}
