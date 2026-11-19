package com.enterprise.mm.warehouse.reports;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

class WarehouseReportExportTest {
    private final WarehouseReportExport exporter=new WarehouseReportExport();

    static Stream<Arguments> languagesAndLayouts() {
        return Stream.of("ru","en","uz").flatMap(language -> Stream.of(WarehouseReport.Type.values())
            .map(type -> Arguments.of(language,type)));
    }

    @ParameterizedTest @MethodSource("languagesAndLayouts")
    void historicalNotesDoNotClaimStockDiscrepancies(String language,WarehouseReport.Type type) throws Exception {
        var report=report(type,List.of("ONE_C_HISTORY_INTEGRATED","UNMAPPED_1C_REFERENCES","FUTURE_INFORMATION_NOTE"));
        String history=switch(language) { case "en" -> "This report includes posted 1C"; case "uz" -> "Hisobot boshlang‘ich qoldiq"; default -> "Отчёт включает проведённые движения 1С"; };
        String unmapped=switch(language) { case "en" -> "Legacy references use their original 1C"; case "uz" -> "Eski ma’lumotnomalar 1C"; default -> "Старые справочники показаны с исходными названиями"; };
        String ledger=ledgerMessage(language);
        for(String content:contents(report,language)) {
            assertThat(content).contains(history,unmapped,"1C-UNKNOWN").doesNotContain(ledger,"FUTURE_INFORMATION_NOTE");
        }
    }

    @ParameterizedTest @MethodSource("languagesAndLayouts")
    void actualStockDiscrepancyStillAppearsInBothFormats(String language,WarehouseReport.Type type) throws Exception {
        for(String content:contents(report(type,List.of("LEDGER_DIFFERS_FROM_CURRENT_BALANCES")),language)) {
            assertThat(content).contains(ledgerMessage(language));
        }
    }

    private List<String> contents(WarehouseReport report,String language) throws Exception {
        String csv=new String(exporter.csv(report,language),StandardCharsets.UTF_8);
        var text=new StringBuilder();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(exporter.xlsx(report,language)))) {
            for(var row:workbook.getSheetAt(0)) for(var cell:row) text.append(cell.toString()).append('\n');
        }
        return List.of(csv,text.toString());
    }

    private static String ledgerMessage(String language) {
        return switch(language) {
            case "en" -> "Warehouse accounting data differs from current balances";
            case "uz" -> "Harakatlar registri joriy qoldiqlarga mos emas";
            default -> "Данные складского учёта расходятся с текущими остатками";
        };
    }

    private static WarehouseReport report(WarehouseReport.Type type,List<String> warnings) {
        LocalDate date=LocalDate.of(2026,10,7);
        return new WarehouseReport(type,date,date,date,LocalDateTime.of(2026,10,12,1,0),"Navoiyazot","UZS",
            null,null,warnings,List.of(),0,20,0,List.of(),null,null);
    }
}
