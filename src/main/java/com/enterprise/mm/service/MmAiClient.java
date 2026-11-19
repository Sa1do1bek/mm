package com.enterprise.mm.service;

import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchAiRequest;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchResult;
import com.enterprise.mm.payload.MaterialDedupDtos.MaterialDedupResult;
import com.enterprise.mm.payload.RfqComparisonDtos.RfqComparisonResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public interface MmAiClient {
    default RfqComparisonResult compareRfqQuotations(UUID rfqId, String language) {
        return compareRfqQuotations(new AiAnalysisJobService.RfqQuotationComparisonAiRequest(rfqId), language);
    }

    RfqComparisonResult compareRfqQuotations(AiAnalysisJobService.RfqQuotationComparisonAiRequest request, String language);

    MaterialDedupResult searchMaterialDuplicates(AiAnalysisJobService.MaterialDedupSearchAiRequest request, String language);

    MaterialDedupResult scanMaterialDuplicates(String language);

    JsonNode analyzeNonMovingStock(AiAnalysisJobService.NonMovingStockAiRequest request, String language);

    MarketPriceResearchResult researchMarketPrice(MarketPriceResearchAiRequest request, String language);

    JsonNode recommendMxik(UUID materialId, String language);

    JsonNode recommendMaterialStockAccount(JsonNode context, String language);

    JsonNode extractInventoryDocument(UUID analysisId,
        java.util.List<InventoryAiDraftService.SourceFile> files, String language);

    default JsonNode extractInventoryDocument(UUID analysisId,
        java.util.List<InventoryAiDraftService.SourceFile> files, String language, int commissionAnalysisVersion) {
        return extractInventoryDocument(analysisId, files, language);
    }

    default JsonNode extractInventoryDocument(UUID analysisId,
        java.util.List<InventoryAiDraftService.SourceFile> files, String language, int commissionAnalysisVersion,
        int orderAnalysisVersion) {
        return extractInventoryDocument(analysisId, files, language, commissionAnalysisVersion);
    }

    default JsonNode extractInventoryDocument(UUID analysisId,
        java.util.List<InventoryAiDraftService.SourceFile> files, String language, int commissionAnalysisVersion,
        int orderAnalysisVersion, InventoryOrderAnalysisScopeService.Scope scope) {
        return extractInventoryDocument(analysisId, files, language, commissionAnalysisVersion, orderAnalysisVersion);
    }

    /** Server-built receipt V2 snapshot; never a browser-supplied context. */
    JsonNode suggestReceiptAdvice(JsonNode backendContext, String language);

    boolean isConfigured();

    boolean isReceiptAdviceConfigured();

    JsonNode copilotMessage(JsonNode request);

    JsonNode diagnostics();
}
