package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.ImportService;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "tester", roles = "ADMIN")
class ImportServiceTest {
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired ImportService importService;
    @Autowired MaterialRepository materialRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;

    @Test
    void materialImportValidatesFileRowsAndCreatesTenantScopedMaterial() throws Exception {
        assertThatThrownBy(() -> importService.importMaterials(new MockMultipartFile(
            "file",
            "materials.txt",
            "text/plain",
            "not-xlsx".getBytes()
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only .xlsx");

        assertThatThrownBy(() -> importService.importMaterials(materialWorkbook("MAT-IMPORT-BAD", "Bad", "UNKNOWN_METHOD")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unknown valuation method");

        String previousTenant = TenantContext.currentTenant();
        try {
            TenantContext.setTenant("tenant-import-materials");
            int imported = importService.importMaterials(materialWorkbook("MAT-IMPORT-OK", "Imported material", String.valueOf("FIFO")));

            assertThat(imported).isEqualTo(1);
            Material material = materialRepository.findByTenantIdAndCodeAndIsDeletedFalse("tenant-import-materials", "MAT-IMPORT-OK").orElseThrow();
            assertThat(material.getTenantId()).isEqualTo("tenant-import-materials");
            assertThat(material.getValuationMethod()).isEqualTo(com.enterprise.mm.enums.ValuationMethod.FIFO);
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }

    @Test
    void materialImportUpdatesOnlyTheCurrentCompanyMaterial() throws Exception {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        String tenantId = "tenant-import-company-scope";
        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        try {
            TenantContext.setTenant(tenantId);
            CompanyContext.setCompany(companyB);
            Material companyBMaterial = material("MAT-COMPANY-SCOPED");
            companyBMaterial.setName("Company B material");
            companyBMaterial = materialRepository.save(companyBMaterial);

            CompanyContext.setCompany(companyA);
            int imported = importService.importMaterials(
                materialWorkbook("MAT-COMPANY-SCOPED", "Company A imported material", "FIFO")
            );

            assertThat(imported).isEqualTo(1);
            assertThat(materialRepository.findById(companyBMaterial.getId())).hasValueSatisfying(material -> {
                assertThat(material.getCompanyId()).isEqualTo(companyB);
                assertThat(material.getName()).contains("Company B material");
            });
            assertThat(materialRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyA))
                .singleElement()
                .satisfies(material -> {
                    assertThat(material.getCode()).isEqualTo("MAT-COMPANY-SCOPED");
                    assertThat(material.getName()).contains("Company A imported material");
                });
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    @Test
    void stockBalanceImportRejectsForeignTenantReferences() throws Exception {
        String previousTenant = TenantContext.currentTenant();
        try {
            TenantContext.setTenant("tenant-stock-import-a");
            Material materialA = material("MAT-STOCK-IMPORT-A");
            Warehouse warehouseA = warehouse("WH-STOCK-IMPORT-A");

            TenantContext.setTenant("tenant-stock-import-b");
            Warehouse warehouseB = warehouse("WH-STOCK-IMPORT-B");
            assertThatThrownBy(() -> importService.importStockBalances(stockWorkbook(materialA.getId(), warehouseB.getId(), "3", "30")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Direct balance overwrite is disabled");

            TenantContext.setTenant("tenant-stock-import-a");
            assertThatThrownBy(() -> importService.importStockBalances(stockWorkbook(materialA.getId(), warehouseA.getId(), "3", "30")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("/api/opening-stock");
            assertThat(stockBalanceRepository.findBalance("tenant-stock-import-a", materialA.getId(), warehouseA.getId(), true, null, true, null)).isEmpty();
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }

    @Test
    void stockBalanceImportCannotBypassOpeningBoundaryWithBusinessCodes() throws Exception {
        String previousTenant = TenantContext.currentTenant();
        try {
            TenantContext.setTenant("tenant-stock-import-codes");
            Material material = material("055");
            Warehouse warehouse = warehouse("WH-055");

            assertThatThrownBy(() -> importService.importStockBalances(stockWorkbook("055", "WH-055", "7", "70")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Direct balance overwrite is disabled");
            assertThat(stockBalanceRepository.findBalance("tenant-stock-import-codes", material.getId(), warehouse.getId(), true, null, true, null)).isEmpty();
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }

    private MockMultipartFile materialWorkbook(String code, String name, String valuationMethod) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("materials");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("code");
            header.createCell(1).setCellValue("name");
            header.createCell(2).setCellValue("valuationMethod");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(code);
            row.createCell(1).setCellValue(name);
            row.createCell(2).setCellValue(valuationMethod);
            workbook.write(out);
            return new MockMultipartFile("file", "materials.xlsx", XLSX_TYPE, out.toByteArray());
        }
    }

    private MockMultipartFile stockWorkbook(UUID materialId, UUID warehouseId, String quantity, String totalValue) throws Exception {
        return stockWorkbook(materialId.toString(), warehouseId.toString(), quantity, totalValue);
    }

    private MockMultipartFile stockWorkbook(String materialRef, String warehouseRef, String quantity, String totalValue) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("stock");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("materialId");
            header.createCell(1).setCellValue("warehouseId");
            header.createCell(2).setCellValue("quantity");
            header.createCell(3).setCellValue("totalValue");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(materialRef);
            row.createCell(1).setCellValue(warehouseRef);
            row.createCell(2).setCellValue(quantity);
            row.createCell(3).setCellValue(totalValue);
            workbook.write(out);
            return new MockMultipartFile("file", "stock.xlsx", XLSX_TYPE, out.toByteArray());
        }
    }

    private Material material(String code) {
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }
}
