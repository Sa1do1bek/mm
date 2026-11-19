package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceSelectorService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class SupplyWorkspaceSelectorServiceTest {
    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = {"WAREHOUSE_MANAGER", "WAREHOUSE_HEAD"})
    void bothWarehouseLeadershipNamesAreEligibleSupplyOwners(RoleName role) {
        User owner = user("warehouse-owner", "Warehouse owner", COMPANY_A, true, role);
        when(userRepository.searchActiveCompanyUsers("tenant-a", COMPANY_A, null, Pageable.unpaged()))
            .thenReturn(new PageImpl<>(List.of(owner)));

        assertThat(service.owners(scope(Set.of(COMPANY_A), false), COMPANY_A, null))
            .singleElement().satisfies(option -> assertThat(option.role()).isEqualTo(SupplyAssignmentRole.WAREHOUSE_MANAGER));
    }

    private static final UUID COMPANY_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANY_B = UUID.fromString("10000000-0000-0000-0000-000000000002");

    private CompanyRepository companyRepository;
    private UserRepository userRepository;
    private SupplyWorkspaceSelectorService service;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        userRepository = mock(UserRepository.class);
        service = new SupplyWorkspaceSelectorService(companyRepository, userRepository);
    }

    @Test
    void companySelectorOnlyReturnsCompaniesInsideAuthenticatedScope() {
        Company first = company(COMPANY_A, "A", "Alpha Plant");
        Company second = company(COMPANY_B, "B", "Beta Plant");
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", COMPANY_A))
            .thenReturn(Optional.of(first));
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", COMPANY_B))
            .thenReturn(Optional.of(second));

        var options = service.companies(scope(Set.of(COMPANY_A), false), "alpha");

        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option.id()).isEqualTo(COMPANY_A);
            assertThat(option.code()).isEqualTo("A");
            assertThat(option.displayName()).isEqualTo("Alpha Plant");
        });
        verify(companyRepository).findByTenantIdAndIdAndIsDeletedFalse("tenant-a", COMPANY_A);
    }

    @Test
    void globalAdminCompanySelectorRemainsTenantBound() {
        when(companyRepository.findByTenantIdAndIsDeletedFalseOrderByNameAsc("tenant-a"))
            .thenReturn(List.of(
                company(COMPANY_A, "A", "Alpha Plant"),
                company(COMPANY_B, "B", "Beta Plant")));

        var options = service.companies(scope(Set.of(), true), "beta");

        assertThat(options).extracting(option -> option.id()).containsExactly(COMPANY_B);
    }

    @Test
    void ownerSelectorFiltersToActiveEligibleRolesInOnePermittedCompany() {
        User specialist = user(
            "specialist", "Supply Specialist", COMPANY_A, true,
            RoleName.PROCUREMENT_SPECIALIST, RoleName.VIEWER);
        User manager = user(
            "manager", "Procurement Manager", COMPANY_A, true,
            RoleName.PROCUREMENT_MANAGER);
        User viewer = user("viewer", "Read Only", COMPANY_A, true, RoleName.VIEWER);
        User disabled = user(
            "disabled", "Disabled Specialist", COMPANY_A, false,
            RoleName.PROCUREMENT_SPECIALIST);
        when(userRepository.searchActiveCompanyUsers(
            "tenant-a", COMPANY_A, "supply", Pageable.unpaged()))
            .thenReturn(new PageImpl<>(List.of(specialist, manager, viewer, disabled)));

        var options = service.owners(scope(Set.of(COMPANY_A), false), COMPANY_A, " supply ");

        assertThat(options).hasSize(2);
        assertThat(options).extracting(option -> option.username())
            .containsExactly("manager", "specialist");
        assertThat(options).extracting(option -> option.role())
            .containsExactly(
                SupplyAssignmentRole.PROCUREMENT_MANAGER,
                SupplyAssignmentRole.PROCUREMENT_SPECIALIST);
        assertThat(options).allSatisfy(option -> assertThat(option.companyId()).isEqualTo(COMPANY_A));
    }

    @Test
    void ownerSelectorRejectsCrossCompanyAndAmbiguousGlobalQueries() {
        SupplyAccessScope currentCompany = scope(Set.of(COMPANY_A), false);
        SupplyAccessScope global = scope(Set.of(), true);

        assertThatThrownBy(() -> service.owners(currentCompany, COMPANY_B, null))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_COMPANY_FORBIDDEN");
        assertThatThrownBy(() -> service.owners(global, null, null))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_SELECTOR_COMPANY_REQUIRED");
    }

    private SupplyAccessScope scope(Set<UUID> companyIds, boolean global) {
        return new SupplyAccessScope("tenant-a", companyIds, Set.of(), global, false);
    }

    private Company company(UUID id, String code, String name) {
        Company company = new Company();
        company.setId(id);
        company.setTenantId("tenant-a");
        company.setCode(code);
        company.setName(name);
        company.setStatus("ACTIVE");
        return company;
    }

    private User user(
        String username,
        String fullName,
        UUID companyId,
        boolean enabled,
        RoleName... roles
    ) {
        User user = new User();
        user.setId(UUID.nameUUIDFromBytes(username.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        user.setTenantId("tenant-a");
        user.setCompanyId(companyId);
        user.setUsername(username);
        user.setName(fullName);
        user.setFullName(fullName);
        user.setEnabled(enabled);
        user.setRoles(new java.util.LinkedHashSet<>(List.of(roles)));
        return user;
    }
}
