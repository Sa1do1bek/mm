package com.enterprise.mm.overview.service;

import com.enterprise.mm.overview.payload.ManagementOverviewDtos.ConcentrationCell;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.DecisionRow;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.LeadTimeBucket;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.MonthTrend;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SupplierExposure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@FunctionalInterface
public interface ManagementOverviewDataSource {
    ManagementFacts load(LocalDate from, LocalDate to, String requestedCurrency);

    record ManagementFacts(
        String currencyCode,
        Long fulfilledOnTimeCount,
        Long fulfilledDemandCount,
        BigDecimal grossInventoryValue,
        BigDecimal impairmentReserve,
        BigDecimal frozenExposure,
        Long deliveryScheduleCount,
        Long overdueDeliveryCount,
        Long orderedCount,
        Long completedOrderCount,
        List<MonthTrend> trends,
        List<ConcentrationCell> concentration,
        List<SupplierExposure> supplierExposure,
        List<LeadTimeBucket> leadTime,
        List<DecisionRow> decisions,
        List<SourceHealth> sourceHealth
    ) {
        public ManagementFacts {
            trends = copy(trends);
            concentration = copy(concentration);
            supplierExposure = copy(supplierExposure);
            leadTime = copy(leadTime);
            decisions = copy(decisions);
            sourceHealth = copy(sourceHealth);
        }

        private static <T> List<T> copy(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }
}
