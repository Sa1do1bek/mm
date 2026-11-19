package com.enterprise.mm.overview;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.overview.controller.OperationalOverviewController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OperationalOverviewControllerContractTest {

    @Test
    void operationalEndpointUsesAnExplicitMmRoleAllowList() throws Exception {
        PreAuthorize authorization = OperationalOverviewController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).contains("hasAnyRole");
        assertThat(authorization.value()).contains("MM_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_OPERATOR");
        assertThat(authorization.value()).doesNotContain("isAuthenticated");
    }

    @Test
    void operationalEndpointDoesNotAcceptAReportingPeriodForCurrentSnapshotData() throws Exception {
        Method method = OperationalOverviewController.class.getMethod("operational");

        assertThat(method.getParameterCount()).isZero();
    }
}
