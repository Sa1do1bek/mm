package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.WarehouseCommandViewDtos;
import com.enterprise.mm.repository.FixedAssetRepository;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WarehouseCommandViewFixedAssetsContractTest {

    @Test
    void commandViewContractCarriesFixedAssetPermissionCountAndPreview() {
        assertThat(Arrays.stream(WarehouseCommandViewDtos.TabKey.values()).map(Enum::name))
            .contains("FIXED_ASSETS");
        assertThat(Arrays.stream(WarehouseCommandViewDtos.OverviewPreviews.class.getRecordComponents())
            .map(component -> component.getName()))
            .contains("fixedAssets");
        assertThat(Arrays.stream(WarehouseCommandViewDtos.class.getDeclaredClasses())
            .map(Class::getSimpleName))
            .contains("FixedAssetRow");
    }

    @Test
    void repositorySupportsPagedWarehouseCustodyAssetsForSummaryCountsAndPreview() {
        assertThat(Arrays.stream(FixedAssetRepository.class.getMethods())
            .map(method -> method.getName()))
            .contains("findByTenantIdAndCompanyIdAndWarehouseIdAndCustodyKindAndIsDeletedFalse");
    }
}
