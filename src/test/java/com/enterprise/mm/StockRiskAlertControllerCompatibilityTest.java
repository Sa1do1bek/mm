package com.enterprise.mm;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.enterprise.mm.controller.StockRiskAlertController;
import com.enterprise.mm.service.StockRiskAlertService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StockRiskAlertControllerCompatibilityTest {
    @Test
    void originalRouteReturnsArrayAndPageRouteReturnsMetadataWithSameScope() throws Exception {
        var service = mock(StockRiskAlertService.class);
        var company = UUID.randomUUID();
        when(service.search(isNull(), eq(false), isNull(), eq(company), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 3), 7));
        var mvc = MockMvcBuilders.standaloneSetup(new StockRiskAlertController(service))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules())).build();
        mvc.perform(get("/api/inventory/stock-risk-alerts").param("orgId", company.toString()).param("page", "2").param("size", "3"))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        mvc.perform(get("/api/inventory/stock-risk-alerts/page").param("orgId", company.toString()).param("page", "2").param("size", "3"))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(7)).andExpect(jsonPath("$.data.page").value(2));
        verify(service, times(2)).search(isNull(), eq(false), isNull(), eq(company), isNull(), isNull(), isNull(), eq(PageRequest.of(2, 3)));
    }
}
