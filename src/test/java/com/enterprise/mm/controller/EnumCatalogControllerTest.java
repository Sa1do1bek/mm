package com.enterprise.mm.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.payload.EnumOptionResponse;
import com.enterprise.mm.service.EnumCatalogService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EnumCatalogControllerTest {

    @Test
    void returnsCombinedCatalogInApiResponseWithCacheHeader() throws Exception {
        EnumCatalogService service = org.mockito.Mockito.mock(EnumCatalogService.class);
        when(service.allEnums()).thenReturn(Map.of(
            "purchase-orders",
            Map.of("statuses", List.of(option("DRAFT", 1, "default")))
        ));
        MockMvc mockMvc = enumMockMvc(service);

        mockMvc.perform(get("/api/enums").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CacheControl.maxAge(java.time.Duration.ofHours(1)).getHeaderValue()))
            .andExpect(jsonPath("$.data.purchase-orders.statuses[0].code").value("DRAFT"))
            .andExpect(jsonPath("$.data.purchase-orders.statuses[0].labels.ru").value("DRAFT"))
            .andExpect(jsonPath("$.data.purchase-orders.statuses[0].sortOrder").value(1))
            .andExpect(jsonPath("$.data.purchase-orders.statuses[0].active").value(true))
            .andExpect(jsonPath("$.data.purchase-orders.statuses[0].meta.color").value("default"))
            .andExpect(jsonPath("$.timestamp").exists());
    }


    @Test
    void returnsModuleBulkEnumsInApiResponse() throws Exception {
        EnumCatalogService service = org.mockito.Mockito.mock(EnumCatalogService.class);
        when(service.moduleEnums("suppliers"))
            .thenReturn(Map.of("statuses", List.of(option("ACTIVE", 1, "green"))));
        MockMvc mockMvc = enumMockMvc(service);

        mockMvc.perform(get("/api/suppliers/enums").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.statuses[0].code").value("ACTIVE"))
            .andExpect(jsonPath("$.data.statuses[0].meta.color").value("green"));
    }

    @Test
    void returnsSingleModuleEnumInApiResponse() throws Exception {
        EnumCatalogService service = org.mockito.Mockito.mock(EnumCatalogService.class);
        when(service.enumValues("stock-movements", "movement-types"))
            .thenReturn(List.of(option("RECEIPT", 1, "green")));
        MockMvc mockMvc = enumMockMvc(service);

        mockMvc.perform(get("/api/stock-movements/enums/movement-types").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].code").value("RECEIPT"))
            .andExpect(jsonPath("$.data[0].labels.en").value("RECEIPT"))
            .andExpect(jsonPath("$.data[0].meta.color").value("green"));
    }

    @Test
    void enumBulkAndSingleEndpointsArePublicReadApis() throws Exception {
        String security = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/security/SecurityConfig.java"
        ));

        assertThat(security).contains(
            ".requestMatchers(HttpMethod.GET, \"/api/enums\", \"/api/*/enums\", \"/api/*/enums/*\").permitAll()"
        );
    }

    @Test
    void returnsNotFoundForUnknownEnumSource() throws Exception {
        EnumCatalogService service = org.mockito.Mockito.mock(EnumCatalogService.class);
        when(service.enumValues("materials", "unknown")).thenThrow(new IllegalArgumentException("Unknown enum source"));
        MockMvc mockMvc = enumMockMvc(service);

        mockMvc.perform(get("/api/materials/enums/unknown").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }

    private MockMvc enumMockMvc(EnumCatalogService service) {
        return MockMvcBuilders.standaloneSetup(new EnumCatalogController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
    }

    private EnumOptionResponse option(String code, int sortOrder, String color) {
        return new EnumOptionResponse(
            code,
            Map.of("ru", code, "uz", code, "en", code),
            sortOrder,
            true,
            Map.of("color", color)
        );
    }
}
