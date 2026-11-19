package com.enterprise.mm.payload;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockValuationStatisticsResponse {
    private long totalRuns;
    private long completedRuns;
    private long failedRuns;
    private BigDecimal currentGlobalStockValue;
    private long valuatedMaterialsCount;
}
