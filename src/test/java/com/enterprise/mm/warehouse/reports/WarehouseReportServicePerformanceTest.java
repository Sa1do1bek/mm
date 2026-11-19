package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import com.enterprise.mm.service.RoleDataScopeService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarehouseReportServicePerformanceTest {
    @Test void fullDetailUsesLoadedRowsAsItsCount() {
        var query=mock(WarehouseReportQuery.class);
        var accounts=mock(WarehouseAccountReportQuery.class);
        var access=mock(RoleDataScopeService.class);
        var settings=mock(InventoryAccountingSettingsService.class);
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        var from=LocalDate.of(2026,10,1);
        var to=LocalDate.of(2026,10,14);
        var requested=new WarehouseReport.Filter(WarehouseReport.Type.MATERIAL_STATEMENT,from,to,null,null,null,null,0,1);
        var row=new WarehouseReport.Row("key",null,null,null,null,null,null,null,null,null,
            null,null,null,null,null,null,"0","0","0","0","0","0","0","0","0");
        when(accounts.available()).thenReturn(true);
        when(query.scope()).thenReturn(new MapSqlParameterSource());
        when(settings.findOrDefault()).thenReturn(new InventoryAccountingSettings());
        when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenReturn(List.of("Company"));
        when(accounts.rows(requested,true)).thenReturn(List.of(row,row));

        var result=new WarehouseReportService(query,access,settings,jdbc,accounts).generate(requested,true);

        assertThat(result.content()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(2);
        verify(accounts,never()).count(any());
    }
}
