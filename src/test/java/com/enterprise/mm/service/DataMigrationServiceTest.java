package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.DataMigrationRow;
import com.enterprise.mm.entity.DataMigrationRun;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.DataMigrationDtos.MigrationValidationResponse;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.repository.DataMigrationRowRepository;
import com.enterprise.mm.repository.DataMigrationRunRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class DataMigrationServiceTest {
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000009999");

    @Mock
    private DataMigrationRunRepository runRepository;
    @Mock
    private DataMigrationRowRepository rowRepository;
    @Mock
    private MaterialRepository materialRepository;
    @Mock
    private WarehouseRepository warehouseRepository;
    @Mock
    private StorageLocationRepository storageLocationRepository;
    @Mock
    private UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private DataMigrationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-migration-test");
        CompanyContext.setCompany(COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void templatesExposeDownloadMetadataAndGenerateReadableWorkbooks() throws Exception {
        assertThat(service.templateContentType()).isEqualTo(XLSX_TYPE);
        assertThat(service.templates())
            .extracting("code")
            .containsExactly("1c-stock-balances", "materials-master-data", "warehouse-inventory-count", "reference-mapping");

        for (String code : List.of("1c-stock-balances", "materials-master-data", "warehouse-inventory-count", "reference-mapping.xlsx")) {
            byte[] bytes = service.templateWorkbook(code);

            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
                assertThat(workbook.getSheetAt(0).getRow(0).getPhysicalNumberOfCells()).isPositive();
                assertThat(workbook.getSheet("Справка")).isNotNull();
            }
        }

        assertThatThrownBy(() -> service.templateWorkbook("unknown-template"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unknown migration template");
    }

    @Test
    void validateAndStageRejectsUnsupportedTemplatesAndInvalidFiles() throws Exception {
        assertThatThrownBy(() -> service.validateAndStage("materials-master-data", workbook("materials.xlsx", List.of("warehouseCode"), List.of(List.of("WH-1")))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Validation is available");

        assertThatThrownBy(() -> service.validateAndStage("1c-stock-balances", new MockMultipartFile("file", "empty.xlsx", XLSX_TYPE, new byte[0])))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Migration file is empty");

        assertThatThrownBy(() -> service.validateAndStage("1c-stock-balances", new MockMultipartFile("file", "balances.csv", "text/csv", "x".getBytes())))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only XLSX migration files are supported");

        assertThatThrownBy(() -> service.validateAndStage("1c-stock-balances", new MockMultipartFile("file", "broken.xlsx", XLSX_TYPE, "not xlsx".getBytes())))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid XLSX file for migration");
    }

    @Test
    void validateAndStageClassifiesOkWarningAndErrorRows() throws Exception {
        stubRunPersistence();
        stubKnownReferences();
        MockMultipartFile file = workbook(
            "balances.xlsx",
            List.of(
                "companyCode", "warehouseCode", "storageLocationCode", "materialCode", "materialName", "uomCode",
                "batchNumber", "serialNumber", "quantity", "unitCost", "totalValue", "currencyCode", "accountCode",
                "ikpuCode", "tnvedCode", "expirationDate", "balanceDate"
            ),
            List.of(
                List.of("TST", "WH-1", "SL-A", "MAT-OK", "Known material", "PCS", "B1", "S1", "10", "2.50", "25.00", "UZS", "1010", "01234501001000000", "7208390000", "2026-02-01", "2026-01-01"),
                List.of("TST", "WH-1", "SL-A", "MAT-WARN", "Unmapped material", "PCS", "B2", "", "4", "3.00", "12.04", "", "", "", "", "2025-12-31", "2026-01-01"),
                List.of("TST", "WH-1", "SL-A", "MAT-OK", "Known material", "PCS", "B1", "S1", "-1", "2.50", "25.00", "UZS", "1010", "01234501001000000", "7208390000", "2026-02-01", "2026-01-01")
            )
        );

        MigrationValidationResponse response = service.validateAndStage("1c-stock-balances.xlsx", file);

        assertThat(response.migrationType()).isEqualTo("1c-stock-balances");
        assertThat(response.status()).isEqualTo("VALIDATED_WITH_ERRORS");
        assertThat(response.totalRows()).isEqualTo(3);
        assertThat(response.validRows()).isEqualTo(1);
        assertThat(response.warningRows()).isEqualTo(1);
        assertThat(response.errorRows()).isEqualTo(1);
        assertThat(response.rows()).extracting("rowStatus").containsExactly("OK", "WARNING", "ERROR");
        assertThat(response.rows().get(0).sourceKey()).isEqualTo("WH-1|SL-A|MAT-OK|B1|S1");
        assertThat(response.rows().get(1).currencyCode()).isEqualTo("UZS");
        assertThat(response.rows().get(1).warnings())
            .anySatisfy(message -> assertThat(message).contains("Валюта не указана"))
            .anySatisfy(message -> assertThat(message).contains("Материал с кодом MAT-WARN не найден"));
        assertThat(response.rows().get(2).errors())
            .anySatisfy(message -> assertThat(message).contains("Количество не может быть отрицательным"))
            .anySatisfy(message -> assertThat(message).contains("Дубль строки"));
        verify(rowRepository).saveAll(any());
        verify(auditService).log(
            eq("DataMigrationRun"),
            eq(response.runId()),
            eq("VALIDATE_XLSX"),
            anyString(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void rowsReturnsTenantAndCompanyScopedValidationRows() {
        UUID runId = UUID.randomUUID();
        DataMigrationRow row = new DataMigrationRow();
        row.setRowNumber(2);
        row.setRowStatus("ERROR");
        row.setSourceKey("WH-1||MAT-1||");
        row.setWarehouseCode("WH-1");
        row.setMaterialCode("MAT-1");
        row.setUomCode("PCS");
        row.setQuantity(new BigDecimal("3.5"));
        row.setBalanceDate(LocalDate.of(2026, 1, 6));
        row.setErrorMessages("first error\nsecond error");
        row.setWarningMessages("first warning\n");
        when(rowRepository.findByTenantIdAndCompanyIdAndRunIdAndIsDeletedFalseOrderByRowNumber("tenant-migration-test", COMPANY_ID, runId))
            .thenReturn(List.of(row));

        var rows = service.rows(runId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).errors()).containsExactly("first error", "second error");
        assertThat(rows.get(0).warnings()).containsExactly("first warning");
        assertThat(rows.get(0).quantity()).isEqualByComparingTo("3.5");
    }

    private void stubRunPersistence() {
        when(runRepository.save(any(DataMigrationRun.class))).thenAnswer(invocation -> {
            DataMigrationRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID());
            }
            return run;
        });
        when(rowRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubKnownReferences() {
        UUID warehouseId = UUID.randomUUID();
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setCode("WH-1");
        warehouse.setName("Warehouse 1");
        StorageLocation location = new StorageLocation();
        location.setId(UUID.randomUUID());
        location.setCode("SL-A");
        location.setName("Slot A");
        location.setWarehouseId(warehouseId);
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode("MAT-OK");
        material.setName("Known material");
        Currency currency = new Currency();
        currency.setId(UUID.randomUUID());
        currency.setCode("UZS");
        currency.setIsoCode("UZS");

        when(warehouseRepository.findByTenantIdAndCodeAndIsDeletedFalse("tenant-migration-test", "WH-1")).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByTenantIdAndCodeAndIsDeletedFalse("tenant-migration-test", "SL-A")).thenReturn(Optional.of(location));
        when(unitOfMeasureRepository.existsByScopedLookup(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "PCS")).thenReturn(true);
        when(materialRepository.findByTenantIdAndCodeAndIsDeletedFalse("tenant-migration-test", "MAT-OK")).thenReturn(Optional.of(material));
        when(materialRepository.findByTenantIdAndCodeAndIsDeletedFalse("tenant-migration-test", "MAT-WARN")).thenReturn(Optional.empty());
        when(currencyRepository.findByTenantIdAndCodeAndIsDeletedFalse("tenant-migration-test", "UZS")).thenReturn(Optional.of(currency));
    }

    private MockMultipartFile workbook(String fileName, List<String> headers, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("data");
            var headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                List<String> values = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                    row.createCell(columnIndex).setCellValue(values.get(columnIndex));
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", fileName, XLSX_TYPE, out.toByteArray());
        }
    }
}
