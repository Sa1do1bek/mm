package com.enterprise.mm.overview.service;

import com.enterprise.mm.overview.payload.OperationalOverviewDtos.ProcessStage;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.RawException;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.TrendPoint;
import java.util.List;

@FunctionalInterface
public interface OperationalOverviewDataSource {
    OperationalFacts load();

    record OperationalFacts(
        Long criticalOverdue,
        Long blockedFlow,
        Long demandMaterialCount,
        Long shortageMaterialCount,
        List<CohortSource> criticalOverdueSources,
        List<CohortSource> blockedFlowSources,
        List<ProcessStage> stages,
        List<TrendPoint> trend,
        List<SourceHealth> sourceHealth,
        List<RawException> priorityExceptions
    ) {
        public OperationalFacts {
            criticalOverdueSources = criticalOverdueSources == null
                ? List.of() : List.copyOf(criticalOverdueSources);
            blockedFlowSources = blockedFlowSources == null
                ? List.of() : List.copyOf(blockedFlowSources);
            stages = stages == null ? List.of() : List.copyOf(stages);
            trend = trend == null ? List.of() : List.copyOf(trend);
            sourceHealth = sourceHealth == null ? List.of() : List.copyOf(sourceHealth);
            priorityExceptions = priorityExceptions == null ? List.of() : List.copyOf(priorityExceptions);
        }
    }

    record CohortSource(String key, String label, Long count, String drilldownUrl) {
    }
}
