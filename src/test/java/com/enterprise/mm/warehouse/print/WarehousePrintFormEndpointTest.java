package com.enterprise.mm.warehouse.print;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehousePrintFormEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Print endpoint requires authentication and appropriate warehouse/manager role")
    void printEndpointRequiresAuthAndRole() throws Exception {
        UUID docId = UUID.randomUUID();
        mvc.perform(get("/api/warehouse-documents/ISSUE_REQUEST/" + docId + "/print"))
            .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/warehouse-documents/ISSUE_REQUEST/" + docId + "/print")
            .with(user("external").roles("ANONYMOUS_GUEST")))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/warehouse-documents/ISSUE_REQUEST/" + docId + "/print")
            .with(user("keeper").roles("WAREHOUSE_KEEPER")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Print M-11 HTML generates print preview with standard layout and styling")
    void printHtmlPreviewReturnsStyledHtml() throws Exception {
        UUID docId = UUID.randomUUID();
        mvc.perform(get("/api/warehouse-documents/ISSUE_REQUEST/" + docId + "/print")
            .param("format", "html")
            .param("lang", "ru")
            .with(user("manager").roles("MM_MANAGER")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(Matchers.containsString("М-11")))
            .andExpect(content().string(Matchers.containsString("AO &quot;Navoiyazot&quot;")))
            .andExpect(content().string(Matchers.containsString("@media print")))
            .andExpect(content().string(Matchers.containsString("window.print()")));
    }

    @Test
    @DisplayName("Print M-11 Uzbek HTML renders localized titles")
    void printHtmlPreviewUzbekLocalization() throws Exception {
        UUID docId = UUID.randomUUID();
        mvc.perform(get("/api/warehouse-documents/ISSUE_REQUEST/" + docId + "/print")
            .param("format", "html")
            .param("lang", "uz")
            .with(user("operator").roles("WAREHOUSE_OPERATOR")))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("Talabnoma-yukxati")));
    }

    @Test
    @DisplayName("Print M-11 Excel produces valid A4-formatted workbook")
    void printExcelGeneratesValidSpreadsheet() throws Exception {
        UUID docId = UUID.randomUUID();
        byte[] bytes = mvc.perform(get("/api/warehouse-documents/TRANSFER/" + docId + "/print")
            .param("format", "xlsx")
            .param("lang", "ru")
            .with(user("accountant").roles("ACCOUNTANT")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition", Matchers.containsString(".xlsx")))
            .andReturn().getResponse().getContentAsByteArray();

        assertThat(bytes).isNotEmpty();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isGreaterThanOrEqualTo(1);
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPrintSetup().getPaperSize()).isEqualTo(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
        }
    }

    @Test
    @DisplayName("Print data JSON returns structured metadata with amount in words")
    void printDataJsonReturnsCompleteMetadata() throws Exception {
        UUID docId = UUID.randomUUID();
        mvc.perform(get("/api/warehouse-documents/PURCHASE_ORDER/" + docId + "/print")
            .param("format", "json")
            .param("lang", "ru")
            .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.formType").exists())
            .andExpect(jsonPath("$.data.organizationName").value("AO \"Navoiyazot\""))
            .andExpect(jsonPath("$.data.totalAmountInWords").isString())
            .andExpect(jsonPath("$.data.lines").isArray());
    }

    @Test
    @DisplayName("Evidence bundle returns tamper-proof SHA-256 fingerprint")
    void evidenceBundleReturnsTamperProofHash() throws Exception {
        UUID docId = UUID.randomUUID();
        mvc.perform(get("/api/warehouse-documents/TRANSFER/" + docId + "/evidence")
            .with(user("auditor").roles("AUDITOR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documentId").value(docId.toString()))
            .andExpect(jsonPath("$.data.documentType").value("TRANSFER"))
            .andExpect(jsonPath("$.data.status").value("VERIFIED"))
            .andExpect(jsonPath("$.data.tamperProofHash").isNotEmpty())
            .andExpect(jsonPath("$.data.isIntegrityVerified").value(true));
    }

    @Test
    @DisplayName("Digital signing records signature with PINFL and returns immutable evidence")
    void signDocumentEvidenceRecordsSignature() throws Exception {
        UUID docId = UUID.randomUUID();
        String jsonPayload = """
            {
                "signatureType": "EDS_GOV_UZ",
                "pinfl": "30101901230045",
                "comment": "Приемка согласована и утверждена без замечаний"
            }
            """;

        mvc.perform(post("/api/warehouse-documents/TRANSFER/" + docId + "/evidence/sign")
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonPayload)
            .with(user("keeper1").roles("WAREHOUSE_KEEPER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documentId").value(docId.toString()))
            .andExpect(jsonPath("$.data.signatures").isArray())
            .andExpect(jsonPath("$.data.signatures[0].signerUsername").value("keeper1"))
            .andExpect(jsonPath("$.data.signatures[0].pinfl").value("30101901230045"))
            .andExpect(jsonPath("$.data.signatures[0].signatureType").value("EDS_GOV_UZ"))
            .andExpect(jsonPath("$.data.signatures[0].signatureHash").isNotEmpty())
            .andExpect(jsonPath("$.data.signatures[0].comment").value("Приемка согласована и утверждена без замечаний"));

        // Verify that subsequent fetch of evidence bundle preserves the signed record
        mvc.perform(get("/api/warehouse-documents/TRANSFER/" + docId + "/evidence")
            .with(user("auditor").roles("AUDITOR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.signatures[0].pinfl").value("30101901230045"));
    }
}
