package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ProductCodeSyncTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;

    @Test
    void soliqProductCodeSyncUpsertsReferencesAndMaterialUsesThem() throws Exception {
        MaterialReferences references = materialReferences("IKPU-TNVED");

        mockMvc.perform(post("/api/integrations/product-codes/ikpu/sync")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"records":[{"ikpuCode":"09999999001000000","name":"Test IKPU material","unitCode":"PCS","groupCode":"TEST","vatRate":"12"}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"dictionary\":\"IKPU\"")));

        mockMvc.perform(post("/api/integrations/product-codes/tnved/sync")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"records":[{"tnvedCode":"9801000000","name":"Test TN VED material","sectionCode":"XX","unitCode":"PCS","vatRate":"12"}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"dictionary\":\"TNVED\"")));

        mockMvc.perform(get("/api/ikpu-codes")
                .with(user("admin").roles("ADMIN"))
                .param("search", "09999999001000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Test IKPU material")));

        mockMvc.perform(get("/api/mxik")
                .with(user("admin").roles("ADMIN"))
                .param("searchParam", "09999999001000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Test IKPU material")));

        mockMvc.perform(get("/api/tnved-codes")
                .with(user("admin").roles("ADMIN"))
                .param("search", "9801000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Test TN VED material")));

        MaterialGroup group = materialGroup("MG-PRODUCT-CODE");
        UnitOfMeasure unit = unitOfMeasure("PCS-PRODUCT-CODE");

        String materialResponse = mockMvc.perform(post("/api/materials")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"MAT-IKPU-TNVED-TEST",
                      "name":"Material with product codes",
                      "status":"ACTIVE",
                      "materialGroupId":"%s",
                      "unitOfMeasureId":"%s",
                      "mxikCode":"09999999001000000",
                      "tnvedCode":"9801000000"
                    }
                    """.formatted(references.materialGroupId(), references.unitOfMeasureId())))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"mxikCode\":\"09999999001000000\"")))
            .andExpect(content().string(containsString("\"tnvedCode\":\"9801000000\"")))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode material = objectMapper.readTree(materialResponse).path("data");
        assertThat(material.path("mxikCode").asText()).isEqualTo("09999999001000000");
        assertThat(material.path("tnvedCodeId").asText()).isNotBlank();
    }

    private MaterialGroup materialGroup(String code) {
        return materialGroupRepository.findByTenantIdAndCodeAndIsDeletedFalse("default", code).orElseGet(() -> {
            MaterialGroup group = new MaterialGroup();
            group.setCode(code);
            group.setName(code);
            group.setStatus("ACTIVE");
            return materialGroupRepository.save(group);
        });
    }

    private UnitOfMeasure unitOfMeasure(String code) {
        return unitOfMeasureRepository.findFirstByCodeIgnoreCaseAndIsDeletedFalse(code).orElseGet(() -> {
            UnitOfMeasure unit = new UnitOfMeasure();
            unit.setCode(code);
            unit.setName(code);
            unit.setStatus("ACTIVE");
            unit.setMeasurementNameShort("pcs");
            unit.setMeasurementNameLong("Pieces");
            return unitOfMeasureRepository.save(unit);
        });
    }

    @Test
    void mxikSyncAliasUsesMxikDictionaryName() throws Exception {
        mockMvc.perform(post("/api/integrations/product-codes/mxik/sync")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"records":[{"mxikCode":"09999999002000000","name":"Alias MXIK material","unitCode":"PCS","groupCode":"TEST","vatRate":"12"}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"dictionary\":\"MXIK\"")));

        mockMvc.perform(get("/api/mxik")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Alias MXIK material")));

        mockMvc.perform(get("/api/mxik")
                .with(user("admin").roles("ADMIN"))
                .param("searchParam", "09999999002000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Alias MXIK material")));
    }

    @Test
    void productCodeCsvImportLoadsIkpuAndLinkedTnved() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "ikpu.csv",
            "text/csv",
            """
            mxikCode,nameRu,internationalCode,categoryUnitName
            03333333001000000,CSV IKPU Row,8501300000,PCS
            """.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/reports/import/ikpu-codes")
                .file(file)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"data\":1")));

        mockMvc.perform(get("/api/ikpu-codes")
                .with(user("admin").roles("ADMIN"))
                .param("search", "03333333001000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("CSV IKPU Row")));

        mockMvc.perform(get("/api/mxik")
                .with(user("admin").roles("ADMIN"))
                .param("searchParam", "03333333001000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("CSV IKPU Row")));

        mockMvc.perform(get("/api/tnved-codes")
                .with(user("admin").roles("ADMIN"))
                .param("search", "8501300000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("CSV IKPU Row")));
    }

    @Test
    void productCodeCsvImportMxikAliasLoadsMxikAndLinkedTnved() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "mxik.csv",
            "text/csv",
            """
            mxikCode,nameRu,internationalCode,categoryUnitName
            03333333002000000,CSV MXIK Alias Row,8501400000,PCS
            """.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/reports/import/mxik-codes")
                .file(file)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"data\":1")));

        mockMvc.perform(get("/api/mxik")
                .with(user("admin").roles("ADMIN"))
                .param("searchParam", "03333333002000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("CSV MXIK Alias Row")));
    }

    @Test
    void productCodeCsvImportAcceptsRussianTnvedHeaders() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "tnved.csv",
            "text/csv",
            """
            Код ТН ВЭД;Наименование
            9901000000;Русский заголовок ТН ВЭД
            """.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/reports/import/tnved-codes")
                .file(file)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"data\":1")));

        String response = mockMvc.perform(get("/api/tnved-codes")
                .with(user("admin").roles("ADMIN"))
                .param("search", "9901000000"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

        assertThat(response).contains("Русский заголовок ТН ВЭД");
    }

    @Test
    void productCodeCsvImportAcceptsLongOfficialTnvedNames() throws Exception {
        String longName = "Официальное длинное наименование продукции ТН ВЭД с детализацией по характеристикам, назначению и дополнительным признакам ".repeat(4);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "tnved-long.csv",
            "text/csv",
            ("""
            Код ТН ВЭД;Наименование
            9902000000;%s
            """.formatted(longName)).getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/reports/import/tnved-codes")
                .file(file)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"data\":1")));

        String response = mockMvc.perform(get("/api/tnved-codes")
                .with(user("admin").roles("ADMIN"))
                .param("search", "9902000000"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

        assertThat(response).contains(longName.substring(0, 120));
    }

    @Test
    void soliqProductCodeSyncLoadsAllLivePages() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ikpu", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean secondPage = query != null && query.contains("page=1");
            String body = secondPage
                ? """
                  {"items":[{"ikpuCode":"08888888003000000","name":"Paged IKPU 3","unitCode":"PCS"}],"totalPages":2}
                  """
                : """
                  {"items":[{"ikpuCode":"08888888001000000","name":"Paged IKPU 1","unitCode":"PCS"},{"ikpuCode":"08888888002000000","name":"Paged IKPU 2","unitCode":"PCS"}],"totalPages":2}
                  """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            mockMvc.perform(put("/api/settings/integrations/SOLIQ")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "systemCode":"SOLIQ",
                          "providerName":"Soliq Service",
                          "enabled":true,
                          "mockMode":false,
                          "baseUrl":"%s",
                          "authType":"NONE"
                        }
                        """.formatted(baseUrl)))
                .andExpect(status().isOk());

            mockMvc.perform(post("/api/integrations/product-codes/ikpu/sync")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sourcePath":"/ikpu","fullSync":true,"pageSize":2,"maxPages":10}
                        """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"mode\":\"LIVE\"")))
                .andExpect(content().string(containsString("\"received\":3")))
                .andExpect(content().string(containsString("\"pagesFetched\":2")))
                .andExpect(content().string(containsString("\"complete\":true")));
        } finally {
            resetSoliqToMock();
            server.stop(0);
        }
    }

    @Test
    void soliqProductCodeSyncContinuesAfterShortPagesWithoutTotals() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tnved", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean secondPage = query != null && query.contains("page=1");
            boolean thirdPage = query != null && query.contains("page=2");
            String body;
            if (thirdPage) {
                body = "{\"items\":[]}";
            } else if (secondPage) {
                body = """
                  {"items":[
                    {"tnvedCode":"9100000001","name":"Paged TNVED 6"},
                    {"tnvedCode":"9100000002","name":"Paged TNVED 7"}
                  ]}
                  """;
            } else {
                body = """
                  {"items":[
                    {"tnvedCode":"9000000001","name":"Paged TNVED 1"},
                    {"tnvedCode":"9000000002","name":"Paged TNVED 2"},
                    {"tnvedCode":"9000000003","name":"Paged TNVED 3"},
                    {"tnvedCode":"9000000004","name":"Paged TNVED 4"},
                    {"tnvedCode":"9000000005","name":"Paged TNVED 5"}
                  ]}
                  """;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            mockMvc.perform(put("/api/settings/integrations/SOLIQ")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "systemCode":"SOLIQ",
                          "providerName":"Soliq Service",
                          "enabled":true,
                          "mockMode":false,
                          "baseUrl":"%s",
                          "authType":"NONE"
                        }
                        """.formatted(baseUrl)))
                .andExpect(status().isOk());

            mockMvc.perform(post("/api/integrations/product-codes/tnved/sync")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sourcePath":"/tnved","fullSync":true,"pageSize":1000,"maxPages":10}
                        """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"mode\":\"LIVE\"")))
                .andExpect(content().string(containsString("\"received\":7")))
                .andExpect(content().string(containsString("\"pagesFetched\":3")))
                .andExpect(content().string(containsString("\"complete\":true")));
        } finally {
            resetSoliqToMock();
            server.stop(0);
        }
    }

    @Test
    void tnvedProductCodeSyncLoadsCustomsHscodePages() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hscode-list", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean secondPage = form.contains("iDisplayStart=2");
            String body = secondPage
                ? """
                  {"iTotalRecords":3,"iTotalDisplayRecords":3,"aaData":[
                    ["0101299000","Customs TN VED 3","кг","шт"]
                  ]}
                  """
                : """
                  {"iTotalRecords":3,"iTotalDisplayRecords":3,"aaData":[
                    ["0101210000","Customs TN VED 1","кг","шт"],
                    ["0101291000","Customs TN VED 2","кг","шт"]
                  ]}
                  """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String catalogUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hscode-list";
            mockMvc.perform(post("/api/integrations/product-codes/tnved/sync")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sourcePath":"%s","fullSync":true,"requireLive":true,"pageSize":2,"maxPages":5}
                        """.formatted(catalogUrl)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"mode\":\"LIVE\"")))
                .andExpect(content().string(containsString("\"sourceSystem\":\"CUSTOMS\"")))
                .andExpect(content().string(containsString("\"received\":3")))
                .andExpect(content().string(containsString("\"pagesFetched\":2")))
                .andExpect(content().string(containsString("\"complete\":true")));

            mockMvc.perform(get("/api/tnved-codes")
                    .with(user("admin").roles("ADMIN"))
                    .param("search", "0101299000"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Customs TN VED 3")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void soliqProductCodeSyncAcceptsLiveCatalogUrlAndMxikInternationalCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/catalog", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean secondPage = query != null && query.contains("page=1");
            String body = secondPage
                ? """
                  {"success":true,"code":0,"data":[]}
                  """
                : """
                  {"success":true,"code":0,"data":[{
                    "mxikCode":"07777777001000000",
                    "name":"Tasnif live style row",
                    "internationalCode":"8501101000",
                    "groupNameRu":"Электрические машины",
                    "classNameRu":"Двигатели",
                    "subPositionNameRu":"Двигатели мощностью не более 37,5 Вт",
                    "categoryUnitName":"PCS",
                    "isActive":"1"
                  }]}
                  """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String catalogUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/catalog";
            mockMvc.perform(post("/api/integrations/product-codes/ikpu/sync")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sourcePath":"%s","fullSync":true,"requireLive":true,"pageSize":1000,"maxPages":5}
                        """.formatted(catalogUrl)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"mode\":\"LIVE\"")))
                .andExpect(content().string(containsString("\"received\":1")))
                .andExpect(content().string(containsString("\"pagesFetched\":2")))
                .andExpect(content().string(containsString("\"complete\":true")));

            mockMvc.perform(get("/api/ikpu-codes")
                .with(user("admin").roles("ADMIN"))
                .param("search", "07777777001000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("07777777001000000")));

            mockMvc.perform(get("/api/tnved-codes")
                .with(user("admin").roles("ADMIN"))
                .param("search", "8501101000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("8501101000")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void soliqProductCodeSyncReadsTasnifPagedDataContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/elasticsearch/search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean secondPage = query != null && query.contains("page=1");
            String body = secondPage
                ? """
                  {"success":true,"code":0,"data":{"content":[
                    {"mxikCode":"06666666002000000","name":"Tasnif content IKPU 2","internationalCode":"8501200000","groupName":"Machines"}
                  ],"last":true,"totalElements":2,"totalPages":2,"number":1,"size":1}}
                  """
                : """
                  {"success":true,"code":0,"data":{"content":[
                    {"mxikCode":"06666666001000000","name":"Tasnif content IKPU 1","internationalCode":"8501101000","groupName":"Machines"}
                  ],"last":false,"totalElements":2,"totalPages":2,"number":0,"size":1}}
                  """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String catalogUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/elasticsearch/search?search=&lang=ru";
            mockMvc.perform(post("/api/integrations/product-codes/ikpu/sync")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sourcePath":"%s","fullSync":true,"requireLive":true,"pageSize":1,"maxPages":5}
                        """.formatted(catalogUrl)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"mode\":\"LIVE\"")))
                .andExpect(content().string(containsString("\"received\":2")))
                .andExpect(content().string(containsString("\"pagesFetched\":2")))
                .andExpect(content().string(containsString("\"complete\":true")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void soliqProductCodeSyncOverridesFixedPageAndSizeInSourcePath() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/elasticsearch/search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean secondPage = query != null && query.contains("page=1") && query.contains("size=1");
            String body = secondPage
                ? """
                  {"success":true,"code":0,"data":{"content":[
                    {"mxikCode":"04444444002000000","name":"Overridden page 2","internationalCode":"8501200000"}
                  ],"last":true,"totalElements":2,"totalPages":2,"number":1,"size":1}}
                  """
                : """
                  {"success":true,"code":0,"data":{"content":[
                    {"mxikCode":"04444444001000000","name":"Overridden page 1","internationalCode":"8501101000"}
                  ],"last":false,"totalElements":2,"totalPages":2,"number":0,"size":1}}
                  """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String catalogUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/elasticsearch/search?search=&lang=ru&page=0&size=5";
            mockMvc.perform(post("/api/integrations/product-codes/ikpu/sync")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sourcePath":"%s","fullSync":true,"requireLive":true,"pageSize":1,"maxPages":5}
                        """.formatted(catalogUrl)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"mode\":\"LIVE\"")))
                .andExpect(content().string(containsString("\"received\":2")))
                .andExpect(content().string(containsString("\"pagesFetched\":2")))
                .andExpect(content().string(containsString("\"complete\":true")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void soliqProductCodeSyncFailsWhenFullLivePaginationDoesNotAdvance() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/elasticsearch/search", exchange -> {
            String body = """
              {"success":true,"code":0,"data":{"content":[
                {"mxikCode":"05555555001000000","name":"Duplicated first page","internationalCode":"8501101000"}
              ],"last":false,"totalElements":2,"totalPages":2,"number":0,"size":1}}
              """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String catalogUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/elasticsearch/search?search=&lang=ru";
            mockMvc.perform(post("/api/integrations/product-codes/ikpu/sync")
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sourcePath":"%s","fullSync":true,"requireLive":true,"pageSize":1,"maxPages":5}
                        """.formatted(catalogUrl)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("full sync is incomplete")))
                .andExpect(content().string(containsString("duplicate rows")));
        } finally {
            server.stop(0);
        }
    }

    private MaterialReferences materialReferences(String suffix) {
        MaterialGroup group = new MaterialGroup();
        group.setCode("MG-" + suffix);
        group.setName("Material group " + suffix);
        group.setStatus("ACTIVE");
        group = materialGroupRepository.save(group);

        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode("UOM-" + suffix);
        unit.setName("Unit " + suffix);
        unit.setStatus("ACTIVE");
        unit.setMeasurementNameShort("pcs");
        unit.setMeasurementNameLong("Pieces");
        unit = unitOfMeasureRepository.save(unit);

        return new MaterialReferences(group.getId(), unit.getId());
    }

    private record MaterialReferences(UUID materialGroupId, UUID unitOfMeasureId) {
    }

    private void resetSoliqToMock() throws Exception {
        mockMvc.perform(put("/api/settings/integrations/SOLIQ")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "systemCode":"SOLIQ",
                      "providerName":"Soliq Service",
                      "enabled":true,
                      "mockMode":true,
                      "baseUrl":"",
                      "authType":"NONE"
                    }
                    """))
            .andExpect(status().isOk());
    }
}
