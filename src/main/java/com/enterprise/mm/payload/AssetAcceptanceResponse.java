package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetAcceptanceResponse extends BaseRecordDto {
    private UUID contractId;
    private String contractNumber;
    private String contractName;
    private UUID purchaseOrderId;
    private String purchaseOrderName;
    private UUID supplierId;
    private String supplierName;
    private UUID asnId;
    private String deliveryReference;
    private UUID warehouseId;
    private String warehouseName;
    private UUID storageLocationId;
    private UUID receivedByEmployeeId;
    private LocalDate receiptDate;
    private LocalDateTime receivedAt;
    private String currencyCode;
    private String exchangeRateSource;
    private String sourceEvidence;
    private String approvalStatus;
    private String postingStatus;
    private UUID approvalInstanceId;
    private LocalDateTime postedAt;
    private String postedBy;
    private List<AssetAcceptanceLineResponse> lines = List.of();
    @JsonIgnore
    @Override
    public String getCode() {
        return super.getCode();
    }
}
