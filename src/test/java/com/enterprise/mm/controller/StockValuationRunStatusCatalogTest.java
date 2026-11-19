package com.enterprise.mm.controller;

import com.enterprise.mm.entity.EnumTranslation;
import com.enterprise.mm.payload.EnumOptionResponse;
import com.enterprise.mm.repository.EnumTranslationRepository;
import com.enterprise.mm.service.EnumCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StockValuationRunStatusCatalogTest {
    private static final String[] CODES = {"DRAFT", "QUEUED", "RUNNING", "COMPLETED",
        "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED"};



    @Test
    void exposesSevenRunStatusesWithoutChangingTheFiveJobStatuses() throws Exception {
        var repository = mock(EnumTranslationRepository.class);
        when(repository.findByEnumNameIn(anyCollection())).thenReturn(List.of());
        var service = new EnumCatalogService(repository);
        var mvc = MockMvcBuilders.standaloneSetup(new EnumCatalogController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
        mvc.perform(get("/api/inventory/enums/valuation-run-statuses"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].code", org.hamcrest.Matchers.contains(CODES)));
        assertThat(service.moduleEnums("inventory").get("valuation-run-statuses"))
            .extracting(EnumOptionResponse::code).containsExactly(CODES);
        assertThat(service.allEnums().get("inventory").get("valuation-run-statuses"))
            .extracting(EnumOptionResponse::code).containsExactly(CODES);
        assertThat(service.enumValues("inventory", "valuation-job-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("QUEUED", "RUNNING", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED");
    }


}
