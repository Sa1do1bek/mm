package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.enterprise.mm.common.*;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.StockExpiryStatus;
import com.enterprise.mm.payload.StockBalanceSearchRequest;
import com.enterprise.mm.payload.StockBalanceResponse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(StockExpiryIntegrationTest.FixedDate.class)
class StockExpiryIntegrationTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    @TestConfiguration static class FixedDate {
        @Bean @Primary StockExpiryPolicy fixedStockExpiryPolicy() {
            return new StockExpiryPolicy(Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("Asia/Tashkent")));
        }
    }
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired StockBalanceService balances;
    @Autowired WarehouseCommandViewService warehouseView;
    @Autowired MockMvc mvc;
    @Autowired StockBalanceExcelExport excel;
    @MockBean RoleDataScopeService scope;
    String tenant;
    UUID company, warehouse, otherWarehouse, material, unit;
    Map<String, UUID> ids;

    @BeforeEach void setup() {
        tenant = "stock-expiry-" + UUID.randomUUID(); company = UUID.randomUUID(); resetScope();
        ids = new LinkedHashMap<>();
        tx.executeWithoutResult(status -> {
            Company c = persist(new Company()); company = c.getId(); c.setCompanyId(company); resetScope();
            warehouse = persist(new Warehouse()).getId(); otherWarehouse = persist(new Warehouse()).getId();
            UnitOfMeasure uom = new UnitOfMeasure(); uom.setStockQuantityScale(7); unit = persist(uom).getId();
            Material m = new Material(); m.setUnitOfMeasureId(unit); m.setShelfLifeDays(1); material = persist(m).getId();
            m.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
            row("yesterday", -1, null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            row("today", 0, null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            row("tomorrow", 1, null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            row("day30", 30, null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            row("day31", 31, null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            row("unknown", null, null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            row("legacy", null, -2, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            row("precedence", 50, -3, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            row("zero", -4, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            row("reserved", 5, null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO);
            row("quarantine", 6, null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN);
            StockBalance missing = balance(null); ids.put("missingBatch", persist(missing).getId());
        });
        when(scope.effectiveWarehouseId(any())).thenAnswer(call -> call.getArgument(0));
        when(scope.canAccessPayload(any())).thenReturn(true);
        when(scope.canAccessWarehouse(any())).thenAnswer(call -> warehouse.equals(call.getArgument(0)));
        when(scope.applyWarehouseScope(any(SearchCriteria.class))).thenAnswer(call -> {
            assertThat(TenantContext.currentTenant()).isEqualTo(tenant);
            assertThat(CompanyContext.currentCompany()).isEqualTo(company);
            var request = (StockBalanceSearchRequest) call.getArgument(0);
            assertThat(request.quantity()).isNull();
            assertThat(request.dateFrom()).isNull(); assertThat(request.dateTo()).isNull();
            assertThat(request.materialId()).isNull(); assertThat(request.stockBatchId()).isNull();
            return request;
        });
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void unfilteredReadPreservesRowsAndClassifiesDatesIncludingMissingBatches() {
        var all = query(null, null, 0, 100);
        assertThat(all.getTotalElements()).isEqualTo(12);
        Map<UUID, StockBalanceResponse> byId = new HashMap<>(); all.forEach(r -> byId.put(r.getId(), r));
        assertThat(byId.get(ids.get("yesterday")).getExpiryStatus()).isEqualTo(StockExpiryStatus.EXPIRED);
        assertThat(byId.get(ids.get("today")).getExpiryStatus()).isEqualTo(StockExpiryStatus.EXPIRES_TODAY);
        assertThat(byId.get(ids.get("day30")).getDaysUntilExpiry()).isEqualTo(30L);
        assertThat(byId.get(ids.get("day31")).isExpiryWarning()).isFalse();
        assertThat(byId.get(ids.get("unknown")).getExpiryStatus()).isEqualTo(StockExpiryStatus.UNKNOWN);
        assertThat(byId.get(ids.get("missingBatch")).getExpiryStatus()).isEqualTo(StockExpiryStatus.UNKNOWN);
        assertThat(byId.get(ids.get("precedence")).getExpirationDate()).isEqualTo(TODAY.plusDays(50));
        assertThat(byId.get(ids.get("legacy")).getDaysUntilExpiry()).isEqualTo(-2L);
        assertThat(byId.get(ids.get("zero")).isExpiryWarning()).isFalse();
        assertThat(all).allSatisfy(r -> assertThat(r.getExpiryAsOfDate()).isEqualTo(TODAY));
    }

    @Test void warningFilterIncludesExpiredTodaySoonReservedAndQuarantineButNotZeroOrUnknown() {
        var warning = query(null, true, 0, 100);
        assertThat(warning.getTotalElements()).isEqualTo(7);
        assertThat(warning).extracting(StockBalanceResponse::getId).containsExactlyInAnyOrder(
            ids.get("yesterday"), ids.get("today"), ids.get("tomorrow"), ids.get("day30"), ids.get("legacy"), ids.get("reserved"), ids.get("quarantine"));
        assertThat(query(null, false, 0, 100).getTotalElements()).isEqualTo(5);
    }

    @Test void statusFiltersAreOrCombinedBeforePaginationAndUsePositivePhysicalStock() {
        var requested = List.of(StockExpiryStatus.EXPIRED, StockExpiryStatus.EXPIRING_SOON);
        var first = query(requested, null, 0, 2); var second = query(requested, null, 1, 2); var third = query(requested, null, 2, 2);
        assertThat(first.getTotalElements()).isEqualTo(6); assertThat(first.getTotalPages()).isEqualTo(3);
        Set<UUID> seen = new HashSet<>();
        for (var page : List.of(first, second, third)) page.forEach(r -> assertThat(seen.add(r.getId())).isTrue());
        assertThat(seen).hasSize(6).doesNotContain(ids.get("zero"), ids.get("today"));
        assertThat(query(List.of(StockExpiryStatus.UNKNOWN), null, 0, 100).getTotalElements()).isEqualTo(2);
        assertThat(query(List.of(StockExpiryStatus.EXPIRED), false, 0, 100)).isEmpty();
        assertThat(query(List.of(StockExpiryStatus.EXPIRES_TODAY), null, 0, 100).getTotalElements()).isEqualTo(1);
    }

    @Test void getByIdAndBothWarehouseBalanceQueriesExposeSameClassificationAndFilteredTotals() {
        resetScope();
        var detail = balances.get(ids.get("day30"));
        assertThat(detail.getExpiryStatus()).isEqualTo(StockExpiryStatus.EXPIRING_SOON);
        var page = warehouseView.balances(warehouse, null, true, null, true, PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(7); assertThat(page.getTotalPages()).isEqualTo(4);
        assertThat(page).allSatisfy(r -> { assertThat(r.expiryWarning()).isTrue(); assertThat(r.expiryAsOfDate()).isEqualTo(TODAY); });
        assertThat(warehouseView.balances(warehouse, null, false, List.of(StockExpiryStatus.EXPIRED), null, PageRequest.of(0, 1)).getTotalElements()).isEqualTo(2);
        var unfiltered = warehouseView.balances(warehouse, null, false, PageRequest.of(0, 100));
        assertThat(unfiltered.getTotalElements()).isEqualTo(12);
        assertThat(unfiltered).filteredOn(r -> r.id().equals(ids.get("missingBatch"))).singleElement()
            .satisfies(r -> assertThat(r.expiryStatus()).isEqualTo(StockExpiryStatus.UNKNOWN));
    }

    @Test void readDoesNotMutateStockBatchMaterialOrCreateMovements() {
        resetScope();
        var before = snapshot();
        query(null, true, 0, 100); balances.get(ids.get("yesterday"));
        warehouseView.balances(warehouse, null, true, null, true, PageRequest.of(0, 100));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void tenantCompanyWarehouseAndDeletedRowsStayOutsideFilteredResults() {
        tx.executeWithoutResult(status -> {
            StockBalance foreignTenant = balance(null); foreignTenant.setTenantId("foreign-" + tenant); persistWithoutScope(foreignTenant);
            Company foreignCompany = persist(new Company()); foreignCompany.setCompanyId(foreignCompany.getId());
            StockBalance foreignCompanyBalance = balance(null); foreignCompanyBalance.setCompanyId(foreignCompany.getId()); persistWithoutScope(foreignCompanyBalance);
            StockBalance elsewhere = balance(null); elsewhere.setWarehouseId(otherWarehouse); persist(elsewhere);
            row("deleted", -1, null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            em.find(StockBalance.class, ids.get("deleted")).softDelete();
        });
        assertThat(query(null, null, 0, 100).getTotalElements()).isEqualTo(12);
        assertThat(query(List.of(StockExpiryStatus.UNKNOWN), null, 0, 100).getTotalElements()).isEqualTo(2);
        assertThatThrownBy(() -> warehouseView.balances(otherWarehouse, null, true, PageRequest.of(0, 25)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void httpRoutesBindExpiryFiltersSerializeMetadataAndRejectInvalidStatus() throws Exception {
        mvc.perform(get("/api/stock/balances").with(user("expiry-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString())
            .param("warehouseId", warehouse.toString()).param("expiryStatus", "EXPIRED").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].expiryStatus").value("EXPIRED"))
            .andExpect(jsonPath("$.data.content[0].expiryWarning").value(true))
            .andExpect(jsonPath("$.data.content[0].expiryAsOfDate").value("2026-10-24"));
        mvc.perform(get("/api/warehouses/" + warehouse + "/command-view/balances").with(user("expiry-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString()).param("expiryWarning", "true").param("size", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(7))
            .andExpect(jsonPath("$.data.content[0].expiryWarning").value(true));
        mvc.perform(get("/api/stock/balances").with(user("expiry-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString()).param("warehouseId", warehouse.toString()).param("expiryStatus", "NO_SUCH_STATUS"))
            .andExpect(status().isBadRequest());
    }

    @Test void customHorizonIncludesTheExactRequestedDayAndSortingIsStable() {
        tx.executeWithoutResult(s -> row("day12",12,null,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO));
        var options = new com.enterprise.mm.payload.StockBalanceViewOptions();
        options.setExpiresWithinDays(12); options.setSortBy("daysUntilExpiry"); options.setSortDir("ASC");
        var ascending=advanced(options,0,100);
        assertThat(ascending.getTotalElements()).isEqualTo(5);
        assertThat(ascending).extracting(StockBalanceResponse::getDaysUntilExpiry).containsExactly(0L,1L,5L,6L,12L);
        options.setSortDir("DESC");
        assertThat(advanced(options,0,100)).extracting(StockBalanceResponse::getDaysUntilExpiry).containsExactly(12L,6L,5L,1L,0L);
        options.setIncludeExpired(true);
        assertThat(advanced(options,0,100).getTotalElements()).isEqualTo(7);
        options.setExpiresWithinDays(0);options.setIncludeExpired(false);
        assertThat(advanced(options,0,100)).extracting(StockBalanceResponse::getDaysUntilExpiry).containsExactly(0L);
    }

    @Test void customHorizonHasNoHardcodedThirtyDayLimitAndUnknownDatesSortLastBothWays() {
        var o=new com.enterprise.mm.payload.StockBalanceViewOptions();o.setExpiresWithinDays(50);o.setSortBy("daysUntilExpiry");
        assertThat(advanced(o,0,100)).extracting(StockBalanceResponse::getDaysUntilExpiry).contains(50L,31L);
        o.setExpiresWithinDays(null);o.setPositiveOnly(true);
        for(String direction:List.of("ASC","DESC")) {
            o.setSortDir(direction); var rows=advanced(o,0,100).getContent();
            assertThat(rows.get(rows.size()-1).getDaysUntilExpiry()).isNull();
            assertThat(rows.get(rows.size()-2).getDaysUntilExpiry()).isNull();
            assertThat(rows.getFirst().getDaysUntilExpiry()).isEqualTo(direction.equals("ASC")?-2L:50L);
        }
    }

    @Test void advancedFiltersCombineWithDatesReservationQuarantineAndNumericBounds() {
        var o=new com.enterprise.mm.payload.StockBalanceViewOptions();o.setExpiresWithinDays(12);o.setHasReserved(true);
        assertThat(advanced(o,0,100)).extracting(StockBalanceResponse::getId).containsExactly(ids.get("reserved"));
        o.setHasReserved(null);o.setHasQuarantine(true);
        assertThat(advanced(o,0,100)).extracting(StockBalanceResponse::getId).containsExactly(ids.get("quarantine"));
        o.setHasQuarantine(null);o.setQuantityMin(new BigDecimal("11"));assertThat(advanced(o,0,100)).isEmpty();
        o.setQuantityMin(BigDecimal.TEN);o.setQuantityMax(BigDecimal.TEN);o.setAvailableQuantityMin(BigDecimal.ONE);
        assertThat(advanced(o,0,100).getTotalElements()).isEqualTo(2);
        o.setExpiryDateFrom(TODAY.plusDays(1));o.setExpiryDateTo(TODAY.plusDays(1));
        assertThat(advanced(o,0,100)).extracting(StockBalanceResponse::getId).containsExactly(ids.get("tomorrow"));
        o.setUnitOfMeasureId(UUID.randomUUID());assertThat(advanced(o,0,100)).isEmpty();
    }

    @Test void newReadColumnsExposeActualGroupUnitsAndBatchDates() {
        resetScope();
        UUID group=tx.execute(s -> {
            MaterialGroup g=new MaterialGroup();g.setName("Chemicals");persist(g);
            em.find(Material.class,material).setMaterialGroupId(g.getId());
            var balance=em.find(StockBalance.class,ids.get("day30"));var batch=em.find(StockBatch.class,balance.getStockBatchId());
            batch.setManufacturedOn(TODAY.minusDays(100));batch.setReceivedDate(TODAY.minusDays(20));batch.setManufacturerBatchNumber("0000123");
            return g.getId();
        });
        var r=balances.get(ids.get("day30"));
        assertThat(r.getMaterialGroupId()).isEqualTo(group);assertThat(r.getMaterialGroupName()).isNotBlank();
        assertThat(r.getUnitOfMeasureId()).isEqualTo(unit);assertThat(r.getManufacturedOn()).isEqualTo(TODAY.minusDays(100));
        assertThat(r.getReceivedDate()).isEqualTo(TODAY.minusDays(20));assertThat(r.getManufacturerBatchNumber()).isEqualTo("0000123");
        assertThat(r.getCompanyName()).isNotBlank();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"uz","ru","en"})
    void localizedExportMatchesFilteredTableAndSortsTheDisplayedNames(String language) throws Exception {
        resetScope();
        tx.executeWithoutResult(s -> {
            var uom=em.find(UnitOfMeasure.class,unit);
            uom.setMeasurementNameShort("pc");uom.setMeasurementNameShortRu("шт.");uom.setMeasurementNameShortUz("dona");
            for(int i=0;i<2;i++) {
                var m=persist(new Material());m.setCode("localized-export-"+i);m.setUnitOfMeasureId(unit);
                m.setName(i==0?LocalizedText.format("Ящик","Alpha valve","Zebra klapan"):
                    LocalizedText.format("Арматура","Zulu valve","Anor klapan"));
                var b=balance(null);b.setMaterialId(m.getId());
                b.setQuantity(BigDecimal.valueOf(i+1));b.setAvailableQuantity(BigDecimal.valueOf(i+1));
                b.setReservedQuantity(BigDecimal.ZERO);b.setQuarantineQuantity(BigDecimal.ZERO);persist(b);
            }
        });
        var old=org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
            for(String direction:List.of("ASC","DESC")) {
                var http=new com.enterprise.mm.payload.StockBalanceHttpFilter();http.setWarehouseId(warehouse);
                http.setSearch("localized-export");http.setPositiveOnly(true);
                http.setQuantityMin(BigDecimal.ONE);http.setQuantityMax(BigDecimal.valueOf(2));
                http.setSortBy("materialName");http.setSortDir(direction);
                var request=http.toRequest();var expected=balances.search(request,PageRequest.of(0,10)).getContent();
                assertThat(expected).hasSize(2);
                String first=language.equals("en")?"Alpha valve":language.equals("ru")?"Арматура":"Anor klapan";
                if(direction.equals("ASC")) assertThat(LocalizedText.translate(expected.getFirst().getMaterialName(),Locale.forLanguageTag(language))).isEqualTo(first);
                assertThat(balances.search(request,PageRequest.of(0,1)).getTotalElements()).isEqualTo(2);
                // Explicit export language must also control async query sorting and UOM labels.
                org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.FRENCH);
                var output=new java.io.ByteArrayOutputStream();
                excel.write(request,List.of("id","materialName","unitCode","quantity"),language,output);
                assertThat(org.springframework.context.i18n.LocaleContextHolder.getLocale()).isEqualTo(Locale.FRENCH);
                try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(output.toByteArray()))) {
                    var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(2);
                    for(int i=0;i<expected.size();i++) {
                        var row=sheet.getRow(i+1);
                        assertThat(row.getCell(0).getStringCellValue()).isEqualTo(expected.get(i).getId().toString());
                        assertThat(row.getCell(1).getStringCellValue()).isEqualTo(LocalizedText.translate(expected.get(i).getMaterialName(),Locale.forLanguageTag(language)));
                        assertThat(row.getCell(2).getStringCellValue()).isEqualTo(language.equals("uz")?"dona":language.equals("ru")?"шт.":"pc");
                    }
                }
                org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
            }
        } finally {org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(old);}
    }

    @Test void unitSortingUsesHumanLabelsInsteadOfImportedSourceCodes() {
        resetScope();
        tx.executeWithoutResult(s -> {
            for(int i=0;i<2;i++) {
                var u=persist(new UnitOfMeasure());u.setCode(i==0?"1C-UOM-Z":"1C-UOM-A");
                u.setMeasurementNameShort(i==0?"aaa":"zzz");
                var m=persist(new Material());m.setCode("unit-sort-"+i);m.setUnitOfMeasureId(u.getId());
                var b=balance(null);b.setMaterialId(m.getId());persist(b);
            }
        });
        var http=new com.enterprise.mm.payload.StockBalanceHttpFilter();http.setWarehouseId(warehouse);
        http.setSearch("unit-sort");http.setSortBy("unitCode");http.setSortDir("ASC");
        assertThat(balances.search(http.toRequest(),PageRequest.of(0,10)).getContent())
            .extracting(StockBalanceResponse::getUnitLabel).containsExactly("aaa","zzz");
    }

    @Test void exportContainsEveryFilteredRowInTheSameOrderAndUsesReadOnlyRepeatableRead() throws Exception {
        resetScope();
        tx.executeWithoutResult(s -> {
            for(int i=0;i<105;i++)row("export-"+i,12,null,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO);
        });
        var o=new com.enterprise.mm.payload.StockBalanceViewOptions();o.setExpiresWithinDays(12);o.setSortBy("daysUntilExpiry");o.setSortDir("DESC");
        var expected=advanced(o,0,200).getContent();
        var request=com.enterprise.mm.payload.StockBalanceSearchRequest.forWarehouse(warehouse,null,null,null,o);
        when(scope.canAccessPayload(any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);return true;
        });
        var output=new java.io.ByteArrayOutputStream();excel.write(request,List.of("id","daysUntilExpiry","quantity"),"en",output);
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            var sheet=book.getSheet("Balances");assertThat(sheet.getLastRowNum()).isEqualTo(expected.size());
            assertThat(expected.size()).isGreaterThan(100);
            for(int i=0;i<expected.size();i++) {
                assertThat(sheet.getRow(i+1).getCell(0).getStringCellValue()).isEqualTo(expected.get(i).getId().toString());
                assertThat(sheet.getRow(i+1).getCell(1).getNumericCellValue()).isEqualTo(expected.get(i).getDaysUntilExpiry().doubleValue());
            }
            assertThat(book.getSheet("Report info")).isNotNull();
            assertThat(sheet.getPaneInformation()).isNotNull();assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
        }
    }

    @Test void exportPreservesLongDecimalsCodesDatesAndSelectedColumnOrder() throws Exception {
        resetScope();
        tx.executeWithoutResult(s -> {
            em.find(Material.class,material).setCode("=1+1");
            var b=em.find(StockBalance.class,ids.get("today"));b.setQuantity(new BigDecimal("123456789012345.1234567"));
        });
        var o=new com.enterprise.mm.payload.StockBalanceViewOptions();o.setExpiresWithinDays(0);
        var request=com.enterprise.mm.payload.StockBalanceSearchRequest.forWarehouse(warehouse,null,null,null,o);
        var output=new java.io.ByteArrayOutputStream();excel.write(request,List.of("materialCode","quantity","expirationDate"),"en",output);
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            var row=book.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=1+1");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("123456789012345.1234567");
            assertThat(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(row.getCell(2))).isTrue();
            assertThat(row.getCell(2).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(TODAY);
        }
    }

    @Test void invalidAdvancedFiltersAndUnknownColumnsFailBeforeExport() {
        resetScope();var o=new com.enterprise.mm.payload.StockBalanceViewOptions();o.setExpiresWithinDays(-1);
        assertThatThrownBy(()->advanced(o,0,20)).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        o.setExpiresWithinDays(12);o.setSortBy("materialName;drop table stock_balances");
        assertThatThrownBy(()->advanced(o,0,20)).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        o.setSortBy("quantity");o.setSortDir("SIDEWAYS");
        assertThatThrownBy(()->advanced(o,0,20)).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        o.setSortDir("ASC");o.setQuantityMin(BigDecimal.TEN);o.setQuantityMax(BigDecimal.ONE);
        assertThatThrownBy(()->advanced(o,0,20)).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        var valid=com.enterprise.mm.payload.StockBalanceSearchRequest.empty();
        assertThatThrownBy(()->excel.validate(valid,List.of("password"),"en")).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        assertThatThrownBy(()->excel.validate(valid,List.of("quantity","quantity"),"en")).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
    }

    @Test void httpAdvancedListAndDownloadBindTheSameFiltersAndIncludeDayTwelve() throws Exception {
        resetScope();tx.executeWithoutResult(s -> row("day12",12,null,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO));
        mvc.perform(get("/api/stock/balances").with(user("expiry-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString()).param("warehouseId",warehouse.toString())
            .param("expiresWithinDays","12").param("sortBy","daysUntilExpiry").param("sortDir","DESC").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(5))
            .andExpect(jsonPath("$.data.content[0].daysUntilExpiry").value(12));
        mvc.perform(get("/api/warehouses/"+warehouse+"/command-view/balances").with(user("expiry-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString()).param("expiresWithinDays","12")
            .param("sortBy","daysUntilExpiry").param("sortDir","DESC").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(5))
            .andExpect(jsonPath("$.data.content[0].daysUntilExpiry").value(12));
        var pending=mvc.perform(get("/api/stock/balances/export").with(user("expiry-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString()).param("warehouseId",warehouse.toString())
            .param("expiresWithinDays","12").param("sortBy","daysUntilExpiry").param("sortDir","DESC")
            .param("columns","id","daysUntilExpiry").param("language","en"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        pending.getAsyncResult(30000);
        var downloaded=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(pending))
            .andExpect(status().isOk()).andExpect(header().string("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(downloaded))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(5);
            assertThat(book.getSheetAt(0).getRow(1).getCell(1).getNumericCellValue()).isEqualTo(12d);
        }
    }

    @Test void exportHonorsWarehouseAuthorityBeforeStreaming() throws Exception {
        resetScope();when(scope.canAccessWarehouse(warehouse)).thenReturn(false);
        var request=com.enterprise.mm.payload.StockBalanceSearchRequest.forWarehouse(warehouse,null,null,null,new com.enterprise.mm.payload.StockBalanceViewOptions());
        assertThatThrownBy(()->excel.validate(request,List.of("id"),"en"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        mvc.perform(get("/api/stock/balances/export").with(user("expiry-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString()).param("warehouseId",warehouse.toString()))
            .andExpect(status().isForbidden()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncNotStarted());
    }

    @Test void exportMetadataPreservesLongFiltersWithoutExcelCellTruncation() throws Exception {
        resetScope();String search="x".repeat(35000);
        var http=new com.enterprise.mm.payload.StockBalanceHttpFilter();http.setWarehouseId(warehouse);http.setSearch(search);
        var output=new java.io.ByteArrayOutputStream();excel.write(http.toRequest(),List.of("id"),"en",output);
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            StringBuilder filters=new StringBuilder();var metadata=book.getSheet("Report info");
            for(var row:metadata)if(row.getCell(0).getStringCellValue().startsWith("Filters"))filters.append(row.getCell(1).getStringCellValue());
            assertThat(filters.toString()).contains(search);
            assertThat(book.getSheet("Balances").getLastRowNum()).isZero();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing", "blank", "deleted", "known"})
    void currencyVisibilityMatchesAcrossDetailListWarehouseAndHttpExcel(String setting) throws Exception {
        resetScope();
        tx.executeWithoutResult(s -> {
            if(!setting.equals("missing")) {
                var config=new InventoryAccountingSettings();
                config.setBaseCurrencyCode(setting.equals("blank")?"   ":"USD");
                config.setDeleted(setting.equals("deleted"));persist(config);
            }
        });
        boolean known=setting.equals("known");
        var before=snapshot();
        var o=new com.enterprise.mm.payload.StockBalanceViewOptions();o.setExpiresWithinDays(0);
        var detail=balances.get(ids.get("today"));
        var list=advanced(o,0,100).getContent().getFirst();
        var request=StockBalanceSearchRequest.forWarehouse(warehouse,null,null,null,o);
        var command=warehouseView.balances(warehouse,request,o,PageRequest.of(0,100)).getContent().getFirst();
        assertThat(detail.getCurrencyCode()).isEqualTo(known?"USD":null);
        assertThat(list.getCurrencyCode()).isEqualTo(detail.getCurrencyCode());
        assertThat(command.currencyCode()).isEqualTo(detail.getCurrencyCode());
        if(known) {
            assertThat(detail.getTotalValue()).isEqualByComparingTo(BigDecimal.TEN);
            assertThat(list.getTotalValue()).isEqualByComparingTo(BigDecimal.TEN);
            assertThat(new BigDecimal(command.totalValue())).isEqualByComparingTo(BigDecimal.TEN);
        } else {
            assertThat(detail.getTotalValue()).isNull();assertThat(list.getTotalValue()).isNull();assertThat(command.totalValue()).isNull();
        }
        for(String route:List.of("/api/stock/balances", "/api/warehouses/"+warehouse+"/command-view/balances")) {
            var result=mvc.perform(get(route).with(user("currency-reader").roles("MM_MANAGER"))
                .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString())
                .param("warehouseId",warehouse.toString()).param("expiresWithinDays","0"))
                .andExpect(status().isOk());
            if(known) result.andExpect(jsonPath("$.data.content[0].currencyCode").value("USD"))
                .andExpect(jsonPath("$.data.content[0].totalValue").isString());
            else result.andExpect(jsonPath("$.data.content[0].currencyCode").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].totalValue").doesNotExist());
        }
        var pending=mvc.perform(get("/api/stock/balances/export").with(user("currency-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString()).param("warehouseId",warehouse.toString())
            .param("expiresWithinDays","0").param("columns","totalValue","currencyCode","quantity").param("language","en"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        pending.getAsyncResult(30000);
        var bytes=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(pending))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var row=book.getSheetAt(0).getRow(1);
            if(known) {
                assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(10d);
                assertThat(row.getCell(1).getStringCellValue()).isEqualTo("USD");
            } else {
                assertThat(row.getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                assertThat(row.getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            }
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(10d);
        }
        resetScope(); // MockMvc request filters clear the caller thread context.
        var defaults=new java.io.ByteArrayOutputStream();excel.write(request,null,"en",defaults);
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(defaults.toByteArray()))) {
            var row=book.getSheetAt(0).getRow(1);
            if(known) {
                assertThat(row.getCell(15).getNumericCellValue()).isEqualTo(10d);
                assertThat(row.getCell(16).getStringCellValue()).isEqualTo("USD");
            } else {
                assertThat(row.getCell(15).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                assertThat(row.getCell(16).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            }
        }
        assertThat(snapshot()).isEqualTo(before); // Read masking never changes stored amounts or stock.
    }

    @Test void knownCurrencyKeepsARealZeroBookValue() {
        resetScope();tx.executeWithoutResult(s -> {
            var config=new InventoryAccountingSettings();config.setBaseCurrencyCode("USD");persist(config);
            em.find(StockBalance.class,ids.get("today")).setTotalValue(BigDecimal.ZERO);
        });
        assertThat(balances.get(ids.get("today")).getTotalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test void exportAsyncRedispatchDoesNotRequireASecondJwtButInitialRequestStillDoes() throws Exception {
        resetScope();
        mvc.perform(get("/api/stock/balances/export")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())
            .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString()).param("warehouseId",warehouse.toString()))
            .andExpect(status().isUnauthorized());
        var pending=mvc.perform(get("/api/stock/balances/export").with(user("expiry-reader").roles("MM_MANAGER"))
            .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString()).param("warehouseId",warehouse.toString())
            .param("columns","id").param("language","en"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        pending.getAsyncResult(30000);
        var bytes=mvc.perform(servletContext -> {
            var dispatch=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(pending).buildRequest(servletContext);
            return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous().postProcessRequest(dispatch);
        })
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(12);
        }
        mvc.perform(get("/api/stock/balances")
            .with(request -> { request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC); return request; })
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())
            .header("X-Tenant-Id",tenant).header("X-Company-Id",company.toString()))
            .andExpect(status().isUnauthorized());
    }

    private Page<StockBalanceResponse> advanced(com.enterprise.mm.payload.StockBalanceViewOptions options,int page,int size) {
        resetScope();return balances.search(com.enterprise.mm.payload.StockBalanceSearchRequest.forWarehouse(warehouse,null,null,null,options),PageRequest.of(page,size));
    }

    private Page<StockBalanceResponse> query(List<StockExpiryStatus> statuses, Boolean warning, int page, int size) {
        resetScope();
        var request = new StockBalanceSearchRequest(null, null, warehouse, null, null, null, null, null, false,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, statuses, warning, null);
        return balances.search(request, PageRequest.of(page, size, Sort.by("id")));
    }
    private List<Object> snapshot() {
        resetScope();
        return tx.execute(status -> List.of(
            em.createQuery("select b.id, b.version, b.quantity, b.availableQuantity, b.reservedQuantity, b.quarantineQuantity, b.totalValue, b.status from StockBalance b where b.tenantId=:tenant order by b.id", Object[].class)
                .setParameter("tenant", tenant).getResultList().stream().map(Arrays::asList).toList(),
            em.createQuery("select b.id, b.version, b.expiresOn, b.expirationDate from StockBatch b where b.tenantId=:tenant order by b.id", Object[].class)
                .setParameter("tenant", tenant).getResultList().stream().map(Arrays::asList).toList(),
            em.find(Material.class, material).getStatus(), em.find(Material.class, material).getVersion(),
            em.createQuery("select count(m) from StockMovement m where m.tenantId=:tenant", Long.class).setParameter("tenant", tenant).getSingleResult()));
    }
    private void row(String name, Integer expires, Integer legacy, BigDecimal quantity, BigDecimal available, BigDecimal reserved, BigDecimal quarantine) {
        StockBatch batch = new StockBatch(); batch.setMaterialId(material); batch.setWarehouseId(warehouse); batch.setBatchNumber(name);
        batch.setExpiresOn(expires == null ? null : TODAY.plusDays(expires)); batch.setExpirationDate(legacy == null ? null : TODAY.plusDays(legacy));
        persist(batch);
        StockBalance b = balance(batch.getId()); b.setQuantity(quantity); b.setAvailableQuantity(available); b.setReservedQuantity(reserved); b.setQuarantineQuantity(quarantine);
        ids.put(name, persist(b).getId());
    }
    private StockBalance balance(UUID batch) {
        StockBalance b = new StockBalance(); b.setMaterialId(material); b.setWarehouseId(warehouse); b.setStockBatchId(batch);
        b.setQuantity(BigDecimal.TEN); b.setAvailableQuantity(BigDecimal.TEN); b.setTotalValue(BigDecimal.TEN); return b;
    }
    private <T extends BusinessEntity> T persist(T entity) {
        entity.setTenantId(tenant); entity.setCompanyId(company); return persistWithoutScope(entity);
    }
    private <T extends BusinessEntity> T persistWithoutScope(T entity) {
        entity.setName("Expiry test"); entity.setCode("EXP-" + UUID.randomUUID()); entity.setStatus("ACTIVE"); em.persist(entity); return entity;
    }
    private void resetScope() { TenantContext.setTenant(tenant); CompanyContext.setCompany(company); }
}
