package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.mapper.StockBalanceMapper;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.StockBalanceReadQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.i18n.LocaleContextHolder;

class StockBalanceExcelExportTest {
    private StockBalanceService balances;
    private StockBalanceExcelExport exporter;
    private Locale previous;
    private final LocalDate day=LocalDate.of(2026,10,29);

    @BeforeEach void setup() {
        previous=LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(Locale.FRENCH);
        TenantContext.setTenant("export-test");CompanyContext.setCompany(UUID.randomUUID());
        balances=mock(StockBalanceService.class);
        exporter=new StockBalanceExcelExport(balances,mock(StockBalanceReadQuery.class),
            new StockExpiryPolicy(Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"),ZoneId.of("Asia/Tashkent"))),new ObjectMapper());
    }
    @AfterEach void cleanup() {
        LocaleContextHolder.setLocale(previous);TenantContext.clear();CompanyContext.clear();
    }

    @ParameterizedTest
    @CsvSource({"uz,Klapan,dona,Qoldiqlar,Hisobot haqida", "ru,Клапан,шт.,Остатки,О отчете", "en,Valve,pc,Balances,Report info"})
    void exportsOneLanguageReadableUnitsTypedCellsAndUsableLayout(String lang,String name,String unit,String sheetName,String infoName) throws Exception {
        var source=row(lang);
        var request=new StockBalanceHttpFilter();request.setWarehouseId(UUID.randomUUID());request.setSearch("0000123");
        request.setSortBy("quantity");request.setSortDir("desc");request.setQuantityMin(BigDecimal.ONE);
        // The same request object, including all filters/options, reaches the shared query unchanged.
        var criteria=request.toRequest();
        doAnswer(call->{
            assertThat(LocaleContextHolder.getLocale().getLanguage()).isEqualTo(lang);
            Consumer<StockBalanceResponse> consume=call.getArgument(2);consume.accept(source);return null;})
            .when(balances).exportRows(same(criteria),eq(day),any());
        var output=new ByteArrayOutputStream();exporter.write(criteria,null,lang,output);
        verify(balances).exportRows(same(criteria),eq(day),any());
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.FRENCH);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var sheet=book.getSheet(sheetName);var data=sheet.getRow(1);
            assertThat(data.getCell(3).getStringCellValue()).isEqualTo("0000123");
            assertThat(data.getCell(4).getStringCellValue()).isEqualTo(name);
            assertThat(data.getCell(5).getStringCellValue()).doesNotContain("ru:","en:","uz:");
            assertThat(data.getCell(10).getStringCellValue()).isEqualTo(unit);
            assertThat(data.getCell(11).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(data.getCell(11).getNumericCellValue()).isEqualTo(12.3456789);
            assertThat(DateUtil.isCellDateFormatted(data.getCell(2))).isTrue();
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:T2");
            assertThat(sheet.getPaneInformation().getVerticalSplitPosition()).isEqualTo((short)5);
            assertThat(sheet.getColumnWidth(4)).isGreaterThan(sheet.getColumnWidth(10));
            assertThat(data.getHeightInPoints()).isGreaterThanOrEqualTo(28);
            assertThat(sheet.getPrintSetup().getLandscape()).isTrue();
            assertThat(sheet.getRepeatingRows().formatAsString()).isEqualTo("1:1");
            assertThat(sheet.getRow(0).getCell(4).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            var info=book.getSheet(infoName);assertThat(info).isNotNull();
            assertThat(info.getRow(6).getCell(1).getStringCellValue()).doesNotContain("quantity","DESC","null dates");
            assertThat(info.getRow(8).getCell(1).getStringCellValue()).contains("0000123").doesNotContain("quantityMin","sortBy","sortDir");
            if(lang.equals("uz")) {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/export-preview"));
                java.nio.file.Files.write(java.nio.file.Path.of("target/export-preview/batches-uz.xlsx"),output.toByteArray());
            }
        }
    }

    @Test void explicitLegacyUnitCodeColumnUsesLabelAndDoesNotExposeImportedIdentifiers() throws Exception {
        var source=row("en");source.setUnitLabel(null);
        doAnswer(call->{Consumer<StockBalanceResponse> consume=call.getArgument(2);consume.accept(source);return null;})
            .when(balances).exportRows(any(),any(),any());
        var options=new StockBalanceViewOptions();options.setSortBy("createdAt");
        var output=new ByteArrayOutputStream();exporter.write(StockBalanceSearchRequest.empty().withOptions(options),List.of("unitCode","materialName","unitLabel"),"en",output);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var data=book.getSheetAt(0).getRow(1);
            assertThat(data.getCell(0).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("Valve");
            assertThat(data.getCell(2).getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    @ParameterizedTest @CsvSource({"uz,dona", "ru,шт.", "en,pc"})
    void mapperResolvesUnitLabelInTheRequestedLocale(String lang,String expected) {
        var unit=new UnitOfMeasure();unit.setCode("1C-UOM-8115001a");
        unit.setMeasurementNameShort("pc");unit.setMeasurementNameShortRu("шт.");unit.setMeasurementNameShortUz("dona");
        var material=new Material();material.setUnitOfMeasure(unit);
        var balance=new StockBalance();balance.setMaterial(material);
        LocaleContextHolder.setLocale(Locale.forLanguageTag(lang));
        assertThat(new StockBalanceMapper().toResponse(balance).getUnitLabel()).isEqualTo(expected);
    }

    private StockBalanceResponse row(String lang) {
        var r=new StockBalanceResponse();r.setMaterialCode("0000123");
        r.setMaterialName(LocalizedText.format("Клапан","Valve","Klapan"));
        r.setMaterialGroupName(LocalizedText.format("Арматура","Valves","Armatura"));
        r.setWarehouseName(LocalizedText.format("Склад 073.1","Warehouse 073.1","Ombor 073.1"));
        r.setStorageLocationName(LocalizedText.format("Стеллаж A","Rack A","Stellaj A"));
        r.setStockBatchNumber("BATCH-001");r.setUnitCode("1C-UOM-8115001a4bcf769a11dd6917e608c590");
        r.setUnitLabel(lang.equals("uz")?"dona":lang.equals("ru")?"шт.":"pc");
        r.setQuantity(new BigDecimal("12.3456789"));r.setAvailableQuantity(new BigDecimal("10.3456789"));
        r.setExpirationDate(day.plusDays(10));r.setDaysUntilExpiry(10L);
        r.setExpiryStatus(com.enterprise.mm.enums.StockExpiryStatus.EXPIRING_SOON);r.setExpiryWarning(true);
        return r;
    }
}
