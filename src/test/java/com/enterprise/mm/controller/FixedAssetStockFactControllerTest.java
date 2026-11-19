package com.enterprise.mm.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.enterprise.mm.enums.PickingMethod;
import com.enterprise.mm.enums.StockAllocationMode;
import com.enterprise.mm.payload.FixedAssetStockFactRequest;
import com.enterprise.mm.service.FixedAssetStockFactService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FixedAssetStockFactControllerTest {
    private static final String ROOT = "/api/workspaces/warehouse-execution/manual/fixed-asset-stock";
    FixedAssetStockFactService service;
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(FixedAssetStockFactService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FixedAssetStockFactController(service)).build();
    }

    private MockMultipartFile request(String quantity) {
        String json = """
            {"movementDate":"%s","lines":[{"materialId":"%s","quantity":%s,"unitCost":100}]}
            """.formatted(LocalDate.now(), UUID.randomUUID(), quantity);
        return new MockMultipartFile("request", "request.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test void receiptAndIssueNeedNeitherWarehouseNorDocuments() throws Exception {
        for (String action : new String[]{"receipt", "issue"})
            mvc.perform(multipart(ROOT + "/" + action).file(request("2"))
                .header("Idempotency-Key", action)).andExpect(status().isOk());
        verify(service).record(any(FixedAssetStockFactRequest.class), isNull(), eq("receipt"), eq(true));
        verify(service).record(any(FixedAssetStockFactRequest.class), isNull(), eq("issue"), eq(false));
    }

    @Test void acceptsWholeQuantityWrittenWithTrailingDecimalZeros() throws Exception {
        mvc.perform(multipart(ROOT + "/receipt").file(request("2.00"))
            .header("Idempotency-Key", "whole-decimal")).andExpect(status().isOk());
        verify(service).record(any(), isNull(), eq("whole-decimal"), eq(true));
    }

    @Test void rejectsMissingRetryKeyAndInvalidQuantities() throws Exception {
        mvc.perform(multipart(ROOT + "/receipt").file(request("1"))).andExpect(status().isBadRequest());
        for (String quantity : new String[]{"0", "-1", "1.5", "1000000000000000"})
            mvc.perform(multipart(ROOT + "/receipt").file(request(quantity))
                .header("Idempotency-Key", "key")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void searchAcceptsCodeNameAndCombinedSearchWithoutWarehouse() throws Exception {
        mvc.perform(get(ROOT + "/materials").param("code", "6364").param("name", "Nasos")
            .param("search", "pump")).andExpect(status().isOk());
        verify(service).materials(eq("pump"), eq("6364"), eq("Nasos"), any());
    }

    @Test void optionsResolveWarehouseOnServer() throws Exception {
        UUID material = UUID.randomUUID();
        mvc.perform(get(ROOT + "/issue-options").param("materialId", material.toString())).andExpect(status().isOk());
        verify(service).issueOptions(material, PickingMethod.FIFO, 0, 25);
    }

    @Test void previewAndIssueAcceptManualAllocations() throws Exception {
        UUID material = UUID.randomUUID(), position = UUID.randomUUID();
        String json = """
            {"movementDate":"%s","lines":[{"materialId":"%s","quantity":2,
             "pickingMethod":"LIFO","allocationMode":"MANUAL",
             "allocations":[{"stockBalanceId":"%s","quantity":2}]}]}
            """.formatted(LocalDate.now(), material, position);
        mvc.perform(post(ROOT + "/issue-preview").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk());
        mvc.perform(multipart(ROOT + "/issue")
            .file(new MockMultipartFile("request", "request.json", "application/json", json.getBytes(StandardCharsets.UTF_8)))
            .header("Idempotency-Key", "manual")).andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(FixedAssetStockFactRequest.class);
        verify(service).previewIssue(request.capture());
        var line = request.getValue().inWarehouse(UUID.randomUUID(), false).lines().getFirst();
        assertThat(line.pickingMethod()).isEqualTo(PickingMethod.LIFO);
        assertThat(line.allocationMode()).isEqualTo(StockAllocationMode.MANUAL);
        assertThat(line.allocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.stockBalanceId()).isEqualTo(position);
            assertThat(allocation.quantity()).isEqualByComparingTo("2");
        });
        verify(service).record(eq(request.getValue()), isNull(), eq("manual"), eq(false));
    }

    @Test void validatesNestedAllocationsForBothPreviewAndIssue() throws Exception {
        for (String allocation : new String[]{"null", "{}",
            "{\"stockBalanceId\":\"" + UUID.randomUUID() + "\"}",
            "{\"stockBalanceId\":\"" + UUID.randomUUID() + "\",\"quantity\":0}",
            "{\"stockBalanceId\":\"" + UUID.randomUUID() + "\",\"quantity\":-1}"}) {
            String json = """
                {"movementDate":"%s","lines":[{"materialId":"%s","quantity":2,
                 "allocationMode":"MANUAL","allocations":[%s]}]}
                """.formatted(LocalDate.now(), UUID.randomUUID(), allocation);
            mvc.perform(post(ROOT + "/issue-preview").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
            mvc.perform(multipart(ROOT + "/issue")
                .file(new MockMultipartFile("request", "request.json", "application/json", json.getBytes(StandardCharsets.UTF_8)))
                .header("Idempotency-Key", "invalid")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void rejectsInvalidPagingBeforeSearching() throws Exception {
        // Standalone MVC propagates the application's exception without the global advice.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(get(ROOT + "/materials").param("size", "1000")))
            .hasRootCauseInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        verifyNoInteractions(service);
    }
}
