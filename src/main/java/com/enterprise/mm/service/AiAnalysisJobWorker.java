package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeRequest;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeResponse;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchAiRequest;
import com.fasterxml.jackson.core.JsonGenerator;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiAnalysisJobWorker {
    private static final int INVALID_INVENTORY_RESPONSE_LOG_CHUNK_CHARS = 8_000;
    private static final int INVALID_INVENTORY_RESPONSE_LOG_MAX_CHARS = 4_000_000;
    private final AiAnalysisJobRepository jobRepository;
    private final AiAnalysisJobTransactionService transactionService;
    private final ObjectMapper objectMapper;
    private final PriceAiAnalysisService priceAiAnalysisService;
    private final MmAiClient mmAiClient;
    private final MaterialDuplicateCandidateService materialDuplicateCandidateService;
    private final InventoryAiDraftService inventoryDrafts;
    private final InventoryAiResultService inventoryResults;
    private final MaterialStockAccountRecommendationService stockAccountRecommendations;

    @Async("aiAnalysisJobExecutor")
    public void run(UUID jobId) {
        runInternal(jobId);
    }

    @Async("mxikAnalysisJobExecutor")
    public void runMxik(UUID jobId) {
        runInternal(jobId);
    }

    private void runInternal(UUID jobId) {
        try {
            AiAnalysisJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            String jobCorrelationId = job.getId() == null ? jobId.toString() : job.getId().toString();
            MDC.put("requestId", jobCorrelationId);
            MDC.put("aiJobId", jobCorrelationId);
            TenantContext.setTenant(job.getTenantId());
            CompanyContext.setCompany(job.getCompanyId());
            if (!transactionService.markRunning(jobId)) {
                log.info("Skipping AI analysis job because it is no longer queued: jobId={}", jobId);
                return;
            }
            execute(job);
        } catch (MmAiServiceException ex) {
            log.error("AI analysis job failed from upstream MM AI service: jobId={} errorCode={}", jobId, ex.errorCode(), ex);
            try {
                transactionService.markFailed(jobId, ex.errorCode(), ex.failureMessage());
            } catch (RuntimeException ignored) {
                // Preserve context cleanup even if failure persistence is unavailable.
            }
        } catch (RuntimeException ex) {
            log.error("AI analysis job failed: jobId={}", jobId, ex);
            try {
                transactionService.markFailed(jobId, "AI_JOB_FAILED", ex.getMessage());
            } catch (RuntimeException ignored) {
                // Preserve context cleanup even if failure persistence is unavailable.
            }
        } finally {
            MDC.remove("aiJobId");
            MDC.remove("requestId");
            TenantContext.clear();
            CompanyContext.clear();
        }
    }

    private void execute(AiAnalysisJob job) {
        if (job.getAnalysisType() == AiAnalysisType.MATERIAL_STOCK_ACCOUNT_RECOMMENDATION) {
            var request = readRequest(job, MaterialStockAccountRecommendationService.Request.class);
            var context = request.context().deepCopy();
            if (context instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.put("jobId", job.getId().toString());
                object.put("language", job.getRequestedLanguage() == null || job.getRequestedLanguage().isBlank()
                    ? "uz" : job.getRequestedLanguage());
            }
            var raw = mmAiClient.recommendMaterialStockAccount(context, job.getRequestedLanguage());
            var result = stockAccountRecommendations.validate(job.getId(), request, raw);
            transactionService.markCompleted(job.getId(), "MM_AI", null, null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION) {
            var request = readRequest(job, InventoryAiDraftService.AnalysisRequest.class);
            var files = inventoryDrafts.readSnapshot(request, job.getRequestedBy());
            var raw = request.orderAnalysisVersion() == 2
                ? mmAiClient.extractInventoryDocument(job.getId(), files, job.getRequestedLanguage(),
                    request.commissionAnalysisVersion(), request.orderAnalysisVersion(), request.scope())
                : request.orderAnalysisVersion() == 1
                ? mmAiClient.extractInventoryDocument(job.getId(), files, job.getRequestedLanguage(),
                    request.commissionAnalysisVersion(), request.orderAnalysisVersion())
                : mmAiClient.extractInventoryDocument(job.getId(), files, job.getRequestedLanguage(),
                    request.commissionAnalysisVersion());
            com.fasterxml.jackson.databind.node.ObjectNode result;
            try {
                result = (com.fasterxml.jackson.databind.node.ObjectNode) inventoryResults.validate(raw, job.getId(), request);
            } catch (RuntimeException ex) {
                logRejectedInventoryResponse(job.getId(), raw, ex);
                throw ex;
            }
            var analyzedFiles = result.putArray("analyzedFileIds");
            request.documents().forEach(file -> analyzedFiles.add(file.fileId().toString()));
            transactionService.markCompleted(job.getId(), "MM_AI", result.path("model").asText(null), null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.PRICE_INTELLIGENCE) {
            PriceAiAnalyzeRequest request = readRequest(job, PriceAiAnalyzeRequest.class);
            PriceAiAnalyzeResponse result = priceAiAnalysisService.analyze(request);
            transactionService.markCompleted(job.getId(), result.providerSystem(), result.providerModel(), null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.RFQ_QUOTATION_COMPARISON) {
            AiAnalysisJobService.RfqQuotationComparisonAiRequest request =
                readRequest(job, AiAnalysisJobService.RfqQuotationComparisonAiRequest.class);
            var result = mmAiClient.compareRfqQuotations(request, job.getRequestedLanguage());
            transactionService.markCompleted(job.getId(), "MM_AI", null, null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.MATERIAL_DEDUP_SCAN) {
            var result = mmAiClient.scanMaterialDuplicates(job.getRequestedLanguage());
            materialDuplicateCandidateService.importAiScanResult(job.getId(), result);
            transactionService.markCompleted(job.getId(), "MM_AI", null, null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.MATERIAL_DEDUP_SEARCH) {
            AiAnalysisJobService.MaterialDedupSearchAiRequest request =
                readRequest(job, AiAnalysisJobService.MaterialDedupSearchAiRequest.class);
            var result = mmAiClient.searchMaterialDuplicates(request, job.getRequestedLanguage());
            transactionService.markCompleted(job.getId(), "MM_AI", null, null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.NON_MOVING_STOCK) {
            AiAnalysisJobService.NonMovingStockAiRequest request =
                readRequest(job, AiAnalysisJobService.NonMovingStockAiRequest.class);
            var result = mmAiClient.analyzeNonMovingStock(request, job.getRequestedLanguage());
            transactionService.markCompleted(job.getId(), "MM_AI", null, null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.MARKET_PRICE_RESEARCH) {
            MarketPriceResearchAiRequest request = readRequest(job, MarketPriceResearchAiRequest.class);
            var result = mmAiClient.researchMarketPrice(request, job.getRequestedLanguage());
            transactionService.markCompleted(job.getId(), "MM_AI", null, null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.MATERIAL_MXIK_MATCH) {
            var request = readRequest(job, AiAnalysisJobService.MxikMatchRequest.class);
            var result = recommendMxik(request.materialId(), job.getRequestedLanguage());
            transactionService.markCompleted(job.getId(), "MM_AI", null, null, writeJson(result));
            return;
        }
        if (job.getAnalysisType() == AiAnalysisType.MATERIAL_MXIK_MATCH_BATCH) {
            var request = readRequest(job, AiAnalysisJobService.MxikBatchRequest.class);
            int completed = 0;
            int failed = 0;
            if (!"SELECTED".equals(request.scope()) || request.materialIds() == null
                || request.materialIds().isEmpty()
                || request.materialIds().size() > AiAnalysisJobService.MAX_MXIK_BATCH_SIZE) {
                throw new IllegalArgumentException("Unsupported MXIK batch scope");
            }
            for (UUID materialId : request.materialIds()) {
                if (runMxikChild(job, materialId)) completed++; else failed++;
            }
            transactionService.markCompleted(job.getId(), "MM_AI", null, null,
                writeJson(java.util.Map.of("completed", completed, "failed", failed)));
            return;
        }
        transactionService.markFailed(job.getId(), "UNSUPPORTED_ANALYSIS_TYPE", "Unsupported AI analysis type: " + job.getAnalysisType());
    }

    private void logRejectedInventoryResponse(UUID analysisId, JsonNode raw, RuntimeException error) {
        String payload = raw == null ? "null" : raw.toString();
        int loggedChars = Math.min(payload.length(), INVALID_INVENTORY_RESPONSE_LOG_MAX_CHARS);
        if (loggedChars < payload.length() && Character.isHighSurrogate(payload.charAt(loggedChars - 1)))
            loggedChars--;
        List<Integer> boundaries = new ArrayList<>();
        for (int offset = 0; offset < loggedChars;) {
            int end = Math.min(offset + INVALID_INVENTORY_RESPONSE_LOG_CHUNK_CHARS, loggedChars);
            if (end < loggedChars && Character.isHighSurrogate(payload.charAt(end - 1))) end--;
            boundaries.add(end);
            offset = end;
        }
        JsonNode warnings = raw == null ? null : raw.path("warnings");
        log.error("Rejected MM AI inventory response: analysisId={} reason={} responseChars={} loggedChars={} "
                + "warningsType={} warningsCount={} chunks={}", analysisId, error.getMessage(), payload.length(),
            loggedChars, warnings == null ? "missing" : warnings.getNodeType(),
            warnings != null && warnings.isArray() ? warnings.size() : null, boundaries.size());
        for (int part = 0, offset = 0; part < boundaries.size(); part++) {
            int end = boundaries.get(part);
            log.error("Rejected MM AI inventory response payload: analysisId={} part={}/{} json={}",
                analysisId, part + 1, boundaries.size(), payload.substring(offset, end));
            offset = end;
        }
    }

    private JsonNode recommendMxik(UUID materialId, String language) {
        var result = mmAiClient.recommendMxik(materialId, language);
        if (result == null || !materialId.toString().equals(result.path("materialId").asText())
            || !("RECOMMENDED".equals(result.path("status").asText())
                || "MANUAL_REVIEW_REQUIRED".equals(result.path("status").asText()))) {
            throw new IllegalArgumentException("MM AI MXIK response does not match the requested material");
        }
        return result;
    }

    private boolean runMxikChild(AiAnalysisJob parent, UUID materialId) {
        UUID childId = transactionService.createMxikChild(parent, materialId);
        if (!transactionService.markRunning(childId)) {
            return false;
        }
        String previousRequestId = MDC.get("requestId");
        String previousAiJobId = MDC.get("aiJobId");
        MDC.put("requestId", childId.toString());
        MDC.put("aiJobId", childId.toString());
        MDC.put("aiBatchJobId", parent.getId().toString());
        try {
            var result = recommendMxik(materialId, parent.getRequestedLanguage());
            transactionService.markCompleted(childId, "MM_AI", null, null, writeJson(result));
            return true;
        } catch (MmAiServiceException ex) {
            transactionService.markFailed(childId, ex.errorCode(), ex.failureMessage());
        } catch (RuntimeException ex) {
            transactionService.markFailed(childId, "AI_JOB_FAILED", ex.getMessage());
        } finally {
            restoreMdc("requestId", previousRequestId);
            restoreMdc("aiJobId", previousAiJobId);
            MDC.remove("aiBatchJobId");
        }
        return false;
    }

    private void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private <T> T readRequest(AiAnalysisJob job, Class<T> type) {
        try {
            return objectMapper.readValue(job.getRequestPayload(), type);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("AI analysis request payload is invalid", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.copy()
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("AI analysis result cannot be serialized", ex);
        }
    }
}
