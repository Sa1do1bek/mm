package com.enterprise.mm.warehouse.reports;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WarehouseOverviewExportTest {
    @Test void exportsAllWarehousesWithSeparateUnitsExactDecimalsAndApprovalMetadata() throws Exception {
        var units=List.of(new WarehouseOverview.UnitTotal("kg","kg","кг","kg","1.1234567","2","1","2.1234567"),
            new WarehouseOverview.UnitTotal("pcs","pcs","шт.","dona","10","0","0","10"));
        var approval=new WarehouseOverview.Approval(UUID.randomUUID(),2,OffsetDateTime.parse("2026-10-15T12:00:00+05:00"),"reviewer");
        var line=new WarehouseOverview.WarehouseLine(UUID.randomUUID(),"001","ru: Склад\nen: Warehouse\nuz: Ombor",
            "1234567890123456.1234","20","10","1234567890123466.1234",units,approval);
        var pending=new WarehouseOverview.WarehouseLine(UUID.randomUUID(),"002","=SUM(A1:A2)","0","0","0","0",List.of(),null);
        var report=new WarehouseOverview(LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),"UZS",List.of("UNMAPPED_1C_REFERENCES"),List.of(line,pending));
        for (String language:List.of("ru","en","uz")) {
            try (var workbook=new XSSFWorkbook(new ByteArrayInputStream(new WarehouseOverviewExport().xlsx(report,language)))) {
                assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
                var amounts=workbook.getSheetAt(0); var quantities=amounts;
                assertThat(amounts.getRow(1).getCell(1).getStringCellValue()).contains("2026-10-01","2026-10-15","UZS");
                assertThat(amounts.getRow(5).getCell(2).getStringCellValue()).isEqualTo(language.equals("ru")?"Склад":language.equals("uz")?"Ombor":"Warehouse");
                assertThat(amounts.getRow(5).getCell(5).getStringCellValue()).isEqualTo("1234567890123456.1234");
                assertThat(amounts.getRow(5).getCell(7).getNumericCellValue()).isEqualTo(20);
                assertThat(amounts.getRow(5).getCell(13).getNumericCellValue()).isEqualTo(2);
                assertThat(amounts.getRow(5).getCell(14).getStringCellValue()).isEqualTo("reviewer");
                assertThat(amounts.getRow(8).getCell(2).getCellType()).isEqualTo(CellType.STRING);
                assertThat(amounts.getRow(8).getCell(2).getStringCellValue()).isEqualTo("=SUM(A1:A2)");
                assertThat(amounts.getRow(5).getCell(4).getCellType()).isEqualTo(CellType.BLANK);
                assertThat(amounts.getRow(6).getOutlineLevel()).isEqualTo(1);
                assertThat(quantities.getRow(6).getCell(4).getNumericCellValue()).isEqualTo(1.1234567);
                assertThat(quantities.getRow(7).getCell(4).getNumericCellValue()).isEqualTo(10);
            }
        }
    }

    @Test void exportsAnEmptyOverviewWithHeaders() throws Exception {
        var report=new WarehouseOverview(LocalDate.now(),LocalDate.now(),"UZS",List.of(),List.of());
        try (var workbook=new XSSFWorkbook(new ByteArrayInputStream(new WarehouseOverviewExport().xlsx(report,"en")))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetAt(0).getRow(3).getCell(4).getStringCellValue()).isEqualTo("Opening balance");
        }
    }
}
