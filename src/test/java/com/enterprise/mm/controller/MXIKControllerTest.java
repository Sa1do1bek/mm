package com.enterprise.mm.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.payload.MXIKSearchResponse;
import com.enterprise.mm.payload.mxik.MXIKResponse;
import com.enterprise.mm.payload.mxikImport.MXIKNameCountProjection;
import com.enterprise.mm.service.MXIKProductImportService;
import com.enterprise.mm.service.MXIKService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MXIKControllerTest {

    @Test
    void listUsesCountlessSlicePagination() throws Exception {
        MXIKService mxikService = mock(MXIKService.class);
        MXIKProductImportService importService = mock(MXIKProductImportService.class);
        when(mxikService.searchMXIK(any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new SliceImpl<>(
                List.of(mxik("0001", "Pump"), mxik("0002", "Valve")),
                PageRequest.of(0, 2),
                true
            ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MXIKController(mxikService, importService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

        mockMvc.perform(get("/api/mxik")
                .param("page", "0")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(2))
            .andExpect(jsonPath("$.data.content[0].kod").value("0001"))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void catalogGroupsUsesRealPagination() throws Exception {
        MXIKService mxikService = mock(MXIKService.class);
        MXIKProductImportService importService = mock(MXIKProductImportService.class);
        when(importService.listGroupCounts(any(Pageable.class)))
            .thenReturn(new PageImpl<>(
                List.of(group("Boilers", 17), group("Pumps", 41)),
                PageRequest.of(1, 2),
                7
            ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MXIKController(mxikService, importService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

        mockMvc.perform(get("/api/mxik/catalog/groups")
                .param("page", "1")
                .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(2))
            .andExpect(jsonPath("$.data.content[0].name").value("Boilers"))
            .andExpect(jsonPath("$.data.content[0].count").value(17))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(7))
            .andExpect(jsonPath("$.data.totalPages").value(4));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(importService).listGroupCounts(pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void getMXIKByCodeReturnsOk() throws Exception {
        MXIKService mxikService = mock(MXIKService.class);
        MXIKProductImportService importService = mock(MXIKProductImportService.class);

        when(mxikService.getMXIKByCode("123456")).thenReturn(mxik("123456", "Test MXIK"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MXIKController(mxikService, importService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

        mockMvc.perform(get("/api/mxik/code/{code}", "123456"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kod").value("123456"))
            .andExpect(jsonPath("$.data.name").value("Test MXIK"))
            .andExpect(jsonPath("$.data.type").value("GOODS"));
        
        verify(mxikService).getMXIKByCode("123456");
    }

    @Test
    void mxikSearchNameReturnsPage() throws Exception {
        MXIKService mxikService = mock(MXIKService.class);
        MXIKProductImportService importService = mock(MXIKProductImportService.class);

        MXIKSearchResponse searchResponse = new MXIKSearchResponse(
                1L, "12345", "Search Name", List.of()
        );
        when(importService.mxikSearchName("Search Name", 0, 10))
            .thenReturn(new PageImpl<>(
                List.of(searchResponse),
                PageRequest.of(0, 10),
                1
            ));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MXIKController(mxikService, importService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

        mockMvc.perform(get("/api/mxik/catelog/{name}", "Search Name")
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].name").value("Search Name"))
            .andExpect(jsonPath("$.data.content[0].kod").value("12345"));
    }

    private MXIKNameCountProjection group(String name, long count) {
        return new MXIKNameCountProjection() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    private MXIKResponse mxik(String code, String name) {
        MXIKResponse response = new MXIKResponse();
        response.setKod(code);
        response.setName(name);
        response.setType("GOODS");
        return response;
    }
}
