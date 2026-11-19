package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ManagementOpportunityCalculationRun;
import com.enterprise.mm.payload.ManagementOpportunityCalculationRunResponse;
import org.springframework.stereotype.Component;

@Component
public class ManagementOpportunityCalculationRunMapper {
    public ManagementOpportunityCalculationRunResponse toResponse(ManagementOpportunityCalculationRun entity) {
        if (entity == null) {
            return null;
        }
        ManagementOpportunityCalculationRunResponse dto = new ManagementOpportunityCalculationRunResponse();
        BaseMapper.copy(entity, dto);
        dto.setAsOfDate(entity.getAsOfDate());
        dto.setTargetCurrencyCode(entity.getTargetCurrencyCode());
        dto.setRequestedTypes(entity.getRequestedTypes());
        dto.setCandidateCount(entity.getCandidateCount());
        dto.setSkippedCount(entity.getSkippedCount());
        dto.setSkipSummary(entity.getSkipSummary());
        dto.setStartedAt(entity.getStartedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setFailureSummary(entity.getFailureSummary());
        return dto;
    }
}
