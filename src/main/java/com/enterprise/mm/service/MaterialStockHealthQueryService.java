package com.enterprise.mm.service;

import com.enterprise.mm.entity.IlliquidStockPolicy;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.payload.MaterialCommandViewDtos.AllowedModuleKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DecimalMetric;
import com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipType;
import com.enterprise.mm.payload.MaterialCommandViewDtos.StockHealthResponse;
import com.enterprise.mm.repository.IlliquidStockPolicyRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialStockHealthQueryService {
    private final NonMovingStockCaseLineRepository caseLineRepository;
    private final NonMovingStockRepository nonMovingStockRepository;
    private final IlliquidStockPolicyRepository policyRepository;
    private final IlliquidStockPolicyResolver policyResolver;

    public StockHealthSnapshot stockHealthSnapshot(MaterialScope scope, LocalDate asOfDate) {
        UUID warehouseId = scope.warehouseId();
        List<NonMovingStockCaseLineRepository.Material360CaseProjection> cases =
            caseLineRepository.findMaterial360OpenCaseEvidence(
                scope.tenantId(),
                scope.companyId(),
                scope.materialId(),
                warehouseId,
                PageRequest.of(0, 5));
        long openCaseCount = caseLineRepository.countMaterial360OpenCases(
            scope.tenantId(), scope.companyId(), scope.materialId(), warehouseId);
        long activeDetectionCount = nonMovingStockRepository.countMaterial360Active(
            scope.tenantId(), scope.companyId(), scope.materialId(), warehouseId);
        IlliquidStockPolicy resolvedPolicy = resolvePolicy(scope.material(), warehouseId,
            policyRepository.findActivePoliciesForResolution(scope.tenantId(), scope.companyId(), asOfDate));

        List<LinkTarget> reportLinks = new ArrayList<>();
        Map<String, String> reportParameters = resolvedPolicy == null
            ? Map.of("materialId", scope.materialId().toString())
            : Map.of(
                "materialId", scope.materialId().toString(),
                "policyId", resolvedPolicy.getId().toString());
        reportLinks.add(LinkTarget.of(AllowedModuleKey.REPORTS.key(), null, reportParameters));

        Material material = scope.material();
        StockHealthResponse response = new StockHealthResponse(
            threshold(material.getMinStock(), false),
            threshold(material.getSafetyStock(), true),
            threshold(material.getReorderPoint(), true),
            threshold(material.getMaxStock(), false),
            openCaseCount > 0 || activeDetectionCount > 0,
            cases.stream().map(this::mapCase).toList(),
            List.copyOf(reportLinks));
        return new StockHealthSnapshot(response, openCaseCount + activeDetectionCount);
    }

    private RelationshipRow mapCase(NonMovingStockCaseLineRepository.Material360CaseProjection evidence) {
        return new RelationshipRow(
            evidence.getId(),
            RelationshipType.NON_MOVING_STOCK_CASE,
            evidence.getCaseNo(),
            evidence.getCaseName(),
            evidence.getCaseStatus(),
            evidence.getDetectedAt(),
            "NON_MOVING_STOCK_CASE",
            evidence.getCaseId(),
            LinkTarget.of(AllowedModuleKey.NON_MOVING_STOCK_CASES.key(), evidence.getCaseId(), Map.of()));
    }

    private DecimalMetric threshold(BigDecimal value, boolean zeroMeansNoData) {
        if (value == null || (zeroMeansNoData && value.signum() <= 0)) {
            return DecimalMetric.noData();
        }
        return DecimalMetric.available(value);
    }

    private IlliquidStockPolicy resolvePolicy(
        Material material,
        UUID warehouseId,
        List<IlliquidStockPolicy> candidates
    ) {
        String materialCategory = material.getMaterialGroup() == null
            ? null
            : firstNonBlank(material.getMaterialGroup().getCode(), material.getMaterialGroup().getName());
        String materialType = material.getMxik() == null ? "GOODS" : material.getMxik().getType();
        return policyResolver.resolve(candidates, null, warehouseId, materialCategory, materialType).orElse(null);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public record StockHealthSnapshot(StockHealthResponse response, long count) {
    }
}
