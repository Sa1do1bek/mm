package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetDepreciationScheduleResponse {
    private UUID fixedAssetId;
    private String assetName;
    private String assetCode;
    private String inventoryNumber;
    private BigDecimal acquisitionValue;
    private BigDecimal currentBookValue;
    private BigDecimal salvageValue;
    private Integer usefulLifeMonths;
    private Integer remainingLifeMonths;
    private String depreciationMethod;
    private BigDecimal monthlyDepreciationAmount;
    private List<AssetDepreciationEntryResponse> scheduleEntries;
}
