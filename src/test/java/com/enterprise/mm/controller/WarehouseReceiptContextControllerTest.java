package com.enterprise.mm.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.enterprise.mm.service.WarehouseReceiptAiAdviceService;
import com.enterprise.mm.service.WarehouseReceiptContextService;
import com.enterprise.mm.payload.WarehouseReceiptContextDtos.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WarehouseReceiptContextControllerTest {
    WarehouseReceiptContextService contexts = mock(WarehouseReceiptContextService.class);
    WarehouseReceiptAiAdviceService ai = mock(WarehouseReceiptAiAdviceService.class);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new WarehouseReceiptContextController(contexts, ai)).build();
    UUID contract = UUID.randomUUID(), warehouse = UUID.randomUUID();
    String path() { return "/api/workspaces/warehouse-execution/manual/contracts/" + contract; }

    @Test void openingContextNeverCallsAi() throws Exception {
        mvc.perform(get(path() + "/receipt-context-v2").param("warehouseId", warehouse.toString()))
            .andExpect(status().isOk());
        verify(contexts).get(warehouse, contract, 0, 25);
        verifyNoInteractions(ai);
    }

    @Test void aiRequiresExplicitPostAndValidWarehouseAndPageSize() throws Exception {
        mvc.perform(post(path() + "/receipt-ai-advice").contentType(MediaType.APPLICATION_JSON)
            .content("{\"warehouseId\":\"" + warehouse + "\",\"size\":26}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path() + "/receipt-ai-advice").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(ai, contexts);
    }

    @Test void exposesPartialAdviceAndChoiceFieldsInsideExistingApiResponse() throws Exception {
        UUID spec = UUID.randomUUID(), material = UUID.randomUUID();
        when(ai.advise(eq(contract), any())).thenReturn(new AiAdvice("READY", "Optional advice", null,
            List.of(new AiSuggestion(spec, material, false, 0.8, "Choose material", "MATERIAL_CHOICE_REQUIRED", true)),
            false, List.of("PARTIAL_ADVICE"), null));
        mvc.perform(post(path() + "/receipt-ai-advice").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .content("{\"warehouseId\":\"" + warehouse + "\",\"page\":0,\"size\":25,\"language\":\"uz\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.data.complete").value(false))
            .andExpect(jsonPath("$.data.warnings[0]").value("PARTIAL_ADVICE"))
            .andExpect(jsonPath("$.data.suggestions[0].requiresMaterialChoice").value(true))
            .andExpect(jsonPath("$.data.suggestions[0].reasonCode").value("MATERIAL_CHOICE_REQUIRED"));
    }
}
