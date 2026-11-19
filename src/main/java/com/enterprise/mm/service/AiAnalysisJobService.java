package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.event.AiAnalysisJobCreatedEvent;
import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchAiRequest;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchJobRequest;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeRequest;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.service.inventory.InventoryAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiAnalysisJobService {
    public static final int MAX_MXIK_BATCH_SIZE = 25;

    private static final List<AiAnalysisJobStatus> ACTIVE_STATUSES = List.of(
        AiAnalysisJobStatus.QUEUED,
        AiAnalysisJobStatus.RUNNING
    );

    private final AiAnalysisJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final UnitOfMeasureDisplayService unitDisplay;
    private final InventoryAiDraftService inventoryDrafts;
    private final InventoryAiResultService inventoryResults;
    private final InventoryAccessService inventoryAccess;
    private final MaterialStockAccountRecommendationService stockAccountRecommendations;

    @Transactional
    public AiAnalysisJobResponse startMaterialStockAccountJob(UUID materialId, String requestedLanguage) {
        return startMaterialStockAccountJob(materialId, null, requestedLanguage);
    }

    @Transactional
    public AiAnalysisJobResponse startMaterialStockAccountJob(UUID materialId, UUID draftId, String requestedLanguage) {
        var request = stockAccountRecommendations.snapshot(materialId, requestedBy(), draftId);
        return startJob(AiAnalysisType.MATERIAL_STOCK_ACCOUNT_RECOMMENDATION,
            "MATERIAL", materialId, request, requestedLanguage);
    }

    @Transactional
    public AiAnalysisJobResponse startInventoryDraftJob(UUID draftId, String requestedLanguage) {
        return startInventoryDraftJob(draftId, requestedLanguage, false);
    }

    @Transactional
    public AiAnalysisJobResponse startInventoryDraftJob(UUID draftId, String requestedLanguage, boolean orderAnalysis) {
        InventoryAiDraftService.AnalysisRequest request = inventoryDrafts.snapshot(draftId, requestedBy(), orderAnalysis);
        return startInventoryDraftJobWithRequest(draftId, requestedLanguage, request);
    }

    @Transactional
    public AiAnalysisJobResponse startScopedInventoryOrderJob(UUID draftId, String requestedLanguage) {
        return startInventoryDraftJobWithRequest(draftId, requestedLanguage,
            inventoryDrafts.snapshotScopedOrder(draftId, requestedBy()));
    }

    private AiAnalysisJobResponse startInventoryDraftJobWithRequest(UUID draftId, String requestedLanguage,
        InventoryAiDraftService.AnalysisRequest request) {
        String language = normalizedInventoryLanguage(requestedLanguage);
        List<AiAnalysisJob> equivalent = jobRepository.findEquivalentInventoryJobs(
            TenantContext.currentTenant(), CompanyContext.currentCompany(),
            AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION, InventoryAiDraftService.ENTITY_TYPE,
            draftId, requestHash(request), language, PageRequest.of(0, 1));
        if (!equivalent.isEmpty()) {
            AiAnalysisJob job = equivalent.get(0);
            if (job.getStatus() == AiAnalysisJobStatus.FAILED) {
                job.setStatus(AiAnalysisJobStatus.QUEUED);
                job.setStartedAt(null);
                job.setCompletedAt(null);
                job.setErrorCode(null);
                job.setErrorMessage(null);
                jobRepository.save(job);
            }
            if (job.getStatus() == AiAnalysisJobStatus.QUEUED) {
                eventPublisher.publishEvent(new AiAnalysisJobCreatedEvent(job.getId(), job.getAnalysisType()));
            }
            return toResponse(job);
        }
        return startJob(AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION, InventoryAiDraftService.ENTITY_TYPE,
            draftId, request, language);
    }

    @Transactional
    public AiAnalysisJobResponse startMxikMatchJob(UUID materialId, String requestedLanguage) {
        if (materialId == null) {
            throw new BadRequestException("materialId is required for MXIK recommendation");
        }
        return startJob(AiAnalysisType.MATERIAL_MXIK_MATCH, "MATERIAL", materialId,
            new MxikMatchRequest(materialId), requestedLanguage);
    }

    public record MxikMatchRequest(UUID materialId) {}

    @Transactional
    public AiAnalysisJobResponse startMxikMatchBatchJob(MxikBatchRequest request, String language) {
        if (request == null || !"SELECTED".equals(request.scope())) {
            throw new BadRequestException("Only selected-material MXIK recommendations are supported");
        }
        if (request.materialIds() == null
            || request.materialIds().isEmpty() || request.materialIds().size() > MAX_MXIK_BATCH_SIZE
            || request.materialIds().stream().anyMatch(java.util.Objects::isNull)
            || request.materialIds().stream().distinct().count() != request.materialIds().size()) {
            throw new BadRequestException("Select between 1 and " + MAX_MXIK_BATCH_SIZE + " materials");
        }
        return startJob(AiAnalysisType.MATERIAL_MXIK_MATCH_BATCH, "MATERIAL_CATALOG", null,
            request, language);
    }

    public record MxikBatchRequest(String scope, List<UUID> materialIds, Boolean missingOnly, String search) {}

    @Transactional
    public AiAnalysisJobResponse startPriceJob(PriceAiAnalyzeRequest request, String requestedLanguage) {
        if (request == null || request.materialId() == null) {
            throw new BadRequestException("materialId is required for price AI analysis");
        }
        return startJob(
            AiAnalysisType.PRICE_INTELLIGENCE,
            "MATERIAL",
            request.materialId(),
            request,
            requestedLanguage
        );
    }

    @Transactional
    public AiAnalysisJobResponse startRfqQuotationComparisonJob(UUID rfqId, String requestedLanguage) {
        if (rfqId == null) {
            throw new BadRequestException("rfqId is required for RFQ AI analysis");
        }
        return startJob(
            AiAnalysisType.RFQ_QUOTATION_COMPARISON,
            "RFQ",
            rfqId,
            new RfqQuotationComparisonAiRequest(rfqId),
            requestedLanguage
        );
    }

    @Transactional
    public AiAnalysisJobResponse startMaterialDedupScanJob(String requestedLanguage) {
        return startJob(
            AiAnalysisType.MATERIAL_DEDUP_SCAN,
            "MATERIAL_CATALOG",
            null,
            new MaterialDedupScanAiRequest(),
            requestedLanguage
        );
    }

    @Transactional
    public AiAnalysisJobResponse startMaterialDedupSearchJob(UUID materialId, String materialName, String requestedLanguage) {
        if (materialId == null) {
            throw new BadRequestException("materialId is required for material duplicate AI search");
        }
        if (materialName == null || materialName.isBlank()) {
            throw new BadRequestException("materialName is required for material duplicate AI search");
        }
        return startJob(
            AiAnalysisType.MATERIAL_DEDUP_SEARCH,
            "MATERIAL",
            materialId,
            new MaterialDedupSearchAiRequest(materialId, materialName.trim()),
            requestedLanguage
        );
    }

    @Transactional
    public AiAnalysisJobResponse startMaterialDedupNameSearchJob(String materialName, String requestedLanguage) {
        return startMaterialDedupNameSearchJob(materialName, null, null, null, null, requestedLanguage);
    }

    @Transactional
    public AiAnalysisJobResponse startMaterialDedupNameSearchJob(
        String materialName,
        UUID departmentId,
        UUID materialGroupId,
        Integer limit,
        Boolean includeInactive,
        String requestedLanguage
    ) {
        if (materialName == null || materialName.isBlank()) {
            throw new BadRequestException("materialName is required for material duplicate AI search");
        }
        return startJob(
            AiAnalysisType.MATERIAL_DEDUP_SEARCH,
            "MATERIAL_DRAFT",
            null,
            new MaterialDedupSearchAiRequest(
                null,
                materialName.trim(),
                departmentId,
                CompanyContext.currentCompany(),
                materialGroupId,
                limit == null || limit <= 0 ? 10 : limit,
                includeInactive != null && includeInactive
            ),
            requestedLanguage
        );
    }

    @Transactional
    public AiAnalysisJobResponse startNonMovingStockJob(
        UUID materialGroupId,
        UUID departmentId,
        UUID warehouseId,
        String requestedLanguage
    ) {
        var request = new NonMovingStockAiRequest(materialGroupId, departmentId, warehouseId);
        return startJob(
            AiAnalysisType.NON_MOVING_STOCK,
            "NON_MOVING_STOCK_SCOPE",
            nonMovingStockScopeId(request),
            request,
            requestedLanguage
        );
    }

    @Transactional
    public AiAnalysisJobResponse startMarketPriceResearchJob(
        MarketPriceResearchJobRequest request,
        String requestedLanguage
    ) {
        if (request == null || request.materialId() == null) {
            throw new BadRequestException("materialId is required for market price research");
        }
        MarketPriceResearchAiRequest aiRequest = new MarketPriceResearchAiRequest(
            request.materialId(),
            normalizeCurrency(request.currencyCode()),
            trimToNull(request.quantity()),
            trimToNull(request.materialName()),
            trimToNull(request.manufacturer()),
            trimToNull(request.article()),
            trimToNull(request.unitOfMeasureCode()),
            request.includeLocal() == null ? Boolean.TRUE : request.includeLocal(),
            request.includeGlobal() == null ? Boolean.TRUE : request.includeGlobal(),
            request.maxOffers() == null || request.maxOffers() <= 0 ? 10 : request.maxOffers()
        );
        return startJob(
            AiAnalysisType.MARKET_PRICE_RESEARCH,
            "MATERIAL",
            request.materialId(),
            aiRequest,
            requestedLanguage
        );
    }

    public record RfqQuotationComparisonAiRequest(
        UUID rfqId,
        String referenceCurrency,
        Boolean includeTaxInComparison,
        java.util.List<RfqExchangeRate> exchangeRates
    ) {
        public RfqQuotationComparisonAiRequest(UUID rfqId) {
            this(rfqId, null, null, java.util.List.of());
        }
    }

    public record RfqExchangeRate(String fromCurrency, String toCurrency, String rate) {
    }

    public record MaterialDedupScanAiRequest() {
    }

    public record MaterialDedupSearchAiRequest(
        UUID materialId,
        String name,
        UUID departmentId,
        UUID companyId,
        UUID materialGroupId,
        Integer limit,
        Boolean includeInactive
    ) {
        public MaterialDedupSearchAiRequest(UUID materialId, String name) {
            this(materialId, name, null, CompanyContext.currentCompany(), null, 10, false);
        }
    }

    public record NonMovingStockAiRequest(UUID materialGroupId, UUID departmentId, UUID warehouseId) {
    }

    @Transactional(readOnly = true)
    public AiAnalysisJobResponse getJob(UUID jobId) {
        return toResponse(findScoped(jobId));
    }

    @Transactional(readOnly = true)
    public AiAnalysisJobResponse latest(AiAnalysisType analysisType, String entityType, UUID entityId) {
        if (analysisType == AiAnalysisType.MATERIAL_STOCK_ACCOUNT_RECOMMENDATION)
            throw new BadRequestException("Read stock account recommendations by job ID");
        String normalizedEntityType = normalizedEntityType(entityType);
        if (analysisType == AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION) {
            if (!InventoryAiDraftService.ENTITY_TYPE.equals(normalizedEntityType) || entityId == null) {
                throw new BadRequestException("Inventory AI draft ID is required");
            }
            inventoryDrafts.requireOwner(entityId, requestedBy());
        }
        return jobRepository.findLatestForEntity(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                analysisType,
                normalizedEntityType,
                entityId,
                PageRequest.of(0, 1)
            )
            .stream()
            .findFirst()
            .map(job -> {
                requireScopedInventoryResult(job);
                return toResponse(job);
            })
            .orElseGet(() -> notRequestedResponse(analysisType, normalizedEntityType, entityId));
    }

    public String requestHash(Object request) {
        return sha256(serializeRequest(request));
    }

    public String serializeRequest(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("AI analysis request cannot be serialized");
        }
    }

    private AiAnalysisJobResponse startJob(
        AiAnalysisType analysisType,
        String entityType,
        UUID entityId,
        Object request,
        String requestedLanguage
    ) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        String language = analysisType == AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION
            ? normalizedInventoryLanguage(requestedLanguage) : normalizedLanguage(requestedLanguage);
        String payload = serializeRequest(request);
        String hash = sha256(payload);
        String normalizedEntityType = normalizedEntityType(entityType);

        var existing = jobRepository.findActiveEquivalent(
            tenantId,
            companyId,
            analysisType,
            normalizedEntityType,
            entityId,
            hash,
            language,
            ACTIVE_STATUSES
        );
        if (existing.isPresent()) {
            if (existing.get().getStatus() == AiAnalysisJobStatus.QUEUED) {
                eventPublisher.publishEvent(new AiAnalysisJobCreatedEvent(existing.get().getId(), existing.get().getAnalysisType()));
            }
            return toResponse(existing.get());
        }

        AiAnalysisJob job = AiAnalysisJob.queued(
            tenantId,
            companyId,
            analysisType,
            normalizedEntityType,
            entityId,
            hash,
            language,
            requestedBy(),
            payload
        );
        AiAnalysisJob saved = jobRepository.save(job);
        eventPublisher.publishEvent(new AiAnalysisJobCreatedEvent(saved.getId(), saved.getAnalysisType()));
        return toResponse(saved);
    }

    private AiAnalysisJob findScoped(UUID jobId) {
        AiAnalysisJob job = jobRepository.findScoped(jobId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("AI analysis job not found"));
        if (job.getAnalysisType() == AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION) {
            inventoryDrafts.requireOwner(job.getEntityId(), requestedBy());
            requireScopedInventoryResult(job);
        }
        if (job.getAnalysisType() == AiAnalysisType.MATERIAL_STOCK_ACCOUNT_RECOMMENDATION
            && !requestedBy().equals(job.getRequestedBy()))
            throw new NotFoundException("AI analysis job not found");
        return job;
    }

    private void requireScopedInventoryResult(AiAnalysisJob job) {
        if (job.getAnalysisType() != AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION
            || job.getResultPayload() == null || inventoryAccess.hasRole("ADMIN")
            || !inventoryAccess.hasRole("DEPARTMENT_HEAD")) return;
        var request = readJson(job.getRequestPayload());
        if (!(request instanceof com.fasterxml.jackson.databind.JsonNode node)
            || node.path("orderAnalysisVersion").asInt() != 2)
            throw new org.springframework.security.access.AccessDeniedException(
                "Unscoped inventory AI results are not available to department heads");
    }

    AiAnalysisJobResponse toResponse(AiAnalysisJob job) {
        Object result = readJson(job.getResultPayload());
        if (job.getAnalysisType() == AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION && result instanceof com.fasterxml.jackson.databind.JsonNode node) {
            var resolved = inventoryResults.resolve(node);
            if (resolved.path("orderAnalysis").path("schemaVersion").asInt() == 2
                && resolved instanceof com.fasterxml.jackson.databind.node.ObjectNode root) {
                root = root.deepCopy();
                var order = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("orderAnalysis");
                for (String section : List.of("chairAssignments", "workItems", "commissionRosters")) {
                    order.put(section + "Count", order.path(section).size());
                    order.putArray(section);
                }
                result = root;
            } else result = resolved;
        }
        return new AiAnalysisJobResponse(
            job.getId(),
            job.getAnalysisType() == null ? null : job.getAnalysisType().name(),
            job.getEntityType(),
            job.getEntityId(),
            job.getStatus() == null ? null : job.getStatus().name(),
            job.getRequestedBy(),
            job.getRequestedLanguage(),
            job.getRequestHash(),
            job.getAnalysisType() == AiAnalysisType.MARKET_PRICE_RESEARCH
                ? unitDisplay.marketPriceResult(result, job.getTenantId(), job.getCompanyId())
                : result,
            job.getErrorCode(),
            job.getErrorMessage(),
            job.getProvider(),
            job.getModel(),
            job.getModelVersion(),
            job.getRequestedAt(),
            job.getStartedAt(),
            job.getCompletedAt(),
            job.getUpdatedAt()
        );
    }

    private AiAnalysisJobResponse notRequestedResponse(AiAnalysisType analysisType, String entityType, UUID entityId) {
        return new AiAnalysisJobResponse(
            null,
            analysisType == null ? null : analysisType.name(),
            entityType,
            entityId,
            "NOT_REQUESTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private Object readJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            return payload;
        }
    }

    private String requestedBy() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null || authentication.getName().isBlank()
            ? "system"
            : authentication.getName();
    }

    private String normalizedLanguage(String value) {
        if (value == null || value.isBlank()) {
            return "ru";
        }
        String language = value.trim().toLowerCase(Locale.ROOT);
        int separator = language.indexOf(',');
        if (separator >= 0) {
            language = language.substring(0, separator).trim();
        }
        if (language.startsWith("uz")) {
            return "uz";
        }
        if (language.startsWith("en")) {
            return "en";
        }
        return "ru";
    }

    private String normalizedInventoryLanguage(String value) {
        if (value == null || value.isBlank()) return "uz";
        String language = value.split("[,;]", 2)[0].trim();
        if (language.equalsIgnoreCase("uz-Latn")) return "uz-Latn";
        if (language.equalsIgnoreCase("uz-Cyrl")) return "uz-Cyrl";
        if (language.equalsIgnoreCase("ru") || language.toLowerCase(Locale.ROOT).startsWith("ru-")) return "ru";
        return "uz";
    }

    private String normalizedEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            throw new BadRequestException("entityType is required");
        }
        return entityType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private UUID nonMovingStockScopeId(NonMovingStockAiRequest request) {
        if (request.warehouseId() != null) {
            return request.warehouseId();
        }
        if (request.materialGroupId() != null) {
            return request.materialGroupId();
        }
        return request.departmentId();
    }

    private String normalizeCurrency(String value) {
        String currency = trimToNull(value);
        return currency == null ? "UZS" : currency.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
