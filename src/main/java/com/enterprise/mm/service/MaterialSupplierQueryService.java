package com.enterprise.mm.service;

import com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility;
import com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierRelationshipKind;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierRelationshipRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SupplierScopeStatus;
import com.enterprise.mm.repository.MaterialSupplierRelationshipQueryRepository;
import com.enterprise.mm.repository.MaterialSupplierRelationshipQueryRepository.SupplierEvidenceRecord;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialSupplierQueryService {
    private final MaterialSupplierRelationshipQueryRepository evidenceRepository;
    private final SupplierAccreditationService accreditationService;
    private final Clock materialCommandViewClock;

    public Page<SupplierRelationshipRow> page(MaterialScope scope, SupplierFilter filter, Pageable pageable) {
        return page(scope, filter, pageable, LocalDate.now(materialCommandViewClock));
    }

    public Page<SupplierRelationshipRow> page(
        MaterialScope scope, SupplierFilter filter, Pageable pageable, LocalDate asOfDate
    ) {
        Pageable bounded = bounded(pageable);
        Page<SupplierEvidenceRecord> evidence = evidenceRepository.page(
            scope.tenantId(), scope.companyId(), scope.materialId(), filter, bounded);
        UUID materialGroupId = scope.material().getMaterialGroupId();
        Map<UUID, EffectiveSupplierEligibility> eligibilities = eligibilities(
            evidence, materialGroupId, asOfDate);
        return evidence.map(item -> map(item, eligibilities.get(item.supplierId())));
    }

    // UX-LIVE-02: same effectiveEligibility snapshot as the list/dossier/PO/receipt surfaces, so
    // NOT_ELIGIBLE (evaluated but not covered) is distinguishable from UNKNOWN (no scope known).
    private Map<UUID, EffectiveSupplierEligibility> eligibilities(
        Page<SupplierEvidenceRecord> evidence, UUID materialGroupId, LocalDate asOfDate
    ) {
        if (evidence.isEmpty() || materialGroupId == null) {
            return Map.of();
        }
        Map<UUID, EffectiveSupplierEligibility> result = new LinkedHashMap<>();
        evidence.stream().map(SupplierEvidenceRecord::supplierId).distinct().forEach(supplierId ->
            result.put(supplierId, accreditationService.effectiveEligibility(
                supplierId, java.util.List.of(materialGroupId), asOfDate, "material 360")));
        return Map.copyOf(result);
    }

    private SupplierRelationshipRow map(SupplierEvidenceRecord evidence, EffectiveSupplierEligibility eligibility) {
        return new SupplierRelationshipRow(
            evidence.id(), evidence.supplierId(), evidence.supplierCode(), evidence.supplierName(),
            SupplierRelationshipKind.TRANSACTIONAL_HISTORY, evidence.sourceType(), evidence.sourceId(),
            evidence.lastPrice() == null ? null : evidence.lastPrice().toPlainString(), evidence.currencyCode(),
            evidence.evidenceDate(), scopeStatus(eligibility),
            applicationStatus(eligibility),
            LinkTarget.of(evidence.moduleKey(), evidence.sourceId(), Map.of()));
    }

    private SupplierScopeStatus scopeStatus(EffectiveSupplierEligibility eligibility) {
        if (eligibility == null || "UNKNOWN".equals(eligibility.overallStatus())) {
            return SupplierScopeStatus.UNKNOWN;
        }
        return eligibility.eligibleForRequestedScope() ? SupplierScopeStatus.APPROVED : SupplierScopeStatus.DENIED;
    }

    private String applicationStatus(EffectiveSupplierEligibility eligibility) {
        return eligibility == null || eligibility.groups().isEmpty()
            ? null
            : eligibility.groups().get(0).applicationStatus();
    }

    private Pageable bounded(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) return PageRequest.of(0, 25);
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), Math.min(pageable.getPageSize(), 100));
    }
}
