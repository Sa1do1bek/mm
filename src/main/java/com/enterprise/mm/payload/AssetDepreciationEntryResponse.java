package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetDepreciationEntryResponse extends BaseRecordDto {
    private UUID fixedAssetId;
    private String fixedAssetName;
    private String assetCode;
    private String inventoryNumber;
    private Integer periodYear;
    private Integer periodMonth;
    private LocalDate periodDate;
    private BigDecimal openingBookValue;
    private BigDecimal depreciationAmount;
    private BigDecimal closingBookValue;
    private BigDecimal accumulatedDepreciation;
    private String calculationMethod;
    private boolean isPosted;
    private LocalDateTime postedAt;
    private String postedBy;
    private String runReference;
}
