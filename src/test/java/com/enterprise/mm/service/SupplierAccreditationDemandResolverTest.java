package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Contract tests for the shared procurement-line accreditation classifier. */
class SupplierAccreditationDemandResolverTest {

    private final MaterialRepository materialRepository = mock(MaterialRepository.class);
    private final SupplierAccreditationDemandResolver resolver =
        new SupplierAccreditationDemandResolver(materialRepository);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void distinguishesServiceOnlyAndWorksOnlyDocuments() {
        PurchaseOrderItem serviceLine = purchaseOrderLine(ProcurementLineType.SERVICE, null);
        PurchaseOrderItem worksLine = purchaseOrderLine(ProcurementLineType.WORK, null);

        assertThat(resolver.forPurchaseOrderItems(List.of(serviceLine)))
            .isEqualTo(AccreditationDemand.services());
        assertThat(resolver.forPurchaseOrderItems(List.of(worksLine)))
            .isEqualTo(AccreditationDemand.works());
    }

    @Test
    void mixedDocumentRequiresEveryTypedScopeAndResolvedMaterialGroup() {
        TenantContext.setTenant("tenant-a");
        UUID companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        Material material = new Material();
        material.setId(materialId);
        material.setTenantId("tenant-a");
        material.setCompanyId(companyId);
        material.setMaterialGroupId(materialGroupId);
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", materialId))
            .thenReturn(Optional.of(material));

        AccreditationDemand demand = resolver.forPurchaseOrderItems(List.of(
            purchaseOrderLine(ProcurementLineType.MATERIAL, materialId),
            purchaseOrderLine(ProcurementLineType.SERVICE, null),
            purchaseOrderLine(ProcurementLineType.WORK, null)
        ));

        assertThat(demand).isEqualTo(new AccreditationDemand(
            Set.of(materialGroupId), true, true, true, true));
    }

    @Test
    void unresolvedMaterialLineFailsClosedWithoutDiscardingOtherScopes() {
        AccreditationDemand demand = resolver.forPurchaseOrderItems(List.of(
            purchaseOrderLine(ProcurementLineType.MATERIAL, UUID.randomUUID()),
            purchaseOrderLine(ProcurementLineType.SERVICE, null)
        ));

        assertThat(demand).isEqualTo(new AccreditationDemand(
            Set.of(), true, false, true, false));
    }

    @Test
    void contractSpecificationsUseTheSameDemandRules() {
        ContractSpecification material = new ContractSpecification();
        material.setLineType(ProcurementLineType.MATERIAL);
        material.setMaterialId(null);
        ContractSpecification works = new ContractSpecification();
        works.setLineType(ProcurementLineType.WORK);

        assertThat(resolver.forContractSpecifications(List.of(material, works)))
            .isEqualTo(new AccreditationDemand(Set.of(), true, false, false, true));
    }

    @Test
    void groupContractSpecificationSuppliesMaterialAccreditationScope() {
        UUID materialGroupId = UUID.randomUUID();
        ContractSpecification groupLine = new ContractSpecification();
        groupLine.setLineType(ProcurementLineType.MATERIAL);
        groupLine.setMaterialGroupId(materialGroupId);

        assertThat(resolver.forContractSpecifications(List.of(groupLine)))
            .isEqualTo(new AccreditationDemand(Set.of(materialGroupId), true, true, false, false));
    }

    @Test
    void mixedContractSpecificationsCollectGroupAndExactMaterialScopes() {
        TenantContext.setTenant("tenant-a");
        UUID companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        UUID groupLineId = UUID.randomUUID();
        UUID exactMaterialGroupId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        Material material = new Material();
        material.setCompanyId(companyId);
        material.setMaterialGroupId(exactMaterialGroupId);
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", materialId))
            .thenReturn(Optional.of(material));

        ContractSpecification groupLine = new ContractSpecification();
        groupLine.setLineType(ProcurementLineType.MATERIAL);
        groupLine.setMaterialGroupId(groupLineId);
        ContractSpecification exactLine = new ContractSpecification();
        exactLine.setLineType(ProcurementLineType.MATERIAL);
        exactLine.setMaterialId(materialId);

        assertThat(resolver.forContractSpecifications(List.of(groupLine, exactLine)))
            .isEqualTo(new AccreditationDemand(
                Set.of(groupLineId, exactMaterialGroupId), true, true, false, false));
    }

    @Test
    void sameTenantMaterialFromAnotherCompanyCannotSupplyAccreditationScope() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(UUID.randomUUID());
        UUID materialId = UUID.randomUUID();
        Material otherCompanyMaterial = new Material();
        otherCompanyMaterial.setId(materialId);
        otherCompanyMaterial.setTenantId("tenant-a");
        otherCompanyMaterial.setCompanyId(UUID.randomUUID());
        otherCompanyMaterial.setMaterialGroupId(UUID.randomUUID());
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", materialId))
            .thenReturn(Optional.of(otherCompanyMaterial));

        assertThat(resolver.forPurchaseOrderItems(List.of(
            purchaseOrderLine(ProcurementLineType.MATERIAL, materialId))))
            .isEqualTo(new AccreditationDemand(Set.of(), true, false, false, false));
    }

    private PurchaseOrderItem purchaseOrderLine(ProcurementLineType lineType, UUID materialId) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setLineType(lineType);
        item.setMaterialId(materialId);
        return item;
    }
}
