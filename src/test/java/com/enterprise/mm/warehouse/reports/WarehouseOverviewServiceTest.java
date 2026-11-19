package com.enterprise.mm.warehouse.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarehouseOverviewServiceTest {
    @Test void warehouseListUsesOneAggregateWithoutMaterializingTheFullReport() {
        var reports=mock(WarehouseReportService.class);
        var operational=mock(WarehouseReportQuery.class);
        var accounts=mock(WarehouseAccountReportQuery.class);
        var approvals=mock(WarehouseReportApprovalStore.class);
        var warehouseId=UUID.randomUUID();
        var from=LocalDate.of(2026,10,1);
        var to=LocalDate.of(2026,10,14);
        var filter=new WarehouseReport.Filter(WarehouseReport.Type.MATERIAL_STATEMENT,from,to,null,null,null,null,0,1);
        when(reports.overviewContext(any())).thenReturn(new WarehouseReportService.OverviewContext(filter,"UZS",List.of(),true));
        when(accounts.warehouseTotals(filter)).thenReturn(List.of(new WarehouseOverview.WarehouseUnitTotal(
            warehouseId,"003.7","Warehouse 003.7","pcs","pcs","шт.","dona",
            "2","100","1","50","0","0","3","150")));
        when(approvals.latest(eq(from),eq(to),any())).thenReturn(Map.of());

        var result=new WarehouseOverviewService(reports,operational,accounts,approvals).overview(from,to,null);

        assertThat(result.warehouses()).hasSize(1);
        assertThat(result.warehouses().getFirst().closingAmount()).isEqualTo("150");
        verify(accounts).warehouseTotals(filter);
        verify(reports,never()).generate(any(),anyBoolean());
    }
}
