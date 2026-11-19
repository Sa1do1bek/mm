package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.ReferenceMappingResponse;
import com.enterprise.mm.payload.ReferenceMappingUpdateRequest;
import com.enterprise.mm.payload.ReferenceMappingCreateRequest;
import com.enterprise.mm.payload.ReferenceMappingSearchRequest;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingBulkFailure;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingBulkResult;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ReferenceMappingMapper;
import com.enterprise.mm.service.ReferenceMappingMetadata.PayloadReference;
import com.enterprise.mm.service.ReferenceMappingMetadata.PayloadReferenceField;
import com.enterprise.mm.service.ReferenceMappingMetadata.ReferenceType;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Classifier;
import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.repository.ReferenceMappingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReferenceMappingService {
    private final ReferenceMappingRepository repository;
    private final ReferenceMappingMapper mapper;
    private final String entityName = "ReferenceMapping";
    private final AuditService auditService;
    private static final int CODE_MAX_LENGTH = 80;
    private static final int NAME_MAX_LENGTH = 240;
    private static final int REFERENCE_CODE_MAX_LENGTH = 120;
    private static final int REFERENCE_NAME_MAX_LENGTH = 240;
    private static final int EXTERNAL_CODE_MAX_LENGTH = 160;
    private static final int EXTERNAL_NAME_MAX_LENGTH = 240;
    private static final Map<String, ReferenceType> REFERENCE_TYPES = ReferenceMappingMetadata.referenceTypes();
    private static final Map<String, PayloadReferenceField> PAYLOAD_REFERENCE_FIELDS = ReferenceMappingMetadata.payloadReferenceFields();
    @PersistenceContext
    private EntityManager entityManager;

    protected void beforeCreate(ReferenceMapping entity, ReferenceMappingCreateRequest dto) {
        normalize(entity);
        Optional<Object> reference = validateReference(entity);
        fillReferenceSnapshot(entity, reference);
        ensureUnique(entity);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setCode(code(entity));
        entity.setName(name(entity));
    }
    protected void beforeUpdate(ReferenceMapping entity, ReferenceMappingUpdateRequest dto) {
        normalize(entity);
        Optional<Object> reference = validateReference(entity);
        fillReferenceSnapshot(entity, reference);
        ensureUnique(entity);
        entity.setCode(code(entity));
        entity.setName(name(entity));
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    public ReferenceMappingBulkResult generateDefaultMappings(String externalSystem, String accountingArea) {
        String system = requiredCode(externalSystem, "Внешняя система обязательна");
        String area = requiredCode(blank(accountingArea) ? "MANAGEMENT" : accountingArea, "Область учета обязательна");
        int applied = 0;
        int skipped = 0;
        List<ReferenceMappingBulkFailure> failures = new ArrayList<>();
        for (ReferenceType type : REFERENCE_TYPES.values()) {
            for (Object reference : referenceRows(type)) {
                String referenceCode = referenceCode(reference, type);
                if (blank(referenceCode)) {
                    skipped++;
                    continue;
                }
                UUID referenceId = referenceId(reference);
                String externalCode = externalCode(reference, type);
                if (repository.findByTenantIdAndCompanyIdAndReferenceEntityTypeAndExternalSystemAndExternalCodeAndAccountingAreaAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    type.code(),
                    system,
                    externalCode,
                    area
                ).isPresent()) {
                    skipped++;
                    continue;
                }
                if (referenceId != null && repository
                    .findFirstByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceIdAndExternalSystemAndAccountingAreaAndIsDeletedFalseOrderByPriorityAsc(
                        TenantContext.currentTenant(),
                        CompanyContext.currentCompany(),
                        type.code(),
                        referenceId,
                        system,
                        area
                    ).isPresent()) {
                    skipped++;
                    continue;
                }
                try {
                    ReferenceMapping mapping = new ReferenceMapping();
                    mapping.setReferenceEntityType(type.code());
                    mapping.setReferenceId(referenceId);
                    mapping.setReferenceCode(limit(referenceCode, REFERENCE_CODE_MAX_LENGTH));
                    mapping.setReferenceName(limit(referenceName(reference), REFERENCE_NAME_MAX_LENGTH));
                    mapping.setExternalSystem(system);
                    mapping.setExternalCode(limit(externalCode, EXTERNAL_CODE_MAX_LENGTH));
                    mapping.setExternalName(limit(referenceName(reference), EXTERNAL_NAME_MAX_LENGTH));
                    mapping.setAccountingArea(area);
                    mapping.setDefaultMapping(true);
                    mapping.setPriority(100);
                    beforeCreate(mapping, new ReferenceMappingCreateRequest());
                    repository.save(mapping);
                    applied++;
                } catch (RuntimeException ex) {
                    skipped++;
                    failures.add(new ReferenceMappingBulkFailure(
                        itemKey(type.code(), referenceId, referenceCode, externalCode),
                        referenceId,
                        referenceCode,
                        externalCode,
                        failureMessage(ex)
                    ));
                }
            }
        }
        return new ReferenceMappingBulkResult(
            applied, skipped, List.copyOf(failures), LocalDateTime.now());
    }
    @Transactional(readOnly = true)
    public Optional<ReferenceMappingResponse> resolve(String referenceEntityType, UUID referenceId, String referenceCode, String externalSystem, String accountingArea) {
        String type = requiredCode(referenceEntityType, "Тип НСИ обязателен");
        String system = requiredCode(externalSystem, "Внешняя система обязательна");
        String area = requiredCode(blank(accountingArea) ? "MANAGEMENT" : accountingArea, "Область учета обязательна");
        return findMapping(type, referenceId, referenceCode, system, area).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mappedReferences(JsonNode payload, String externalSystem, String accountingArea) {
        if (payload == null || payload.isNull()) {
            return List.of();
        }
        String system = requiredCode(externalSystem, "Внешняя система обязательна");
        String area = requiredCode(blank(accountingArea) ? "MANAGEMENT" : accountingArea, "Область учета обязательна");
        Map<String, PayloadReference> references = new LinkedHashMap<>();
        collectPayloadReferences(payload, references);
        List<Map<String, Object>> result = new ArrayList<>();
        references.values().forEach(reference -> findMapping(reference.referenceEntityType(), reference.referenceId(), reference.referenceCode(), system, area)
            .ifPresent(mapping -> result.add(Map.of(
                "referenceEntityType", mapping.getReferenceEntityType(),
                "referenceId", mapping.getReferenceId() == null ? "" : mapping.getReferenceId().toString(),
                "referenceCode", blank(mapping.getReferenceCode()) ? "" : mapping.getReferenceCode(),
                "externalSystem", mapping.getExternalSystem(),
                "externalCode", mapping.getExternalCode(),
                "externalName", blank(mapping.getExternalName()) ? "" : mapping.getExternalName(),
                "accountingArea", mapping.getAccountingArea(),
                "accountCode", blank(mapping.getAccountCode()) ? "" : mapping.getAccountCode(),
                "subAccountCode", blank(mapping.getSubAccountCode()) ? "" : mapping.getSubAccountCode()
            ))));
        return result;
    }
    private void normalize(ReferenceMapping entity) {
        entity.setReferenceEntityType(requiredCode(entity.getReferenceEntityType(), "Тип НСИ обязателен"));
        requireKnownReferenceType(entity.getReferenceEntityType());
        entity.setExternalSystem(requiredCode(entity.getExternalSystem(), "Внешняя система обязательна"));
        entity.setExternalCode(requiredText(entity.getExternalCode(), "Внешний код обязателен"));
        entity.setAccountingArea(requiredCode(blank(entity.getAccountingArea()) ? "MANAGEMENT" : entity.getAccountingArea(), "Область учета обязательна"));
        entity.setReferenceCode(trimToNull(entity.getReferenceCode()));
        entity.setReferenceName(trimToNull(entity.getReferenceName()));
        entity.setExternalName(trimToNull(entity.getExternalName()));
        entity.setChartOfAccountsCode(upperOrNull(entity.getChartOfAccountsCode()));
        entity.setAccountCode(upperOrNull(entity.getAccountCode()));
        entity.setSubAccountCode(upperOrNull(entity.getSubAccountCode()));
        entity.setCostCenter(upperOrNull(entity.getCostCenter()));
        entity.setProfitCenter(upperOrNull(entity.getProfitCenter()));
        entity.setWarehouseAccountingCode(upperOrNull(entity.getWarehouseAccountingCode()));
        entity.setTaxCode(upperOrNull(entity.getTaxCode()));
        entity.setCurrencyCode(upperOrNull(entity.getCurrencyCode()));
        if (entity.getPriority() <= 0) {
            entity.setPriority(100);
        }
        if (entity.getValidFrom() != null && entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw new BadRequestException("Дата окончания не может быть раньше даты начала");
        }
    }
    private Optional<Object> validateReference(ReferenceMapping entity) {
        if (entity.getReferenceId() == null) {
            return Optional.empty();
        }
        ReferenceType type = requireKnownReferenceType(entity.getReferenceEntityType());
        Object reference = entityManager.find(type.entityClass(), entity.getReferenceId());
        if (reference == null) {
            throw new BadRequestException("Запись НСИ не найдена: " + entity.getReferenceEntityType() + " / " + entity.getReferenceId());
        }
        if (reference instanceof BusinessEntity businessEntity) {
            if (businessEntity.isDeleted()
                || !TenantContext.currentTenant().equals(businessEntity.getTenantId())
                || !Objects.equals(CompanyContext.currentCompany(), businessEntity.getCompanyId())) {
                throw new BadRequestException("Запись НСИ не принадлежит текущей компании: " + entity.getReferenceEntityType() + " / " + entity.getReferenceId());
            }
        }
        if ("VALUATION_METHOD".equals(type.code())
            && reference instanceof Classifier classifier
            && !"VALUATION_METHOD".equals(classifier.getClassifierType())) {
            throw new BadRequestException(
                "Запись НСИ не является методом оценки: " + entity.getReferenceId());
        }
        return Optional.of(reference);
    }
    private void fillReferenceSnapshot(ReferenceMapping entity, Optional<Object> reference) {
        if (reference.isEmpty()) {
            return;
        }
        ReferenceType type = requireKnownReferenceType(entity.getReferenceEntityType());
        if (blank(entity.getReferenceCode())) {
            entity.setReferenceCode(referenceCode(reference.get(), type));
        }
        if (blank(entity.getReferenceName())) {
            entity.setReferenceName(referenceName(reference.get()));
        }
    }
    private void ensureUnique(ReferenceMapping entity) {
        repository.findByTenantIdAndCompanyIdAndReferenceEntityTypeAndExternalSystemAndExternalCodeAndAccountingAreaAndIsDeletedFalse(
            TenantContext.currentTenant(),
            entity.getCompanyId() == null ? CompanyContext.currentCompany() : entity.getCompanyId(),
            entity.getReferenceEntityType(),
            entity.getExternalSystem(),
            entity.getExternalCode(),
            entity.getAccountingArea()
        ).ifPresent(existing -> {
            if (entity.getId() == null || !existing.getId().equals(entity.getId())) {
                throw new BadRequestException("Такой внешний код уже сопоставлен в этой компании: " + entity.getExternalSystem() + " / " + entity.getExternalCode());
            }
        });
    }
    private String code(ReferenceMapping entity) {
        String externalCode = entity.getExternalCode().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return limit((entity.getReferenceEntityType() + "-" + entity.getExternalSystem() + "-" + externalCode).toUpperCase(Locale.ROOT), CODE_MAX_LENGTH);
    }
    private Optional<ReferenceMapping> findMapping(String type, UUID referenceId, String referenceCode, String system, String area) {
        Optional<ReferenceMapping> mapping = Optional.empty();
        if (referenceId != null) {
            mapping = repository
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
            mapping = repository
                .findFirstByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceCodeAndExternalSystemAndAccountingAreaAndIsDeletedFalseOrderByPriorityAsc(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    type,
                    referenceCode.trim(),
                    system,
                    area
                );
        }
        if (mapping.isEmpty() && !"MANAGEMENT".equals(area)) {
            return findMapping(type, referenceId, referenceCode, system, "MANAGEMENT");
        }
        return mapping;
    }
    private void collectPayloadReferences(JsonNode node, Map<String, PayloadReference> references) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectPayloadReferences(item, references));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            PayloadReferenceField referenceField = PAYLOAD_REFERENCE_FIELDS.get(field.getKey());
            JsonNode value = field.getValue();
            if (referenceField != null && value != null && value.isValueNode() && !blank(value.asText())) {
                PayloadReference reference = referenceField.id()
                    ? new PayloadReference(referenceField.referenceEntityType(), parseUuid(value.asText()), null)
                    : new PayloadReference(referenceField.referenceEntityType(), null, value.asText());
                references.put(reference.referenceEntityType() + ":" + value.asText(), reference);
            }
            collectPayloadReferences(value, references);
        }
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
            return entityManager.createQuery(
                    "select e from " + type.entityClass().getSimpleName() + " e order by " + referenceOrderExpression(type),
                    type.entityClass()
                )
                .getResultList();
        }
        return entityManager.createQuery("""
                select e from %s e
                where e.tenantId = :tenantId
                  and e.companyId = :companyId
                  and e.isDeleted = false
                order by %s
                """.formatted(type.entityClass().getSimpleName(), referenceOrderExpression(type)), type.entityClass())
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", CompanyContext.currentCompany())
            .getResultList();
    }

    private String referenceOrderExpression(ReferenceType type) {
        if (UnitOfMeasure.class.equals(type.entityClass())) {
            return "e.measurementNameShort, e.code";
        }
        return "e.name";
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
    private ReferenceType requireKnownReferenceType(String referenceEntityType) {
        ReferenceType type = REFERENCE_TYPES.get(referenceEntityType);
        if (type == null) {
            throw new BadRequestException("Неизвестный тип НСИ: " + referenceEntityType);
        }
        return type;
    }
    private String name(ReferenceMapping entity) {
        String left = firstNonBlank(entity.getReferenceName(), entity.getReferenceCode(), entity.getReferenceEntityType());
        String right = firstNonBlank(entity.getExternalName(), entity.getExternalCode());
        return limit(left + " / " + entity.getExternalSystem() + " " + right, NAME_MAX_LENGTH);
    }
    private String requiredCode(String value, String message) {
        String normalized = upperOrNull(value);
        if (blank(normalized)) {
            throw new BadRequestException(message);
        }
        return normalized;
    }
    private String requiredText(String value, String message) {
        String normalized = trimToNull(value);
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
    private String itemKey(String type, UUID referenceId, String referenceCode, String externalCode) {
        return String.join(":",
            firstNonBlank(type, "UNKNOWN"),
            firstNonBlank(referenceId == null ? null : referenceId.toString(), referenceCode, "UNKNOWN"),
            firstNonBlank(externalCode, "UNKNOWN"));
    }
    private String failureMessage(RuntimeException exception) {
        if (!(exception instanceof BadRequestException) && !(exception instanceof BusinessException)) {
            return "Mapping operation failed";
        }
        String message = trimToNull(exception.getMessage());
        return message == null ? "Mapping operation failed" : message;
    }
    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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
    @Transactional(readOnly = true)
    public Page<ReferenceMappingResponse> search(ReferenceMappingSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.referenceId(),
            filter.normalizedReferenceEntityType(),
            filter.normalizedExternalSystem(),
            filter.normalizedAccountingArea(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ReferenceMappingResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ReferenceMappingResponse create(ReferenceMappingCreateRequest dto) {
        ReferenceMapping entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ReferenceMapping saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ReferenceMappingResponse update(UUID id, ReferenceMappingUpdateRequest dto) {
        ReferenceMapping entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ReferenceMapping saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ReferenceMapping entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ReferenceMappingResponse restore(UUID id) {
        ReferenceMapping entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        beforeUpdate(entity, new ReferenceMappingUpdateRequest());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ReferenceMapping saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ReferenceMapping findEntity(UUID id) {
        ReferenceMapping entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(ReferenceMapping entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }
    protected boolean companyScoped() {
        return true;
    }
}
