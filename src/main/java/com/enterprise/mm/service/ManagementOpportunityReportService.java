package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.entity.ManagementOpportunityEvidence;
import com.enterprise.mm.payload.ManagementOpportunityBreakdownResponse;
import com.enterprise.mm.payload.ManagementOpportunitySearchRequest;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse;
import com.enterprise.mm.repository.ManagementOpportunityEvidenceRepository;
import com.enterprise.mm.repository.ManagementOpportunityRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementOpportunityReportService {
    private static final ZoneId UZBEKISTAN_ZONE = ZoneId.of("Asia/Tashkent");

    private final ManagementOpportunityRepository repository;
    private final ManagementOpportunityEvidenceRepository evidenceRepository;
    private final ManagementOpportunityAnalytics analytics;

    public ManagementOpportunityReportService(
        ManagementOpportunityRepository repository,
        ManagementOpportunityEvidenceRepository evidenceRepository
    ) {
        this.repository = repository;
        this.evidenceRepository = evidenceRepository;
        this.analytics = new ManagementOpportunityAnalytics(Clock.system(UZBEKISTAN_ZONE));
    }

    @Transactional(readOnly = true)
    public ManagementOpportunitySummaryResponse summary(ManagementOpportunitySearchRequest request) {
        return analytics.summary(filteredCases(normalize(request)));
    }

    @Transactional(readOnly = true)
    public ManagementOpportunityBreakdownResponse breakdown(
        ManagementOpportunitySearchRequest request,
        Pageable pageable
    ) {
        List<ManagementOpportunity> cases = filteredCases(normalize(request));
        Map<UUID, UUID> materialGroupByMaterial = materialGroups(cases);
        ManagementOpportunityBreakdownResponse pageWithoutEvidence = analytics.breakdown(
            cases,
            Map.of(),
            materialGroupByMaterial,
            pageable
        );
        List<UUID> caseIds = pageWithoutEvidence.cases().stream()
            .map(ManagementOpportunityBreakdownResponse.CaseDrilldown::id)
            .toList();
        Map<UUID, List<UUID>> evidenceIdsByCase = caseIds.isEmpty()
            ? Map.of()
            : evidenceRepository
                .findByTenantIdAndOpportunityIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(),
                    caseIds
                )
                .stream()
                .collect(Collectors.groupingBy(
                    ManagementOpportunityEvidence::getOpportunityId,
                    Collectors.mapping(ManagementOpportunityEvidence::getId, Collectors.toList())
                ));
        return evidenceIdsByCase.isEmpty()
            ? pageWithoutEvidence
            : analytics.breakdown(cases, evidenceIdsByCase, materialGroupByMaterial, pageable);
    }

    private List<ManagementOpportunity> filteredCases(ManagementOpportunitySearchRequest request) {
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            request.includeArchived(),
            request.type(),
            blankToNull(request.status()),
            request.completeness(),
            blankToNull(request.sourceAggregateType()),
            request.sourceAggregateId(),
            request.supplierId(),
            request.materialId(),
            request.warehouseId(),
            request.confidence(),
            blankToNull(request.assignedRole()),
            request.asOfDateFrom(),
            request.asOfDateTo(),
            Pageable.unpaged()
        ).getContent();
    }

    private Map<UUID, UUID> materialGroups(List<ManagementOpportunity> cases) {
        List<UUID> materialIds = cases.stream()
            .map(ManagementOpportunity::getMaterialId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        return repository.findMaterialGroups(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            materialIds
        ).stream().filter(row -> row.getMaterialGroupId() != null).collect(Collectors.toMap(
            ManagementOpportunityRepository.MaterialGroupMappingProjection::getMaterialId,
            ManagementOpportunityRepository.MaterialGroupMappingProjection::getMaterialGroupId
        ));
    }

    private ManagementOpportunitySearchRequest normalize(ManagementOpportunitySearchRequest request) {
        return request == null
            ? new ManagementOpportunitySearchRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, false
            )
            : request;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
