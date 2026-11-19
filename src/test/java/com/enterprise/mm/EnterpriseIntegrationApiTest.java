package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.integrations.systems.treasury.mock-mode=false")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class EnterpriseIntegrationApiTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void accountingApiQueuesIdempotentPostingForFi() throws Exception {
        String first = mockMvc.perform(post("/api/integrations/accounting/stock-receipts")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "fi-receipt-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "externalDocumentId":"GR-001",
                      "documentNumber":"GR-001",
                      "documentDate":"2026-06-06",
                      "amount":1250000.50,
                      "currencyCode":"UZS",
                      "supplierTin":"123456789",
                      "supplierName":"TOSHKENT SANOAT TA'MINOTI MChJ",
                      "warehouseCode":"MAIN",
                      "materialCode":"STEEL-2MM",
                      "quantity":10,
                      "unitCode":"PCS",
                      "payload":{"debitAccount":"2010","creditAccount":"6010"}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"eventType\":\"FI.STOCK_RECEIPT_POSTED\"")))
            .andExpect(content().string(containsString("\"aggregateType\":\"FI\"")))
            .andExpect(content().string(containsString("\"idempotencyKey\":\"fi-receipt-001\"")))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

        String second = mockMvc.perform(post("/api/integrations/accounting/stock-receipts")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "fi-receipt-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

        JsonNode firstData = objectMapper.readTree(first).path("data");
        JsonNode secondData = objectMapper.readTree(second).path("data");
        assertThat(secondData.path("id").asText()).isEqualTo(firstData.path("id").asText());
        assertThat(firstData.path("payload").asText()).contains("/api/v1/basic/finmodel/integrations/entries");
    }

    @Test
    void enterpriseIntegrationSettingsAreLiveByDefault() throws Exception {
        mockMvc.perform(get("/api/settings/integrations/TREASURY")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"mockMode\":false")))
            .andExpect(content().string(containsString("http://localhost:8082")))
            .andExpect(content().string(containsString("/api/v1/basic/treasury/payments/requests")));

        mockMvc.perform(get("/api/settings/integrations/HR")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"mockMode\":false")))
            .andExpect(content().string(containsString("http://localhost:8091")))
            .andExpect(content().string(containsString("/api/data-exchange/jobs")));

        mockMvc.perform(get("/api/settings/integrations/WMS")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"mockMode\":false")))
            .andExpect(content().string(containsString("http://localhost:8090")))
            .andExpect(content().string(containsString("/api/v1/integrations/wms/transfers/confirm")));

        mockMvc.perform(get("/api/settings/integrations/ONE_C")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"mockMode\":false")))
            .andExpect(content().string(containsString("http://localhost:8082")))
            .andExpect(content().string(containsString("/api/v1/basic/finmodel/integrations/entries")));
    }

    @Test
    void missingLiveConnectorApisQueueTypedEvents() throws Exception {
        mockMvc.perform(post("/api/integrations/wms/stock-balances")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "wms-stock-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalDocumentId\":\"WMS-001\",\"warehouseCode\":\"MAIN\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"eventType\":\"WMS.STOCK_BALANCE_SYNC\"")))
            .andExpect(content().string(containsString("/api/v1/integrations/wms/transfers/confirm")));

        mockMvc.perform(post("/api/integrations/scada/consumption-signals")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "scada-consume-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalDocumentId\":\"SCADA-001\",\"materialCode\":\"GAS\",\"quantity\":100}"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"eventType\":\"SCADA.CONSUMPTION_SIGNAL\"")))
            .andExpect(content().string(containsString("/api/v1/integrations/scada/events/import")));

        mockMvc.perform(post("/api/integrations/ettn/transport-documents")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "ettn-doc-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalDocumentId\":\"ETTN-001\",\"documentNumber\":\"ETTN-001\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"eventType\":\"ETTN.TRANSPORT_DOCUMENT\"")))
            .andExpect(content().string(containsString("/api/documents/outgoing")));

        mockMvc.perform(post("/api/integrations/exchange/rfq-published")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "exchange-rfq-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalDocumentId\":\"RFQ-001\",\"documentNumber\":\"RFQ-001\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"eventType\":\"EXCHANGE.RFQ_PUBLISHED\"")))
            .andExpect(content().string(containsString("/api/v1/basic/treasury/tenders")));

        mockMvc.perform(post("/api/integrations/one-c/accounting-entries")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "one-c-entry-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalDocumentId\":\"1C-001\",\"documentNumber\":\"1C-001\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"eventType\":\"ONE_C.ACCOUNTING_ENTRY\"")))
            .andExpect(content().string(containsString("/api/v1/basic/finmodel/integrations/entries")));
    }
}
