package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetAcceptanceLineResponse extends BaseRecordDto {
    private UUID assetAcceptanceId;
    private UUID purchaseOrderItemId;
    private UUID contractSpecificationId;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private String materialTypeSnapshot;
    private String unit;
    private String unitShortName;
    private BigDecimal deliveredQuantity;
    private BigDecimal acceptedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal pendingQuantity;
    private BigDecimal holdQuantity;
    private String qualityDecision;
    private BigDecimal acceptedValue;
    private String acceptedCurrencyCode;
    private List<AssetAcceptanceUnitResponse> units = List.of();
}
