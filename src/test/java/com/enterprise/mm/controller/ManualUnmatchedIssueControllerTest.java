package com.enterprise.mm.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.service.ManualUnmatchedIssueService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManualUnmatchedIssueControllerTest {
    private final ManualUnmatchedIssueService service = mock(ManualUnmatchedIssueService.class);
    private MockMvc mvc;
    private final UUID contextId = UUID.randomUUID(), materialId = UUID.randomUUID();

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ManualUnmatchedIssueController(service)).build();
    }

    private MockMultipartFile request(String recipient, String quantity) {
        var json = """
            {"entryContextWarehouseId":"%s","issueDate":"%s","issuedTo":"%s",
             "lines":[{"materialId":"%s","quantity":"%s"}]}
            """.formatted(contextId, LocalDate.now(), recipient, materialId, quantity);
        return new MockMultipartFile("request", "request.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test void acceptsCompletedHandoverWithoutPhysicalWarehouseOrStockPosition() throws Exception {
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-issue/unmatched")
            .file(request("Maintenance", "3"))
            .header("Idempotency-Key", "handover-1")).andExpect(status().isOk());
        verify(service).record(argThat(value -> value.entryContextWarehouseId().equals(contextId)
            && value.lines().getFirst().quantity().intValue() == 3), isNull(), eq("handover-1"));
    }

    @Test void rejectsMissingRecipientAndNonPositiveQuantity() throws Exception {
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-issue/unmatched")
            .file(request("", "3")).header("Idempotency-Key", "key-1")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/workspaces/warehouse-execution/manual/stock-issue/unmatched")
            .file(request("Maintenance", "0")).header("Idempotency-Key", "key-2")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
