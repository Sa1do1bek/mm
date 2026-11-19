package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.security.pilot.PilotCapability;
import com.enterprise.mm.security.pilot.PilotCapabilityPolicy;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ContractManualIssueContextResponse.ContractManualIssueLineResponse;
import com.enterprise.mm.payload.WarehouseReceiptProfileDtos.*;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class WarehouseReceiptEligibilityService {
    private final WarehouseReceiptProfileService profiles;
    private final MaterialGroupRepository groups;
    private final MaterialRepository materials;
    private final ContractReceiptLimitService limits;
    private final PilotCapabilityPolicy capabilities;
    private final StockBalanceRepository balances;

    @Transactional(readOnly = true)
    public List<ContractManualIssueLineResponse> enrich(UUID warehouseId,
        List<ContractManualIssueLineResponse> lines, boolean memberPicker) {
        return enrich(warehouseId, lines, memberPicker, false);
    }

    /** Warehouse preferences suggest the initial selection; they do not veto a user's choice. */
    @Transactional(readOnly = true)
    public List<ContractManualIssueLineResponse> enrichAdvisory(UUID warehouseId,
        List<ContractManualIssueLineResponse> lines, boolean memberPicker) {
        return enrich(warehouseId, lines, memberPicker, true);
    }

    private List<ContractManualIssueLineResponse> enrich(UUID warehouseId,
        List<ContractManualIssueLineResponse> lines, boolean memberPicker, boolean advisory) {
        Profile profile = profiles.get(warehouseId);
        boolean inferred = advisory && !profile.configured();
        if (inferred) {
            var knownGroups = balances.findReceiptStockGroups(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId);
            if (!knownGroups.isEmpty()) profile = new Profile(warehouseId, true, null, profile.purpose(),
                knownGroups.stream().map(id -> new Rule(id, Effect.ALLOW, false)).toList());
        }
        List<UUID> ids = lines.stream().map(ContractManualIssueLineResponse::materialId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, Material> catalog = ids.isEmpty() ? Map.of() : materials
            .findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), ids)
            .stream().collect(Collectors.toMap(Material::getId, Function.identity()));
        Map<UUID, Optional<MaterialGroup>> groupCache = new HashMap<>();
        var stock = ids.isEmpty() ? Map.<UUID, StockBalanceRepository.WarehouseMaterialBalanceSummaryProjection>of()
            : balances.findWarehouseBalancesByMaterialIds(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId, ids)
                .stream().collect(Collectors.toMap(StockBalanceRepository.WarehouseMaterialBalanceSummaryProjection::getMaterialId,
                    Function.identity()));
        Map<UUID, BigDecimal> remainingBySpecification = new HashMap<>();
        List<ContractManualIssueLineResponse> result = new ArrayList<>();
        for (var line : lines) {
            Material material = catalog.get(line.materialId());
            BigDecimal remaining = line.remainingReceiptQuantity() != null ? line.remainingReceiptQuantity()
                : remainingBySpecification.computeIfAbsent(line.specificationId(),
                    id -> limits.remaining(id, line.contractQuantityLimit()));
            Selection decision = remaining.signum() <= 0
                ? decision(profile, Eligibility.BLOCKED, false, "CONTRACT_LIMIT_EXHAUSTED", "No contract receipt quantity remains")
                : evaluate(profile, material, groupCache);
            if (inferred && profile.configured()) decision = new Selection(decision.eligibility(), decision.recommendedSelected(),
                "WAREHOUSE_STOCK_GROUP", decision.reasonCode(), decision.recommendedSelected()
                    ? "Material group is already held in this warehouse" : decision.reasonText(), null);
            if (advisory && Set.of("MATERIAL_GROUP_DENIED", "MATERIAL_GROUP_NOT_ALLOWED").contains(decision.reasonCode())
                && technicalProblem(material) == null
                && remaining.signum() > 0) {
                decision = new Selection(Eligibility.REVIEW_REQUIRED, false, decision.decisionSource(),
                    decision.reasonCode(), decision.reasonText(), decision.policyVersion());
            }
            // Existing group-line metadata denotes the contractual group and must remain intact.
            MaterialGroup actualGroup = material == null || material.getMaterialGroupId() == null ? null
                : lookup(material.getMaterialGroupId(), groupCache).orElse(null);
            var balance = stock.get(line.materialId());
            var summary = new com.enterprise.mm.payload.ContractManualIssueContextResponse.WarehouseStockBalanceSummary(
                balance == null ? BigDecimal.ZERO : balance.getTotalAvailable(),
                balance == null ? BigDecimal.ZERO : balance.getTotalOnHand(),
                balance == null ? BigDecimal.ZERO : balance.getTotalReserved(),
                balance != null && balance.getTotalAvailable().signum() > 0,
                line.availableStock() == null ? null : line.availableStock().defaultStockPositionId());
            result.add(line.withStock(summary).withReceipt(remaining,
                line.materialGroupId() != null ? line.materialGroupId() : actualGroup == null ? null : actualGroup.getId(),
                line.materialGroupId() != null ? line.materialGroupCode() : actualGroup == null ? null : actualGroup.getCode(),
                line.materialGroupId() != null ? line.materialGroupName() : actualGroup == null ? null : actualGroup.getName(),
                line.unitOfMeasureId() != null ? line.unitOfMeasureId() : material == null ? null : material.getUnitOfMeasureId(), decision));
        }
        Map<UUID, Long> eligiblePerSpec = result.stream().filter(l -> l.receiptSelection().recommendedSelected())
            .collect(Collectors.groupingBy(ContractManualIssueLineResponse::specificationId, Collectors.counting()));
        Profile resolvedProfile = profile;
        return result.stream().map(line -> {
            if (line.receiptSelection().recommendedSelected()
                && (memberPicker || eligiblePerSpec.getOrDefault(line.specificationId(), 0L) > 1)) {
                return line.withReceipt(line.remainingReceiptQuantity(), line.materialGroupId(), line.materialGroupCode(),
                    line.materialGroupName(), line.unitOfMeasureId(), decision(resolvedProfile, Eligibility.ALLOWED, false,
                        "MATERIAL_CHOICE_REQUIRED", "Choose material quantities within the shared contract group limit"));
            }
            return line;
        }).toList();
    }

    /** Caller holds the warehouse inventory lock, also used by profile updates. */
    public void validate(Profile profile, Material material) {
        Selection decision = evaluate(profile, material, new HashMap<>());
        if (decision.eligibility() == Eligibility.BLOCKED)
            throw new BusinessException("Warehouse receipt blocked for material " + material.getId() + ": " + decision.reasonText());
    }

    public void validateMaterial(Material material) {
        String problem = technicalProblem(material);
        if (problem != null) throw new BusinessException(problem);
    }

    private String technicalProblem(Material material) {
        if (material == null || material.isDeleted() || !"ACTIVE".equals(material.getStatus())
            || material.getMaterialType() != MaterialType.TMB
            || !TenantContext.currentTenant().equals(material.getTenantId())
            || !CompanyContext.currentCompany().equals(material.getCompanyId())) return "MATERIAL_UNAVAILABLE";
        if (material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED
            && !capabilities.enabled(PilotCapability.SERIALIZED_EXECUTION)) return "SERIALIZED_EXECUTION_DISABLED";
        return null;
    }

    Selection evaluate(Profile profile, Material material, Map<UUID, Optional<MaterialGroup>> cache) {
        if (material == null || material.isDeleted() || !"ACTIVE".equals(material.getStatus())
            || material.getMaterialType() != MaterialType.TMB
            || !TenantContext.currentTenant().equals(material.getTenantId())
            || !CompanyContext.currentCompany().equals(material.getCompanyId()))
            return decision(profile, Eligibility.BLOCKED, false, "MATERIAL_UNAVAILABLE", "Material is unavailable for this receipt flow");
        if (material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED
            && !capabilities.enabled(PilotCapability.SERIALIZED_EXECUTION))
            return decision(profile, Eligibility.BLOCKED, false, "SERIALIZED_EXECUTION_DISABLED", "Serialized material execution is disabled");
        if (!profile.configured())
            return decision(profile, Eligibility.REVIEW_REQUIRED, false, "WAREHOUSE_PROFILE_NOT_CONFIGURED", "Warehouse receipt profile is not configured; select manually");
        Set<UUID> ancestors = new HashSet<>();
        UUID id = material.getMaterialGroupId();
        while (id != null) {
            if (!ancestors.add(id))
                return decision(profile, Eligibility.BLOCKED, false, "MATERIAL_GROUP_HIERARCHY_INVALID", "Material group hierarchy contains a cycle");
            Optional<MaterialGroup> group = lookup(id, cache);
            if (group.isEmpty() || !"ACTIVE".equals(group.get().getStatus()))
                return decision(profile, Eligibility.BLOCKED, false, "MATERIAL_GROUP_UNAVAILABLE", "Material group hierarchy is unavailable");
            id = group.get().getParentId();
        }
        boolean allowed = false;
        for (Rule rule : profile.rules()) {
            boolean matches = Objects.equals(rule.materialGroupId(), material.getMaterialGroupId())
                || (rule.includeDescendants() && ancestors.contains(rule.materialGroupId()));
            if (!matches) continue;
            if (rule.effect() == Effect.DENY)
                return decision(profile, Eligibility.BLOCKED, false, "MATERIAL_GROUP_DENIED", "Material group is denied by the warehouse receipt profile");
            allowed = true;
        }
        return allowed ? decision(profile, Eligibility.ALLOWED, true, "MATERIAL_GROUP_ALLOWED", "Material group matches the warehouse receipt profile")
            : decision(profile, Eligibility.BLOCKED, false, "MATERIAL_GROUP_NOT_ALLOWED", "Material group is not allowed by the warehouse receipt profile");
    }
    private Optional<MaterialGroup> lookup(UUID id, Map<UUID, Optional<MaterialGroup>> cache) {
        return cache.computeIfAbsent(id, key -> groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), key));
    }
    private Selection decision(Profile p, Eligibility status, boolean selected, String code, String text) {
        return new Selection(status, selected, "MATERIAL_GROUP_POLICY", code, text, p.version());
    }
}
