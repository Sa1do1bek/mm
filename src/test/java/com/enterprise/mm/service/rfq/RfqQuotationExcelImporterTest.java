package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Position;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class RfqQuotationExcelImporterTest {
    private static final String TENANT = "tenant-import";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000007702");
    private static final UUID RFQ_ID = UUID.fromString("00000000-0000-0000-0000-000000007703");
    private static final UUID ITEM_ONE = UUID.fromString("00000000-0000-0000-0000-000000007704");
    private static final UUID ITEM_TWO = UUID.fromString("00000000-0000-0000-0000-000000007705");

    @Mock private RFQRepository rfqRepository;
    @Mock private RFQItemRepository itemRepository;
    @Mock private RfqPublishedVersionRepository versionRepository;

    private RfqQuotationExcelImporter importer;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        importer = new RfqQuotationExcelImporter(rfqRepository, itemRepository, versionRepository);
        RFQ rfq = new RFQ();
        rfq.setId(RFQ_ID);
        rfq.setTenantId(TENANT);
        rfq.setCompanyId(COMPANY_ID);
        rfq.setStatus("PUBLISHED");
        rfq.setDueDate(LocalDate.now().plusDays(10));
        rfq.setActivePublishedVersionId(UUID.randomUUID());
        lenient().when(rfqRepository.findByIdAndIsDeletedFalse(RFQ_ID)).thenReturn(Optional.of(rfq));
        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setId(rfq.getActivePublishedVersionId());
        lenient().when(versionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TENANT, COMPANY_ID, rfq.getActivePublishedVersionId()
        )).thenReturn(Optional.of(version));
        lenient().when(itemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TENANT, RFQ_ID))
            .thenReturn(List.of(item(ITEM_ONE), item(ITEM_TWO)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void validGeneratedTemplateSupportsFormulaCellsWithoutPersistingAnything() throws Exception {
        MockMultipartFile file = workbook(workbook -> {
            var sheet = workbook.getSheet("Quotation");
            sheet.getRow(6).getCell(6).setCellFormula("100+25");
            sheet.getRow(6).getCell(7).setCellValue(10);
            sheet.getRow(6).getCell(8).setCellValue(7);
            sheet.getRow(7).getCell(10).setCellValue(true);
            sheet.getRow(7).getCell(11).setCellValue("Not available");
        });

        var preview = importer.preview(RFQ_ID, file);

        assertThat(preview.valid()).isTrue();
        assertThat(preview.issues()).isEmpty();
        assertThat(preview.lines()).hasSize(2);
        assertThat(preview.lines().getFirst().unitPrice()).isEqualByComparingTo("125");
    }

    @Test
    void duplicateUnknownAndMissingIdsAreBlockingIssues() throws Exception {
        MockMultipartFile duplicate = workbook(workbook -> workbook.getSheet("Quotation")
            .getRow(7).getCell(0).setCellValue(ITEM_ONE.toString()));
        var duplicatePreview = importer.preview(RFQ_ID, duplicate);
        assertThat(duplicatePreview.valid()).isFalse();
        assertThat(duplicatePreview.issues()).extracting(issue -> issue.code())
            .contains("DUPLICATE_RFQ_ITEM", "MISSING_RFQ_ITEM");

        MockMultipartFile unknown = workbook(workbook -> workbook.getSheet("Quotation")
            .getRow(6).getCell(0).setCellValue(UUID.randomUUID().toString()));
        var unknownPreview = importer.preview(RFQ_ID, unknown);
        assertThat(unknownPreview.issues()).extracting(issue -> issue.code())
            .contains("UNKNOWN_RFQ_ITEM", "MISSING_RFQ_ITEM");
    }

    @Test
    void workbookForAnotherRfqFailsWithoutADataPreview() throws Exception {
        MockMultipartFile file = workbook(workbook -> workbook.getSheet("_RFQ_META")
            .getRow(0).getCell(1).setCellValue(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> importer.preview(RFQ_ID, file))
            .isInstanceOf(BusinessException.class)
            .hasMessage("RFQ quotation workbook belongs to another RFQ or version");
    }

    private MockMultipartFile workbook(WorkbookChange change) throws Exception {
        RfqPackageSnapshot snapshot = RfqPackageParityTest.snapshot(List.of(
            new Position(ITEM_ONE, "MAT-1", "Material 1", "Spec 1", "EA", BigDecimal.TEN, null),
            new Position(ITEM_TWO, "MAT-2", "Material 2", "Spec 2", "EA", BigDecimal.ONE, null)
        ));
        snapshot = new RfqPackageSnapshot(
            RFQ_ID,
            snapshot.rfqCode(),
            snapshot.title(),
            snapshot.description(),
            snapshot.language(),
            snapshot.dueDate(),
            snapshot.buyer(),
            snapshot.responseInstructions(),
            snapshot.positions(),
            snapshot.sources(),
            snapshot.suppliers(),
            snapshot.documents()
        );
        byte[] rendered = new RfqExcelRenderer().render(snapshot);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            change.apply(workbook);
            workbook.write(output);
            return new MockMultipartFile(
                "file",
                "quotation.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray()
            );
        }
    }

    private RFQItem item(UUID id) {
        RFQItem item = new RFQItem();
        item.setId(id);
        item.setTenantId(TENANT);
        item.setCompanyId(COMPANY_ID);
        item.setRfqId(RFQ_ID);
        return item;
    }

    @FunctionalInterface
    private interface WorkbookChange {
        void apply(XSSFWorkbook workbook);
    }
}
