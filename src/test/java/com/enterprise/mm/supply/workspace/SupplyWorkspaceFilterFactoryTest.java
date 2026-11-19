package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceFilterFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplyWorkspaceFilterFactoryTest {
    private static final Instant NOW = Instant.parse("2026-07-15T20:30:00Z");
    private static final UUID COMPANY_A = UUID.fromString("a65c186e-5302-4a6d-a44f-3f61c84612ac");
    private static final UUID COMPANY_B = UUID.fromString("ea88cfe2-e94a-4139-9bbc-4f86a2354728");
    private static final UUID WAREHOUSE_A = UUID.fromString("e1f40550-d03c-48c8-9014-161c48ce24fa");
    private static final UUID WAREHOUSE_B = UUID.fromString("a04429f0-e6cf-40b7-b25e-43178f77ebf5");

    private final SupplyWorkspaceFilterFactory factory = new SupplyWorkspaceFilterFactory(
        Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Tashkent");
    private final SupplyAccessScope scope = new SupplyAccessScope(
        "tenant-a", Set.of(COMPANY_A), Set.of(WAREHOUSE_A), false, true);

    @Test
    void normalizesScopeDatesSearchCurrencyAndAmountSort() {
        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setAsOf(NOW);
        request.setFrom(LocalDate.of(2026, 7, 1));
        request.setTo(LocalDate.of(2026, 8, 1));
        request.setLens(SupplyLens.MY_WORK);
        request.setCompanyId(COMPANY_A);
        request.setWarehouseId(WAREHOUSE_A);
        request.setCurrencyCode(" usd ");
        request.setSearch("  Pump-42  ");
        request.setStatus(" overdue ");
        request.setSort(SupplyCommitmentSort.OPEN_AMOUNT);

        SupplyWorkspaceQuery query = factory.normalize(request, scope);

        assertThat(query.asOf()).isEqualTo(NOW);
        assertThat(query.asOfDate()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(query.timeZone().getId()).isEqualTo("Asia/Tashkent");
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(query.to()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(query.lens()).isEqualTo(SupplyLens.MY_WORK);
        assertThat(query.companyId()).isEqualTo(COMPANY_A);
        assertThat(query.authorizedCompanyIds()).containsExactly(COMPANY_A);
        assertThat(query.warehouseId()).isEqualTo(WAREHOUSE_A);
        assertThat(query.authorizedWarehouseIds()).containsExactly(WAREHOUSE_A);
        assertThat(query.warehouseScoped()).isTrue();
        assertThat(query.currencyCode()).isEqualTo("USD");
        assertThat(query.search()).isEqualTo("Pump-42");
        assertThat(query.status()).isEqualTo("OVERDUE");
        assertThat(query.sort()).isEqualTo(SupplyCommitmentSort.OPEN_AMOUNT);
    }

    @Test
    void defaultsAsOfAndLensWithoutInventingAnOperationalRange() {
        SupplyWorkspaceQuery query = factory.normalize(new SupplyWorkspaceRequest(), scope);

        assertThat(query.asOf()).isEqualTo(NOW);
        assertThat(query.asOfDate()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(query.lens()).isEqualTo(SupplyLens.TEAM);
        assertThat(query.companyId()).isEqualTo(COMPANY_A);
        assertThat(query.from()).isNull();
        assertThat(query.to()).isNull();
    }

    @Test
    void permitsAnInclusiveExclusiveRangeOfAtMost366Days() {
        SupplyWorkspaceRequest allowed = new SupplyWorkspaceRequest();
        allowed.setFrom(LocalDate.of(2025, 1, 1));
        allowed.setTo(allowed.getFrom().plusDays(366));

        assertThat(factory.normalize(allowed, scope).to())
            .isEqualTo(LocalDate.of(2026, 1, 2));

        SupplyWorkspaceRequest tooLong = new SupplyWorkspaceRequest();
        tooLong.setFrom(LocalDate.of(2025, 1, 1));
        tooLong.setTo(tooLong.getFrom().plusDays(367));
        assertThatThrownBy(() -> factory.normalize(tooLong, scope))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsInvalidRangeCurrencyAmountSortAndScopeExpansion() {
        SupplyWorkspaceRequest reversed = new SupplyWorkspaceRequest();
        reversed.setFrom(LocalDate.of(2026, 7, 2));
        reversed.setTo(LocalDate.of(2026, 7, 2));
        assertThatThrownBy(() -> factory.normalize(reversed, scope))
            .isInstanceOf(BadRequestException.class);

        SupplyWorkspaceRequest invalidCurrency = new SupplyWorkspaceRequest();
        invalidCurrency.setCurrencyCode("US1");
        assertThatThrownBy(() -> factory.normalize(invalidCurrency, scope))
            .isInstanceOf(BadRequestException.class);

        SupplyWorkspaceRequest amountWithoutCurrency = new SupplyWorkspaceRequest();
        amountWithoutCurrency.setSort(SupplyCommitmentSort.OPEN_AMOUNT);
        assertThatThrownBy(() -> factory.normalize(amountWithoutCurrency, scope))
            .isInstanceOf(BadRequestException.class);

        SupplyWorkspaceRequest otherCompany = new SupplyWorkspaceRequest();
        otherCompany.setCompanyId(COMPANY_B);
        assertThatThrownBy(() -> factory.normalize(otherCompany, scope))
            .isInstanceOf(BadRequestException.class);

        SupplyWorkspaceRequest otherWarehouse = new SupplyWorkspaceRequest();
        otherWarehouse.setWarehouseId(WAREHOUSE_B);
        assertThatThrownBy(() -> factory.normalize(otherWarehouse, scope))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void preservesAnExplicitNoWarehouseAccessScopeInsteadOfTreatingItAsGlobal() {
        SupplyAccessScope noWarehouse = new SupplyAccessScope(
            "tenant-a", Set.of(COMPANY_A), Set.of(), false, true);

        SupplyWorkspaceQuery query = factory.normalize(new SupplyWorkspaceRequest(), noWarehouse);

        assertThat(query.warehouseScoped()).isTrue();
        assertThat(query.authorizedWarehouseIds()).isEmpty();
        assertThat(query.warehouseId()).isNull();

        SupplyWorkspaceRequest requested = new SupplyWorkspaceRequest();
        requested.setWarehouseId(WAREHOUSE_A);
        assertThatThrownBy(() -> factory.normalize(requested, noWarehouse))
            .isInstanceOf(BadRequestException.class);
    }
}
