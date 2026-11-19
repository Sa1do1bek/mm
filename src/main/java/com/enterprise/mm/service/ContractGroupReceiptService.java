package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ContractManualIssueContextResponse.ContractManualIssueLineResponse;
import com.enterprise.mm.payload.ContractManualIssueContextResponse.WarehouseStockBalanceSummary;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.security.pilot.PilotCapability;
import com.enterprise.mm.security.pilot.PilotCapabilityPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Group lines are resolved to an actual material only at warehouse receipt. */
@Service
@RequiredArgsConstructor
public class ContractGroupReceiptService {
    private final ContractSpecificationRepository specifications;
    private final MaterialRepository materials;
    private final MaterialGroupRepository groups;
    private final UnitOfMeasureRepository units;
    private final ContractReceiptLimitService receiptLimits;
    private final PilotCapabilityPolicy capabilities;
    private final ContractMaterialLinkRepository links;
    private final ObjectMapper mapper;
    private final EntityManager em;

    List<ContractManualIssueLineResponse> contextLines(UUID contractId) {
        List<ContractManualIssueLineResponse> result = new ArrayList<>();
        for (ContractSpecification spec : specifications.findByTenantIdAndContractIdAndIsDeletedFalse(TenantContext.currentTenant(), contractId)) {
            if (!CompanyContext.currentCompany().equals(spec.getCompanyId()) || !executable(spec)) continue;
            var group = group(spec);
            if (group.isEmpty() || unit(spec).isEmpty()) continue;
            var members = eligibleMembers(spec, "", Pageable.unpaged(Sort.by("code", "id")));
            if (members.isEmpty()) continue;
            BigDecimal available = remaining(spec);
            for (Material material : members) result.add(line(spec, group.get(), material, available));
        }
        return List.copyOf(result);
    }

    PageResponse<ContractManualIssueLineResponse> members(UUID contractId, UUID specificationId, String search, Pageable pageable) {
        ContractSpecification spec = specifications.findByIdAndIsDeletedFalse(specificationId)
            .filter(s -> TenantContext.currentTenant().equals(s.getTenantId())
                && CompanyContext.currentCompany().equals(s.getCompanyId()) && contractId.equals(s.getContractId()))
            .orElseThrow(() -> new NotFoundException("Contract specification not found"));
        MaterialGroup group = requireExecutable(spec);
        BigDecimal remaining = remaining(spec);
        String term = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        return PageResponse.from(eligibleMembers(spec, term, pageable)
            .map(material -> line(spec, group, material, remaining)));
    }

    private Page<Material> eligibleMembers(ContractSpecification spec, String term, Pageable pageable) {
        List<UUID> verified = links.findByTenantIdAndCompanyIdAndSpecificationIdOrderByCreatedAtAscIdAsc(
            spec.getTenantId(), spec.getCompanyId(), spec.getId()).stream()
            .filter(link -> materials.findByIdAndIsDeletedFalse(link.getMaterialId())
                .filter(material -> bindingMatches(spec, material, link)).isPresent())
            .map(ContractMaterialLink::getMaterialId).toList();
        // A nonempty sentinel avoids provider-specific empty IN-list behavior.
        if (verified.isEmpty()) verified = List.of(new UUID(0, 0));
        return materials.findContractGroupReceiptMembers(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            spec.getMaterialGroupId(), spec.getUnitOfMeasureId(), spec.getId(), verified,
            capabilities.enabled(PilotCapability.SERIALIZED_EXECUTION), term, pageable);
    }

    /** Caller holds the specification's pessimistic write lock until posting commits. */
    void validateReceipt(ContractSpecification spec, Material material, BigDecimal quantity) {
        validateReceipt(spec, material, quantity, BigDecimal.ZERO);
    }

    BigDecimal validateReceipt(ContractSpecification spec, Material material, BigDecimal quantity, BigDecimal alreadyRequested) {
        var binding = links.findByTenantIdAndCompanyIdAndSpecificationIdAndMaterialId(
            spec.getTenantId(), spec.getCompanyId(), spec.getId(), material.getId());
        if (binding.isPresent()) {
            // Stock execution may already have loaded these entities. Refresh under locks before trusting their snapshots.
            SerialTrackingPolicy preparedSerialPolicy = material.getSerialTrackingPolicy();
            em.refresh(material, LockModeType.PESSIMISTIC_WRITE);
            if (preparedSerialPolicy != material.getSerialTrackingPolicy())
                throw new BusinessException("Material serial tracking changed during receipt preparation; reload the material");
            for (UUID id : java.util.stream.Stream.of(binding.get().getContractUnitOfMeasureId(), binding.get().getStockUnitOfMeasureId()).distinct().sorted().toList()) {
                UnitOfMeasure unit = em.find(UnitOfMeasure.class, id);
                if (unit == null) throw new BusinessException("Contract material link unit snapshot is unavailable");
                em.refresh(unit, LockModeType.PESSIMISTIC_READ);
            }
        }
        requireExecutable(spec);
        if (material.getMaterialType() != MaterialType.TMB || !"ACTIVE".equals(material.getStatus()) || material.isDeleted()
            || !spec.getTenantId().equals(material.getTenantId()) || !spec.getCompanyId().equals(material.getCompanyId())
            || (!capabilities.enabled(PilotCapability.SERIALIZED_EXECUTION) && material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED)) {
            throw new BusinessException("Selected material is not an eligible member of the contract group");
        }
        BigDecimal factor = factor(spec, material);
        if (quantity == null || quantity.signum() <= 0) throw new BusinessException("Receipt quantity must be positive");
        BigDecimal contractQuantity = quantity.multiply(factor);
        receiptLimits.validate(spec, contractQuantity, alreadyRequested);
        return contractQuantity;
    }

    private MaterialGroup requireExecutable(ContractSpecification spec) {
        if (!executable(spec)) throw new BusinessException("Group contract specification must be ACTIVE with a resolved unit and positive quantity");
        if (unit(spec).isEmpty()) throw new BusinessException("Contract group unit is unavailable in the current company");
        return group(spec).orElseThrow(() -> new BusinessException("Contract material group is unavailable in the current company"));
    }

    private boolean executable(ContractSpecification spec) {
        return !spec.isDeleted() && "ACTIVE".equals(spec.getStatus()) && spec.getLineType() == ProcurementLineType.MATERIAL
            && spec.getMaterialGroupId() != null && spec.getMaterialId() == null && spec.getUnitOfMeasureId() != null
            && spec.getQuantityLimit() != null && spec.getQuantityLimit().signum() > 0
            && spec.getPrice() != null && spec.getPrice().signum() >= 0;
    }

    String unavailableReason(ContractSpecification spec) {
        if (!"ACTIVE".equals(spec.getStatus())) return "SPECIFICATION_NOT_ACTIVE";
        if (spec.getMaterialGroupId() == null || spec.getMaterialId() != null) return "INVALID_GROUP_SPECIFICATION";
        if (spec.getQuantityLimit() == null || spec.getQuantityLimit().signum() <= 0) return "INVALID_CONTRACT_QUANTITY";
        if (spec.getPrice() == null || spec.getPrice().signum() < 0) return "INVALID_CONTRACT_PRICE";
        if (spec.getUnitOfMeasureId() == null || unit(spec).isEmpty()) return "CONTRACT_UNIT_UNAVAILABLE";
        if (group(spec).isEmpty()) return "CONTRACT_GROUP_UNAVAILABLE";
        return null;
    }

    private Optional<MaterialGroup> group(ContractSpecification spec) {
        return groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), spec.getMaterialGroupId())
            .filter(g -> "ACTIVE".equals(g.getStatus()));
    }

    private Optional<UnitOfMeasure> unit(ContractSpecification spec) {
        return units.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), spec.getUnitOfMeasureId())
            .filter(u -> "ACTIVE".equals(u.getStatus()));
    }

    private BigDecimal remaining(ContractSpecification spec) {
        return receiptLimits.remaining(spec.getId(), spec.getQuantityLimit());
    }

    private BigDecimal factor(ContractSpecification spec, Material material) {
        var link = links.findByTenantIdAndCompanyIdAndSpecificationIdAndMaterialId(
            spec.getTenantId(), spec.getCompanyId(), spec.getId(), material.getId());
        if (link.isPresent()) {
            if (!bindingMatches(spec, material, link.get()))
                throw new BusinessException("Contract material link unit or source snapshot changed; receipt is unavailable");
            return link.get().getContractQuantityPerStockQuantity();
        }
        if (!spec.getMaterialGroupId().equals(material.getMaterialGroupId()))
            throw new BusinessException("Selected material is not an eligible member of the contract group");
        if (!spec.getUnitOfMeasureId().equals(material.getUnitOfMeasureId()))
            throw new BusinessException("Selected material unit does not match the contract group unit");
        return BigDecimal.ONE;
    }

    private boolean bindingMatches(ContractSpecification spec, Material material, ContractMaterialLink link) {
        if (!spec.getTenantId().equals(material.getTenantId()) || !spec.getCompanyId().equals(material.getCompanyId())
            || !Objects.equals(spec.getContractId(), link.getContractId())
            || !Objects.equals(spec.getMaterialGroupId(), link.getContractMaterialGroupId())
            || !Objects.equals(spec.getUnitOfMeasureId(), link.getContractUnitOfMeasureId())
            || !Objects.equals(material.getMaterialGroupId(), link.getStockMaterialGroupId())
            || !Objects.equals(material.getUnitOfMeasureId(), link.getStockUnitOfMeasureId())
            || !Objects.equals(material.getCode(), link.getSourcePayload().path("expectedMaterialCode").asText())
            || material.getDescription() == null || !material.getDescription().contains(ContractMaterialLinkMigrationService.marker(
                link.getSourcePayload().path("materialPreparedManifestSha256").asText(),
                link.getSourcePayload().path("materialSourceEntityType").asText(), link.getMaterialSourceId()))) return false;
        var contractUnit = unit(spec);
        var stockUnit = units.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(spec.getTenantId(), spec.getCompanyId(), material.getUnitOfMeasureId())
            .filter(u -> "ACTIVE".equals(u.getStatus()));
        return contractUnit.isPresent() && stockUnit.isPresent()
            && link.getContractUnitSnapshot().equals(mapper.valueToTree(ContractMaterialLinkUnits.snapshot(contractUnit.get())))
            && link.getStockUnitSnapshot().equals(mapper.valueToTree(ContractMaterialLinkUnits.snapshot(stockUnit.get())));
    }

    private ContractManualIssueLineResponse line(ContractSpecification spec, MaterialGroup group, Material material, BigDecimal remaining) {
        BigDecimal factor = material == null ? BigDecimal.ONE : factor(spec, material);
        int scale = material == null ? 7 : StockUomPolicy.quantityScale(material);
        BigDecimal limit = material == null ? spec.getQuantityLimit() : spec.getQuantityLimit().divide(factor, scale, RoundingMode.DOWN);
        BigDecimal available = material == null ? remaining : remaining.divide(factor, scale, RoundingMode.DOWN);
        UnitOfMeasure stockUnit = material == null ? null : material.getUnitOfMeasure();
        String unitLabel = material == null ? spec.getUnit() :
            (stockUnit.getMeasurementNameShort() == null || stockUnit.getMeasurementNameShort().isBlank() ? stockUnit.getCode() : stockUnit.getMeasurementNameShort());
        return new ContractManualIssueLineResponse(spec.getId(), material == null ? null : material.getId(),
            material == null ? group.getCode() : material.getCode(), material == null ? group.getName() : material.getName(),
            unitLabel, limit, spec.getPrice().multiply(factor), spec.getQuantityLimit().multiply(spec.getPrice()),
            MaterialType.TMB.name(), material == null || material.getSerialTrackingPolicy() == null
                ? "NONE" : material.getSerialTrackingPolicy().name(),
            new WarehouseStockBalanceSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null),
            List.of(), group.getId(), group.getCode(), group.getName(), material == null ? spec.getUnitOfMeasureId() : material.getUnitOfMeasureId(),
            available, material == null ? null : factor.stripTrailingZeros().toPlainString());
    }
}
