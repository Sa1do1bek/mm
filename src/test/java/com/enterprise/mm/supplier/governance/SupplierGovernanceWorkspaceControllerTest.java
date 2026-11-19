package com.enterprise.mm.supplier.governance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.supplier.governance.controller.SupplierGovernanceWorkspaceController;
import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.QueueResponse;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceFilter;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceWorkspaceService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SupplierGovernanceWorkspaceControllerTest {
    @Test
    void casesEndpointReturnsTheAppliedQueueContract() throws Exception {
        SupplierGovernanceWorkspaceService service = mock(SupplierGovernanceWorkspaceService.class);
        SupplierGovernanceFilter normalized = new SupplierGovernanceFilter(
            SupplierGovernanceQueueKey.EXPIRING_EVIDENCE, "pump", 45, null);
        when(service.queueForCompany(any(), any())).thenReturn(new QueueResponse(
            normalized.queue(), normalized, 0, List.of(), 0, 25, 0, LocalDateTime.now(), "COMPANY"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SupplierGovernanceWorkspaceController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

        mvc.perform(get("/api/workspaces/supplier-governance/cases")
                .param("queue", "EXPIRING_EVIDENCE")
                .param("search", "pump")
                .param("horizonDays", "45")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.queueKey").value("EXPIRING_EVIDENCE"))
            .andExpect(jsonPath("$.data.appliedFilters.horizonDays").value(45))
            .andExpect(jsonPath("$.data.dataScope").value("COMPANY"));
    }
}
