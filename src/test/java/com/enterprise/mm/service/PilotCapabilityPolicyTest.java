package com.enterprise.mm.service;

import com.enterprise.mm.security.pilot.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class PilotCapabilityPolicyTest {
    @Test void codeEnabledServiceWorksWithAnEmptyDeploymentAllowlist() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("capabilities", java.util.Map.of(
                "mm.pilot.enabled-capabilities", "",
                "mm.pilot.code-enabled-capabilities", "SERVICE_EXECUTION")));
            context.register(PilotCapabilityPolicy.class);
            context.refresh();
            var policy = context.getBean(PilotCapabilityPolicy.class);
            assertThatCode(() -> policy.require(PilotCapability.SERVICE_EXECUTION)).doesNotThrowAnyException();
            assertThat(policy.enabledCapabilities()).containsExactly(PilotCapability.SERVICE_EXECUTION);
        }
    }

    @ParameterizedTest @EnumSource(PilotCapability.class)
    void emptyConfigurationDeniesEveryControlledCapability(PilotCapability capability) {
        var policy = new PilotCapabilityPolicy("");
        assertThat(policy.enabled(capability)).isFalse();
        assertThatThrownBy(() -> policy.require(capability)).isInstanceOf(BusinessException.class).hasMessageContaining(capability.name());
    }

    @ParameterizedTest @EnumSource(PilotCapability.class)
    void explicitAllowlistEnablesOnlySelectedCapability(PilotCapability capability) {
        var policy = new PilotCapabilityPolicy(capability.name());
        assertThatCode(() -> policy.require(capability)).doesNotThrowAnyException();
        assertThat(policy.enabledCapabilities()).containsExactly(capability);
    }

    @Test void invalidConfigurationFailsStartupRatherThanEnablingFeatures() {
        assertThatThrownBy(() -> new PilotCapabilityPolicy("ALL" )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void commandAdvisorPreservesReadsAndEnabledExecution() {
        var disabled = proxy(new PilotCapabilityPolicy(""));
        assertThat(disabled.history()).isEqualTo("history");
        assertThatThrownBy(disabled::mutate).isInstanceOf(BusinessException.class);
        assertThat(proxy(new PilotCapabilityPolicy("FIXED_ASSET_EXECUTION")).mutate()).isEqualTo("mutated");
    }

    @Test void persistenceBackstopProtectsInternalAdaptersButKeepsBudgetReservations() {
        var guard = new PilotPersistenceGuard(new PilotCapabilityPolicy(""));
        for (Object entity : java.util.List.of(new FixedAsset(), new MaterialUnit(), new ServiceAcceptanceAct(), new ContractPayment())) {
            assertThatThrownBy(() -> guard.check(entity, null, null)).isInstanceOf(BusinessException.class);
        }
        var budget = new BudgetItem(); budget.setReservedAmount(BigDecimal.TEN); budget.setUsedAmount(BigDecimal.ZERO);
        assertThatCode(() -> guard.check(budget, null, null)).doesNotThrowAnyException();
        budget.setUsedAmount(BigDecimal.ONE);
        assertThatThrownBy(() -> guard.check(budget, null, null)).isInstanceOf(BusinessException.class).hasMessageContaining("MM_FINANCE_EXECUTION");
        var enabled = new PilotPersistenceGuard(new PilotCapabilityPolicy(Arrays.stream(PilotCapability.values()).map(Enum::name).collect(Collectors.joining(","))));
        assertThatCode(() -> enabled.check(new MaterialUnit(), null, null)).doesNotThrowAnyException();
    }

    @Test void excludedReplayTypesCannotReachTransportWhileOrdinaryStockEventsCan() {
        var policy = new PilotCapabilityPolicy("");
        for (String type : java.util.List.of("TREASURY.PAYMENT_REQUEST", "ASSET.COMMISSIONED", "MM_MRP_PR_CREATED", "SERVICE.ACCEPTED")) {
            var event = new IntegrationEvent(); event.setEventType(type);
            assertThat(policy.permitsIntegration(event)).isFalse();
            assertThatThrownBy(() -> policy.requireIntegration(event)).isInstanceOf(BusinessException.class);
        }
        var event = new IntegrationEvent(); event.setEventType("MM_STOCK_ISSUED");
        assertThat(policy.permitsIntegration(event)).isTrue();
    }

    private Commands proxy(PilotCapabilityPolicy policy) {
        ProxyFactory factory = new ProxyFactory(new Commands()); factory.setProxyTargetClass(true);
        factory.addAdvisor(new PilotCapabilityConfiguration().pilotCapabilityAdvisor(policy));
        return (Commands) factory.getProxy();
    }
    @PilotCapabilityBoundary(value=PilotCapability.FIXED_ASSET_EXECUTION, reads={"history"})
    public static class Commands {
        public String history() { return "history"; }
        public String mutate() { return "mutated"; }
    }
}
