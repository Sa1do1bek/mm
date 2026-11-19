package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.LandedLogisticsEventResponse;
import com.enterprise.mm.payload.LandedLogisticsEventUpdateRequest;
import com.enterprise.mm.payload.LandedLogisticsEventCreateRequest;
import com.enterprise.mm.payload.LandedLogisticsEventSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.LandedLogisticsEventMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ImportDeliveryStatus;
import com.enterprise.mm.enums.LandedLogisticsEventType;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.LandedLogisticsEvent;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.LandedLogisticsEventRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LandedLogisticsEventService {
    private static final List<String> TRANSPORT_MILESTONES = List.of(
        LandedLogisticsEventType.BOOKED.name(),
        LandedLogisticsEventType.PICKED_UP.name(),
        LandedLogisticsEventType.DEPARTED_ORIGIN.name(),
        LandedLogisticsEventType.ARRIVED_BORDER.name(),
        LandedLogisticsEventType.AT_CUSTOMS.name(),
        LandedLogisticsEventType.CUSTOMS_RELEASED.name(),
        LandedLogisticsEventType.ARRIVED_WAREHOUSE.name(),
        LandedLogisticsEventType.DELIVERED.name()
    );
    private final String entityName = "LandedLogisticsEvent";
    private final LandedLogisticsEventRepository repository;
    private final LandedLogisticsEventMapper mapper;
    private final SupplyLogisticsGuard guard;
    private final ImportDeliveryRepository importDeliveryRepository;
    private final AuditService auditService;

    @Transactional
    public LandedLogisticsEventResponse create(LandedLogisticsEventCreateRequest dto) {
        LandedLogisticsEvent entity = mapper.toEntity(dto);
        normalizeForExternalIdentity(entity);
        LandedLogisticsEvent replay = findExternalReplay(entity);
        if (replay != null) {
            if (!sameRecordedEvent(entity, replay)) {
                throw new BusinessException("External logistics event identity already exists with different event data");
            }
            return mapper.toResponse(replay);
        }
        beforeCreate(entity, dto);
        LandedLogisticsEvent savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        LandedLogisticsEventResponse saved = mapper.toResponse(savedEntity);
        applyToImportDelivery(saved.getImportDeliveryId(), saved.getEventType(), saved.isExceptionFlag(), saved.getEventDate(), saved.getDescription());
        return saved;
    }
    @Transactional
    public LandedLogisticsEventResponse update(UUID id, LandedLogisticsEventUpdateRequest dto) {
        findEntity(id);
        throw new BusinessException("Recorded logistics events are append-only; create a correction event instead");
    }
    @Transactional
    public LandedLogisticsEventResponse resolve(UUID id) {
        LandedLogisticsEvent event = findEntity(id);
        if (!event.isExceptionFlag()) {
            throw new BusinessException("Only exception logistics event can be resolved");
        }
        event.setResolvedAt(LocalDateTime.now());
        event.setExceptionFlag(false);
        LandedLogisticsEvent saved = repository.save(event);
        ImportDelivery delivery = guard.importDelivery(saved.getImportDeliveryId());
        List<LandedLogisticsEvent> remainingExceptions = repository
            .findByTenantIdAndCompanyIdAndImportDeliveryIdAndExceptionFlagTrueAndResolvedAtIsNullAndIsDeletedFalseOrderByEventDateDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                saved.getImportDeliveryId()
            );
        if (remainingExceptions.isEmpty()) {
            delivery.setRiskFlag(false);
            delivery.setRiskReason(null);
        } else {
            delivery.setRiskFlag(true);
            delivery.setRiskReason(riskReason(remainingExceptions.get(0)));
        }
        importDeliveryRepository.save(delivery);
        auditService.log("LandedLogisticsEvent", id, "RESOLVE", event.getExternalEventId(), null, null);
        return mapper.toResponse(saved);
    }
    protected void beforeCreate(LandedLogisticsEvent entity, LandedLogisticsEventCreateRequest dto) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        ImportDelivery delivery = validate(entity);
        validateChronologyAndProgression(entity, delivery);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setStatus("RECORDED");
    }
    protected void beforeUpdate(LandedLogisticsEvent entity, LandedLogisticsEventUpdateRequest dto) {
        validate(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private ImportDelivery validate(LandedLogisticsEvent entity) {
        ImportDelivery delivery = guard.importDelivery(entity.getImportDeliveryId());
        entity.setEventType(normalizeEventType(entity.getEventType()));
        if (entity.getEventDate() == null) {
            entity.setEventDate(LocalDateTime.now());
        }
        if (LandedLogisticsEventType.EXCEPTION.name().equals(entity.getEventType())) {
            entity.setExceptionFlag(true);
        }
        normalizeExternalIdentity(entity);
        return delivery;
    }
    private void applyToImportDelivery(UUID importDeliveryId, String eventType, boolean exceptionFlag, LocalDateTime eventDate, String details) {
        ImportDelivery delivery = guard.importDelivery(importDeliveryId);
        delivery.setLastEventAt(eventDate == null ? LocalDateTime.now() : eventDate);
        if (transportRank(eventType) >= 0) {
            delivery.setCurrentMilestone(eventType);
        }
        if (exceptionFlag) {
            delivery.setRiskFlag(true);
            delivery.setRiskReason(details == null || details.isBlank() ? "Logistics exception" : details);
        }
        if (LandedLogisticsEventType.BOOKED.name().equals(eventType) && ImportDeliveryStatus.PLANNED.name().equals(delivery.getStatus())) {
            delivery.setStatus(ImportDeliveryStatus.BOOKED.name());
        } else if (LandedLogisticsEventType.PICKED_UP.name().equals(eventType) || LandedLogisticsEventType.DEPARTED_ORIGIN.name().equals(eventType)) {
            delivery.setStatus(ImportDeliveryStatus.IN_TRANSIT.name());
        } else if (LandedLogisticsEventType.AT_CUSTOMS.name().equals(eventType) || LandedLogisticsEventType.ARRIVED_BORDER.name().equals(eventType)) {
            delivery.setStatus(ImportDeliveryStatus.AT_CUSTOMS.name());
        } else if (LandedLogisticsEventType.CUSTOMS_RELEASED.name().equals(eventType)) {
            delivery.setStatus(ImportDeliveryStatus.CLEARED.name());
        } else if (LandedLogisticsEventType.DELIVERED.name().equals(eventType) || LandedLogisticsEventType.ARRIVED_WAREHOUSE.name().equals(eventType)) {
            delivery.setStatus(ImportDeliveryStatus.DELIVERED.name());
            delivery.setActualArrivalDate(LocalDate.now());
        }
        importDeliveryRepository.save(delivery);
    }
    private String normalizeEventType(String value) {
        String normalized = value == null || value.isBlank()
            ? LandedLogisticsEventType.BOOKED.name()
            : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (String candidate : Arrays.stream(LandedLogisticsEventType.values()).map(Enum::name).toList()) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException("Unsupported eventType: " + value);
    }
    @Transactional(readOnly = true)
    public Page<LandedLogisticsEventResponse> search(LandedLogisticsEventSearchRequest request, Pageable pageable) {
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
            filter.normalizedExternalSystem(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public LandedLogisticsEventResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public void softDelete(UUID id) {
        findEntity(id);
        throw new BusinessException("Recorded logistics events cannot be deleted; create a correction event instead");
    }
    @Transactional
    public LandedLogisticsEventResponse restore(UUID id) {
        LandedLogisticsEvent entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        LandedLogisticsEvent saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected LandedLogisticsEvent findEntity(UUID id) {
        LandedLogisticsEvent entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected boolean companyScoped() {
        return true;
    }

    private void normalizeForExternalIdentity(LandedLogisticsEvent entity) {
        entity.setEventType(normalizeEventType(entity.getEventType()));
        if (LandedLogisticsEventType.EXCEPTION.name().equals(entity.getEventType())) {
            entity.setExceptionFlag(true);
        }
        normalizeExternalIdentity(entity);
    }

    private void normalizeExternalIdentity(LandedLogisticsEvent entity) {
        if (entity.getExternalSystem() == null || entity.getExternalSystem().isBlank()) {
            entity.setExternalSystem(null);
        } else {
            entity.setExternalSystem(entity.getExternalSystem().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        }
        if (entity.getExternalEventId() == null || entity.getExternalEventId().isBlank()) {
            entity.setExternalEventId(null);
        } else {
            entity.setExternalEventId(entity.getExternalEventId().trim());
        }
    }

    private LandedLogisticsEvent findExternalReplay(LandedLogisticsEvent entity) {
        if (entity.getExternalSystem() == null || entity.getExternalEventId() == null) {
            return null;
        }
        return repository.findByTenantIdAndCompanyIdAndExternalSystemAndExternalEventIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            entity.getExternalSystem(),
            entity.getExternalEventId()
        ).orElse(null);
    }

    private boolean sameRecordedEvent(LandedLogisticsEvent incoming, LandedLogisticsEvent existing) {
        boolean sameExceptionSemantics = LandedLogisticsEventType.EXCEPTION.name().equals(incoming.getEventType())
            || incoming.isExceptionFlag() == existing.isExceptionFlag();
        boolean sameEventDate = incoming.getEventDate() == null || Objects.equals(incoming.getEventDate(), existing.getEventDate());
        return Objects.equals(incoming.getImportDeliveryId(), existing.getImportDeliveryId())
            && Objects.equals(incoming.getEventType(), existing.getEventType())
            && sameEventDate
            && blankOrEqual(incoming.getCode(), existing.getCode())
            && blankOrEqual(incoming.getName(), existing.getName())
            && Objects.equals(incoming.getDescription(), existing.getDescription())
            && Objects.equals(incoming.getLocation(), existing.getLocation())
            && Objects.equals(incoming.getCarrierStatus(), existing.getCarrierStatus())
            && sameExceptionSemantics;
    }

    private boolean blankOrEqual(String incoming, String existing) {
        return incoming == null || incoming.isBlank() || Objects.equals(incoming, existing);
    }

    private void validateChronologyAndProgression(LandedLogisticsEvent entity, ImportDelivery delivery) {
        LandedLogisticsEvent latest = repository
            .findFirstByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalseOrderByEventDateDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                entity.getImportDeliveryId()
            )
            .orElse(null);
        if (latest != null && latest.getEventDate() != null && entity.getEventDate().isBefore(latest.getEventDate())) {
            throw new BusinessException("Logistics event timestamp cannot be older than the latest recorded event");
        }
        int proposedRank = transportRank(entity.getEventType());
        if (proposedRank < 0) {
            return;
        }
        String currentMilestone = highestTransportMilestone(delivery, latest);
        if (currentMilestone != null && proposedRank < transportRank(currentMilestone)) {
            throw new BusinessException(
                "Logistics transport milestone cannot regress from " + currentMilestone + " to " + entity.getEventType()
            );
        }
    }

    private String highestTransportMilestone(ImportDelivery delivery, LandedLogisticsEvent latest) {
        String highest = canonicalTransportMilestone(delivery.getCurrentMilestone());
        int highestRank = transportRank(highest);
        if (highestRank < 0) {
            highest = canonicalTransportMilestone(delivery.getStatus());
            highestRank = transportRank(highest);
        }
        String latestMilestone = latest == null ? null : canonicalTransportMilestone(latest.getEventType());
        int latestRank = transportRank(latestMilestone);
        if (latestRank > highestRank) {
            highest = latestMilestone;
        }
        return highest;
    }

    private int transportRank(String value) {
        String milestone = canonicalTransportMilestone(value);
        return milestone == null ? -1 : TRANSPORT_MILESTONES.indexOf(milestone);
    }

    private String canonicalTransportMilestone(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "IN_TRANSIT" -> LandedLogisticsEventType.DEPARTED_ORIGIN.name();
            case "CLEARED" -> LandedLogisticsEventType.CUSTOMS_RELEASED.name();
            case "CLOSED" -> LandedLogisticsEventType.DELIVERED.name();
            default -> value;
        };
    }

    private String riskReason(LandedLogisticsEvent event) {
        return event.getDescription() == null || event.getDescription().isBlank()
            ? "Logistics exception"
            : event.getDescription();
    }
}
