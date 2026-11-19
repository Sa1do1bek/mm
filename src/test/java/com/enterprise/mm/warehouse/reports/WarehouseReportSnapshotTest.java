package com.enterprise.mm.warehouse.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WarehouseReportSnapshotTest {
    @Test void savedReportRoundTripsWithDatesAndExactDecimalStrings() throws Exception {
        UUID warehouse=UUID.randomUUID();
        WarehouseReport original=new WarehouseReport(WarehouseReport.Type.MATERIAL_STATEMENT,
            LocalDate.of(2026,10,1),LocalDate.of(2026,10,8),LocalDate.of(2026,9,1),
            LocalDateTime.of(2026,10,8,14,30),"Company","UZS",warehouse,"Central",List.of(),
            List.of(new WarehouseReport.Row("row",UUID.randomUUID(),warehouse,"01","Central",null,null,null,
                "M-01","Copper","kg",null,null,null,"—","—","10.0000","1.0000001","10.0000",
                "2.0000000","20.0000","0.0000000","0.0000","3.0000001","30.0000")),
            0,1,1,List.of(),null,null);
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        WarehouseReport restored=mapper.readValue(mapper.writeValueAsString(original),WarehouseReport.class);
        assertThat(restored).isEqualTo(original);
        assertThat(restored.content().getFirst().closingQuantity()).isEqualTo("3.0000001");
    }
}
