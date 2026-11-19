package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.service.HttpMmAiClient;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.payload.MaterialDedupDtos.MaterialDedupResult;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchAiRequest;
import com.enterprise.mm.payload.RfqComparisonDtos.RfqComparisonResult;
import com.enterprise.mm.payload.RfqComparisonDtos.SupplierComparison;
import com.enterprise.mm.payload.RfqComparisonDtos.ItemOffer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.web.client.RestTemplateBuilder;

class HttpMmAiClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContext.clear();
        CompanyContext.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void recommendMxikSendsOnlyMaterialIdAndRequiredScopedHeaders() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> tenantHeader = new AtomicReference<>();
        AtomicReference<String> companyHeader = new AtomicReference<>();
        AtomicReference<String> languageHeader = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/mxik-recommendation/recommend", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            companyHeader.set(exchange.getRequestHeaders().getFirst("X-Company-Id"));
            languageHeader.set(exchange.getRequestHeaders().getFirst("Accept-Language"));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] body = ("{\"materialId\":\"" + materialId + "\",\"status\":\"MANUAL_REVIEW_REQUIRED\",\"reason\":\"Review\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        HttpMmAiClient client = new HttpMmAiClient(new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
            Duration.ofSeconds(1), Duration.ofSeconds(2));

        JsonNode response = client.recommendMxik(materialId, "uz");

        assertThat(requestBody.get().size()).isEqualTo(1);
        assertThat(requestBody.get().path("materialId").asText()).isEqualTo(materialId.toString());
        assertThat(tenantHeader.get()).isEqualTo("tenant-a");
        assertThat(companyHeader.get()).isEqualTo(companyId.toString());
        assertThat(languageHeader.get()).isEqualTo("uz");
        assertThat(apiKeyHeader.get()).isEqualTo("test-key");
        assertThat(response.path("status").asText()).isEqualTo("MANUAL_REVIEW_REQUIRED");
    }

    @Test
    void compareRfqQuotationsSendsApiKeyAndRequestIdHeaders() throws Exception {
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/quotation-comparison/from-rfq", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] body = "{\"summary\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        MDC.put("requestId", "req-ai-123");

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        RfqComparisonResult result = client.compareRfqQuotations(
            java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "en"
        );

        assertThat(result.summary()).isEqualTo("ok");
        assertThat(path.get()).isEqualTo("/v1/quotation-comparison/from-rfq");
        assertThat(apiKey.get()).isEqualTo("secret-ai-key");
        assertThat(requestId.get()).isEqualTo("req-ai-123");
    }

    @Test
    void copilotMessageSendsBrowserSafeBodyThroughServerSideApiKey() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/copilot/messages", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] body = """
                {
                  "runId":"run_1",
                  "status":"NEEDS_REVIEW",
                  "answer":"Found 2 supplier offers.",
                  "cards":[],
                  "actions":[]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        JsonNode result = client.copilotMessage(objectMapper.readTree("""
            {
              "message":"Which suppliers quoted for Dell laptops?",
              "locale":"en",
              "conversationId":"chat-window-1",
              "executionMode":"poll",
              "runId":"agent_test",
              "includeEvidence":true
            }
            """));

        assertThat(apiKey.get()).isEqualTo("secret-ai-key");
        assertThat(requestBody.get().path("message").asText()).isEqualTo("Which suppliers quoted for Dell laptops?");
        assertThat(requestBody.get().path("conversationId").asText()).isEqualTo("chat-window-1");
        assertThat(requestBody.get().path("executionMode").asText()).isEqualTo("poll");
        assertThat(requestBody.get().path("runId").asText()).isEqualTo("agent_test");
        assertThat(result.path("answer").asText()).isEqualTo("Found 2 supplier offers.");
    }

    @Test
    void compareRfqQuotationsSendsOptionalV05ComparisonContext() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/quotation-comparison/from-rfq", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] body = "{\"summary\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        UUID rfqId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");
        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        client.compareRfqQuotations(
            new AiAnalysisJobService.RfqQuotationComparisonAiRequest(
                rfqId,
                "UZS",
                true,
                java.util.List.of(new AiAnalysisJobService.RfqExchangeRate("USD", "UZS", "12650.2500"))
            ),
            "en"
        );

        assertThat(requestBody.get().path("rfq").asText()).isEqualTo(rfqId.toString());
        assertThat(requestBody.get().path("referenceCurrency").asText()).isEqualTo("UZS");
        assertThat(requestBody.get().path("includeTaxInComparison").asBoolean()).isTrue();
        assertThat(requestBody.get().path("exchangeRates").get(0).path("rate").asText()).isEqualTo("12650.2500");
        assertThat(requestBody.get().path("language").asText()).isEqualTo("en");
    }

    @Test
    void searchMaterialDuplicatesSendsV05ContextBody() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/material-dedup/search-by-name", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] body = """
                {
                  "query":{"name":"Bearing 6207","domain":"BEARING","domainAmbiguous":false},
                  "matches":[{"candidateMaterialId":"22222222-2222-2222-2222-222222222222","candidateMaterialName":"Bearing 6207 ZZ","similarityScore":"0.9300","similarityPercent":"93.00","verdict":"LIKELY_DUPLICATE","reason":"Same bearing size","signals":[{"kind":"POSITIVE","detail":"Exact normalized name","weight":"0.72"}]}],
                  "stats":{"pairsEvaluated":12},
                  "llmUsed":false
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        UUID materialId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID departmentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID companyId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID materialGroupId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        MaterialDedupResult result = client.searchMaterialDuplicates(
            new AiAnalysisJobService.MaterialDedupSearchAiRequest(
                materialId,
                "Bearing 6207",
                departmentId,
                companyId,
                materialGroupId,
                25,
                true
            ),
            "ru"
        );

        assertThat(requestBody.get().path("materialId").asText()).isEqualTo(materialId.toString());
        assertThat(requestBody.get().path("name").asText()).isEqualTo("Bearing 6207");
        assertThat(requestBody.get().path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(requestBody.get().path("companyId").asText()).isEqualTo(companyId.toString());
        assertThat(requestBody.get().path("materialGroupId").asText()).isEqualTo(materialGroupId.toString());
        assertThat(requestBody.get().path("limit").asInt()).isEqualTo(25);
        assertThat(requestBody.get().path("includeInactive").asBoolean()).isTrue();
        assertThat(requestBody.get().path("language").asText()).isEqualTo("ru");
        assertThat(result.query().domain()).isEqualTo("BEARING");
        assertThat(result.llmUsed()).isFalse();
        assertThat(result.matches()).singleElement()
            .satisfies(match -> {
                assertThat(match.candidateMaterialName()).isEqualTo("Bearing 6207 ZZ");
                assertThat(match.signals()).singleElement()
                    .satisfies(signal -> assertThat(signal.kind()).isEqualTo("POSITIVE"));
            });
    }

    @Test
    void compareRfqQuotationsDeserializesV05ResultWithQuotationKeyDecimalStringsAndAffiliations() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/quotation-comparison/from-rfq", exchange -> {
            byte[] body = """
                {
                  "summary":"Comparable quotations",
                  "recommendation":"NEEDS_CLARIFICATION",
                  "lowestTotalSupplierName":"Supplier A",
                  "referenceCurrency":"UZS",
                  "rfqStatus":"PUBLISHED",
                  "rfqDueDate":"2026-09-10",
                  "suppliers":[
                    {"quotationKey":"q-1","supplierId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","supplierName":"Supplier A","quotationId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","totalGross":"125000.0000","effectiveTotal":"125000.0000","exchangeRateSource":"CBU_OFFICIAL","isLowestTotal":true,"warnings":["Tax included"],"performance":{"evaluations":4,"onTimePercent":"90.00"}},
                    {"quotationKey":"q-2","supplierId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","supplierName":"Supplier A","quotationId":"cccccccc-cccc-cccc-cccc-cccccccccccc","totalGross":"130000.0000","effectiveTotal":"130000.0000","exchangeRateSource":"CBU_OFFICIAL","isLowestTotal":false,"warnings":[],"performance":null}
                  ],
                  "items":[{"itemKey":"line-1","materialName":"Bearing","requestedQuantity":"2.0000","offers":[{"quotationKey":"q-1","unitPrice":"62500.0000","lineGross":"125000.0000"},{"quotationKey":"q-2","unitPrice":"65000.0000","lineGross":"130000.0000"}]}],
                  "affiliations":[{"type":"BANK_ACCOUNT","blocking":true,"supplierNames":["Supplier A","Supplier B"],"details":"Shared account"}],
                  "findings":[{"severity":"WARNING","category":"AFFILIATION","title":"Shared bank account","details":"Review before award"}],
                  "comparabilityStatus":"NEEDS_CLARIFICATION"
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        RfqComparisonResult result = client.compareRfqQuotations(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "en"
        );

        assertThat(result.recommendation()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(result.comparabilityStatus()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.title()).isEqualTo("Shared bank account");
            assertThat(finding.details()).isEqualTo("Review before award");
        });
        JsonNode serialized = objectMapper.valueToTree(result);
        assertThat(serialized.path("affiliations").get(0).path("type").asText()).isEqualTo("BANK_ACCOUNT");
        assertThat(serialized.path("affiliations").get(0).path("details").asText()).isEqualTo("Shared account");
        assertThat(result.suppliers()).hasSize(2)
            .extracting(SupplierComparison::quotationKey)
            .containsExactly("q-1", "q-2");
        assertThat(result.suppliers().get(0).totalGross()).isEqualTo("125000.0000");
        assertThat(result.items()).singleElement()
            .satisfies(item -> assertThat(item.offers())
                .extracting(ItemOffer::quotationKey)
                .containsExactly("q-1", "q-2"));
        assertThat(result.affiliations()).singleElement()
            .satisfies(affiliation -> {
                assertThat(affiliation.signal()).isEqualTo("BANK_ACCOUNT");
                assertThat(affiliation.blocking()).isTrue();
            });
    }

    @Test
    void researchMarketPriceSendsV05BodyHeadersAndDeserializesResult() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market-price/research", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] body = """
                {
                  "status":"OK",
                  "queries":["bearing 6208 price"],
                  "llmUsed":false,
                  "suggestedUnitPrice":"55.2500",
                  "priceBasis":"GLOBAL_REFERENCE",
                  "unitConfidence":"KNOWN",
                  "confidence":"LOW",
                  "offers":[{"title":"Offer","source":"web","sourceUrl":"https://example.test/offer","convertedUnitPrice":"55.2500","extractionType":"STRUCTURED","excluded":false}],
                  "findings":[{"severity":"WARNING","category":"BASIS","title":"Global reference","details":"May exclude logistics"}],
                  "marketPriceDraft":{"materialId":"22222222-2222-2222-2222-222222222222","marketUnitPrice":"55.2500","currencyCode":"USD","sourceType":"AI_WEB_RESEARCH","sourceReference":"market research 123"}
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        MDC.put("requestId", "market-req-1");

        UUID materialId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        var result = client.researchMarketPrice(
            new MarketPriceResearchAiRequest(
                materialId,
                "USD",
                "3.5",
                "Bearing 6208",
                "SKF",
                "6208-2RS",
                "pcs",
                true,
                false,
                20
            ),
            "en"
        );

        assertThat(apiKey.get()).isEqualTo("secret-ai-key");
        assertThat(requestId.get()).isEqualTo("market-req-1");
        assertThat(requestBody.get().path("materialId").asText()).isEqualTo(materialId.toString());
        assertThat(requestBody.get().path("currencyCode").asText()).isEqualTo("USD");
        assertThat(requestBody.get().path("quantity").asText()).isEqualTo("3.5");
        assertThat(requestBody.get().path("materialName").asText()).isEqualTo("Bearing 6208");
        assertThat(requestBody.get().path("manufacturer").asText()).isEqualTo("SKF");
        assertThat(requestBody.get().path("article").asText()).isEqualTo("6208-2RS");
        assertThat(requestBody.get().path("unitOfMeasureCode").asText()).isEqualTo("pcs");
        assertThat(requestBody.get().path("includeLocal").asBoolean()).isTrue();
        assertThat(requestBody.get().path("includeGlobal").asBoolean()).isFalse();
        assertThat(requestBody.get().path("maxOffers").asInt()).isEqualTo(20);
        assertThat(requestBody.get().path("language").asText()).isEqualTo("en");
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.recommendedUnitPrice()).isEqualByComparingTo("55.2500");
        assertThat(result.offers()).singleElement()
            .satisfies(offer -> {
                assertThat(offer.unitPrice()).isEqualByComparingTo("55.2500");
                assertThat(offer.sourceUrl()).isEqualTo("https://example.test/offer");
            });
    }

    @Test
    void researchMarketPriceDeserializesWrappedSnakeCaseAiResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market-price/research", exchange -> {
            objectMapper.readTree(exchange.getRequestBody());
            byte[] body = """
                {
                  "data": {
                    "status": "OK",
                    "queries": [{
                      "query": "industrial led fixture 150w price",
                      "scope": "local"
                    }],
                    "llm_used": true,
                    "suggested_unit_price": 122000,
                    "price_basis": "LOCAL_MARKET",
                    "unit_confidence": "ESTIMATED",
                    "market_median": 121500,
                    "sample_size": 4,
                    "offers": [{
                      "source_url": "https://example.test/led",
                      "converted_unit_price": 122000,
                      "currency_code": "UZS"
                    }],
                    "market_price_draft": {
                      "material_id": "26db8327-2248-48a4-a805-64c54821e2fc",
                      "market_unit_price": 122000,
                      "currency_code": "UZS",
                      "source_type": "AI_WEB_RESEARCH",
                      "source_reference": "market research"
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        var result = client.researchMarketPrice(
            new MarketPriceResearchAiRequest(
                UUID.fromString("26db8327-2248-48a4-a805-64c54821e2fc"),
                "UZS",
                "1000",
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "en"
        );

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.recommendedUnitPrice()).isEqualByComparingTo("122000");
        assertThat(result.priceBasis()).isEqualTo("LOCAL_MARKET");
        assertThat(result.market().median()).isEqualByComparingTo("121500");
        assertThat(result.market().sampleSize()).isEqualTo(4);
        assertThat(result.offers()).singleElement()
            .satisfies(offer -> {
                assertThat(offer.sourceUrl()).isEqualTo("https://example.test/led");
                assertThat(offer.unitPrice()).isEqualByComparingTo("122000");
                assertThat(offer.currencyCode()).isEqualTo("UZS");
            });
    }

    @Test
    void researchMarketPriceNormalizesLegacyVerboseResultToCompactBusinessEvidence() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market-price/research", exchange -> {
            objectMapper.readTree(exchange.getRequestBody());
            byte[] body = """
                {
                  "status":"OK",
                  "queries":["dell technologies","dell technologies"],
                  "llmUsed":true,
                  "suggestedUnitPrice":"12500000.0000",
                  "currencyCode":"UZS",
                  "priceBasis":"LOCAL_UZ",
                  "unitConfidence":"KNOWN",
                  "confidence":"MEDIUM",
                  "marketMedian":"12300000.0000",
                  "marketAverage":"12450000.0000",
                  "marketMin":"11200000.0000",
                  "marketMax":"13900000.0000",
                  "sampleSize":8,
                  "outliersRemoved":1,
                  "offers":[
                    {"title":"Dell Latitude 5550","source":"Prom.uz","sourceUrl":"https://example.test/1","rawPrice":"12 300 000 UZS","convertedUnitPrice":"12300000.0000","currencyCode":"UZS","relevance":"0.97","excluded":false},
                    {"title":"Dell Latitude 5550","source":"Prom.uz","sourceUrl":"https://example.test/1","convertedUnitPrice":"12300000.0000","currencyCode":"UZS","relevance":"0.96","excluded":false},
                    {"title":"Dell Latitude 5550 i5","source":"Glotr.uz","sourceUrl":"https://example.test/2","convertedUnitPrice":"12700000.0000","currencyCode":"UZS","relevance":"0.95","excluded":false},
                    {"title":"Dell Latitude 5550 i7","source":"Shop.uz","sourceUrl":"https://example.test/3","convertedUnitPrice":"12800000.0000","currencyCode":"UZS","relevance":"0.93","excluded":false},
                    {"title":"Dell Latitude 5550 32 GB","source":"Market.uz","sourceUrl":"https://example.test/4","convertedUnitPrice":"13200000.0000","currencyCode":"UZS","relevance":"0.91","excluded":false},
                    {"title":"Dell Latitude 5550 8 GB","source":"Catalog.uz","sourceUrl":"https://example.test/5","convertedUnitPrice":"13900000.0000","currencyCode":"UZS","relevance":"0.90","excluded":false},
                    {"title":"Dell Latitude 5550 basic","source":"Listing.uz","sourceUrl":"https://example.test/6","convertedUnitPrice":"11200000.0000","currencyCode":"UZS","relevance":"0.89","excluded":false},
                    {"title":"Dell wireless mouse","source":"Prom.uz","sourceUrl":"https://example.test/mouse","convertedUnitPrice":"150000.0000","currencyCode":"UZS","relevance":"0.20","excluded":true,"exclusionReason":"UNRELATED_PRODUCT"}
                  ],
                  "findings":[
                    {"severity":"INFO","category":"LLM_USED","title":"LLM used","details":"Implementation detail"},
                    {"severity":"WARNING","category":"PRICE_SPREAD","title":"Wide spread","details":"Bozor narxlari sezilarli darajada farq qiladi."}
                  ],
                  "internalComparison":{"status":"OK","sampleSize":4,"averageHistoricalPrice":"11950000.0000","lastPurchasePrice":"12100000.0000","marketVsInternalPercent":"3.3000"},
                  "marketPriceDraft":{"marketUnitPrice":"12500000.0000","currencyCode":"UZS"}
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        var result = client.researchMarketPrice(
            new MarketPriceResearchAiRequest(
                UUID.fromString("26db8327-2248-48a4-a805-64c54821e2fc"),
                "UZS",
                "1",
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "en"
        );

        assertThat(result.recommendedUnitPrice()).isEqualByComparingTo("12500000.0000");
        assertThat(result.market().sampleSize()).isEqualTo(8);
        assertThat(result.offers()).hasSize(8);
        assertThat(result.offers()).extracting(offer -> offer.title()).contains("Dell wireless mouse");
        assertThat(result.offers()).first()
            .satisfies(offer -> {
                assertThat(offer.title()).isEqualTo("Dell Latitude 5550");
                assertThat(offer.unitPrice()).isEqualByComparingTo("12300000.0000");
                assertThat(offer.convertedUnitPrice()).isEqualByComparingTo("12300000.0000");
                assertThat(offer.rawPrice()).isEqualTo("12 300 000 UZS");
                assertThat(offer.relevance()).isEqualByComparingTo("0.97");
            });
        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings()).extracting(finding -> finding.category()).contains("LLM_USED", "PRICE_SPREAD");
        assertThat(result.internalComparison().sampleSize()).isEqualTo(4);
    }

    @Test
    void researchMarketPriceKeepsOffersWhenAiUsesPriceAliases() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market-price/research", exchange -> {
            objectMapper.readTree(exchange.getRequestBody());
            byte[] body = """
                {
                  "status":"OK",
                  "recommendedUnitPrice":1205000.0,
                  "currencyCode":"UZS",
                  "priceBasis":"LOCAL_UZ",
                  "confidence":"LOW",
                  "market":{"median":1205000.0,"average":1217617.33,"min":110352.0,"max":2337500.0,"sampleSize":3},
                  "offers":[
                    {"title":"Bearing 6208","source":"Prom.uz","url":"https://example.test/prom","price":1205000.0,"currency":"UZS","relevance":0.93},
                    {"title":"Bearing 6208 sealed","source":"Glotr.uz","source_url":"https://example.test/glotr","unit_price_uzs":2337500.0,"relevance":0.91},
                    {"title":"Bearing 6208 basic","source":"Market.uz","sourceUrl":"https://example.test/market","amount":110352.0,"currency_code":"UZS","score":86}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        var result = client.researchMarketPrice(
            new MarketPriceResearchAiRequest(
                UUID.fromString("26db8327-2248-48a4-a805-64c54821e2fc"),
                "UZS",
                "1",
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "en"
        );

        assertThat(result.offers()).hasSize(3);
        assertThat(result.offers()).extracting(offer -> offer.sourceUrl())
            .containsExactly("https://example.test/prom", "https://example.test/glotr", "https://example.test/market");
        assertThat(result.offers()).extracting(offer -> offer.unitPrice())
            .extracting(Object::toString)
            .containsExactly("1205000.0", "2337500.0", "110352.0");
        assertThat(result.offers()).extracting(offer -> offer.currencyCode())
            .containsExactly("UZS", "UZS", "UZS");
        assertThat(result.offers()).extracting(offer -> offer.relevance())
            .extracting(Object::toString)
            .containsExactly("0.93", "0.91", "0.86");
    }

    @Test
    void researchMarketPriceKeepsOffersWhenAiNestsThemUnderResult() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market-price/research", exchange -> {
            objectMapper.readTree(exchange.getRequestBody());
            byte[] body = """
                {
                  "data": {
                    "status": "COMPLETED",
                    "result": {
                      "status":"OK",
                      "recommendedUnitPrice":1205000.0,
                      "currencyCode":"UZS",
                      "priceBasis":"LOCAL_UZ",
                      "confidence":"LOW",
                      "market":{"median":1205000.0,"average":1217617.33,"min":110352.0,"max":2337500.0,"sampleSize":3},
                      "offers":[
                        {"title":"Bearing 6208","source":"Prom.uz","url":"https://example.test/prom","price":1205000.0,"currency":"UZS","relevance":0.93}
                      ]
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        var result = client.researchMarketPrice(
            new MarketPriceResearchAiRequest(
                UUID.fromString("26db8327-2248-48a4-a805-64c54821e2fc"),
                "UZS",
                "1",
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "en"
        );

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.recommendedUnitPrice()).isEqualByComparingTo("1205000.0");
        assertThat(result.market().sampleSize()).isEqualTo(3);
        assertThat(result.offers()).singleElement()
            .satisfies(offer -> {
                assertThat(offer.sourceUrl()).isEqualTo("https://example.test/prom");
                assertThat(offer.unitPrice()).isEqualByComparingTo("1205000.0");
            });
    }

    @Test
    void researchMarketPriceKeepsOfferEvidenceWhenUnknownFieldsDoNotMapToUnitPrice() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market-price/research", exchange -> {
            objectMapper.readTree(exchange.getRequestBody());
            byte[] body = """
                {
                  "status":"OK",
                  "recommendedUnitPrice":1205000.0,
                  "currencyCode":"UZS",
                  "priceBasis":"LOCAL_UZ",
                  "confidence":"LOW",
                  "market":{"median":1205000.0,"average":1217617.33,"min":110352.0,"max":2337500.0,"sampleSize":3},
                  "offers":[
                    {
                      "title":"Bearing 6208",
                      "source":"Prom.uz",
                      "sourceUrl":"https://example.test/prom",
                      "rawPrice":"1 205 000 UZS",
                      "extractionMetadata":{"selector":".price"},
                      "unknownProviderField":"kept by ignoreUnknown"
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        var result = client.researchMarketPrice(
            new MarketPriceResearchAiRequest(
                UUID.fromString("26db8327-2248-48a4-a805-64c54821e2fc"),
                "UZS",
                "1",
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "en"
        );

        assertThat(result.offers()).singleElement()
            .satisfies(offer -> {
                assertThat(offer.title()).isEqualTo("Bearing 6208");
                assertThat(offer.source()).isEqualTo("Prom.uz");
                assertThat(offer.sourceUrl()).isEqualTo("https://example.test/prom");
                assertThat(offer.unitPrice()).isNull();
            });
    }

    @Test
    void researchMarketPriceExposesRejectedOfferDiagnosticsSeparately() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market-price/research", exchange -> {
            objectMapper.readTree(exchange.getRequestBody());
            byte[] body = """
                {
                  "status":"OK",
                  "recommendedUnitPrice":709800.0,
                  "currencyCode":"UZS",
                  "priceBasis":"LOCAL_UZ",
                  "confidence":"LOW",
                  "market":{"median":709800.0,"average":709800.0,"min":709800.0,"max":709800.0,"sampleSize":1},
                  "offers":[
                    {"title":"Different material","source":"Prom.uz","sourceUrl":"https://example.test/other","convertedUnitPrice":650000.0,"currencyCode":"UZS","relevance":0.21,"excluded":true,"exclusionReason":"UNRELATED_PRODUCT"},
                    {"title":"Weak match","source":"Glotr.uz","sourceUrl":"https://example.test/weak","convertedUnitPrice":709800.0,"currencyCode":"UZS","relevance":0.42}
                  ],
                  "findings":[{"severity":"INFO","category":"OFFER_RELEVANCE","message":"2 offers were excluded by rule"}]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        var result = client.researchMarketPrice(
            new MarketPriceResearchAiRequest(
                UUID.fromString("26db8327-2248-48a4-a805-64c54821e2fc"),
                "UZS",
                "1",
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "en"
        );

        assertThat(result.offers()).hasSize(2);
        assertThat(result.rejectedOffers()).hasSize(2);
        assertThat(result.rejectedOffers()).first()
            .satisfies(offer -> {
                assertThat(offer.title()).isEqualTo("Weak match");
                assertThat(offer.relevance()).isEqualByComparingTo("0.42");
            });
        assertThat(result.rejectedOffers()).anySatisfy(offer -> {
            assertThat(offer.title()).isEqualTo("Different material");
            assertThat(offer.exclusionReason()).isEqualTo("UNRELATED_PRODUCT");
        });
    }

    @Test
    void researchMarketPricePreservesFullResultContractFields() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/market-price/research", exchange -> {
            objectMapper.readTree(exchange.getRequestBody());
            byte[] body = """
                {
                  "status":"OK",
                  "confidence":"NONE",
                  "priceBasis":"NONE",
                  "unitConfidence":"UNKNOWN",
                  "material":{"materialId":"26db8327-2248-48a4-a805-64c54821e2fc","code":"MAT-1","name":"Material","description":"Desc","materialGroup":"Group","unitOfMeasureCode":"pcs","manufacturer":"Maker","article":"A-1","source":"MM_DATABASE"},
                  "candidates":[{"materialId":"36db8327-2248-48a4-a805-64c54821e2fc","code":"MAT-2","name":"Candidate"}],
                  "recommendedUnitPrice":100,
                  "recommendedPriceReliability":"UNAVAILABLE",
                  "recommendedPriceMethod":"median",
                  "recommendedTotalPrice":300,
                  "quantity":3,
                  "unitOfMeasureCode":"pcs",
                  "suggestedUnitPrice":100,
                  "currencyCode":"UZS",
                  "market":{"median":100,"average":110,"min":90,"max":130,"sampleSize":4,"outliersRemoved":1,"spreadRatio":1.44},
                  "offerSummary":{"found":5,"usedInStatistics":4,"excluded":1,"localOffers":3,"globalOffers":2,"shown":5},
                  "generatedAt":"2026-08-26T09:57:24.839Z",
                  "offers":[{"title":"Offer","source":"Source","sourceUrl":"https://example.test/offer","rawPrice":"10 USD","currencyCode":"USD","unitPrice":10,"convertedUnitPrice":100,"fxRate":10,"fxSource":"CBU","unitOfMeasureCode":"pcs","unitMatch":"UNKNOWN","observedAt":"2026-08-26T09:57:24.839Z","sourceKind":"UNKNOWN","relevance":0.9,"basis":"NONE","extraction":"STRUCTURED","matched":true,"usedInStatistics":false,"excluded":false,"excludeReason":"none"}],
                  "queries":[{"engine":"search","query":"material price","basis":"LOCAL_UZ","resultCount":5,"cached":false,"error":"none"}],
                  "findings":[{"severity":"INFO","category":"SOURCE","message":"ok","details":"details"}],
                  "internalComparison":{"verdict":"MARKET_HIGHER","sampleSize":2,"averageHistoricalPrice":95,"lastPurchasePrice":90,"currencyCode":"UZS","marketVsInternalPercent":10},
                  "explanation":"explanation",
                  "llmUsed":false,
                  "marketPriceDraft":{"materialId":"26db8327-2248-48a4-a805-64c54821e2fc","marketUnitPrice":100,"currencyCode":"UZS","effectiveDate":"2026-08-26","sourceType":"AI_WEB_RESEARCH","sourceReference":"ref"}
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        HttpMmAiClient client = new HttpMmAiClient(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret-ai-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
        );

        var result = client.researchMarketPrice(
            new MarketPriceResearchAiRequest(
                UUID.fromString("26db8327-2248-48a4-a805-64c54821e2fc"),
                "UZS",
                "3",
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            "en"
        );

        assertThat(result.unitConfidence()).isEqualTo("UNKNOWN");
        assertThat(result.material().materialGroup()).isEqualTo("Group");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> assertThat(candidate.code()).isEqualTo("MAT-2"));
        assertThat(result.recommendedPriceReliability()).isEqualTo("UNAVAILABLE");
        assertThat(result.recommendedPriceMethod()).isEqualTo("median");
        assertThat(result.recommendedTotalPrice()).isEqualByComparingTo("300");
        assertThat(result.quantity()).isEqualByComparingTo("3");
        assertThat(result.unitOfMeasureCode()).isEqualTo("pcs");
        assertThat(result.suggestedUnitPrice()).isEqualByComparingTo("100");
        assertThat(result.market().outliersRemoved()).isEqualTo(1);
        assertThat(result.market().spreadRatio()).isEqualByComparingTo("1.44");
        assertThat(result.offerSummary().found()).isEqualTo(5);
        assertThat(result.generatedAt()).isEqualTo("2026-08-26T09:57:24.839Z");
        assertThat(result.offers()).singleElement().satisfies(offer -> {
            assertThat(offer.rawPrice()).isEqualTo("10 USD");
            assertThat(offer.unitPrice()).isEqualByComparingTo("10");
            assertThat(offer.currencyCode()).isEqualTo("USD");
            assertThat(offer.convertedUnitPrice()).isEqualByComparingTo("100");
            assertThat(offer.fxRate()).isEqualByComparingTo("10");
            assertThat(offer.fxSource()).isEqualTo("CBU");
            assertThat(offer.unitMatch()).isEqualTo("UNKNOWN");
            assertThat(offer.sourceKind()).isEqualTo("UNKNOWN");
            assertThat(offer.basis()).isEqualTo("NONE");
            assertThat(offer.extraction()).isEqualTo("STRUCTURED");
            assertThat(offer.matched()).isTrue();
            assertThat(offer.usedInStatistics()).isFalse();
            assertThat(offer.excluded()).isFalse();
            assertThat(offer.excludeReason()).isEqualTo("none");
        });
        assertThat(result.queries()).singleElement().satisfies(query -> assertThat(query.resultCount()).isEqualTo(5));
        assertThat(result.findings()).singleElement().satisfies(finding -> assertThat(finding.details()).isEqualTo("details"));
        assertThat(result.internalComparison().verdict()).isEqualTo("MARKET_HIGHER");
        assertThat(result.internalComparison().currencyCode()).isEqualTo("UZS");
        assertThat(result.explanation()).isEqualTo("explanation");
        assertThat(result.llmUsed()).isFalse();
        assertThat(result.marketPriceDraft().sourceType()).isEqualTo("AI_WEB_RESEARCH");
    }
}
