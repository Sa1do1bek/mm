package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MarketPriceResearchSecurityContractTest {
    @Test
    void marketPriceResearchJobAllowsProcurementAndDemandPlanRolesBeforeFinanceWriteFallback() throws Exception {
        String security = Files.readString(Path.of("src/main/java/com/enterprise/mm/security/SecurityConfig.java"));

        int researchRule = security.indexOf(".requestMatchers(HttpMethod.POST, \"/api/market-prices/ai/research/jobs\")");
        int financeWriteFallback = security.indexOf(".requestMatchers(FINANCE).hasAnyRole");

        assertThat(researchRule).isGreaterThanOrEqualTo(0);
        assertThat(financeWriteFallback).isGreaterThan(researchRule);
        assertThat(security.substring(researchRule, financeWriteFallback))
            .contains("\"PROCUREMENT_MANAGER\"", "\"PROCUREMENT_SPECIALIST\"", "\"DEPARTMENT_HEAD\"", "\"DEPARTMENT_WORKER\"");
    }
}
