package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.controller.StockAccountResolutionController;
import com.enterprise.mm.payload.WarehouseStockFactRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManualReceiptAccountCompatibilityTest {
    @Test void manualReceiptOptionsUseTheSameCandidatesAsPosting() {
        var resolver = mock(StockAccountResolver.class);
        var accounts = mock(MaterialStockAccountResolutionService.class);
        UUID materialId = UUID.randomUUID();
        var one = new MaterialStockAccountResolutionService.Account(UUID.randomUUID(), "1010", "First");
        var two = new MaterialStockAccountResolutionService.Account(UUID.randomUUID(), "1020", "Second");
        var resolution = new MaterialStockAccountResolutionService.Resolution(materialId, 1L, true, "CHOICE_REQUIRED",
            null, null, null, List.of(one, two), null, "fingerprint", List.of());
        when(accounts.get(materialId)).thenReturn(resolution);
        var result = new StockAccountResolutionController(resolver, accounts).manualReceiptOptions(materialId).data();
        assertThat(result.configured()).isNull();
        assertThat(result.candidates()).extracting(StockAccountResolver.Resolution::accountId).containsExactly(one.id(), two.id());
    }

    @Test void frontendAccountIdMapsToTheCurrentPostingField() throws Exception {
        UUID materialId = UUID.randomUUID(), accountId = UUID.randomUUID();
        String json = "{\"materialId\":\"" + materialId + "\",\"quantity\":1,\"accountId\":\"" + accountId + "\"}";
        var line = new ObjectMapper().readValue(json, WarehouseStockFactRequest.Line.class);
        assertThat(line.stockAccountId()).isEqualTo(accountId);
    }
}
