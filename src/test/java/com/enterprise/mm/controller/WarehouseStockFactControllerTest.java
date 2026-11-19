package com.enterprise.mm.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.payload.ManualWarehouseDocumentResponse;
import com.enterprise.mm.service.WarehouseStockFactService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WarehouseStockFactControllerTest {
    WarehouseStockFactService service;
    MockMvc mvc;
    final UUID warehouseId = UUID.randomUUID(), materialId = UUID.randomUUID();
    @BeforeEach void setup() {
        service = mock(WarehouseStockFactService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WarehouseStockFactController(service,
            org.mockito.Mockito.mock(com.enterprise.mm.service.ManualReceiptAccountDraftService.class))).build();
    }
    MockMultipartFile request(String quantity) {
        var json = """
            {"warehouseId":"%s","movementDate":"%s","lines":[{"materialId":"%s","quantity":"%s","unitCost":"12"}]}
            """.formatted(warehouseId, LocalDate.now(), materialId, quantity);
        return new MockMultipartFile("request", "request.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }
    MockMultipartFile proof() { return new MockMultipartFile("files", "proof.pdf", "application/pdf", new byte[] {1}); }
    @Test void acceptsReceiptWithoutContractAndKeepsProofInCommand() throws Exception {
        when(service.record(any(), anyList(), eq("receipt-key"), eq(true))).thenReturn(
            new ManualWarehouseDocumentResponse(UUID.randomUUID(), "WMF-1", MovementType.RECEIPT, "POSTED", List.of(UUID.randomUUID()), 1));
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-receipt")
            .file(request("2.5")).file(proof()).header("Idempotency-Key", "receipt-key")).andExpect(status().isOk());
        verify(service).record(argThat(r -> r.warehouseId().equals(warehouseId) && r.lines().getFirst().quantity().toPlainString().equals("2.5")),
            argThat(files -> files.size() == 1 && files.getFirst().getOriginalFilename().equals("proof.pdf")), eq("receipt-key"), eq(true));
    }
    @Test void routesManualIssueToPhysicalFactCommand() throws Exception {
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-issue")
            .file(request("1")).file(proof()).header("Idempotency-Key", "issue-key")).andExpect(status().isOk());
        verify(service).record(any(), anyList(), eq("issue-key"), eq(false));
    }
    @Test void acceptsReceiptAndIssueWithoutProofFiles() throws Exception {
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-receipt")
            .file(request("1")).header("Idempotency-Key", "receipt-key")).andExpect(status().isOk());
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-issue")
            .file(request("1")).header("Idempotency-Key", "issue-key")).andExpect(status().isOk());
        verify(service).record(any(), isNull(), eq("receipt-key"), eq(true));
        verify(service).record(any(), isNull(), eq("issue-key"), eq(false));
    }
    @Test void rejectsMissingIdempotencyKey() throws Exception {
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-issue")
            .file(request("1")).file(proof())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void rejectsNonPositiveLineQuantitiesBeforeService() throws Exception {
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-receipt")
            .file(request("0")).file(proof()).header("Idempotency-Key", "key")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
