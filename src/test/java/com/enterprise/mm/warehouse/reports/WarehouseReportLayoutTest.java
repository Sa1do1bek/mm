package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.warehouse.history.WarehouseSourceProfileExport;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WarehouseReportLayoutTest {
    @Test void allDetailedReportsUseTheReferenceHeadersAndAccountWarehouseHierarchy() throws Exception {
        for(var type:WarehouseReport.Type.values())for(String language:List.of("ru","en","uz")) {
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(new WarehouseReportExport().xlsx(report(type),language)))) {
                var sheet=workbook.getSheetAt(0);
                assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
                assertThat(sheet.getRow(0).getCell(0)).isNull();
                assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).contains("08.10.2026");
                assertThat(sheet.getMergedRegions()).anySatisfy(range->assertThat(range.formatAsString()).isEqualTo("B4:B5"));
                assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("1010");
                assertThat(sheet.getRow(6).getCell(2).getStringCellValue()).isEqualTo("003.4");
                assertThat(sheet.getRow(6).getCell(3).getStringCellValue()).isEqualTo("003.4 склад");
                assertThat(sheet.getRow(7).getOutlineLevel()).isEqualTo(2);
                int codeColumn=type==WarehouseReport.Type.RECEIPTS || type==WarehouseReport.Type.ISSUES?3:2;
                assertThat(sheet.getRow(7).getCell(codeColumn).getStringCellValue()).isEqualTo("001");
                assertThat(sheet.getRow(7).getCell(codeColumn).getCellStyle().getDataFormatString()).isEqualTo("@");
                // Mixed units never become a single subtotal quantity.
                assertThat(sheet.getRow(5).getCell(6).getCellType()).isEqualTo(CellType.BLANK);
                int amountColumn=type==WarehouseReport.Type.MATERIAL_STATEMENT?13:7;
                assertThat(sheet.getRow(5).getCell(amountColumn).getNumericCellValue()).isEqualTo(30);
                assertThat(sheet.getRow(7).getCell(1).getCellStyle().getFont().getFontName()).isEqualTo("Arial");
                assertThat(sheet.getRow(7).getCell(1).getCellStyle().getFont().getFontHeightInPoints()).isEqualTo((short)8);
                assertThat(sheet.getRow(5).getCell(1).getCellStyle().getFillForegroundXSSFColor().getARGBHex()).endsWith("FAEBD7");
                for(var row:sheet)for(var cell:row)assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
            }
        }
    }

    @Test void sourceBalancesUseTheSameLayoutAndRetainSourceIdentifiersAndValueOnlyAmounts() throws Exception {
        var rows=List.of(source("001","kg","1.1234567","10","MATERIAL"),source("002","pcs","2","20","MATERIAL"),
            source("003","","0","1234567890123456.1234","VALUE_ONLY"));
        for(String language:List.of("ru","en","uz")) {
            byte[] bytes=new WarehouseSourceProfileExport().export(Map.of("content",rows),"BALANCE_DETAILS","xlsx",language);
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var sheet=workbook.getSheetAt(0);
                assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).contains("08.10.2026");
                assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("1010");
                assertThat(sheet.getRow(6).getCell(2).getStringCellValue()).isEqualTo("003.4");
                assertThat(sheet.getRow(7).getCell(2).getStringCellValue()).isEqualTo("001");
                assertThat(sheet.getRow(7).getCell(6).getNumericCellValue()).isEqualTo(1.1234567);
                assertThat(sheet.getRow(7).getCell(9).getStringCellValue()).isEqualTo("source:001");
                assertThat(sheet.getRow(7).getOutlineLevel()).isEqualTo(2);
                assertThat(sheet.getRow(9).getCell(6).getCellType()).isEqualTo(CellType.BLANK);
                assertThat(sheet.getRow(9).getCell(7).getStringCellValue()).isEqualTo("1234567890123456.1234");
                assertThat(sheet.getRow(10).getCell(7).getStringCellValue()).isEqualTo("1234567890123486.1234");
                assertThat(sheet.getRow(5).getCell(6).getCellType()).isEqualTo(CellType.BLANK);
            }
        }
    }

    static WarehouseReport report(WarehouseReport.Type type) {
        var date=LocalDate.of(2026,10,8);
        return new WarehouseReport(type,date,date,date,LocalDateTime.of(2026,10,15,10,0),"Navoiyazot","UZS",null,null,List.of(),
            List.of(detail("001","kg","1.1234567","10"),detail("002","pcs","2","20")),0,50,2,List.of(),null,null,"1010",null,date);
    }

    private static WarehouseReport.Row detail(String code,String unit,String quantity,String amount) {
        return new WarehouseReport.Row(code,UUID.nameUUIDFromBytes(code.getBytes()),UUID.nameUUIDFromBytes("warehouse".getBytes()),"003.4","003.4 склад",
            null,"70000634","Материалы",code,"=Material "+code,unit,unit,unit,unit,"Контрагент","6010","10",
            quantity,amount,quantity,amount,quantity,amount,quantity,amount,"1010","Материалы","Материалы","AVAILABLE");
    }

    static Map<String,Object> source(String code,String unit,String quantity,String amount,String type) {
        return Map.ofEntries(Map.entry("accountCode","1010"),Map.entry("materialCode",code),Map.entry("materialName","Материал "+code),
            Map.entry("warehouseCode","003.4"),Map.entry("warehouseName","003.4 склад"),Map.entry("sourceId","source:"+code),
            Map.entry("sourceDate","2026-10-08"),Map.entry("uom",unit),Map.entry("uomNameRu",unit),Map.entry("quantity",quantity),
            Map.entry("amount",amount),Map.entry("sourceType",type));
    }
}
