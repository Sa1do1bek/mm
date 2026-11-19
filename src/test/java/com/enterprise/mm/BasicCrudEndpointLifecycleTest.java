package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BasicCrudEndpointLifecycleTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void simpleCrudEndpointSupportsLifecycle(Endpoint endpoint) throws Exception {
        String code = endpoint.code();
        String createdName = endpoint.label() + " created";
        String updatedName = endpoint.label() + " updated";

        String createResponse = mockMvc.perform(post(endpoint.path())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(endpoint.createBody().apply(code, createdName)))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.code").isNotEmpty())
            .andExpect(jsonPath("$.data.name").value(createdName))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(createResponse).path("data");
        String id = data.path("id").asText();
        String storedCode = data.path("code").asText();
        if (!endpoint.path().equals("/api/warehouses") && !endpoint.path().equals("/api/budget-items")) {
            assertThat(storedCode).isEqualTo(code);
        }

        mockMvc.perform(get(endpoint.path() + "/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.code").value(storedCode));

        mockMvc.perform(put(endpoint.path() + "/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(endpoint.updateBody().apply(code, updatedName)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.name").value(updatedName));

        mockMvc.perform(delete(endpoint.path() + "/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());

        mockMvc.perform(get(endpoint.path() + "/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isNotFound());

        mockMvc.perform(post(endpoint.path() + "/" + id + "/restore")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.deleted").value(false));

        mockMvc.perform(get(endpoint.path() + "/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void budgetItemCreateDefaultsReservedAmountWhenOmitted() throws Exception {
        String code = "BI-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        mockMvc.perform(post("/api/budget-items")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"%s","name":"Budget without reservation","status":"ACTIVE"}
                    """.formatted(code)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.code").isNotEmpty())
            .andExpect(jsonPath("$.data.reservedAmount").value(0));
    }

    static Stream<Endpoint> endpoints() {
        return Stream.of(
            Endpoint.simple("/api/material-groups", "MG"),
            Endpoint.simple("/api/manufacturers", "MFR"),
            Endpoint.simple("/api/uom", "UOM"),
            Endpoint.simple("/api/department-types", "DT"),
            Endpoint.simple("/api/classifier-types", "CT"),
            Endpoint.simple("/api/warehouses", "WH"),
            Endpoint.simple("/api/equipment", "EQ"),
            Endpoint.simple("/api/budget-items", "BI"),
            Endpoint.simple("/api/positions", "POS"),
            Endpoint.simple("/api/approval-workflows", "AWF"),
            Endpoint.simple("/api/integrations/events", "IE"),
            Endpoint.simple("/api/notifications", "NTF"),
            Endpoint.simple("/api/inventory-finance/accounting-reconciliations", "IAR"),
            Endpoint.simple("/api/inventory-finance/period-closes", "IPC"),
            Endpoint.withBody("/api/currencies", "CUR",
                (code, name) -> """
                    {"code":"%s","name":"%s","status":"ACTIVE","isoCode":"%s","minorUnit":2}
                    """.formatted(code.substring(0, 3), name, code.substring(0, 3)),
                (code, name) -> """
                    {"code":"%s","name":"%s","status":"INACTIVE","isoCode":"%s","minorUnit":2}
                    """.formatted(code.substring(0, 3), name, code.substring(0, 3))),
            Endpoint.withBody("/api/chart-of-accounts", "COA",
                (code, name) -> """
                    {"code":"%s","name":"%s","status":"ACTIVE","accountCode":"%s","accountType":"ASSET"}
                    """.formatted(code, name, code),
                (code, name) -> """
                    {"code":"%s","name":"%s","status":"INACTIVE","accountCode":"%s","accountType":"ASSET"}
                    """.formatted(code, name, code))
        );
    }

    record Endpoint(
        String path,
        String prefix,
        BiFunction<String, String, String> createBody,
        BiFunction<String, String, String> updateBody
    ) {
        static Endpoint simple(String path, String prefix) {
            return withBody(path, prefix, BasicCrudEndpointLifecycleTest::simpleCreateBody, BasicCrudEndpointLifecycleTest::simpleUpdateBody);
        }

        static Endpoint withBody(
            String path,
            String prefix,
            BiFunction<String, String, String> createBody,
            BiFunction<String, String, String> updateBody
        ) {
            return new Endpoint(path, prefix, createBody, updateBody);
        }

        String code() {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            if ("CUR".equals(prefix)) {
                return "XTS";
            }
            return prefix + "-" + suffix;
        }

        String label() {
            return prefix + " resource";
        }

        @Override
        public String toString() {
            return path;
        }
    }

    private static String simpleCreateBody(String code, String name) {
        return """
            {"code":"%s","name":"%s","status":"ACTIVE","description":"created by lifecycle test"}
            """.formatted(code, name);
    }

    private static String simpleUpdateBody(String code, String name) {
        return """
            {"code":"%s","name":"%s","status":"INACTIVE","description":"updated by lifecycle test"}
            """.formatted(code, name);
    }
}
