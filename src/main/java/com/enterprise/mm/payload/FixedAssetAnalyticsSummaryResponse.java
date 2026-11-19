package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.Map;
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
public class FixedAssetAnalyticsSummaryResponse {
    private long totalCount;
    private long inWarehouseCount;
    private long inUseCount;
    private long idleCount;
    private long underRepairCount;
    private long disposedCount;
    private BigDecimal totalAcquisitionValue;
    private BigDecimal totalAccumulatedDepreciation;
    private BigDecimal totalNetBookValue;
    private Map<String, CategoryMetric> categoryMetrics;
    private Map<String, DepartmentMetric> departmentMetrics;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryMetric {
        private String category;
        private long count;
        private BigDecimal totalAcquisitionValue;
        private BigDecimal totalBookValue;
        private BigDecimal totalAccumulatedDepreciation;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepartmentMetric {
        private String departmentName;
        private long count;
        private BigDecimal totalBookValue;
    }
}
