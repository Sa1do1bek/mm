package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
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
public class AssetDepreciationRunResponse {
    private String runReference;
    private Integer year;
    private Integer month;
    private int processedAssetCount;
    private BigDecimal totalDepreciationAmount;
    private List<AssetDepreciationEntryResponse> entries;
}
