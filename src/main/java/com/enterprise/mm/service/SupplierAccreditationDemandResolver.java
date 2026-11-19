package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Canonical typed accreditation-demand resolver for procurement documents and awards. */
@Component
@RequiredArgsConstructor
public class SupplierAccreditationDemandResolver {
    private final MaterialRepository materialRepository;

    public AccreditationDemand forPurchaseOrderItems(Collection<PurchaseOrderItem> items) {
        return resolve(
            items == null ? null : items.stream()
                .map(item -> item == null ? null : new DemandLine(item.getLineType(), item.getMaterialId()))
                .toList(),
            CompanyContext.currentCompany(),
            materialRepository
        );
    }

    public AccreditationDemand forContractSpecifications(Collection<ContractSpecification> specifications) {
        return resolve(
            specifications == null ? null : specifications.stream()
                .map(specification -> specification == null
                    ? null
                    : new DemandLine(specification.getLineType(), specification.getMaterialId(),
                        specification.getMaterialGroupId()))
                .toList(),
            CompanyContext.currentCompany(),
            materialRepository
        );
    }

    public AccreditationDemand forRfqItems(Collection<RFQItem> items) {
        return resolve(
            items == null ? null : items.stream()
                .map(item -> item == null ? null : new DemandLine(item.getLineType(), item.getMaterialId()))
                .toList(),
            CompanyContext.currentCompany(),
            materialRepository
        );
    }

    public AccreditationDemand forPurchaseRequestItems(
        RequestType requestType,
        Collection<PurchaseRequestItem> items
    ) {
        return resolve(
            items == null ? null : items.stream()
                .map(item -> item == null ? null : new DemandLine(
                    item.getLineType() == ProcurementLineType.WORK
                        ? ProcurementLineType.WORK
                        : requestType != null && requestType.isServiceStyle()
                        ? ProcurementLineType.SERVICE
                        : item.getLineType(),
                    item.getMaterialId()))

                .toList(),
            CompanyContext.currentCompany(),
            materialRepository
        );
    }

    public static AccreditationDemand resolve(
        Collection<DemandLine> lines,
        UUID companyId,
        MaterialRepository materialRepository
    ) {
        Set<UUID> materialGroupIds = new LinkedHashSet<>();
        Map<UUID, Optional<Material>> materialById = new HashMap<>();
        boolean materialRequired = false;
        boolean materialScopeComplete = true;
        boolean serviceRequired = false;
        boolean worksRequired = false;

        if (lines != null) {
            for (DemandLine line : lines) {
                ProcurementLineType lineType = line == null || line.lineType() == null
                    ? ProcurementLineType.MATERIAL
                    : line.lineType();
                switch (lineType) {
                    case SERVICE -> serviceRequired = true;
                    case WORK -> worksRequired = true;
                    case MATERIAL -> {
                        materialRequired = true;
                        UUID materialId = line == null ? null : line.materialId();
                        if (materialId == null) {
                            UUID materialGroupId = line == null ? null : line.materialGroupId();
                            if (materialGroupId == null) {
                                materialScopeComplete = false;
                            } else {
                                materialGroupIds.add(materialGroupId);
                            }
                            continue;
                        }
                        Optional<Material> material = materialById.computeIfAbsent(
                            materialId,
                            id -> materialRepository.findByTenantIdAndIdAndIsDeletedFalse(
                                    TenantContext.currentTenant(), id)
                                .filter(candidate -> Objects.equals(companyId, candidate.getCompanyId())));
                        UUID materialGroupId = material.map(Material::getMaterialGroupId).orElse(null);
                        if (materialGroupId == null) {
                            materialScopeComplete = false;
                        } else {
                            materialGroupIds.add(materialGroupId);
                        }
                    }
                }
            }
        }

        return new AccreditationDemand(
            materialGroupIds,
            materialRequired,
            materialScopeComplete,
            serviceRequired,
            worksRequired);
    }

    public record DemandLine(ProcurementLineType lineType, UUID materialId, UUID materialGroupId) {
        public DemandLine(ProcurementLineType lineType, UUID materialId) {
            this(lineType, materialId, null);
        }
    }
}
