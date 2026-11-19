package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptResponse;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiAnalyzeRequest;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiAnalyzeResponse;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiApplyRequest;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiApplyResponse;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingBulkFailure;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiRelationDto;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiSuggestionDto;
import com.enterprise.mm.payload.ReferenceMappingResponse;
import com.enterprise.mm.payload.ReferenceMappingUpdateRequest;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ReferenceMappingMapper;
import com.enterprise.mm.service.ReferenceMappingMetadata.ReferenceType;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.repository.ReferenceMappingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReferenceMappingAiService {
    private static final Set<String> AI_PROVIDER_SYSTEMS = Set.of("CHATGPT", "CLAUDE", "GEMINI");
    private static final Map<String, ReferenceType> REFERENCE_TYPES = ReferenceMappingMetadata.referenceTypes();
    private final ReferenceMappingRepository referenceMappingRepository;
    private final ReferenceMappingMapper referenceMappingMapper;
    private final ReferenceMappingService referenceMappingService;
    private final ObjectProvider<IntegrationSettingService> integrationSettingServiceProvider;
    private final ObjectMapper objectMapper;
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public ReferenceMappingAiAnalyzeResponse analyzeWithAi(ReferenceMappingAiAnalyzeRequest request) {
        if (request == null) {
            throw new BadRequestException("Параметры AI-анализа обязательны");
        }
        String typeCode = requiredCode(request.referenceEntityType(), "Тип НСИ обязателен");
        ReferenceType type = requireKnownReferenceType(typeCode);
        String system = requiredCode(request.externalSystem(), "Внешняя система обязательна");
        String area = requiredCode(blank(request.accountingArea()) ? "MANAGEMENT" : request.accountingArea(), "Область учета обязательна");
        String provider = aiProvider(request.providerSystem());
        boolean onlyUnmapped = request.onlyUnmapped() == null || request.onlyUnmapped();
        int limit = limit(request.limit());
        List<ReferenceCandidate> candidates = new ArrayList<>();
        for (Object reference : referenceRows(type)) {
            UUID referenceId = referenceId(reference);
            String referenceCode = referenceCode(reference, type);
            String referenceName = referenceName(reference);
            Optional<ReferenceMapping> existing = findExactMapping(typeCode, referenceId, referenceCode, system, area);
            if (onlyUnmapped && existing.isPresent()) {
                continue;
            }
            candidates.add(new ReferenceCandidate(typeCode, referenceId, referenceCode, referenceName, externalCode(reference, type), reference, existing.orElse(null)));
            if (candidates.size() >= limit) {
                break;
            }
        }
        List<ReferenceMappingAiSuggestionDto> ruleSuggestions = candidates.stream()
            .map(candidate -> ruleSuggestion(candidate, system, area))
            .toList();
        List<ReferenceMappingAiRelationDto> relations = relationHints(candidates);
        AiPromptResponse ai = runAiReferenceAnalysis(provider, typeCode, system, area, candidates, ruleSuggestions, relations);
        List<ReferenceMappingAiSuggestionDto> suggestions = mergeAiSuggestions(ruleSuggestions, ai == null ? null : ai.response());
        return new ReferenceMappingAiAnalyzeResponse(
            provider,
            ai == null ? "DISABLED" : ai.status(),
            ai == null ? "AI provider service is not available" : ai.message(),
            ai == null ? null : ai.model(),
            typeCode,
            system,
            area,
            candidates.size(),
            suggestions,
            relations,
            LocalDateTime.now()
        );
    }
    public ReferenceMappingAiApplyResponse applyAiSuggestions(ReferenceMappingAiApplyRequest request) {
        if (request == null) {
            throw new BadRequestException("Параметры применения AI-предложений обязательны");
        }
        String typeCode = requiredCode(request.referenceEntityType(), "Тип НСИ обязателен");
        requireKnownReferenceType(typeCode);
        String system = requiredCode(request.externalSystem(), "Внешняя система обязательна");
        String area = requiredCode(blank(request.accountingArea()) ? "MANAGEMENT" : request.accountingArea(), "Область учета обязательна");
        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<ReferenceMappingBulkFailure> failures = new ArrayList<>();
        List<ReferenceMappingResponse> savedMappings = new ArrayList<>();
        for (int index = 0; index < request.suggestions().size(); index++) {
            ReferenceMappingAiSuggestionDto suggestion = request.suggestions().get(index);
            if (suggestion == null || blank(suggestion.externalCode())) {
                skipped++;
                failures.add(new ReferenceMappingBulkFailure(
                    suggestionKey(typeCode, suggestion, index),
                    suggestion == null ? null : suggestion.referenceId(),
                    suggestion == null ? null : suggestion.referenceCode(),
                    suggestion == null ? null : suggestion.externalCode(),
                    "External mapping code is required"
                ));
                continue;
            }
            try {
                Optional<Object> reference = referenceFromSuggestion(typeCode, suggestion);
                String referenceCode = firstNonBlank(suggestion.referenceCode(), reference.map(value -> referenceCode(value, requireKnownReferenceType(typeCode))).orElse(null));
                Optional<ReferenceMapping> existing = findExactMapping(typeCode, suggestion.referenceId(), referenceCode, system, area);
                ReferenceMappingUpdateRequest dto = new ReferenceMappingUpdateRequest();
                existing.map(referenceMappingMapper::toResponse)
                    .map(ReferenceMappingResponse::getStatus)
                    .ifPresent(dto::setStatus);
                dto.setReferenceEntityType(typeCode);
                dto.setReferenceId(suggestion.referenceId());
                dto.setReferenceCode(referenceCode);
                dto.setReferenceName(firstNonBlank(suggestion.referenceName(), reference.map(this::referenceName).orElse(null)));
                dto.setExternalSystem(system);
                dto.setExternalCode(suggestion.externalCode());
                dto.setExternalName(firstNonBlank(suggestion.externalName(), dto.getReferenceName()));
                dto.setAccountingArea(area);
                dto.setChartOfAccountsCode(suggestion.chartOfAccountsCode());
                dto.setAccountCode(suggestion.accountCode());
                dto.setSubAccountCode(suggestion.subAccountCode());
                dto.setCostCenter(suggestion.costCenter());
                dto.setProfitCenter(suggestion.profitCenter());
                dto.setWarehouseAccountingCode(suggestion.warehouseAccountingCode());
                dto.setTaxCode(suggestion.taxCode());
                dto.setCurrencyCode(suggestion.currencyCode());
                dto.setPriority(suggestion.priority() == null || suggestion.priority() <= 0 ? 100 : suggestion.priority());
                dto.setDefaultMapping(suggestion.defaultMapping() == null || suggestion.defaultMapping());
                dto.setStatus(firstNonBlank(dto.getStatus(), "ACTIVE"));
                dto.setName(firstNonBlank(dto.getReferenceName(), "Reference Mapping", null));
                if (existing.isPresent()) {
                    savedMappings.add(referenceMappingService.update(existing.get().getId(), dto));
                    updated++;
                } else {
                    savedMappings.add(referenceMappingService.create(dto));
                    created++;
                }
            } catch (RuntimeException ex) {
                skipped++;
                failures.add(new ReferenceMappingBulkFailure(
                    suggestionKey(typeCode, suggestion, index),
                    suggestion.referenceId(),
                    suggestion.referenceCode(),
                    suggestion.externalCode(),
                    failureMessage(ex)
                ));
            }
        }
        return new ReferenceMappingAiApplyResponse(
            created + updated,
            created,
            updated,
            skipped,
            List.copyOf(failures),
            List.copyOf(savedMappings),
            LocalDateTime.now()
        );
    }
    private ReferenceMappingAiSuggestionDto ruleSuggestion(ReferenceCandidate candidate, String system, String area) {
        ReferenceMapping existing = candidate.existingMapping();
        if (existing != null) {
            return new ReferenceMappingAiSuggestionDto(
                candidate.referenceId(),
                firstNonBlank(existing.getReferenceCode(), candidate.referenceCode()),
                firstNonBlank(existing.getReferenceName(), candidate.referenceName()),
                existing.getExternalCode(),
                existing.getExternalName(),
                area,
                existing.getChartOfAccountsCode(),
                existing.getAccountCode(),
                existing.getSubAccountCode(),
                existing.getCostCenter(),
                existing.getProfitCenter(),
                existing.getWarehouseAccountingCode(),
                existing.getTaxCode(),
                existing.getCurrencyCode(),
                existing.getPriority(),
                existing.isDefaultMapping(),
                1.0d,
                "Запись уже сопоставлена в выбранной компании, системе и области учета",
                "EXISTING",
                existing.getId(),
                true
            );
        }
        String external = normalizeExternalCode(firstNonBlank(candidate.preferredExternalCode(), candidate.referenceCode(), candidate.referenceName()), system);
        AccountDefaults defaults = accountDefaults(candidate.referenceEntityType(), area, external, candidate.referenceCode());
        return new ReferenceMappingAiSuggestionDto(
            candidate.referenceId(),
            candidate.referenceCode(),
            candidate.referenceName(),
            external,
            candidate.referenceName(),
            area,
            defaults.chartOfAccountsCode(),
            defaults.accountCode(),
            defaults.subAccountCode(),
            defaults.costCenter(),
            defaults.profitCenter(),
            defaults.warehouseAccountingCode(),
            defaults.taxCode(),
            defaults.currencyCode(),
            100,
            true,
            confidence(candidate, external),
            defaults.reason(),
            "AI_RULES",
            null,
            false
        );
    }
    private AccountDefaults accountDefaults(String type, String area, String externalCode, String referenceCode) {
        return switch (type) {
            case "MATERIAL", "MATERIAL_GROUP", "STOCK_BATCH", "IKPU_CODE", "MXIK_CODE", "TNVED_CODE" -> materialDefaults(type, area, externalCode, referenceCode);
            case "SUPPLIER" -> new AccountDefaults("UZ_NAS", "6010", null, null, null, null, taxCode(referenceCode), null, "Поставщик относится к расчетам с поставщиками; предложен счет 6010");
            case "WAREHOUSE", "STORAGE_LOCATION", "RFID_TAG" -> new AccountDefaults("UZ_NAS", null, null, null, null, externalCode, null, null, "Складская НСИ получает складской учетный код для WMS/1C");
            case "CURRENCY", "CURRENCY_RATE" -> new AccountDefaults(null, null, null, null, null, null, null, currencyCode(referenceCode), "Валютная НСИ сохраняет ISO-код валюты для учета курсов");
            case "BUDGET_ITEM" -> new AccountDefaults(null, null, null, externalCode, null, null, null, null, "Статья бюджета используется как центр затрат/аналитика бюджета");
            case "DEPARTMENT", "DEPARTMENT_TYPE", "POSITION", "EMPLOYEE" -> new AccountDefaults(null, null, null, externalCode, null, null, null, null, "Оргструктура связана с HR и центрами затрат");
            default -> new AccountDefaults(null, null, null, null, null, null, null, null, "Предложение построено по коду и наименованию записи НСИ");
        };
    }
    private AccountDefaults materialDefaults(String type, String area, String externalCode, String referenceCode) {
        String account = switch (area) {
            case "FINANCIAL", "TAX" -> "1010";
            case "WAREHOUSE" -> null;
            default -> "INV_MATERIALS";
        };
        String subAccount = switch (type) {
            case "IKPU_CODE" -> "IKPU";
            case "MXIK_CODE" -> "MXIK";
            case "TNVED_CODE" -> "TNVED";
            case "STOCK_BATCH" -> "BATCH";
            default -> "MM";
        };
        return new AccountDefaults(
            "UZ_NAS",
            account,
            subAccount,
            null,
            null,
            "WAREHOUSE".equals(area) ? externalCode : null,
            "IKPU_CODE".equals(type) || "MXIK_CODE".equals(type) || "TNVED_CODE".equals(type) ? taxCode(referenceCode) : null,
            null,
            "Материальная НСИ относится к запасам; предложены счет запасов и аналитика по типу справочника"
        );
    }
    private Double confidence(ReferenceCandidate candidate, String externalCode) {
        if (blank(externalCode)) {
            return 0.3d;
        }
        if (externalCode.equalsIgnoreCase(firstNonBlank(candidate.preferredExternalCode(), candidate.referenceCode()))) {
            return 0.92d;
        }
        return 0.78d;
    }
    private List<ReferenceMappingAiRelationDto> relationHints(List<ReferenceCandidate> candidates) {
        List<ReferenceMappingAiRelationDto> result = new ArrayList<>();
        for (ReferenceCandidate candidate : candidates) {
            Object reference = candidate.reference();
            switch (candidate.referenceEntityType()) {
                case "MATERIAL" -> {
                    addRelation(result, candidate, "MATERIAL_GROUP", readUuidProperty(reference, "getMaterialGroupId"), "BELONGS_TO", "Материал входит в группу материалов");
                    addRelation(result, candidate, "UNIT_OF_MEASURE", readUuidProperty(reference, "getUnitOfMeasureId"), "USES_UOM", "Материал использует единицу измерения");
                    addRelation(result, candidate, "IKPU_CODE", readUuidProperty(reference, "getIkpuCodeId"), "TAX_PRODUCT_CODE", "Материал связан с кодом ИКПУ");
                    addCodeRelation(result, candidate, "MXIK_CODE", readStringProperty(reference, "getMxikCode"), "TAX_PRODUCT_CODE", "Материал связан с кодом MXIK");
                    addRelation(result, candidate, "TNVED_CODE", readUuidProperty(reference, "getTnvedCodeId"), "CUSTOMS_CODE", "Материал связан с кодом ТНВЭД");
                }
                case "STORAGE_LOCATION" -> addRelation(result, candidate, "WAREHOUSE", readUuidProperty(reference, "getWarehouseId"), "LOCATED_IN", "Зона хранения принадлежит складу");
                case "STOCK_BATCH" -> {
                    addRelation(result, candidate, "MATERIAL", readUuidProperty(reference, "getMaterialId"), "BATCH_OF", "Партия относится к материалу");
                    addRelation(result, candidate, "WAREHOUSE", readUuidProperty(reference, "getWarehouseId"), "STORED_IN", "Партия хранится на складе");
                }
                case "DEPARTMENT" -> {
                    addRelation(result, candidate, "DEPARTMENT", readUuidProperty(reference, "getParentDepartmentId"), "PARENT_DEPARTMENT", "Подразделение входит в иерархию оргструктуры");
                    addRelation(result, candidate, "EMPLOYEE", readUuidProperty(reference, "getManagerEmployeeId"), "MANAGED_BY", "Подразделение связано с руководителем");
                }
                case "POSITION" -> addRelation(result, candidate, "DEPARTMENT", readUuidProperty(reference, "getDepartmentId"), "BELONGS_TO", "Должность относится к подразделению");
                case "EMPLOYEE" -> {
                    addRelation(result, candidate, "DEPARTMENT", readUuidProperty(reference, "getDepartmentId"), "WORKS_IN", "Сотрудник относится к подразделению");
                    addRelation(result, candidate, "POSITION", readUuidProperty(reference, "getPositionId"), "HAS_POSITION", "Сотрудник занимает должность");
                    addRelation(result, candidate, "EMPLOYEE", readUuidProperty(reference, "getManagerEmployeeId"), "REPORTS_TO", "Сотрудник связан с руководителем");
                }
                case "CURRENCY_RATE" -> addCodeRelation(result, candidate, "CURRENCY", readStringProperty(reference, "getCurrencyCode"), "RATE_FOR", "Курс относится к валюте");
                case "RFID_TAG" -> addRfidRelation(result, candidate);
                default -> {
                }
            }
        }
        return result;
    }
    private void addRfidRelation(List<ReferenceMappingAiRelationDto> result, ReferenceCandidate candidate) {
        String targetType = upperOrNull(readStringProperty(candidate.reference(), "getEntityType"));
        UUID targetId = readUuidProperty(candidate.reference(), "getEntityId");
        if (!blank(targetType) && REFERENCE_TYPES.containsKey(targetType)) {
            addRelation(result, candidate, targetType, targetId, "IDENTIFIES", "RFID-метка идентифицирует связанную запись НСИ");
        }
    }
    private void addRelation(List<ReferenceMappingAiRelationDto> result, ReferenceCandidate source, String targetTypeCode, UUID targetId, String relationType, String reason) {
        if (targetId == null || !REFERENCE_TYPES.containsKey(targetTypeCode)) {
            return;
        }
        ReferenceType targetType = REFERENCE_TYPES.get(targetTypeCode);
        Object target = entityManager.find(targetType.entityClass(), targetId);
        if (target == null) {
            return;
        }
        if (target instanceof BusinessEntity businessEntity
            && (businessEntity.isDeleted()
                || !TenantContext.currentTenant().equals(businessEntity.getTenantId())
                || !com.enterprise.mm.security.AccessScope.canAccessCompany(businessEntity.getCompanyId()))) {
            return;
        }
        result.add(new ReferenceMappingAiRelationDto(
            source.referenceEntityType(),
            source.referenceId(),
            source.referenceCode(),
            targetTypeCode,
            targetId,
            referenceCode(target, targetType),
            relationType,
            0.98d,
            reason
        ));
    }
    private void addCodeRelation(List<ReferenceMappingAiRelationDto> result, ReferenceCandidate source, String targetTypeCode, String targetCode, String relationType, String reason) {
        if (blank(targetCode) || !REFERENCE_TYPES.containsKey(targetTypeCode)) {
            return;
        }
        result.add(new ReferenceMappingAiRelationDto(
            source.referenceEntityType(),
            source.referenceId(),
            source.referenceCode(),
            targetTypeCode,
            null,
            targetCode.trim(),
            relationType,
            0.98d,
            reason
        ));
    }
    private AiPromptResponse runAiReferenceAnalysis(
        String provider,
        String typeCode,
        String system,
        String area,
        List<ReferenceCandidate> candidates,
        List<ReferenceMappingAiSuggestionDto> ruleSuggestions,
        List<ReferenceMappingAiRelationDto> relations
    ) {
        IntegrationSettingService integrationSettingService = integrationSettingServiceProvider.getIfAvailable();
        if (integrationSettingService == null) {
            return null;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", TenantContext.currentTenant());
        context.put("companyId", CompanyContext.currentCompany());
        context.put("referenceEntityType", typeCode);
        context.put("externalSystem", system);
        context.put("accountingArea", area);
        context.put("references", candidates.stream().map(this::candidateContext).toList());
        context.put("ruleSuggestions", ruleSuggestions);
        context.put("relations", relations);
        String prompt = """
            Ты ERP MM архитектор. Проанализируй справочник НСИ и его связи для маппинга с внешней системой.
            Верни только JSON вида {"suggestions":[{"referenceId":"uuid","externalCode":"...","externalName":"...","accountCode":"...","costCenter":"...","warehouseAccountingCode":"...","taxCode":"...","currencyCode":"...","confidence":0.0,"reason":"..."}]}.
            Не придумывай связи, которых нет в context.relations. Учитывай компанию, систему учета и область учета.
            """;
        try {
            return integrationSettingService.testAiProvider(ExternalIntegrationSystem.from(provider), new AiPromptRequest(prompt, context));
        } catch (RuntimeException ex) {
            return new AiPromptResponse(provider, provider, null, "AI_ERROR", ex.getMessage(), null, null, LocalDateTime.now());
        }
    }
    private Map<String, Object> candidateContext(ReferenceCandidate candidate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("referenceId", candidate.referenceId() == null ? null : candidate.referenceId().toString());
        out.put("referenceCode", candidate.referenceCode());
        out.put("referenceName", candidate.referenceName());
        out.put("preferredExternalCode", candidate.preferredExternalCode());
        if (candidate.existingMapping() != null) {
            out.put("existingExternalCode", candidate.existingMapping().getExternalCode());
            out.put("existingAccountCode", candidate.existingMapping().getAccountCode());
        }
        return out;
    }
    private List<ReferenceMappingAiSuggestionDto> mergeAiSuggestions(List<ReferenceMappingAiSuggestionDto> baseSuggestions, String aiText) {
        Map<UUID, ReferenceMappingAiSuggestionDto> aiByReferenceId = parseAiSuggestions(aiText);
        if (aiByReferenceId.isEmpty()) {
            return baseSuggestions;
        }
        List<ReferenceMappingAiSuggestionDto> result = new ArrayList<>();
        for (ReferenceMappingAiSuggestionDto base : baseSuggestions) {
            ReferenceMappingAiSuggestionDto ai = aiByReferenceId.get(base.referenceId());
            if (ai == null) {
                result.add(base);
                continue;
            }
            result.add(new ReferenceMappingAiSuggestionDto(
                base.referenceId(),
                base.referenceCode(),
                base.referenceName(),
                firstNonBlank(ai.externalCode(), base.externalCode()),
                firstNonBlank(ai.externalName(), base.externalName()),
                base.accountingArea(),
                firstNonBlank(ai.chartOfAccountsCode(), base.chartOfAccountsCode()),
                firstNonBlank(ai.accountCode(), base.accountCode()),
                firstNonBlank(ai.subAccountCode(), base.subAccountCode()),
                firstNonBlank(ai.costCenter(), base.costCenter()),
                firstNonBlank(ai.profitCenter(), base.profitCenter()),
                firstNonBlank(ai.warehouseAccountingCode(), base.warehouseAccountingCode()),
                firstNonBlank(ai.taxCode(), base.taxCode()),
                firstNonBlank(ai.currencyCode(), base.currencyCode()),
                ai.priority() == null ? base.priority() : ai.priority(),
                ai.defaultMapping() == null ? base.defaultMapping() : ai.defaultMapping(),
                clamp(ai.confidence() == null ? base.confidence() : ai.confidence()),
                firstNonBlank(ai.reason(), base.reason()),
                "AI",
                base.existingMappingId(),
                base.existingMapping()
            ));
        }
        return result;
    }
    private Map<UUID, ReferenceMappingAiSuggestionDto> parseAiSuggestions(String aiText) {
        if (blank(aiText)) {
            return Map.of();
        }
        JsonNode root = readJsonFragment(aiText);
        JsonNode suggestions = root == null ? null : (root.isArray() ? root : root.path("suggestions"));
        if (suggestions == null || !suggestions.isArray()) {
            return Map.of();
        }
        Map<UUID, ReferenceMappingAiSuggestionDto> result = new LinkedHashMap<>();
        for (JsonNode node : suggestions) {
            UUID referenceId = parseUuid(node.path("referenceId").asText(null));
            if (referenceId == null) {
                continue;
            }
            result.put(referenceId, new ReferenceMappingAiSuggestionDto(
                referenceId,
                null,
                text(node, "referenceName"),
                text(node, "externalCode"),
                text(node, "externalName"),
                null,
                text(node, "chartOfAccountsCode"),
                text(node, "accountCode"),
                text(node, "subAccountCode"),
                text(node, "costCenter"),
                text(node, "profitCenter"),
                text(node, "warehouseAccountingCode"),
                text(node, "taxCode"),
                text(node, "currencyCode"),
                node.path("priority").canConvertToInt() ? node.path("priority").asInt() : null,
                node.has("defaultMapping") ? node.path("defaultMapping").asBoolean() : null,
                node.path("confidence").isNumber() ? node.path("confidence").asDouble() : null,
                text(node, "reason"),
                "AI",
                null,
                null
            ));
        }
        return result;
    }
    private JsonNode readJsonFragment(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            int objectStart = text.indexOf('{');
            int objectEnd = text.lastIndexOf('}');
            if (objectStart >= 0 && objectEnd > objectStart) {
                try {
                    return objectMapper.readTree(text.substring(objectStart, objectEnd + 1));
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
            int arrayStart = text.indexOf('[');
            int arrayEnd = text.lastIndexOf(']');
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                try {
                    return objectMapper.readTree(text.substring(arrayStart, arrayEnd + 1));
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
    }
    private Optional<Object> referenceFromSuggestion(String typeCode, ReferenceMappingAiSuggestionDto suggestion) {
        if (suggestion.referenceId() == null) {
            return Optional.empty();
        }
        ReferenceType type = requireKnownReferenceType(typeCode);
        Object reference = entityManager.find(type.entityClass(), suggestion.referenceId());
        return Optional.ofNullable(reference);
    }
    private Optional<ReferenceMapping> findExactMapping(String type, UUID referenceId, String referenceCode, String system, String area) {
        Optional<ReferenceMapping> mapping = Optional.empty();
        if (referenceId != null) {
            mapping = referenceMappingRepository
                .findFirstByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceIdAndExternalSystemAndAccountingAreaAndIsDeletedFalseOrderByPriorityAsc(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    type,
                    referenceId,
                    system,
                    area
                );
        }
        if (mapping.isEmpty() && !blank(referenceCode)) {
            mapping = referenceMappingRepository
                .findFirstByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceCodeAndExternalSystemAndAccountingAreaAndIsDeletedFalseOrderByPriorityAsc(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    type,
                    referenceCode.trim(),
                    system,
                    area
                );
        }
        return mapping;
    }
    private List<?> referenceRows(ReferenceType type) {
        if ("VALUATION_METHOD".equals(type.code())) {
            return entityManager.createQuery("""
                    select e from Classifier e
                    where e.tenantId = :tenantId
                      and e.companyId = :companyId
                      and e.isDeleted = false
                      and e.classifierType = :classifierType
                    order by e.name
                    """, Classifier.class)
                .setParameter("tenantId", TenantContext.currentTenant())
                .setParameter("companyId", CompanyContext.currentCompany())
                .setParameter("classifierType", "VALUATION_METHOD")
                .getResultList();
        }
        if (!BusinessEntity.class.isAssignableFrom(type.entityClass())) {
            return entityManager.createQuery("select e from " + type.entityClass().getSimpleName() + " e order by e.name", type.entityClass())
                .getResultList();
        }
        return entityManager.createQuery("""
                select e from %s e
                where e.tenantId = :tenantId
                  and e.companyId = :companyId
                  and e.isDeleted = false
                order by e.name
                """.formatted(type.entityClass().getSimpleName()), type.entityClass())
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", CompanyContext.currentCompany())
            .getResultList();
    }
    private UUID referenceId(Object reference) {
        try {
            Object value = reference.getClass().getMethod("getId").invoke(reference);
            return value instanceof UUID id ? id : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
    private String referenceCode(Object reference, ReferenceType type) {
        return firstNonBlank(businessCode(reference), readStringProperty(reference, type.preferredCodeGetter()), referenceId(reference) == null ? null : referenceId(reference).toString());
    }
    private String externalCode(Object reference, ReferenceType type) {
        return firstNonBlank(readStringProperty(reference, type.preferredCodeGetter()), businessCode(reference), referenceId(reference) == null ? null : referenceId(reference).toString());
    }
    private String businessCode(Object reference) {
        return reference instanceof BusinessEntity entity ? entity.getCode() : null;
    }
    private String referenceName(Object reference) {
        if (reference instanceof BusinessEntity entity) {
            return firstNonBlank(entity.getName(), entity.getCode());
        }
        return firstNonBlank(readStringProperty(reference, "getName"), referenceId(reference) == null ? null : referenceId(reference).toString());
    }
    private String readStringProperty(Object reference, String methodName) {
        if (blank(methodName)) {
            return null;
        }
        try {
            Method method = reference.getClass().getMethod(methodName);
            Object value = method.invoke(reference);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
    private UUID readUuidProperty(Object reference, String methodName) {
        try {
            Object value = reference.getClass().getMethod(methodName).invoke(reference);
            return value instanceof UUID id ? id : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
    private ReferenceType requireKnownReferenceType(String referenceEntityType) {
        ReferenceType type = REFERENCE_TYPES.get(referenceEntityType);
        if (type == null) {
            throw new BadRequestException("Неизвестный тип НСИ: " + referenceEntityType);
        }
        return type;
    }
    private String aiProvider(String value) {
        String provider = requiredCode(blank(value) ? "CHATGPT" : value, "AI-провайдер обязателен");
        if (!AI_PROVIDER_SYSTEMS.contains(provider)) {
            throw new BadRequestException("Для AI-анализа доступны только CHATGPT, CLAUDE или GEMINI");
        }
        return provider;
    }
    private int limit(Integer raw) {
        if (raw == null) {
            return 50;
        }
        if (raw < 1 || raw > 1000) {
            throw new BadRequestException("AI analysis limit must be between 1 and 1000");
        }
        return raw;
    }
    private String normalizeExternalCode(String value, String system) {
        String normalized = firstNonBlank(value);
        if (blank(normalized)) {
            return "";
        }
        if (Set.of("ONE_C", "FI", "TREASURY", "BUDGETING", "HR", "WMS", "SCADA").contains(system)) {
            return normalized.trim()
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        }
        return normalized.trim();
    }
    private String taxCode(String value) {
        String normalized = firstNonBlank(value);
        return blank(normalized) ? null : normalized.replaceAll("[^A-Za-z0-9]+", "").toUpperCase(Locale.ROOT);
    }
    private String currencyCode(String value) {
        String normalized = firstNonBlank(value);
        if (normalized.length() >= 3) {
            return normalized.substring(0, 3).toUpperCase(Locale.ROOT);
        }
        return blank(normalized) ? null : normalized.toUpperCase(Locale.ROOT);
    }
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
    private Double clamp(Double value) {
        if (value == null) {
            return null;
        }
        return Math.max(0d, Math.min(1d, value));
    }
    private UUID parseUuid(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
    private String requiredCode(String value, String message) {
        String normalized = upperOrNull(value);
        if (blank(normalized)) {
            throw new BadRequestException(message);
        }
        return normalized;
    }
    private String upperOrNull(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.replace('-', '_').toUpperCase(Locale.ROOT);
    }
    private String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return "";
    }
    private String suggestionKey(
        String typeCode,
        ReferenceMappingAiSuggestionDto suggestion,
        int index
    ) {
        if (suggestion == null) {
            return typeCode + ":item-" + index;
        }
        return String.join(":",
            typeCode,
            firstNonBlank(
                suggestion.referenceId() == null ? null : suggestion.referenceId().toString(),
                suggestion.referenceCode(),
                "item-" + index),
            firstNonBlank(suggestion.externalCode(), "UNMAPPED"));
    }
    private String failureMessage(RuntimeException exception) {
        if (!(exception instanceof BadRequestException) && !(exception instanceof BusinessException)) {
            return "Mapping operation failed";
        }
        String message = trimToNull(exception.getMessage());
        return message == null ? "Mapping operation failed" : message;
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
    private record ReferenceCandidate(
        String referenceEntityType,
        UUID referenceId,
        String referenceCode,
        String referenceName,
        String preferredExternalCode,
        Object reference,
        ReferenceMapping existingMapping
    ) {
    }
    private record AccountDefaults(
        String chartOfAccountsCode,
        String accountCode,
        String subAccountCode,
        String costCenter,
        String profitCenter,
        String warehouseAccountingCode,
        String taxCode,
        String currencyCode,
        String reason
    ) {
    }
}
