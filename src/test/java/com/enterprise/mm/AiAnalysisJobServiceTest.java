package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.event.AiAnalysisJobCreatedEvent;
import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiFindingDto;
import com.enterprise.mm.payload.MaterialDedupDtos.MaterialDedupResult;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchAiRequest;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketComparableOffer;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceFinding;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceInternalComparison;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchJobRequest;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchResult;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketStatistics;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeRequest;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeResponse;
import com.enterprise.mm.payload.RfqComparisonDtos.RfqComparisonResult;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.service.AiAnalysisJobTransactionService;
import com.enterprise.mm.service.AiAnalysisJobWorker;
import com.enterprise.mm.service.MaterialDuplicateCandidateService;
import com.enterprise.mm.service.MmAiServiceException;
import com.enterprise.mm.service.MmAiClient;
import com.enterprise.mm.service.PriceAiAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class AiAnalysisJobServiceTest {
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock AiAnalysisJobRepository jobRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PriceAiAnalysisService priceAiAnalysisService;
    @Mock MmAiClient mmAiClient;
    @Mock MaterialDuplicateCandidateService materialDuplicateCandidateService;

    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    AiAnalysisJobService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("buyer", "n/a"));
        service = new AiAnalysisJobService(jobRepository, objectMapper, eventPublisher, new com.enterprise.mm.service.UnitOfMeasureDisplayService(org.mockito.Mockito.mock(com.enterprise.mm.repository.UnitOfMeasureRepository.class)), org.mockito.Mockito.mock(com.enterprise.mm.service.InventoryAiDraftService.class), org.mockito.Mockito.mock(com.enterprise.mm.service.InventoryAiResultService.class), org.mockito.Mockito.mock(com.enterprise.mm.service.inventory.InventoryAccessService.class), org.mockito.Mockito.mock(com.enterprise.mm.service.MaterialStockAccountRecommendationService.class));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void startPriceJobReturnsActiveEquivalentJobInsteadOfCreatingDuplicate() {
        PriceAiAnalyzeRequest request = priceRequest();
        AiAnalysisJob existing = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.PRICE_INTELLIGENCE,
            "MATERIAL",
            MATERIAL_ID,
            "same-request",
            "en",
            "buyer",
            "{}"
        );
        existing.setId(UUID.fromString("30000000-0000-0000-0000-000000000003"));

        when(jobRepository.findActiveEquivalent(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.PRICE_INTELLIGENCE,
            "MATERIAL",
            MATERIAL_ID,
            service.requestHash(request),
            "en",
            List.of(AiAnalysisJobStatus.QUEUED, AiAnalysisJobStatus.RUNNING)
        )).thenReturn(Optional.of(existing));

        AiAnalysisJobResponse response = service.startPriceJob(request, "en");

        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(response.status()).isEqualTo("QUEUED");
        verify(jobRepository, never()).save(any());
        ArgumentCaptor<AiAnalysisJobCreatedEvent> event = ArgumentCaptor.forClass(AiAnalysisJobCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().jobId()).isEqualTo(existing.getId());
    }

    @Test
    void mxikBatchRejectsFilteredScope() {
        var request = new AiAnalysisJobService.MxikBatchRequest("FILTERED", null, true, "");

        assertThatThrownBy(() -> service.startMxikMatchBatchJob(request, "en"))
            .hasMessage("Only selected-material MXIK recommendations are supported");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void mxikBatchRejectsMoreThanTwentyFiveSelectedMaterials() {
        List<UUID> materialIds = java.util.stream.IntStream.range(0, 26)
            .mapToObj(ignored -> UUID.randomUUID())
            .toList();
        var request = new AiAnalysisJobService.MxikBatchRequest("SELECTED", materialIds, null, null);

        assertThatThrownBy(() -> service.startMxikMatchBatchJob(request, "en"))
            .hasMessage("Select between 1 and 25 materials");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void startPriceJobPersistsScopeLanguageAndPublishesJobEvent() {
        PriceAiAnalyzeRequest request = priceRequest();
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("40000000-0000-0000-0000-000000000004"));
            return job;
        });

        AiAnalysisJobResponse response = service.startPriceJob(request, "uz");

        assertThat(response.id()).isEqualTo(UUID.fromString("40000000-0000-0000-0000-000000000004"));
        assertThat(response.analysisType()).isEqualTo("PRICE_INTELLIGENCE");
        assertThat(response.entityType()).isEqualTo("MATERIAL");
        assertThat(response.entityId()).isEqualTo(MATERIAL_ID);
        assertThat(response.requestedLanguage()).isEqualTo("uz");

        ArgumentCaptor<AiAnalysisJob> job = ArgumentCaptor.forClass(AiAnalysisJob.class);
        verify(jobRepository).save(job.capture());
        assertThat(job.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(job.getValue().getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(job.getValue().getRequestedBy()).isEqualTo("buyer");
        assertThat(job.getValue().getRequestHash()).isEqualTo(service.requestHash(request));

        ArgumentCaptor<AiAnalysisJobCreatedEvent> event = ArgumentCaptor.forClass(AiAnalysisJobCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().jobId()).isEqualTo(response.id());
    }

    @Test
    void startRfqQuotationComparisonJobPersistsRfqEntityContext() {
        UUID rfqId = UUID.fromString("60000000-0000-0000-0000-000000000006");
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("70000000-0000-0000-0000-000000000007"));
            return job;
        });

        AiAnalysisJobResponse response = service.startRfqQuotationComparisonJob(rfqId, "en");

        assertThat(response.analysisType()).isEqualTo("RFQ_QUOTATION_COMPARISON");
        assertThat(response.entityType()).isEqualTo("RFQ");
        assertThat(response.entityId()).isEqualTo(rfqId);
        assertThat(response.requestedLanguage()).isEqualTo("en");
    }

    @Test
    void startMaterialDedupScanJobPersistsCatalogScope() {
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("a0000000-0000-0000-0000-00000000000a"));
            return job;
        });

        AiAnalysisJobResponse response = service.startMaterialDedupScanJob("ru");

        assertThat(response.analysisType()).isEqualTo("MATERIAL_DEDUP_SCAN");
        assertThat(response.entityType()).isEqualTo("MATERIAL_CATALOG");
        assertThat(response.entityId()).isNull();
        assertThat(response.requestedLanguage()).isEqualTo("ru");
    }

    @Test
    void startMaterialDedupSearchJobPersistsMaterialContext() {
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("a1000000-0000-0000-0000-00000000000a"));
            return job;
        });

        AiAnalysisJobResponse response = service.startMaterialDedupSearchJob(MATERIAL_ID, "Bearing 6204", "en-GB");

        assertThat(response.analysisType()).isEqualTo("MATERIAL_DEDUP_SEARCH");
        assertThat(response.entityType()).isEqualTo("MATERIAL");
        assertThat(response.entityId()).isEqualTo(MATERIAL_ID);
        assertThat(response.requestedLanguage()).isEqualTo("en");
    }

    @Test
    void startMaterialDedupNameSearchJobPersistsDraftContext() {
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("a2000000-0000-0000-0000-00000000000a"));
            return job;
        });

        AiAnalysisJobResponse response = service.startMaterialDedupNameSearchJob("Bearing 6205", "ru");

        assertThat(response.analysisType()).isEqualTo("MATERIAL_DEDUP_SEARCH");
        assertThat(response.entityType()).isEqualTo("MATERIAL_DRAFT");
        assertThat(response.entityId()).isNull();
        assertThat(response.requestedLanguage()).isEqualTo("ru");
    }

    @Test
    void startMaterialDedupNameSearchJobPersistsV05ContextDefaults() throws Exception {
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("a3000000-0000-0000-0000-00000000000a"));
            return job;
        });

        service.startMaterialDedupNameSearchJob(" Bearing 6206 ", "en");

        ArgumentCaptor<AiAnalysisJob> job = ArgumentCaptor.forClass(AiAnalysisJob.class);
        verify(jobRepository).save(job.capture());
        JsonNode payload = objectMapper.readTree(job.getValue().getRequestPayload());
        assertThat(payload.path("name").asText()).isEqualTo("Bearing 6206");
        assertThat(payload.path("companyId").asText()).isEqualTo(COMPANY_ID.toString());
        assertThat(payload.path("limit").asInt()).isEqualTo(10);
        assertThat(payload.path("includeInactive").asBoolean()).isFalse();
    }

    @Test
    void startMaterialDedupNameSearchJobPersistsProvidedV05Context() throws Exception {
        UUID departmentId = UUID.fromString("a4000000-0000-0000-0000-00000000000a");
        UUID materialGroupId = UUID.fromString("a5000000-0000-0000-0000-00000000000a");
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("a6000000-0000-0000-0000-00000000000a"));
            return job;
        });

        service.startMaterialDedupNameSearchJob(
            "Pump seal",
            departmentId,
            materialGroupId,
            25,
            true,
            "en"
        );

        ArgumentCaptor<AiAnalysisJob> job = ArgumentCaptor.forClass(AiAnalysisJob.class);
        verify(jobRepository).save(job.capture());
        JsonNode payload = objectMapper.readTree(job.getValue().getRequestPayload());
        assertThat(payload.path("name").asText()).isEqualTo("Pump seal");
        assertThat(payload.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(payload.path("materialGroupId").asText()).isEqualTo(materialGroupId.toString());
        assertThat(payload.path("limit").asInt()).isEqualTo(25);
        assertThat(payload.path("includeInactive").asBoolean()).isTrue();
    }

    @Test
    void startNonMovingStockJobPersistsRequestedScope() {
        UUID warehouseId = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("c0000000-0000-0000-0000-00000000000c"));
            return job;
        });

        AiAnalysisJobResponse response = service.startNonMovingStockJob(null, null, warehouseId, "en-US");

        assertThat(response.analysisType()).isEqualTo("NON_MOVING_STOCK");
        assertThat(response.entityType()).isEqualTo("NON_MOVING_STOCK_SCOPE");
        assertThat(response.entityId()).isEqualTo(warehouseId);
        assertThat(response.requestedLanguage()).isEqualTo("en");
    }

    @Test
    void startMarketPriceResearchJobPersistsEntityAndPrivacyScopedPayload() throws Exception {
        when(jobRepository.findActiveEquivalent(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            AiAnalysisJob job = invocation.getArgument(0);
            job.setId(UUID.fromString("c1000000-0000-0000-0000-00000000000c"));
            return job;
        });

        AiAnalysisJobResponse response = service.startMarketPriceResearchJob(
            new MarketPriceResearchJobRequest(
                MATERIAL_ID,
                " usd ",
                "12.5000",
                " Bearing 6208 ",
                " SKF ",
                " 6208-2RS ",
                "pcs",
                true,
                false,
                15
            ),
            "en-US"
        );

        assertThat(response.analysisType()).isEqualTo("MARKET_PRICE_RESEARCH");
        assertThat(response.entityType()).isEqualTo("MATERIAL");
        assertThat(response.entityId()).isEqualTo(MATERIAL_ID);
        assertThat(response.requestedLanguage()).isEqualTo("en");

        ArgumentCaptor<AiAnalysisJob> job = ArgumentCaptor.forClass(AiAnalysisJob.class);
        verify(jobRepository).save(job.capture());
        JsonNode payload = objectMapper.readTree(job.getValue().getRequestPayload());
        assertThat(payload.path("materialId").asText()).isEqualTo(MATERIAL_ID.toString());
        assertThat(payload.path("currencyCode").asText()).isEqualTo("USD");
        assertThat(payload.path("quantity").asText()).isEqualTo("12.5000");
        assertThat(payload.path("materialName").asText()).isEqualTo("Bearing 6208");
        assertThat(payload.path("manufacturer").asText()).isEqualTo("SKF");
        assertThat(payload.path("article").asText()).isEqualTo("6208-2RS");
        assertThat(payload.path("unitOfMeasureCode").asText()).isEqualTo("pcs");
        assertThat(payload.path("includeLocal").asBoolean()).isTrue();
        assertThat(payload.path("includeGlobal").asBoolean()).isFalse();
        assertThat(payload.path("maxOffers").asInt()).isEqualTo(15);
        assertThat(payload.has("departmentId")).isFalse();
        assertThat(payload.has("warehouseId")).isFalse();
        assertThat(payload.has("internalPrice")).isFalse();
    }

    @Test
    void latestReturnsNotRequestedWhenEntityHasNoAiJobYet() {
        when(jobRepository.findLatestForEntity(
            any(), any(), any(), any(), any(), any()
        )).thenReturn(Page.empty());

        AiAnalysisJobResponse response = service.latest(
            AiAnalysisType.PRICE_INTELLIGENCE,
            "material",
            MATERIAL_ID
        );

        assertThat(response.id()).isNull();
        assertThat(response.analysisType()).isEqualTo("PRICE_INTELLIGENCE");
        assertThat(response.entityType()).isEqualTo("MATERIAL");
        assertThat(response.entityId()).isEqualTo(MATERIAL_ID);
        assertThat(response.status()).isEqualTo("NOT_REQUESTED");
    }

    @Test
    void workerCompletesPriceJobWithSerializedAnalysisResult() {
        UUID jobId = UUID.fromString("50000000-0000-0000-0000-000000000005");
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.PRICE_INTELLIGENCE,
            "MATERIAL",
            MATERIAL_ID,
            service.requestHash(priceRequest()),
            "en",
            "buyer",
            service.serializeRequest(priceRequest())
        );
        job.setId(jobId);
        PriceAiAnalyzeResponse analysis = new PriceAiAnalyzeResponse(
            "CHATGPT",
            "MOCK_OK",
            "ok",
            "gpt-test",
            MATERIAL_ID,
            "Bearing",
            BigDecimal.valueOf(150),
            BigDecimal.valueOf(95),
            BigDecimal.valueOf(120),
            BigDecimal.valueOf(107.5),
            BigDecimal.valueOf(120),
            BigDecimal.valueOf(95),
            BigDecimal.valueOf(39.53),
            List.of(),
            List.of(new ContractAiFindingDto("WARNING", "PRICE_HISTORY", "High", "Above history")),
            List.of(),
            null,
            LocalDateTime.now()
        );

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(priceAiAnalysisService.analyze(priceRequest())).thenReturn(analysis);

        AiAnalysisJobWorker worker = worker();
        worker.run(jobId);

        assertThat(job.getStatus()).isEqualTo(AiAnalysisJobStatus.COMPLETED);
        assertThat(job.getResultPayload()).contains("\"providerStatus\":\"MOCK_OK\"");
        assertThat(job.getCompletedAt()).isNotNull();
    }

    @Test
    void workerCompletesRfqQuotationComparisonJobWithMmAiResult() throws Exception {
        UUID rfqId = UUID.fromString("80000000-0000-0000-0000-000000000008");
        UUID jobId = UUID.fromString("90000000-0000-0000-0000-000000000009");
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.RFQ_QUOTATION_COMPARISON,
            "RFQ",
            rfqId,
            "rfq-request",
            "en",
            "buyer",
            objectMapper.writeValueAsString(new AiAnalysisJobService.RfqQuotationComparisonAiRequest(rfqId))
        );
        job.setId(jobId);
        RfqComparisonResult result = objectMapper.readValue("""
            {
              "summary":"Supplier A is cheaper",
              "recommendation":"COMPARABLE",
              "comparabilityStatus":"COMPARABLE",
              "suppliers":[{"quotationKey":"q-1","supplierName":"Supplier A","totalGross":"100.0000","effectiveTotal":"100.0000","isLowestTotal":true}],
              "items":[{"itemKey":"line-1","offers":[{"quotationKey":"q-1","unitPrice":"100.0000","lineGross":"100.0000"}]}],
              "findings":[{"severity":"INFO","category":"PRICE","title":"Lowest total","details":"Supplier A has lowest total"}]
            }
            """, RfqComparisonResult.class);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(mmAiClient.compareRfqQuotations(any(AiAnalysisJobService.RfqQuotationComparisonAiRequest.class), eq("en"))).thenAnswer(invocation -> {
            AiAnalysisJobService.RfqQuotationComparisonAiRequest request = invocation.getArgument(0);
            assertThat(request.rfqId()).isEqualTo(rfqId);
            assertThat(MDC.get("requestId")).isEqualTo(jobId.toString());
            assertThat(MDC.get("aiJobId")).isEqualTo(jobId.toString());
            return result;
        });

        AiAnalysisJobWorker worker = worker();
        worker.run(jobId);

        assertThat(job.getStatus()).isEqualTo(AiAnalysisJobStatus.COMPLETED);
        assertThat(job.getProvider()).isEqualTo("MM_AI");
        assertThat(job.getResultPayload()).contains("Supplier A is cheaper");
    }

    @Test
    void workerStoresMxikRecommendationWithMaterialVersion() throws Exception {
        UUID jobId = UUID.randomUUID();
        AiAnalysisJob job = AiAnalysisJob.queued("tenant-a", COMPANY_ID,
            AiAnalysisType.MATERIAL_MXIK_MATCH, "MATERIAL", MATERIAL_ID,
            "mxik-request", "uz", "buyer",
            objectMapper.writeValueAsString(new AiAnalysisJobService.MxikMatchRequest(MATERIAL_ID)));
        job.setId(jobId);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(mmAiClient.recommendMxik(MATERIAL_ID, "uz")).thenReturn(objectMapper.readTree(
            "{\"materialId\":\"" + MATERIAL_ID + "\",\"materialVersion\":3,\"status\":\"RECOMMENDED\",\"recommendedMxik\":{\"code\":\"123\",\"name\":\"Test\"}}"));

        worker().run(jobId);

        assertThat(job.getStatus()).isEqualTo(AiAnalysisJobStatus.COMPLETED);
        assertThat(job.getResultPayload()).contains("\"materialVersion\":3");
        assertThat(job.getResultPayload()).contains("\"recommendedMxik\"");
    }

    @Test
    void workerCompletesMaterialDedupScanJobWithMmAiResult() throws Exception {
        UUID jobId = UUID.fromString("d0000000-0000-0000-0000-00000000000d");
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.MATERIAL_DEDUP_SCAN,
            "MATERIAL_CATALOG",
            null,
            "dedup-request",
            "ru",
            "buyer",
            objectMapper.writeValueAsString(new AiAnalysisJobService.MaterialDedupScanAiRequest())
        );
        job.setId(jobId);
        MaterialDedupResult result = objectMapper.readValue("""
            {
              "query":{"name":"catalog scan","domainAmbiguous":false},
              "matches":[
                {"candidateMaterialId":"20000000-0000-0000-0000-000000000002","candidateMaterialName":"Bearing copy","similarityScore":"0.9400","similarityPercent":"94.00","verdict":"LIKELY_DUPLICATE","reason":"Same code"}
              ],
              "findings":["One likely duplicate"],
              "llmUsed":true
            }
            """, MaterialDedupResult.class);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(mmAiClient.scanMaterialDuplicates("ru")).thenReturn(result);

        AiAnalysisJobWorker worker = worker();
        worker.run(jobId);

        assertThat(job.getStatus()).isEqualTo(AiAnalysisJobStatus.COMPLETED);
        assertThat(job.getProvider()).isEqualTo("MM_AI");
        assertThat(job.getResultPayload()).contains("Bearing copy");
        verify(materialDuplicateCandidateService).importAiScanResult(jobId, result);
    }

    @Test
    void workerCompletesMaterialDedupSearchJobWithMmAiResult() throws Exception {
        UUID jobId = UUID.fromString("d1000000-0000-0000-0000-00000000000d");
        var request = new AiAnalysisJobService.MaterialDedupSearchAiRequest(MATERIAL_ID, "Bearing 6204");
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.MATERIAL_DEDUP_SEARCH,
            "MATERIAL",
            MATERIAL_ID,
            "dedup-search-request",
            "en",
            "buyer",
            objectMapper.writeValueAsString(request)
        );
        job.setId(jobId);
        MaterialDedupResult result = objectMapper.readValue("""
            {
              "query":{"name":"Bearing 6204","domain":"BEARING","domainAmbiguous":true},
              "matches":[{"candidateMaterialId":"20000000-0000-0000-0000-000000000002","candidateMaterialName":"Bearing 6204 ZZ","similarityScore":"0.9200","similarityPercent":"92.00","verdict":"REVIEW","reason":"Close bearing name","signals":[{"kind":"REVIEW","detail":"Ambiguous domain","weight":"0.20"}]}],
              "llmUsed":false
            }
            """, MaterialDedupResult.class);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(mmAiClient.searchMaterialDuplicates(request, "en")).thenReturn(result);

        AiAnalysisJobWorker worker = worker();
        worker.run(jobId);

        assertThat(job.getStatus()).isEqualTo(AiAnalysisJobStatus.COMPLETED);
        assertThat(job.getProvider()).isEqualTo("MM_AI");
        assertThat(job.getResultPayload()).contains("Bearing 6204 ZZ");
    }

    @Test
    void workerCompletesNonMovingStockJobWithMmAiResult() throws Exception {
        UUID warehouseId = UUID.fromString("e0000000-0000-0000-0000-00000000000e");
        UUID jobId = UUID.fromString("f0000000-0000-0000-0000-00000000000f");
        var request = new AiAnalysisJobService.NonMovingStockAiRequest(null, null, warehouseId);
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.NON_MOVING_STOCK,
            "NON_MOVING_STOCK_SCOPE",
            warehouseId,
            "non-moving-request",
            "en",
            "buyer",
            objectMapper.writeValueAsString(request)
        );
        job.setId(jobId);
        JsonNode result = objectMapper.readTree("""
            {"summary":"Transfer slow stock first","risks":["expiry exposure"]}
            """);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(mmAiClient.analyzeNonMovingStock(request, "en")).thenReturn(result);

        AiAnalysisJobWorker worker = worker();
        worker.run(jobId);

        assertThat(job.getStatus()).isEqualTo(AiAnalysisJobStatus.COMPLETED);
        assertThat(job.getProvider()).isEqualTo("MM_AI");
        assertThat(job.getResultPayload()).contains("Transfer slow stock first");
    }

    @Test
    void workerStoresCompactMarketPriceResearchBusinessResult() throws Exception {
        UUID jobId = UUID.fromString("f1000000-0000-0000-0000-00000000001f");
        var request = new MarketPriceResearchAiRequest(
            MATERIAL_ID,
            "UZS",
            "4",
            null,
            null,
            null,
            null,
            true,
            true,
            10
        );
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.MARKET_PRICE_RESEARCH,
            "MATERIAL",
            MATERIAL_ID,
            "market-research-request",
            "en",
            "buyer",
            objectMapper.writeValueAsString(request)
        );
        job.setId(jobId);
        MarketPriceResearchResult result = new MarketPriceResearchResult(
            "OK",
            new BigDecimal("12500000.0000"),
            "UZS",
            "LOCAL_UZ",
            "MEDIUM",
            new MarketStatistics(
                new BigDecimal("12300000.0000"),
                new BigDecimal("12450000.0000"),
                new BigDecimal("11200000.0000"),
                new BigDecimal("13900000.0000"),
                7
            ),
            List.of(
                offer("Dell Latitude 5550 16/512 GB", "Prom.uz", "https://example.test/1", "12300000.0000", "0.97"),
                offer("Dell Latitude 5550", "Glotr.uz", "https://example.test/2", "12700000.0000", "0.95"),
                offer("Dell Latitude 5550 i5", "Shop.uz", "https://example.test/3", "12800000.0000", "0.93"),
                offer("Dell Latitude 5550 i7", "Market.uz", "https://example.test/4", "13200000.0000", "0.91"),
                offer("Dell Latitude 5550 32 GB", "Catalog.uz", "https://example.test/5", "13900000.0000", "0.90"),
                offer("Dell Latitude 5550 8 GB", "Listing.uz", "https://example.test/6", "11200000.0000", "0.89"),
                offer("Dell wireless mouse", "Prom.uz", "https://example.test/mouse", "150000.0000", "0.20")
            ),
            List.of(),
            new MarketPriceInternalComparison(4, new BigDecimal("11950000.0000"), new BigDecimal("12100000.0000"), new BigDecimal("3.3000")),
            List.of(
                new MarketPriceFinding("INFO", "LLM_USED", "Provider used an LLM"),
                new MarketPriceFinding("WARNING", "PRICE_SPREAD", "Bozor narxlari sezilarli darajada farq qiladi.")
            )
        );

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(mmAiClient.researchMarketPrice(request, "en")).thenReturn(result);

        worker().run(jobId);

        JsonNode payload = objectMapper.readTree(job.getResultPayload());
        assertThat(job.getResultPayload()).contains("\"recommendedUnitPrice\":12500000.0000");
        assertThat(payload.path("recommendedUnitPrice").decimalValue()).isEqualByComparingTo("12500000.0000");
        assertThat(payload.path("currencyCode").asText()).isEqualTo("UZS");
        assertThat(payload.path("market").path("sampleSize").asInt()).isEqualTo(7);
        assertThat(payload.path("offers")).hasSize(7);
        assertThat(payload.path("offers").findValuesAsText("title")).contains("Dell wireless mouse");
        assertThat(payload.path("offers").get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("12300000.0000");
        assertThat(payload.path("offers").get(0).has("convertedUnitPrice")).isFalse();
        assertThat(payload.path("offers").get(0).has("rawPrice")).isFalse();
        assertThat(payload.path("findings")).hasSize(2);
        assertThat(payload.path("findings").findValuesAsText("category")).contains("LLM_USED", "PRICE_SPREAD");
        assertThat(payload.path("internalComparison").path("sampleSize").asInt()).isEqualTo(4);
        assertThat(payload.has("suggestedUnitPrice")).isFalse();
        assertThat(payload.has("queries")).isFalse();
        assertThat(payload.has("llmUsed")).isFalse();
        assertThat(payload.has("unitConfidence")).isFalse();
        assertThat(payload.has("marketMedian")).isFalse();
        assertThat(payload.has("sampleSize")).isFalse();
        assertThat(payload.has("outliersRemoved")).isFalse();
        assertThat(payload.has("candidates")).isFalse();
        assertThat(payload.has("marketPriceDraft")).isFalse();
    }

    @Test
    void workerCompletesMarketPriceResearchJobWithTypedMmAiResult() throws Exception {
        UUID jobId = UUID.fromString("f1000000-0000-0000-0000-00000000000f");
        var request = new MarketPriceResearchAiRequest(
            MATERIAL_ID,
            "UZS",
            "4",
            null,
            null,
            null,
            null,
            true,
            true,
            10
        );
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.MARKET_PRICE_RESEARCH,
            "MATERIAL",
            MATERIAL_ID,
            "market-research-request",
            "en",
            "buyer",
            objectMapper.writeValueAsString(request)
        );
        job.setId(jobId);
        MarketPriceResearchResult result = objectMapper.readValue("""
            {
              "status":"OK",
              "recommendedUnitPrice":"125000.0000",
              "currencyCode":"UZS",
              "priceBasis":"LOCAL_UZ",
              "confidence":"MEDIUM",
              "market":{"median":"125000.0000","sampleSize":3},
              "findings":[{"severity":"INFO","category":"SOURCE","message":"Three usable offers"}]
            }
            """, MarketPriceResearchResult.class);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(mmAiClient.researchMarketPrice(request, "en")).thenReturn(result);

        worker().run(jobId);

        assertThat(job.getStatus()).isEqualTo(AiAnalysisJobStatus.COMPLETED);
        assertThat(job.getProvider()).isEqualTo("MM_AI");
        assertThat(job.getResultPayload()).contains("\"status\":\"OK\"");
        assertThat(job.getResultPayload()).contains("\"recommendedUnitPrice\":125000.0000");
        assertThat(job.getResultPayload()).doesNotContain("marketPriceDraft");
    }

    @Test
    void workerPreservesStructuredMmAiFailureCode() throws Exception {
        UUID rfqId = UUID.fromString("f2000000-0000-0000-0000-00000000000f");
        UUID jobId = UUID.fromString("f3000000-0000-0000-0000-00000000000f");
        AiAnalysisJob job = AiAnalysisJob.queued(
            "tenant-a",
            COMPANY_ID,
            AiAnalysisType.RFQ_QUOTATION_COMPARISON,
            "RFQ",
            rfqId,
            "rfq-request",
            "en",
            "buyer",
            objectMapper.writeValueAsString(new AiAnalysisJobService.RfqQuotationComparisonAiRequest(rfqId))
        );
        job.setId(jobId);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.findForUpdate(jobId)).thenReturn(Optional.of(job));
        when(mmAiClient.compareRfqQuotations(any(AiAnalysisJobService.RfqQuotationComparisonAiRequest.class), eq("en")))
            .thenThrow(new MmAiServiceException(422, "AMBIGUOUS_RFQ_REFERENCE", "More than one RFQ matched", "req-123"));

        worker().run(jobId);

        assertThat(job.getStatus()).isEqualTo(AiAnalysisJobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("AMBIGUOUS_RFQ_REFERENCE");
        assertThat(job.getErrorMessage()).contains("More than one RFQ matched");
        assertThat(job.getErrorMessage()).contains("422");
        assertThat(job.getErrorMessage()).contains("req-123");
    }

    private MarketComparableOffer offer(String title, String source, String sourceUrl, String unitPrice, String relevance) {
        return new MarketComparableOffer(title, source, sourceUrl, new BigDecimal(unitPrice), "UZS", new BigDecimal(relevance));
    }

    private PriceAiAnalyzeRequest priceRequest() {
        return new PriceAiAnalyzeRequest(
            MATERIAL_ID,
            null,
            null,
            BigDecimal.valueOf(150),
            BigDecimal.TEN,
            "UZS",
            "CHATGPT",
            20
        );
    }

    private AiAnalysisJobWorker worker() {
        return new AiAnalysisJobWorker(
            jobRepository,
            new AiAnalysisJobTransactionService(jobRepository),
            objectMapper,
            priceAiAnalysisService,
            mmAiClient,
            materialDuplicateCandidateService,
            org.mockito.Mockito.mock(com.enterprise.mm.service.InventoryAiDraftService.class),
            org.mockito.Mockito.mock(com.enterprise.mm.service.InventoryAiResultService.class),
            org.mockito.Mockito.mock(com.enterprise.mm.service.MaterialStockAccountRecommendationService.class)
        );
    }
}
