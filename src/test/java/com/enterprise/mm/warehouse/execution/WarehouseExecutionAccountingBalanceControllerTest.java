package com.enterprise.mm.warehouse.execution;

import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.warehouse.execution.controller.WarehouseExecutionAccountingBalanceController;
import com.enterprise.mm.warehouse.reports.WarehouseReport;
import com.enterprise.mm.warehouse.reports.WarehouseReportExport;
import com.enterprise.mm.warehouse.reports.WarehouseReportService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarehouseExecutionAccountingBalanceControllerTest {
    private final WarehouseReportService reports = mock(WarehouseReportService.class);
    private final WarehouseReportExport exporter = mock(WarehouseReportExport.class);
    private final RoleDataScopeService access = mock(RoleDataScopeService.class);
    private final WarehouseExecutionAccountingBalanceController controller =
            new WarehouseExecutionAccountingBalanceController(reports, exporter, access);

    @Test
    void stockReportUsesAssignedWarehouseAccountDateAndMaterialFilters() {
        UUID assigned = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 10, 8);
        when(access.availabilityWarehouseId(null)).thenReturn(assigned);
        when(reports.generate(any(), org.mockito.ArgumentMatchers.eq(false))).thenAnswer(call -> {
            WarehouseReport.Filter filter = call.getArgument(0);
            assertThat(filter.type()).isEqualTo(WarehouseReport.Type.STOCK_BALANCES);
            assertThat(filter.warehouseId()).isEqualTo(assigned);
            assertThat(filter.accountCode()).isEqualTo("1017");
            assertThat(filter.search()).isEqualTo("ЗАТВОР");
            assertThat(filter.materialGroupId()).isEqualTo(group);
            assertThat(filter.from()).isEqualTo(date);
            assertThat(filter.to()).isEqualTo(date);
            assertThat(filter.page()).isEqualTo(1);
            assertThat(filter.size()).isEqualTo(10);
            return new WarehouseReport(filter.type(), date, date, date, null, "Company", "UZS",
                    assigned, "Warehouse", List.of(), List.of(), 1, 10, 0, List.of(),
                    null, null, "1017", null, date);
        });

        WarehouseReport result = controller.balances(null, "1017", "ЗАТВОР", group, date, 1, 10).data();
        assertThat(result.currencyCode()).isEqualTo("UZS");
    }

    @Test
    void requiresSingleWarehouseAndReturnsNoAccountOptionsWithoutAnImport() {
        assertThatThrownBy(() -> controller.balances(null, null, null, null, null, 0, 10))
                .hasMessageContaining("Warehouse is required");
        UUID warehouse = UUID.randomUUID();
        when(access.availabilityWarehouseId(warehouse)).thenReturn(warehouse);
        when(reports.options()).thenReturn(new WarehouseReportService.Options(
                LocalDate.now(), null, warehouse, "Warehouse", null, 0, null, List.of()));
        assertThat(controller.accounts(warehouse, null, 0, 50).data().content()).isEmpty();
        verify(reports).options();
    }

    @Test
    void returnsOperationalBalancesWhenThereIsNoAccountingImport() {
        UUID warehouse = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        when(access.availabilityWarehouseId(warehouse)).thenReturn(warehouse);
        var report = new WarehouseReport(WarehouseReport.Type.STOCK_BALANCES, date, date, date, null,
                "Company", "UZS", warehouse, "Warehouse", List.of("HISTORY_STARTS_AT_OPENING"),
                List.of(), 0, 10, 0, List.of(), null, null, null, null, null);
        when(reports.generate(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(report);
        assertThat(controller.balances(warehouse, null, null, null, null, 0, 10).data()).isSameAs(report);
        assertThat(report.accountSnapshotDate()).isNull();
    }
}
