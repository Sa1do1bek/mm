package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.ContractManualIssueContextResponse.ContractManualIssueLineResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.WarehouseReceiptContextDtos.*;
import com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Selection;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.MaterialRepository;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WarehouseReceiptContextService {
    private static final int MATERIAL_PREVIEW_SIZE = 10;
    private final ManualWarehouseOperationService operations;
    private final ContractSpecificationRepository specifications;
    private final MaterialRepository materials;
    private final ContractGroupReceiptService groups;
    private final ContractReceiptLimitService limits;
    private final WarehouseReceiptEligibilityService eligibility;
    private final WarehouseReceiptProfileService profiles;

    @Transactional(readOnly = true)
    public Context get(UUID warehouseId, UUID contractId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Page must be nonnegative and size between 1 and 100");
        // Scope is checked before any specification, material or profile is exposed.
        var scope = operations.requireReceiptScope(warehouseId, contractId);
        var profile = profiles.get(warehouseId);
        var positions = specifications.findReceiptPositions(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            contractId, PageRequest.of(page, size, Sort.by("createdAt", "id")));
        var directIds = positions.stream().map(ContractSpecification::getMaterialId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, Material> direct = directIds.isEmpty() ? Map.of() : materials
            .findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), directIds)
            .stream().collect(Collectors.toMap(Material::getId, Function.identity()));
        List<Prepared> prepared = new ArrayList<>();
        for (var spec : positions) prepared.add(prepare(contractId, spec, direct));
        var enriched = eligibility.enrichAdvisory(warehouseId,
            prepared.stream().flatMap(p -> p.candidates().stream()).toList(), false);
        Map<String, ContractManualIssueLineResponse> byKey = enriched.stream().collect(Collectors.toMap(
            line -> key(line.specificationId(), line.materialId()), Function.identity()));
        List<Position> result = new ArrayList<>();
        for (var p : prepared) {
            var spec = p.spec();
            List<ContractManualIssueLineResponse> candidates = p.candidates().stream()
                .map(line -> byKey.get(key(line.specificationId(), line.materialId())))
                .map(line -> p.problem() == null ? requireMaterialChoice(line, spec.getMaterialGroupId() != null && p.count() > 1)
                    : line.withReceipt(line.remainingReceiptQuantity(), line.materialGroupId(), line.materialGroupCode(),
                        line.materialGroupName(), line.unitOfMeasureId(), new Selection(
                            com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Eligibility.BLOCKED, false,
                            "CONTRACT_READINESS", p.problem(), "Resolve the contract position before receipt", null))).toList();
            String problem = p.problem();
            if (problem == null && candidates.isEmpty()) problem = "MATERIAL_BINDING_REQUIRED";
            if (problem == null && candidates.size() > 1 && candidates.stream().allMatch(line ->
                line.receiptSelection() != null && line.receiptSelection().eligibility()
                    == com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Eligibility.BLOCKED))
                problem = "NO_EXECUTABLE_MATERIALS";
            if (problem == null && candidates.size() == 1 && candidates.getFirst().receiptSelection() != null
                && candidates.getFirst().receiptSelection().eligibility()
                    == com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Eligibility.BLOCKED)
                problem = candidates.getFirst().receiptSelection().reasonCode();
            if (problem == null && p.count() > 1) problem = "MATERIAL_CHOICE_REQUIRED";
            result.add(new Position(spec.getId(), spec.getVersion(), spec.getName(), spec.getStatus(),
                spec.getMaterialGroupId() == null ? "MATERIAL" : "MATERIAL_GROUP", spec.getMaterialGroupId(),
                spec.getUnitOfMeasureId(), spec.getUnit(), spec.getQuantityLimit(), spec.getPrice(), p.remaining(),
                problem == null ? Readiness.ready() : Readiness.blocked(problem), p.count(), candidates));
        }
        return new Context(contractId, scope.contract().getContractNumber(), warehouseId, scope.warehouse().getName(),
            profile.purpose(), profile.version(), new PageResponse<>(List.copyOf(result), page, size,
                positions.getTotalElements(), positions.getTotalPages()));
    }

    private Prepared prepare(UUID contractId, ContractSpecification spec, Map<UUID, Material> direct) {
        BigDecimal remaining = limits.remaining(spec.getId(), spec.getQuantityLimit());
        String problem = !"ACTIVE".equals(spec.getStatus()) ? "SPECIFICATION_NOT_ACTIVE"
            : spec.getQuantityLimit() == null || spec.getQuantityLimit().signum() <= 0 ? "INVALID_CONTRACT_QUANTITY"
            : spec.getPrice() == null || spec.getPrice().signum() < 0 ? "INVALID_CONTRACT_PRICE" : null;
        if (spec.getMaterialGroupId() != null) {
            if (problem == null) problem = groups.unavailableReason(spec);
            if (problem != null) return new Prepared(spec, remaining, problem, 0, List.of());
            var members = groups.members(contractId, spec.getId(), "", PageRequest.of(0, MATERIAL_PREVIEW_SIZE, Sort.by("name", "id")));
            return new Prepared(spec, remaining, remaining.signum() <= 0 ? "CONTRACT_LIMIT_EXHAUSTED" : null,
                members.totalElements(), members.content());
        }
        Material material = spec.getMaterialId() == null ? null : direct.get(spec.getMaterialId());
        if (material == null) return new Prepared(spec, remaining,
            problem == null ? "MATERIAL_BINDING_REQUIRED" : problem, 0, List.of());
        if (problem == null && spec.getUnitOfMeasureId() != null
            && !spec.getUnitOfMeasureId().equals(material.getUnitOfMeasureId())) problem = "CONTRACT_UNIT_MISMATCH";
        var unit = material.getUnitOfMeasure();
        String unitLabel = unit == null ? spec.getUnit() : unit.getMeasurementNameShort() == null
            ? unit.getCode() : unit.getMeasurementNameShort();
        var line = new ContractManualIssueLineResponse(spec.getId(), material.getId(), material.getCode(), material.getName(),
            unitLabel, spec.getQuantityLimit(), spec.getPrice(), spec.getPrice() == null || spec.getQuantityLimit() == null
                ? null : spec.getPrice().multiply(spec.getQuantityLimit()),
            material.getMaterialType() == null ? null : material.getMaterialType().name(),
            material.getSerialTrackingPolicy() == null ? "NONE" : material.getSerialTrackingPolicy().name(),
            null, List.of(), null, null, null, material.getUnitOfMeasureId(), remaining, "1");
        return new Prepared(spec, remaining, problem, 1, List.of(line));
    }

    private ContractManualIssueLineResponse requireMaterialChoice(ContractManualIssueLineResponse line, boolean multiple) {
        Selection s = line.receiptSelection();
        if (!multiple || s == null || !s.recommendedSelected()) return line;
        return line.withReceipt(line.remainingReceiptQuantity(), line.materialGroupId(), line.materialGroupCode(),
            line.materialGroupName(), line.unitOfMeasureId(), new Selection(s.eligibility(), false, s.decisionSource(),
                "MATERIAL_CHOICE_REQUIRED", "Choose a material within the shared contract group limit", s.policyVersion()));
    }

    static String key(UUID specificationId, UUID materialId) { return specificationId + ":" + materialId; }
    private record Prepared(ContractSpecification spec, BigDecimal remaining, String problem, long count,
        List<ContractManualIssueLineResponse> candidates) {}
}
