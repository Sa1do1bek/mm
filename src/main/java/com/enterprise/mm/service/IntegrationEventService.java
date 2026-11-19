package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.payload.IntegrationEventUpdateRequest;
import com.enterprise.mm.payload.IntegrationEventCreateRequest;
import com.enterprise.mm.payload.IntegrationEventSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.IntegrationEventMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.enums.IntegrationEventDirection;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationEventService {
    private final IntegrationEventRepository repository;
    private final IntegrationEventMapper mapper;
    private final String entityName = "IntegrationEvent";
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    public IntegrationEventService(IntegrationEventRepository repository, IntegrationEventMapper mapper, ObjectMapper objectMapper, AuditService auditService) {
        this.repository = repository;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }
    protected void beforeCreate(IntegrationEvent entity, IntegrationEventCreateRequest dto) {
        entity.setDirection(IntegrationEventDirection.OUTBOUND.name());
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

        entity.setStatus(IntegrationEventStatus.NEW.name());
        if (entity.getIdempotencyKey() == null || entity.getIdempotencyKey().isBlank()) {
            entity.setIdempotencyKey(UUID.randomUUID().toString());
        }
    }
    @Transactional
    public IntegrationEventResponse createIdempotent(IntegrationEventCreateRequest dto, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                idempotencyKey
            );
            if (existing.isPresent()) {
                return mapper.toResponse(existing.get());
            }
            dto.setIdempotencyKey(idempotencyKey);
        }
        return create(dto);
    }
    @Transactional
    public IntegrationEventResponse recordInboundCallbackIdempotent(
        IntegrationEventCreateRequest dto,
        String idempotencyKey
    ) {
        String normalizedKey = idempotencyKey == null ? null : idempotencyKey.trim();
        if (normalizedKey != null && !normalizedKey.isBlank()) {
            var existing = repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                normalizedKey
            );
            if (existing.isPresent()) {
                requireSameInboundCallback(existing.get(), dto);
                return mapper.toResponse(existing.get());
            }
        }
        IntegrationEvent entity = mapper.toEntity(dto);
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("INTEGRATION_CALLBACK-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        entity.setDirection(IntegrationEventDirection.INBOUND.name());
        entity.setStatus(IntegrationEventStatus.PUBLISHED.name());
        entity.setRetryCount(0);
        entity.setNextRetryAt(null);
        entity.setIdempotencyKey(
            normalizedKey == null || normalizedKey.isBlank() ? UUID.randomUUID().toString() : normalizedKey
        );
        IntegrationEvent saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "RECEIVE", saved.getEventType(), null, null);
        return mapper.toResponse(saved);
    }

    private void requireSameInboundCallback(
        IntegrationEvent existing,
        IntegrationEventCreateRequest request
    ) {
        boolean same = IntegrationEventDirection.INBOUND.name().equals(existing.getDirection())
            && Objects.equals(existing.getEventType(), request.getEventType())
            && Objects.equals(existing.getAggregateType(), request.getAggregateType())
            && Objects.equals(existing.getAggregateId(), request.getAggregateId())
            && Objects.equals(existing.getExternalId(), request.getExternalId())
            && Objects.equals(existing.getDescription(), request.getDescription())
            && sameJson(existing.getPayload(), request.getPayload());
        if (!same) {
            throw new ConflictException(
                "Idempotency-Key was already used for a different integration callback"
            );
        }
    }

    private boolean sameJson(String first, String second) {
        if (Objects.equals(first, second)) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        try {
            return objectMapper.readTree(first).equals(objectMapper.readTree(second));
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IntegrationEvent emitIdempotentInCurrentTransaction(
        String eventType,
        String aggregateType,
        UUID aggregateId,
        Map<String, ?> payload,
        String idempotencyKey
    ) {
        try {
            return emitIdempotentInCurrentTransaction(
                eventType,
                aggregateType,
                aggregateId,
                objectMapper.writeValueAsString(payload),
                idempotencyKey
            );
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize integration payload");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IntegrationEvent emitIdempotentInCurrentTransaction(
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String payload,
        String idempotencyKey
    ) {
        String normalizedKey = idempotencyKey == null ? null : idempotencyKey.trim();
        if (normalizedKey == null || normalizedKey.isBlank()) {
            throw new BusinessException("idempotencyKey is required for transactional integration event emit");
        }
        var existing = repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            normalizedKey
        );
        if (existing.isPresent()) {
            requireSameOutboundEvent(existing.get(), eventType, aggregateType, aggregateId, payload);
            return existing.get();
        }

        IntegrationEvent event = new IntegrationEvent();
        event.setDirection(IntegrationEventDirection.OUTBOUND.name());
        event.setCode(eventType + "-" + UUID.randomUUID().toString().substring(0, 8));
        event.setName(eventType);
        event.setStatus(IntegrationEventStatus.NEW.name());
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(payload);
        event.setRetryCount(0);
        event.setIdempotencyKey(normalizedKey);
        IntegrationEvent saved = repository.save(event);
        auditService.logInCurrentTransaction("IntegrationEvent", saved.getId(), "EMIT", eventType, null, null);
        return saved;
    }

    private void requireSameOutboundEvent(
        IntegrationEvent existing,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String payload
    ) {
        boolean same = IntegrationEventDirection.OUTBOUND.name().equals(existing.getDirection())
            && Objects.equals(existing.getEventType(), eventType)
            && Objects.equals(existing.getAggregateType(), aggregateType)
            && Objects.equals(existing.getAggregateId(), aggregateId)
            && sameJson(existing.getPayload(), payload);
        if (!same) {
            throw new ConflictException(
                "Idempotency-Key was already used for a different outbound integration event"
            );
        }
    }

    @Transactional
    public IntegrationEvent emit(String eventType, String aggregateType, UUID aggregateId, String payload) {
        IntegrationEvent event = new IntegrationEvent();
        event.setDirection(IntegrationEventDirection.OUTBOUND.name());
        event.setCode(eventType + "-" + UUID.randomUUID().toString().substring(0, 8));
        event.setName(eventType);
        event.setStatus(IntegrationEventStatus.NEW.name());
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(payload);
        event.setRetryCount(0);
        event.setIdempotencyKey(eventType + ":" + aggregateId + ":" + UUID.randomUUID());
        IntegrationEvent saved = repository.save(event);
        auditService.log("IntegrationEvent", saved.getId(), "EMIT", eventType, null, null);
        return saved;
    }
    @Transactional
    public IntegrationEvent emit(String eventType, String aggregateType, UUID aggregateId, Map<String, ?> payload) {
        try {
            return emit(eventType, aggregateType, aggregateId, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize integration payload");
        }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public IntegrationEvent emitInCurrentTransaction(String eventType, String aggregateType, UUID aggregateId, Map<String, ?> payload) {
        try {
            return emitInCurrentTransaction(eventType, aggregateType, aggregateId, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize integration payload");
        }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public IntegrationEvent emitInCurrentTransaction(String eventType, String aggregateType, UUID aggregateId, String payload) {
        IntegrationEvent event = new IntegrationEvent();
        event.setDirection(IntegrationEventDirection.OUTBOUND.name());
        event.setCode(eventType + "-" + UUID.randomUUID().toString().substring(0, 8));
        event.setName(eventType);
        event.setStatus(IntegrationEventStatus.NEW.name());
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(payload);
        event.setRetryCount(0);
        event.setIdempotencyKey(eventType + ":" + aggregateId + ":" + UUID.randomUUID());
        IntegrationEvent saved = repository.save(event);
        auditService.logInCurrentTransaction("IntegrationEvent", saved.getId(), "EMIT", eventType, null, null);
        return saved;
    }
    @Transactional
    public IntegrationEvent markPublished(IntegrationEvent event, String message) {
        event.setStatus(IntegrationEventStatus.PUBLISHED.name());
        event.setNextRetryAt(null);
        event.setLastError(message);
        IntegrationEvent saved = repository.save(event);
        auditService.log("IntegrationEvent", saved.getId(), "PUBLISH", event.getEventType(), null, null);
        return saved;
    }
    @Transactional
    public IntegrationEventResponse markFailed(UUID id, String error) {
        IntegrationEvent event = findEntity(id);
        requireMutableOutbound(event);
        int retryCount = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
        event.setRetryCount(retryCount);
        event.setLastAttemptAt(LocalDateTime.now());
        if (retryCount >= IntegrationDispatchService.MAX_AUTOMATIC_RETRIES) {
            event.setStatus(IntegrationEventStatus.DEAD.name());
            event.setNextRetryAt(null);
        } else {
            event.setStatus(IntegrationEventStatus.RETRY.name());
            event.setNextRetryAt(LocalDateTime.now().plusMinutes(Math.min(60, retryCount * 5L)));
        }
        event.setLastError(error);
        return mapper.toResponse(repository.save(event));
    }
    @Transactional(readOnly = true)
    public Page<IntegrationEventResponse> search(IntegrationEventSearchRequest request, Pageable pageable) {
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
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public IntegrationEventResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public IntegrationEventResponse create(IntegrationEventCreateRequest dto) {
        IntegrationEvent entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        IntegrationEvent saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public IntegrationEventResponse update(UUID id, IntegrationEventUpdateRequest dto) {
        IntegrationEvent entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutableOutbound(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        IntegrationEvent saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        IntegrationEvent entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutableOutbound(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public IntegrationEventResponse restore(UUID id) {
        IntegrationEvent entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutableOutbound(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        IntegrationEvent saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected IntegrationEvent findEntity(UUID id) {
        IntegrationEvent entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeUpdate(IntegrationEvent entity, IntegrationEventUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private void requireMutableOutbound(IntegrationEvent event) {
        if (IntegrationEventDirection.INBOUND.name().equals(event.getDirection())) {
            throw new BusinessException("Inbound integration callback is immutable");
        }
    }
    protected boolean companyScoped() {
        return true;
    }
}
