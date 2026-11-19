package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.DemandPlanningCycleMapper;
import com.enterprise.mm.payload.DemandPlanningCycleCreateRequest;
import com.enterprise.mm.payload.DemandPlanningCycleUpdateRequest;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class DemandPlanningCycleEditPolicyTest {
    private static final String TENANT = "tenant-demand-cycle-edit";
    private static final UUID COMPANY_ID =
        UUID.fromString("00000000-0000-0000-0000-000000007705");

    private DemandPlanningCycleRepository cycleRepository;
    private DemandPlanRepository demandPlanRepository;
    private DemandPlanningCycleService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        cycleRepository = mock(DemandPlanningCycleRepository.class);
        demandPlanRepository = mock(DemandPlanRepository.class);
        when(cycleRepository.save(any(DemandPlanningCycle.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(demandPlanRepository.findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(eq(TENANT), any()))
            .thenReturn(List.of());
        service = new DemandPlanningCycleService(
            cycleRepository,
            new DemandPlanningCycleMapper(),
            demandPlanRepository,
            mock(DemandPlanItemRepository.class),
            mock(IntegrationEventService.class),
            mock(AuditService.class),
            mock(EntityManager.class),
            mock(BudgetItemService.class),
            mock(RoleDataScopeService.class),
            mock(EmployeeRepository.class),
            mock(UserRepository.class),
            mock(ApprovalEngineService.class),
            mock(DemandPlanNamingService.class)
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("editor.user", "n/a")
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(
        value = DemandPlanningCycleStatus.class,
        names = {"DRAFT", "COLLECTING"}
    )
    void updateAllowsDraftAndCollecting(DemandPlanningCycleStatus status) {
        DemandPlanningCycle cycle = existingCycle(status);
        DemandPlanningCycleUpdateRequest request = editableRequest(cycle);
        request.setName("Updated cycle name");

        var response = service.update(cycle.getId(), request);

        assertThat(response.getName()).isEqualTo("Updated cycle name");
        assertThat(response.getStatus()).isEqualTo(status.name());
    }

    @ParameterizedTest
    @EnumSource(
        value = DemandPlanningCycleStatus.class,
        names = {"COLLECTION_CLOSED", "BUDGET_REVIEW", "BUDGET_APPROVED", "ACTIVE", "CLOSED", "CANCELLED"}
    )
    void updateRejectsNonEditableStatuses(DemandPlanningCycleStatus status) {
        DemandPlanningCycle cycle = existingCycle(status);

        assertThatThrownBy(() -> service.update(cycle.getId(), editableRequest(cycle)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("DRAFT or COLLECTING");
    }

    @Test
    void updatePreservesSystemOwnedFieldsAndNullSafeBusinessFields() {
        DemandPlanningCycle cycle = existingCycle(DemandPlanningCycleStatus.DRAFT);
        cycle.setCode("DPC-LOCKED");
        cycle.setDescription("Keep description");
        cycle.setCurrencyCode("USD");
        cycle.setPlanYear(2027);
        cycle.setTotalPlanCount(3);
        cycle.setTotalItemCount(7);
        cycle.setTotalPlannedAmount(new BigDecimal("1000.00"));
        cycle.setTotalApprovedAmount(new BigDecimal("800.00"));
        cycle.setTotalReservedQuantity(new BigDecimal("5.00"));
        cycle.setGeneratedAt(LocalDateTime.now().minusDays(2));
        cycle.setSubmittedAt(LocalDateTime.now().minusDays(1));
        cycle.setOwnerUsername("cycle.owner");

        DemandPlanningCycleUpdateRequest request = new DemandPlanningCycleUpdateRequest();
        request.setName("Renamed cycle");
        request.setCode("DPC-HACKED");
        // omitted planYear/currency/description must not reset

        var response = service.update(cycle.getId(), request);

        assertThat(response.getName()).isEqualTo("Renamed cycle");
        assertThat(response.getCode()).isEqualTo("DPC-LOCKED");
        assertThat(response.getStatus()).isEqualTo(DemandPlanningCycleStatus.DRAFT.name());
        assertThat(response.getDescription()).isEqualTo("Keep description");
        assertThat(response.getPlanYear()).isEqualTo(2027);
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
        assertThat(response.getTotalPlanCount()).isEqualTo(3);
        assertThat(response.getTotalItemCount()).isEqualTo(7);
        assertThat(response.getTotalPlannedAmount()).isEqualByComparingTo("1000.00");
        assertThat(response.getGeneratedAt()).isEqualTo(cycle.getGeneratedAt());
        assertThat(response.getOwnerUsername()).isEqualTo("cycle.owner");
    }

    @Test
    void requestDtosDoNotExposeServerOwnedFields() {
        Set<String> createFields = fieldNames(DemandPlanningCycleCreateRequest.class);
        Set<String> updateFields = fieldNames(DemandPlanningCycleUpdateRequest.class);

        assertThat(createFields).doesNotContain(
            "status",
            "ownerUsername",
            "totalPlanCount",
            "totalItemCount",
            "totalPlannedAmount",
            "totalApprovedAmount",
            "totalReservedQuantity",
            "generatedAt",
            "submittedAt",
            "approvedAt",
            "activatedAt",
            "closedAt"
        );
        assertThat(updateFields).isEmpty();
    }

    @Test
    void updateRejectsIdentityChangeWhenPlansExist() {
        DemandPlanningCycle cycle = existingCycle(DemandPlanningCycleStatus.DRAFT);
        when(demandPlanRepository.findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(TENANT, cycle.getId()))
            .thenReturn(List.of(new DemandPlan()));

        DemandPlanningCycleUpdateRequest request = editableRequest(cycle);
        request.setPlanYear(2099);

        assertThatThrownBy(() -> service.update(cycle.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("planYear cannot be changed");
    }

    @Test
    void updateRejectsIdentityChangeWhenCollecting() {
        DemandPlanningCycle cycle = existingCycle(DemandPlanningCycleStatus.COLLECTING);
        DemandPlanningCycleUpdateRequest request = editableRequest(cycle);
        request.setCurrencyCode("EUR");

        assertThatThrownBy(() -> service.update(cycle.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("currencyCode cannot be changed");
    }

    @Test
    void softDeleteAllowsOnlyDraftWithoutPlans() {
        DemandPlanningCycle draft = existingCycle(DemandPlanningCycleStatus.DRAFT);
        service.softDelete(draft.getId());
        assertThat(draft.isDeleted()).isTrue();

        DemandPlanningCycle withPlans = existingCycle(DemandPlanningCycleStatus.DRAFT);
        when(demandPlanRepository.findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(TENANT, withPlans.getId()))
            .thenReturn(List.of(new DemandPlan()));
        assertThatThrownBy(() -> service.softDelete(withPlans.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("linked demand plans");

        for (DemandPlanningCycleStatus status : Stream.of(
            DemandPlanningCycleStatus.COLLECTING,
            DemandPlanningCycleStatus.ACTIVE,
            DemandPlanningCycleStatus.CLOSED
        ).toList()) {
            DemandPlanningCycle cycle = existingCycle(status);
            assertThatThrownBy(() -> service.softDelete(cycle.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only DRAFT");
        }
    }

    private DemandPlanningCycle existingCycle(DemandPlanningCycleStatus status) {
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setId(UUID.randomUUID());
        cycle.setTenantId(TENANT);
        cycle.setCompanyId(COMPANY_ID);
        cycle.setCode("DPC-" + status.name());
        cycle.setName("Cycle " + status.name());
        cycle.setStatus(status.name());
        cycle.setPlanYear(2027);
        cycle.setPeriodType("YEAR");
        cycle.setCurrencyCode("UZS");
        cycle.setTotalPlanCount(0);
        cycle.setTotalItemCount(0);
        cycle.setTotalPlannedAmount(BigDecimal.ZERO);
        cycle.setTotalApprovedAmount(BigDecimal.ZERO);
        cycle.setTotalReservedQuantity(BigDecimal.ZERO);
        when(cycleRepository.findByIdAndIsDeletedFalse(cycle.getId())).thenReturn(Optional.of(cycle));
        when(demandPlanRepository.findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(TENANT, cycle.getId()))
            .thenReturn(List.of());
        return cycle;
    }

    private DemandPlanningCycleUpdateRequest editableRequest(DemandPlanningCycle cycle) {
        DemandPlanningCycleUpdateRequest request = new DemandPlanningCycleUpdateRequest();
        request.setCode(cycle.getCode());
        request.setName(cycle.getName());
        request.setPlanYear(cycle.getPlanYear());
        request.setPeriodType(cycle.getPeriodType());
        request.setCurrencyCode(cycle.getCurrencyCode());
        return request;
    }

    private Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
            .map(field -> field.getName())
            .collect(Collectors.toSet());
    }
}
