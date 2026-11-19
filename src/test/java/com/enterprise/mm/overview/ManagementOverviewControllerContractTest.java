package com.enterprise.mm.overview;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.overview.controller.ManagementOverviewController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ManagementOverviewControllerContractTest {

    @Test
    void warehouseHeadMatchesFrontendWarehouseManagerInheritance() {
        PreAuthorize authorization = ManagementOverviewController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).contains("WAREHOUSE_MANAGER", "WAREHOUSE_HEAD");
    }
}
