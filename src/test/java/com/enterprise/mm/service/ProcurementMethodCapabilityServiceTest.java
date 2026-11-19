package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementMethodCapability;
import com.enterprise.mm.enums.ProcurementMethodCapabilityState;
import com.enterprise.mm.payload.ProcurementMethodCapabilityDtos;
import com.enterprise.mm.repository.ProcurementMethodCapabilityRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProcurementMethodCapabilityServiceTest {
    private final ProcurementMethodCapabilityRepository repository = mock(ProcurementMethodCapabilityRepository.class);
    private final ProcurementMethodCapabilityService service = new ProcurementMethodCapabilityService(repository, new ProcurementMethodPolicy());

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void missingConfigurationIsUnavailableBySafeDefault() {
        when(repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "OPEN_TENDER"))
            .thenReturn(Optional.empty());

        var result = service.evaluate("OPEN_TENDER", BigDecimal.TEN, "UZS", "GOODS", null);

        assertThat(result.available()).isFalse();
        assertThat(result.blockingReasons()).containsExactly("procurement.method_capability.not_configured");
        assertThat(result.metadata().showMethod()).isFalse();
    }

    @Test
    void auctionIsIncludedInNormalAvailabilityAndUsesConfiguredState() {
        ProcurementMethodCapability capability = new ProcurementMethodCapability();
        capability.setMethodCode("E_AUCTION");
        capability.setState(ProcurementMethodCapabilityState.ENABLED);
        when(repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "E_AUCTION"))
            .thenReturn(Optional.of(capability));

        assertThat(service.availability(BigDecimal.TEN, "UZS", "GOODS", null))
            .anySatisfy(evaluation -> {
                assertThat(evaluation.methodCode()).isEqualTo("E_AUCTION");
                assertThat(evaluation.available()).isTrue();
                assertThat(evaluation.blockingReasons()).isEmpty();
                assertThat(evaluation.metadata().showMethod()).isTrue();
            });

        var result = service.evaluate("E_AUCTION", BigDecimal.TEN, "UZS", "GOODS", null);

        assertThat(result.available()).isTrue();
        assertThat(result.blockingReasons()).isEmpty();
        assertThat(result.metadata().showMethod()).isTrue();
    }

    @Test
    void auctionCapabilityCanBeEnabledByConfiguration() {
        var request = new ProcurementMethodCapabilityDtos.UpsertRequest("E_AUCTION", ProcurementMethodCapabilityState.ENABLED,
            null, null, null, null, "UZS", null, null, false, false, true, 1, "attempt");
        when(repository.existsByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "E_AUCTION"))
            .thenReturn(false);
        when(repository.save(org.mockito.ArgumentMatchers.any(ProcurementMethodCapability.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(request);

        assertThat(response.methodCode()).isEqualTo("E_AUCTION");
        assertThat(response.state()).isEqualTo(ProcurementMethodCapabilityState.ENABLED);
    }

    @Test
    void enforcementCarriesLocalizationKeyAndExplicitCompanyScope() {
        UUID targetCompany = UUID.randomUUID();
        when(repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", targetCompany, "OPEN_TENDER"))
            .thenReturn(Optional.empty());
        var error = assertThrows(com.enterprise.mm.error.BusinessException.class,
            () -> service.requireAvailableForCompany("OPEN_TENDER", BigDecimal.TEN, "UZS", "GOODS", null, targetCompany));
        assertThat(error.getMessageKey())
            .isEqualTo("procurement.method_capability.not_configured");
        verify(repository).findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", targetCompany, "OPEN_TENDER");
    }

    @Test
    void stateThresholdCurrencyAndRestrictionFailuresAreUserVisible() {
        ProcurementMethodCapability capability = new ProcurementMethodCapability();
        capability.setMethodCode("OPEN_TENDER");
        capability.setState(ProcurementMethodCapabilityState.PILOT);
        capability.setMinAmount(BigDecimal.valueOf(100));
        capability.setMaxAmount(BigDecimal.valueOf(200));
        capability.setCurrencyCode("UZS");
        capability.setAllowedRequestTypes(java.util.Set.of("SERVICES"));
        capability.setAllowedCategoryIds(java.util.Set.of("category-a"));
        when(repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "OPEN_TENDER"))
            .thenReturn(Optional.of(capability));

        var result = service.evaluate("OPEN_TENDER", BigDecimal.valueOf(50), "USD", "GOODS", "category-b");

        assertThat(result.available()).isFalse();
        assertThat(result.blockingReasons()).containsExactlyInAnyOrder(
            "procurement.method_capability.amount_below_minimum", "procurement.method_capability.currency_not_allowed",
            "procurement.method_capability.request_type_not_allowed", "procurement.method_capability.category_not_allowed");
        assertThat(result.metadata().showPilotBadge()).isTrue();
    }

    @Test
    void rejectsInvalidThresholdsAndDateRanges() {
        var dates = new ProcurementMethodCapabilityDtos.UpsertRequest("OPEN_TENDER", ProcurementMethodCapabilityState.ENABLED,
            java.time.LocalDate.of(2026, 2, 1), java.time.LocalDate.of(2026, 1, 1), null, null, "UZS", null, null, false, false, true, 1, "bad");
        assertThatThrownBy(() -> service.create(dates)).isInstanceOf(com.enterprise.mm.error.BusinessException.class);

        var amounts = new ProcurementMethodCapabilityDtos.UpsertRequest("OPEN_TENDER", ProcurementMethodCapabilityState.ENABLED,
            null, null, BigDecimal.TEN, BigDecimal.ONE, "UZS", null, null, false, false, true, 1, "bad");
        assertThatThrownBy(() -> service.create(amounts)).isInstanceOf(com.enterprise.mm.error.BusinessException.class);
    }

    @Test
    void availabilityCannotBypassConfiguredRestrictionsWhenContextIsMissing() {
        ProcurementMethodCapability capability = new ProcurementMethodCapability();
        capability.setMethodCode("OPEN_TENDER");
        capability.setState(ProcurementMethodCapabilityState.ENABLED);
        capability.setMinAmount(BigDecimal.ONE);
        capability.setCurrencyCode("UZS");
        capability.setAllowedRequestTypes(java.util.Set.of("GOODS"));
        capability.setAllowedCategoryIds(java.util.Set.of("category-a"));
        when(repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "OPEN_TENDER"))
            .thenReturn(Optional.of(capability));

        var result = service.evaluate("OPEN_TENDER", null, null, null, null);

        assertThat(result.available()).isFalse();
        assertThat(result.blockingReasons()).containsExactlyInAnyOrder(
            "procurement.method_capability.amount_required", "procurement.method_capability.currency_required",
            "procurement.method_capability.request_type_required", "procurement.method_capability.category_required");
    }

    @Test
    void effectiveDateAndMaximumBoundariesAreEnforced() {
        ProcurementMethodCapability capability = new ProcurementMethodCapability();
        capability.setMethodCode("OPEN_TENDER");
        capability.setState(ProcurementMethodCapabilityState.ENABLED);
        capability.setEffectiveFrom(LocalDate.now().plusDays(1));
        capability.setMaxAmount(BigDecimal.valueOf(100));
        when(repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "OPEN_TENDER"))
            .thenReturn(Optional.of(capability));
        assertThat(service.evaluate("OPEN_TENDER", BigDecimal.valueOf(100), null, null, null).blockingReasons())
            .contains("procurement.method_capability.not_yet_effective");
        capability.setEffectiveFrom(LocalDate.now().minusDays(1));
        assertThat(service.evaluate("OPEN_TENDER", BigDecimal.valueOf(101), null, null, null).blockingReasons())
            .contains("procurement.method_capability.amount_above_maximum");
    }

    @Test
    void updateUsesActiveTenantCompanyScopeAndNeverMovesAnotherCompanyRow() {
        UUID id = UUID.randomUUID();
        UUID currentCompany = CompanyContext.currentCompany();
        when(repository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(id, "default", currentCompany))
            .thenReturn(Optional.empty());
        var request = new ProcurementMethodCapabilityDtos.UpsertRequest("OPEN_TENDER", ProcurementMethodCapabilityState.ENABLED,
            null, null, null, null, "UZS", null, null, false, false, true, 1, "attempt");
        assertThatThrownBy(() -> service.update(id, request)).isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        verify(repository).findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(id, "default", currentCompany);
    }

    @Test
    void normalizesNullRestrictionEntriesAndPopulatesActorAuditFields() {
        var request = new ProcurementMethodCapabilityDtos.UpsertRequest("OPEN_TENDER", ProcurementMethodCapabilityState.ENABLED,
            null, null, null, null, "UZS", new java.util.LinkedHashSet<>(java.util.Arrays.asList("GOODS", null, " ")), null, false, false, true, 1, "initial");
        when(repository.existsByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "OPEN_TENDER"))
            .thenReturn(false);
        when(repository.save(org.mockito.ArgumentMatchers.any(ProcurementMethodCapability.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        var response = service.create(request);
        assertThat(response.allowedRequestTypes()).containsExactly("GOODS");
        assertThat(response.configuredAt()).isNotNull();
        assertThat(response.configuredBy()).isEqualTo("system");
        assertThat(response.companyId()).isEqualTo(CompanyContext.currentCompany());
    }

    @Test
    void rejectsDuplicateActiveCompanyMethodConfiguration() {
        when(repository.existsByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "OPEN_TENDER"))
            .thenReturn(true);
        var request = new ProcurementMethodCapabilityDtos.UpsertRequest("OPEN_TENDER", ProcurementMethodCapabilityState.ENABLED,
            null, null, null, null, "UZS", null, null, false, false, true, 1, "duplicate");
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void availabilityForwardsQueryContextIntoRestrictionEvaluation() {
        ProcurementMethodCapability capability = new ProcurementMethodCapability();
        capability.setMethodCode("OPEN_TENDER");
        capability.setState(ProcurementMethodCapabilityState.ENABLED);
        capability.setMinAmount(BigDecimal.valueOf(100));
        capability.setCurrencyCode("UZS");
        capability.setAllowedRequestTypes(java.util.Set.of("GOODS"));
        capability.setAllowedCategoryIds(java.util.Set.of("category-a"));
        when(repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", CompanyContext.currentCompany(), "OPEN_TENDER"))
            .thenReturn(Optional.of(capability));

        var result = service.availability(BigDecimal.valueOf(50), "USD", "SERVICES", "category-b").stream()
            .filter(evaluation -> evaluation.methodCode().equals("OPEN_TENDER"))
            .findFirst().orElseThrow();

        assertThat(result.blockingReasons()).containsExactlyInAnyOrder(
            "procurement.method_capability.amount_below_minimum",
            "procurement.method_capability.currency_not_allowed",
            "procurement.method_capability.request_type_not_allowed",
            "procurement.method_capability.category_not_allowed");
    }
}
