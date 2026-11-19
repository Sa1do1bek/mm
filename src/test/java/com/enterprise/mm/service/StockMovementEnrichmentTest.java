package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.enterprise.mm.payload.StockMovementSearchRequest;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.warehouse.movements.MovementQuerySupport;
import com.enterprise.mm.warehouse.movements.MovementExportText;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.*;

import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class StockMovementEnrichmentTest {

    private StockMovementService service;
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        StockMovementRepository repository = mock(StockMovementRepository.class);
        StockMovementMapper mapper = new StockMovementMapper();
        service = new StockMovementService(
            repository,
            mapper,
            mock(com.enterprise.mm.repository.StockBalanceRepository.class),
            mock(com.enterprise.mm.repository.StockBatchRepository.class),
            mock(com.enterprise.mm.repository.MaterialRepository.class),
            mock(MaterialCatalogGuard.class),
            mock(com.enterprise.mm.repository.WarehouseRepository.class),
            new StockBalanceMutationService(),
            mock(InventoryPeriodGuardService.class),
            mock(InventoryValuationPolicyService.class),
            mock(StockValuationService.class),
            mock(InventoryCostingService.class),
            mock(com.enterprise.mm.inventory.allocation.StockMovementAllocationWriter.class),
            mock(com.enterprise.mm.repository.StockMovementAllocationRepository.class),
            mock(com.enterprise.mm.repository.StockMovementAllocationCostLayerRepository.class),
            mock(AuditService.class),
            mock(RoleDataScopeService.class),
            mock(InventoryFreezeGuardService.class),
            false
        );
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void enrichMovements_TransferInbound_populatesSourceAndDestinationAndCounterparty() {
        UUID wh1 = UUID.randomUUID();
        UUID wh2 = UUID.randomUUID();

        StockMovementResponse m = new StockMovementResponse();
        m.setMovementType(MovementType.TRANSFER);
        m.setWarehouseId(wh1);
        m.setWarehouseName("Central Warehouse");
        m.setWarehouseCode("WH-01");
        m.setTargetWarehouseId(wh2);
        m.setTargetWarehouseName("Shop Warehouse");
        m.setTargetWarehouseCode("WH-02");
        m.setQuantity(new BigDecimal("10.000"));
        m.setReferenceType("TRANSFER_RECEIVE");
        m.setCreatedAt(LocalDateTime.of(2026, 10, 20, 10, 30));

        service.enrichMovements(List.of(m));

        assertThat(m.getMovementDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(m.getSourceWarehouseId()).isEqualTo(wh2);
        assertThat(m.getSourceWarehouseName()).isEqualTo("Shop Warehouse");
        assertThat(m.getSourceLocation()).isEqualTo("Shop Warehouse");
        assertThat(m.getSourceType()).isEqualTo("WAREHOUSE");
        assertThat(m.getDestinationWarehouseId()).isEqualTo(wh1);
        assertThat(m.getDestinationWarehouseName()).isEqualTo("Central Warehouse");
        assertThat(m.getDestinationLocation()).isEqualTo("Central Warehouse");
        assertThat(m.getDestinationType()).isEqualTo("WAREHOUSE");
        assertThat(m.getCounterparty()).isEqualTo("Shop Warehouse");
    }

    @Test
    void enrichMovements_TransferOutbound_populatesSourceAndDestinationAndCounterparty() {
        UUID wh1 = UUID.randomUUID();
        UUID wh2 = UUID.randomUUID();

        StockMovementResponse m = new StockMovementResponse();
        m.setMovementType(MovementType.TRANSFER);
        m.setWarehouseId(wh1);
        m.setWarehouseName("Central Warehouse");
        m.setWarehouseCode("WH-01");
        m.setTargetWarehouseId(wh2);
        m.setTargetWarehouseName("Shop Warehouse");
        m.setTargetWarehouseCode("WH-02");
        m.setQuantity(new BigDecimal("-10.000"));
        m.setReferenceType("TRANSFER_DISPATCH");

        service.enrichMovements(List.of(m));

        assertThat(m.getSourceWarehouseId()).isEqualTo(wh1);
        assertThat(m.getSourceWarehouseName()).isEqualTo("Central Warehouse");
        assertThat(m.getSourceLocation()).isEqualTo("Central Warehouse");
        assertThat(m.getSourceType()).isEqualTo("WAREHOUSE");
        assertThat(m.getDestinationWarehouseId()).isEqualTo(wh2);
        assertThat(m.getDestinationWarehouseName()).isEqualTo("Shop Warehouse");
        assertThat(m.getDestinationLocation()).isEqualTo("Shop Warehouse");
        assertThat(m.getDestinationType()).isEqualTo("WAREHOUSE");
        assertThat(m.getCounterparty()).isEqualTo("Shop Warehouse");
    }

    @Test
    void enrichMovements_OpeningStock_populatesOpeningBalanceCounterparty() {
        UUID wh = UUID.randomUUID();

        StockMovementResponse m = new StockMovementResponse();
        m.setMovementType(MovementType.ADJUSTMENT);
        m.setWarehouseId(wh);
        m.setWarehouseName("Central Warehouse");
        m.setReferenceType("OPENING_STOCK_EXACT");
        m.setMovementDate(LocalDate.of(2026, 10, 1));

        service.enrichMovements(List.of(m));

        assertThat(m.getCounterparty()).isEqualTo("Opening Stock Balance");
        assertThat(m.getSourceLocation()).isEqualTo("Opening Stock");
        assertThat(m.getSourceType()).isEqualTo("SYSTEM");
        assertThat(m.getDestinationLocation()).isEqualTo("Central Warehouse");
        assertThat(m.getDestinationType()).isEqualTo("WAREHOUSE");
    }

    @Test
    void enrichMovements_DateFallback_whenMovementDateNull() {
        StockMovementResponse m = new StockMovementResponse();
        m.setCreatedAt(LocalDateTime.of(2026, 10, 15, 14, 0));

        service.enrichMovements(List.of(m));

        assertThat(m.getMovementDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @ParameterizedTest
    @CsvSource({"ru,Приход,Расход", "uz,Kirim,Chiqim", "en,Inbound Receipt,Outbound Issue"})
    void genericReceiptAndIssueLabelsUseTheRequestLanguage(String language, String receiptLabel, String issueLabel) {
        var original = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(language));
            var receipt = new StockMovementResponse(); receipt.setMovementType(MovementType.RECEIPT); receipt.setWarehouseName("Warehouse");
            var issue = new StockMovementResponse(); issue.setMovementType(MovementType.ISSUE); issue.setWarehouseName("Warehouse");
            service.enrichMovements(List.of(receipt, issue));
            assertThat(receipt.getSourceLocation()).isEqualTo(receiptLabel);
            assertThat(receipt.getSourceType()).isEqualTo("SYSTEM");
            assertThat(issue.getDestinationLocation()).isEqualTo(issueLabel);
            assertThat(issue.getDestinationType()).isEqualTo("SYSTEM");
        } finally { LocaleContextHolder.setLocale(original); }
    }

    @Test
    void manualReceiptUsesItsStructuredSourceRatherThanTheDocumentReference() throws Exception {
        UUID documentId = UUID.randomUUID();
        stubManualFact(documentId, WarehouseStockFactMetadata.snapshot("OOO Octo", "001"), "OOO Octo · 001");
        var receipt = manualMovement(documentId, MovementType.RECEIPT);
        service.enrichMovements(List.of(receipt));
        assertThat(receipt.getSourceLocation()).isEqualTo("OOO Octo");
        assertThat(receipt.getCounterparty()).isEqualTo("OOO Octo");
        assertThat(receipt.getSourceType()).isEqualTo("MANUAL");
        assertThat(receipt.getDestinationLocation()).isEqualTo("Warehouse");
    }

    @Test
    void legacyManualReceiptRetainsTheSavedTextAndManualIssueUsesTheRecipient() throws Exception {
        UUID documentId = UUID.randomUUID();
        stubManualFact(documentId, null, "OOO Octo · 001");
        var receipt = manualMovement(documentId, MovementType.RECEIPT);
        service.enrichMovements(List.of(receipt));
        assertThat(receipt.getSourceLocation()).isEqualTo("OOO Octo · 001");
        stubManualFact(documentId, WarehouseStockFactMetadata.snapshot("Workshop", "002"), "Workshop · 002");
        var issue = manualMovement(documentId, MovementType.ISSUE);
        service.enrichMovements(List.of(issue));
        assertThat(issue.getSourceLocation()).isEqualTo("Warehouse");
        assertThat(issue.getDestinationLocation()).isEqualTo("Workshop");
        assertThat(issue.getDestinationType()).isEqualTo("MANUAL");
    }

    private StockMovementResponse manualMovement(UUID documentId, MovementType type) {
        var movement = new StockMovementResponse(); movement.setMovementType(type); movement.setWarehouseName("Warehouse");
        movement.setReferenceType(WarehouseStockFactService.REFERENCE_TYPE); movement.setReferenceId(documentId);
        return movement;
    }

    private void stubManualFact(UUID documentId, String payload, String description) throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("doc_id", UUID.class)).thenReturn(documentId);
            when(rs.getString("manual_operation_payload")).thenReturn(payload);
            when(rs.getString("description")).thenReturn(description);
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(contains("from stock_movement_documents"), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    @Test
    void enrichMovements_WithJdbcTemplate_enrichesSupplierAndDepartment() throws Exception {
        UUID poId = UUID.randomUUID();
        UUID supId = UUID.randomUUID();
        UUID irId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(rs.getObject("doc_id", UUID.class)).thenReturn(poId);
            org.mockito.Mockito.when(rs.getObject("supplier_id", UUID.class)).thenReturn(supId);
            org.mockito.Mockito.when(rs.getString("supplier_code")).thenReturn("SUP-01");
            org.mockito.Mockito.when(rs.getString("supplier_name")).thenReturn("Navoiy Kimyo Zavod");
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(contains("from purchase_orders"), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(rs.getObject("doc_id", UUID.class)).thenReturn(irId);
            org.mockito.Mockito.when(rs.getObject("dept_id", UUID.class)).thenReturn(deptId);
            org.mockito.Mockito.when(rs.getString("dept_code")).thenReturn("DEPT-MECH");
            org.mockito.Mockito.when(rs.getString("dept_name")).thenReturn("Mexanika Sehi");
            org.mockito.Mockito.when(rs.getString("receiver_name")).thenReturn("Aziz Karimov");
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(contains("from issue_requests"), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        StockMovementResponse receipt = new StockMovementResponse();
        receipt.setMovementType(MovementType.RECEIPT);
        receipt.setWarehouseName("Central Warehouse");
        receipt.setReferenceType("PurchaseOrder");
        receipt.setReferenceId(poId);

        StockMovementResponse issue = new StockMovementResponse();
        issue.setMovementType(MovementType.ISSUE);
        issue.setWarehouseName("Central Warehouse");
        issue.setReferenceType("ISSUE_REQUEST");
        issue.setReferenceId(irId);

        List<StockMovementResponse> list = new ArrayList<>(List.of(receipt, issue));
        service.enrichMovements(list);

        // Verify receipt enrichment
        assertThat(receipt.getSupplierId()).isEqualTo(supId);
        assertThat(receipt.getSupplierCode()).isEqualTo("SUP-01");
        assertThat(receipt.getSupplierName()).isEqualTo("Navoiy Kimyo Zavod");
        assertThat(receipt.getSourceLocation()).isEqualTo("Navoiy Kimyo Zavod");
        assertThat(receipt.getSourceType()).isEqualTo("SUPPLIER");
        assertThat(receipt.getDestinationLocation()).isEqualTo("Central Warehouse");
        assertThat(receipt.getCounterparty()).isEqualTo("Navoiy Kimyo Zavod");

        // Verify issue enrichment
        assertThat(issue.getDepartmentId()).isEqualTo(deptId);
        assertThat(issue.getDepartmentCode()).isEqualTo("DEPT-MECH");
        assertThat(issue.getDepartmentName()).isEqualTo("Mexanika Sehi");
        assertThat(issue.getSourceLocation()).isEqualTo("Central Warehouse");
        assertThat(issue.getDestinationLocation()).isEqualTo("Mexanika Sehi");
        assertThat(issue.getDestinationType()).isEqualTo("DEPARTMENT");
        assertThat(issue.getCounterparty()).isEqualTo("Mexanika Sehi");
    }
    @Nested
    class ExcelExport {
        private StockMovementService exporter;
        private StockMovementService query;
        private RoleDataScopeService roles;

        @BeforeEach void setupExport() {
            exporter = spy(StockMovementEnrichmentTest.this.service);
            query = exporter;
            roles = mock(RoleDataScopeService.class);
            when(roles.applyWarehouseScope(any())).thenAnswer(a -> a.getArgument(0));
            ReflectionTestUtils.setField(exporter,"roleDataScopeService",roles);
            ReflectionTestUtils.setField(exporter,"maxRows",1000);
        }
        @AfterEach void cleanupExport() { LocaleContextHolder.resetLocaleContext(); }

        @ParameterizedTest
        @CsvSource({"uz,Sana,Kirim,Boshlang‘ich qoldiq,Mis", "ru,Дата,Приход,Начальные остатки,Медь", "en,Date,Receipt,Opening Stock Balance,Copper"})
        void exportsLocalizedTypedCellsAndAllPages(String lang,String dateHeader,String movementType,String opening,String material) throws Exception {
            StockMovementResponse row = row();
            Sort sort = MovementQuerySupport.sort(List.of("code,asc"));
            List<StockMovementResponse> first = Collections.nCopies(500,row);
            doReturn(new PageImpl<>(first,PageRequest.of(0,500,sort),501)).when(query).search(any(),eq(PageRequest.of(0,500,sort)),anyString());
            doReturn(new PageImpl<>(List.of(row),PageRequest.of(1,500,sort),501)).when(query).search(any(),eq(PageRequest.of(1,500,sort)),anyString());
            byte[] bytes = exporter.exportMovements(StockMovementSearchRequest.empty(),sort,lang);
            try(var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                Sheet sheet = book.getSheetAt(0);
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(dateHeader);
                Row data = sheet.getRow(1);
                assertThat(data.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(DateUtil.isCellDateFormatted(data.getCell(0))).isTrue();
                assertThat(data.getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(row.getMovementDate());
                assertThat(data.getCell(1).getCellType()).isEqualTo(CellType.STRING);
                assertThat(data.getCell(1).getStringCellValue()).isEqualTo("=HYPERLINK(\"bad\")");
                assertThat(data.getCell(2).getStringCellValue()).isEqualTo(movementType);
                assertThat(data.getCell(3).getStringCellValue()).isEqualTo(opening);
                assertThat(data.getCell(7).getStringCellValue()).isEqualTo(material);
                assertThat(data.getCell(8).getNumericCellValue()).isEqualTo(2.1234567);
                assertThat(data.getCell(10).getStringCellValue()).isEqualTo(MovementExportText.word("POSTED", lang));
                assertThat(data.getCell(11).getStringCellValue()).isEqualTo("B-42");
                assertThat(data.getCell(12).getStringCellValue()).isEqualTo("Warehouse manager");
                assertThat(sheet.getRow(0).getCell(10).getStringCellValue()).isEqualTo(MovementExportText.word("status", lang));
                assertThat(sheet.getRow(0).getCell(11).getStringCellValue()).isEqualTo(MovementExportText.word("batch", lang));
                assertThat(sheet.getLastRowNum()).isEqualTo(501);
                assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            }
            verify(query,times(2)).search(any(),any(),anyString());
        }

        @Test void appliesFiltersWithoutPrintingTechnicalMetadata() throws Exception {
            UUID warehouseId = UUID.randomUUID();
            var filter = new StockMovementSearchRequest("pump", List.of("POSTED"), warehouseId, null, null, null, false, null, null, null, null, null, null, null, null, LocalDate.of(2026, 10, 22), List.of(MovementType.TRANSFER));
            doReturn(new PageImpl<>(List.of(row()), PageRequest.of(0,500),1)).when(query).search(any(),any(),anyString());
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(exporter.exportMovements(filter,MovementQuerySupport.sort(null),"en")))) {
                var sheet = book.getSheetAt(0);
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Date");
                assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("=HYPERLINK(\"bad\")");
                assertThat(sheet.getLastRowNum()).isEqualTo(1);
                assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
            }
            verify(query).search(eq(filter), any(), eq("en"));
        }

        @Test void emptyResultStillHasHeadersAndLocalizedExplanation() throws Exception {
            doReturn(new PageImpl<>(List.of(),PageRequest.of(0,500),0)).when(query).search(any(),any(),anyString());
            try(var book = new XSSFWorkbook(new ByteArrayInputStream(exporter.exportMovements(StockMovementSearchRequest.empty(),MovementQuerySupport.sort(null),"uz")))) {
                assertThat(book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("Mos harakatlar topilmadi");
            }
        }

        @Test void limitRejectsInsteadOfTruncating() {
            doReturn(new PageImpl<>(Collections.nCopies(500,row()),PageRequest.of(0,500),1001)).when(query).search(any(),any(),anyString());
            assertThatThrownBy(() -> exporter.exportMovements(StockMovementSearchRequest.empty(),MovementQuerySupport.sort(null),"uz"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("1000");
            verify(query).search(any(),any(),anyString());
        }

        @Test void validatesDatesAndSortAndTranslationFallbacks() {
            assertThat(MovementQuerySupport.sort(null).toString()).isEqualTo("movementDate: DESC,id: ASC");
            assertThatThrownBy(() -> MovementQuerySupport.sort(List.of("quantity,sideways"))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> MovementQuerySupport.sort(List.of("code;drop table x,asc"))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> MovementQuerySupport.sort(List.of("code,asc,extra"))).isInstanceOf(BadRequestException.class);
            var filter = new StockMovementSearchRequest(null,null,null,null,LocalDate.of(2026,10,22),LocalDate.of(2026,10,1),false,null,null,null,null);
            assertThatThrownBy(() -> exporter.exportMovements(filter,MovementQuerySupport.sort(null),"en")).isInstanceOf(BadRequestException.class);
            verify(query, never()).search(any(),any(),anyString());
            assertThat(MovementExportText.label("{ru=Медь, uz=Mis, en=Copper}","uz")).isEqualTo("Mis");
            assertThat(MovementExportText.label("{\"ru\":\"Медь\"}","en")).isEqualTo("Медь");
            assertThat(MovementExportText.label("ru: Медь\nen: Copper\nuz: Mis","uz")).isEqualTo("Mis");
            assertThat(MovementExportText.label("Asl nom","en")).isEqualTo("Asl nom");
            assertThat(MovementExportText.language("uz-UZ")).isEqualTo("uz");
            assertThatThrownBy(() -> MovementExportText.language("de")).isInstanceOf(BadRequestException.class);
        }

        @ParameterizedTest
        @CsvSource({"uz,dona", "ru,шт", "en,pcs"})
        void unitTranslationUsesTenantAndCompanyCatalog(String language,String expected) throws Exception {
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:movement-unit-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
            var db = new org.springframework.jdbc.core.JdbcTemplate(source);
            db.execute("create table unit_of_measures(id uuid,tenant_id text,company_id uuid,is_delete boolean,code text,measurement_name_short text,measurement_name_short_ru text,measurement_name_short_uz text)");
            UUID company = UUID.randomUUID();
            com.enterprise.mm.common.TenantContext.setTenant("movement-unit-test");
            com.enterprise.mm.common.CompanyContext.setCompany(company);
            try {
                db.update("insert into unit_of_measures values(?,?,?,false,'PCS','pcs','шт','dona')",UUID.randomUUID(),"movement-unit-test",company);
                db.update("insert into unit_of_measures values(?,?,?,false,'PCS','wrong','wrong','wrong')",UUID.randomUUID(),"other-tenant",company);
                db.update("insert into unit_of_measures values(?,?,?,false,'PCS','wrong','wrong','wrong')",UUID.randomUUID(),"movement-unit-test",UUID.randomUUID());
                var row = row(); row.setUnitOfMeasureCode("PCS");
                var missing = row(); missing.setUnitOfMeasureCode("MISSING");
                doReturn(new PageImpl<>(List.of(row,missing),PageRequest.of(0,500),2)).when(query).search(any(),any(),anyString());
                var service = exporter;
                ReflectionTestUtils.setField(service,"jdbcTemplate",new NamedParameterJdbcTemplate(source));
                try(var book = new XSSFWorkbook(new ByteArrayInputStream(service.exportMovements(StockMovementSearchRequest.empty(),MovementQuerySupport.sort(null),language)))) {
                    assertThat(book.getSheetAt(0).getRow(1).getCell(9).getStringCellValue()).isEqualTo(expected);
                    assertThat(book.getSheetAt(0).getRow(2).getCell(9).getStringCellValue()).isEqualTo("MISSING");
                }
            } finally {
                db.execute("drop all objects");
                com.enterprise.mm.common.TenantContext.clear(); com.enterprise.mm.common.CompanyContext.clear();
            }
        }
        private StockMovementResponse row() {
            var row = new StockMovementResponse(); row.setId(UUID.randomUUID());
            row.setMovementDate(LocalDate.of(2026,10,22)); row.setCode("=HYPERLINK(\"bad\")"); row.setMovementType(MovementType.RECEIPT);
            row.setCounterparty("Opening Stock Balance"); row.setSourceLocation("Opening Stock");
            row.setMaterialName("{\"uz\":\"Mis\",\"ru\":\"Медь\",\"en\":\"Copper\"}"); row.setQuantity(new BigDecimal("2.1234567"));
            row.setStatus("POSTED"); row.setBatchNumber("B-42"); row.setActorName("Warehouse manager");
            return row;
        }
    }

}
