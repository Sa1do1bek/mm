package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.LandedLogisticsEvent;
import com.enterprise.mm.enums.ImportDeliveryStatus;
import com.enterprise.mm.enums.LandedLogisticsEventType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.LandedLogisticsEventMapper;
import com.enterprise.mm.payload.LandedLogisticsEventCreateRequest;
import com.enterprise.mm.payload.LandedLogisticsEventResponse;
import com.enterprise.mm.payload.LandedLogisticsEventUpdateRequest;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.LandedLogisticsEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LandedLogisticsEventIntegrityTest {
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Mock private LandedLogisticsEventRepository repository;
    @Spy private LandedLogisticsEventMapper mapper = new LandedLogisticsEventMapper();
    @Mock private SupplyLogisticsGuard guard;
    @Mock private ImportDeliveryRepository importDeliveryRepository;
    @Mock private AuditService auditService;
    @InjectMocks private LandedLogisticsEventService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(COMPANY_ID);
        when(repository.save(any(LandedLogisticsEvent.class))).thenAnswer(invocation -> {
            LandedLogisticsEvent event = invocation.getArgument(0);
            if (event.getId() == null) {
                event.setId(UUID.randomUUID());
            }
            return event;
        });
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void genericUpdateRejectsRecordedEventBeforeMapperPersistenceOrAudit() {
        LandedLogisticsEvent event = event(LandedLogisticsEventType.BOOKED, LocalDateTime.of(2026, 1, 1, 10, 0), "Booked");
        existing(event);
        LandedLogisticsEventUpdateRequest request = updateRequest(event);
        when(guard.importDelivery(event.getImportDeliveryId())).thenReturn(
            delivery(ImportDeliveryStatus.PLANNED, null, null)
        );

        assertThatThrownBy(() -> service.update(event.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Recorded logistics events are append-only; create a correction event instead");

        verify(mapper, never()).updateEntity(any(), any());
        verify(repository, never()).save(any());
        verifyNoInteractions(guard, importDeliveryRepository, auditService);
    }

    @Test
    void softDeleteRejectsRecordedEventWithoutPersistenceOrAudit() {
        LandedLogisticsEvent event = event(LandedLogisticsEventType.BOOKED, LocalDateTime.of(2026, 1, 1, 10, 0), "Booked");
        existing(event);

        assertThatThrownBy(() -> service.softDelete(event.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Recorded logistics events cannot be deleted; create a correction event instead");

        assertThat(event.isDeleted()).isFalse();
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void resolvingOneExceptionKeepsRiskFromNewestRemainingExceptionAndPreservesTransportMilestone() {
        LocalDateTime milestoneAt = LocalDateTime.of(2026, 1, 1, 9, 0);
        ImportDelivery delivery = delivery(ImportDeliveryStatus.IN_TRANSIT, LandedLogisticsEventType.DEPARTED_ORIGIN.name(), milestoneAt);
        LandedLogisticsEvent resolved = event(LandedLogisticsEventType.EXCEPTION, LocalDateTime.of(2026, 1, 1, 10, 0), "Resolved delay");
        resolved.setImportDeliveryId(delivery.getId());
        LandedLogisticsEvent older = event(LandedLogisticsEventType.EXCEPTION, LocalDateTime.of(2026, 1, 1, 10, 30), "Older unresolved risk");
        LandedLogisticsEvent newest = event(LandedLogisticsEventType.EXCEPTION, LocalDateTime.of(2026, 1, 1, 11, 0), "Newest unresolved risk");
        existing(resolved);
        when(guard.importDelivery(delivery.getId())).thenReturn(delivery);
        when(repository.findByTenantIdAndCompanyIdAndImportDeliveryIdAndExceptionFlagTrueAndResolvedAtIsNullAndIsDeletedFalseOrderByEventDateDesc(
            "default", COMPANY_ID, delivery.getId()
        )).thenReturn(List.of(newest, older));

        var response = service.resolve(resolved.getId());

        assertThat(response.isExceptionFlag()).isFalse();
        assertThat(response.getResolvedAt()).isNotNull();
        assertThat(delivery.isRiskFlag()).isTrue();
        assertThat(delivery.getRiskReason()).isEqualTo("Newest unresolved risk");
        assertThat(delivery.getCurrentMilestone()).isEqualTo(LandedLogisticsEventType.DEPARTED_ORIGIN.name());
        assertThat(delivery.getLastEventAt()).isEqualTo(milestoneAt);
        verify(importDeliveryRepository).save(delivery);
    }

    @Test
    void resolvingLastExceptionClearsRiskWithoutReplacingTransportMilestone() {
        LocalDateTime milestoneAt = LocalDateTime.of(2026, 1, 1, 9, 0);
        ImportDelivery delivery = delivery(ImportDeliveryStatus.DELIVERED, LandedLogisticsEventType.DELIVERED.name(), milestoneAt);
        LandedLogisticsEvent resolved = event(LandedLogisticsEventType.EXCEPTION, LocalDateTime.of(2026, 1, 1, 10, 0), "Damage");
        resolved.setImportDeliveryId(delivery.getId());
        existing(resolved);
        when(guard.importDelivery(delivery.getId())).thenReturn(delivery);
        when(repository.findByTenantIdAndCompanyIdAndImportDeliveryIdAndExceptionFlagTrueAndResolvedAtIsNullAndIsDeletedFalseOrderByEventDateDesc(
            "default", COMPANY_ID, delivery.getId()
        )).thenReturn(List.of());

        service.resolve(resolved.getId());

        assertThat(delivery.isRiskFlag()).isFalse();
        assertThat(delivery.getRiskReason()).isNull();
        assertThat(delivery.getCurrentMilestone()).isEqualTo(LandedLogisticsEventType.DELIVERED.name());
        assertThat(delivery.getLastEventAt()).isEqualTo(milestoneAt);
    }

    @Test
    void createRejectsTimestampOlderThanLatestRecordedEventBeforePersistenceOrAudit() {
        ImportDelivery delivery = delivery(ImportDeliveryStatus.IN_TRANSIT, LandedLogisticsEventType.DEPARTED_ORIGIN.name(), LocalDateTime.of(2026, 1, 1, 10, 0));
        LandedLogisticsEvent latest = event(LandedLogisticsEventType.DEPARTED_ORIGIN, LocalDateTime.of(2026, 1, 1, 10, 0), "Departed");
        LandedLogisticsEventCreateRequest request = createRequest(delivery.getId(), LandedLogisticsEventType.ARRIVED_BORDER, LocalDateTime.of(2026, 1, 1, 9, 59));
        when(guard.importDelivery(delivery.getId())).thenReturn(delivery);
        when(repository.findFirstByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalseOrderByEventDateDesc(
            "default", COMPANY_ID, delivery.getId()
        )).thenReturn(Optional.of(latest));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Logistics event timestamp cannot be older than the latest recorded event");

        verify(repository, never()).save(any());
        verifyNoInteractions(importDeliveryRepository, auditService);
    }

    @Test
    void createRejectsTransportMilestoneRegression() {
        ImportDelivery delivery = delivery(ImportDeliveryStatus.CLEARED, LandedLogisticsEventType.CUSTOMS_RELEASED.name(), LocalDateTime.of(2026, 1, 1, 10, 0));
        LandedLogisticsEventCreateRequest request = createRequest(delivery.getId(), LandedLogisticsEventType.PICKED_UP, LocalDateTime.of(2026, 1, 1, 11, 0));
        when(guard.importDelivery(delivery.getId())).thenReturn(delivery);
        when(repository.findFirstByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalseOrderByEventDateDesc(
            "default", COMPANY_ID, delivery.getId()
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Logistics transport milestone cannot regress from CUSTOMS_RELEASED to PICKED_UP");

        verify(repository, never()).save(any());
        verifyNoInteractions(importDeliveryRepository, auditService);
    }

    @Test
    void exceptionEventRaisesRiskWithoutOverwritingTransportMilestone() {
        LocalDateTime milestoneAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        ImportDelivery delivery = delivery(ImportDeliveryStatus.CLEARED, LandedLogisticsEventType.CUSTOMS_RELEASED.name(), milestoneAt);
        LandedLogisticsEventCreateRequest request = createRequest(delivery.getId(), LandedLogisticsEventType.EXCEPTION, LocalDateTime.of(2026, 1, 1, 11, 0));
        request.setDescription("Border documents need correction");
        when(guard.importDelivery(delivery.getId())).thenReturn(delivery);
        when(repository.findFirstByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalseOrderByEventDateDesc(
            "default", COMPANY_ID, delivery.getId()
        )).thenReturn(Optional.empty());

        assertThatCode(() -> service.create(request)).doesNotThrowAnyException();

        assertThat(delivery.isRiskFlag()).isTrue();
        assertThat(delivery.getRiskReason()).isEqualTo("Border documents need correction");
        assertThat(delivery.getCurrentMilestone()).isEqualTo(LandedLogisticsEventType.CUSTOMS_RELEASED.name());
        assertThat(delivery.getLastEventAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 11, 0));
        assertThat(delivery.getStatus()).isEqualTo(ImportDeliveryStatus.CLEARED.name());
    }

    @Test
    void repeatedCurrentTransportMilestoneIsNotTreatedAsRegression() {
        LocalDateTime latestAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        ImportDelivery delivery = delivery(
            ImportDeliveryStatus.DELIVERED,
            LandedLogisticsEventType.ARRIVED_WAREHOUSE.name(),
            latestAt
        );
        LandedLogisticsEvent latest = event(LandedLogisticsEventType.ARRIVED_WAREHOUSE, latestAt, "Warehouse arrival");
        latest.setImportDeliveryId(delivery.getId());
        LandedLogisticsEventCreateRequest request = createRequest(
            delivery.getId(),
            LandedLogisticsEventType.ARRIVED_WAREHOUSE,
            latestAt.plusMinutes(1)
        );
        when(guard.importDelivery(delivery.getId())).thenReturn(delivery);
        when(repository.findFirstByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalseOrderByEventDateDesc(
            "default", COMPANY_ID, delivery.getId()
        )).thenReturn(Optional.of(latest));

        assertThatCode(() -> service.create(request)).doesNotThrowAnyException();

        assertThat(delivery.getCurrentMilestone()).isEqualTo(LandedLogisticsEventType.ARRIVED_WAREHOUSE.name());
        verify(repository).save(any(LandedLogisticsEvent.class));
    }

    @Test
    void exactExternalReplayReturnsExistingEventWithoutSideEffectsOrAudit() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LandedLogisticsEvent existing = event(LandedLogisticsEventType.ARRIVED_BORDER, occurredAt, "Reached border");
        existing.setExternalSystem("CARRIER_API");
        existing.setExternalEventId("event-42");
        existing.setLocation("Yallama");
        existing.setCarrierStatus("At border");
        LandedLogisticsEventCreateRequest request = createRequest(existing.getImportDeliveryId(), LandedLogisticsEventType.ARRIVED_BORDER, occurredAt);
        request.setName(existing.getName());
        request.setDescription(existing.getDescription());
        request.setLocation(existing.getLocation());
        request.setCarrierStatus(existing.getCarrierStatus());
        request.setExternalSystem(" carrier-api ");
        request.setExternalEventId(" event-42 ");
        when(guard.importDelivery(existing.getImportDeliveryId())).thenReturn(
            delivery(ImportDeliveryStatus.IN_TRANSIT, LandedLogisticsEventType.DEPARTED_ORIGIN.name(), occurredAt.minusHours(1))
        );
        when(repository.findByTenantIdAndCompanyIdAndExternalSystemAndExternalEventIdAndIsDeletedFalse(
            "default", COMPANY_ID, "CARRIER_API", "event-42"
        )).thenReturn(Optional.of(existing));

        AtomicReference<LandedLogisticsEventResponse> response = new AtomicReference<>();
        assertThatCode(() -> response.set(service.create(request))).doesNotThrowAnyException();

        assertThat(response.get().getId()).isEqualTo(existing.getId());
        verify(repository).findByTenantIdAndCompanyIdAndExternalSystemAndExternalEventIdAndIsDeletedFalse(
            "default", COMPANY_ID, "CARRIER_API", "event-42"
        );
        verify(repository, never()).save(any());
        verifyNoInteractions(guard, importDeliveryRepository, auditService);
    }

    @Test
    void exactExternalReplayWithServerDefaultedTimestampReturnsExistingEvent() {
        LandedLogisticsEvent existing = event(
            LandedLogisticsEventType.BOOKED,
            LocalDateTime.of(2026, 1, 1, 10, 0),
            "Carrier booking"
        );
        existing.setExternalSystem("CARRIER_API");
        existing.setExternalEventId("event-with-default-time");
        LandedLogisticsEventCreateRequest request = createRequest(existing.getImportDeliveryId(), LandedLogisticsEventType.BOOKED, null);
        request.setName(existing.getName());
        request.setDescription(existing.getDescription());
        request.setExternalSystem("carrier-api");
        request.setExternalEventId(" event-with-default-time ");
        when(repository.findByTenantIdAndCompanyIdAndExternalSystemAndExternalEventIdAndIsDeletedFalse(
            "default", COMPANY_ID, "CARRIER_API", "event-with-default-time"
        )).thenReturn(Optional.of(existing));

        AtomicReference<LandedLogisticsEventResponse> response = new AtomicReference<>();
        assertThatCode(() -> response.set(service.create(request))).doesNotThrowAnyException();

        assertThat(response.get().getId()).isEqualTo(existing.getId());
        verify(repository, never()).save(any());
        verifyNoInteractions(guard, importDeliveryRepository, auditService);
    }

    @Test
    void conflictingExternalReplayIsRejectedWithoutSideEffectsOrAudit() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LandedLogisticsEvent existing = event(LandedLogisticsEventType.ARRIVED_BORDER, occurredAt, "Reached border");
        existing.setExternalSystem("CARRIER_API");
        existing.setExternalEventId("event-42");
        existing.setLocation("Yallama");
        LandedLogisticsEventCreateRequest request = createRequest(existing.getImportDeliveryId(), LandedLogisticsEventType.ARRIVED_BORDER, occurredAt);
        request.setName(existing.getName());
        request.setDescription(existing.getDescription());
        request.setLocation("Different border post");
        request.setExternalSystem("carrier api");
        request.setExternalEventId("event-42");
        when(guard.importDelivery(existing.getImportDeliveryId())).thenReturn(
            delivery(ImportDeliveryStatus.IN_TRANSIT, LandedLogisticsEventType.DEPARTED_ORIGIN.name(), occurredAt.minusHours(1))
        );
        when(repository.findByTenantIdAndCompanyIdAndExternalSystemAndExternalEventIdAndIsDeletedFalse(
            "default", COMPANY_ID, "CARRIER_API", "event-42"
        )).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("External logistics event identity already exists with different event data");

        verify(repository, never()).save(any());
        verifyNoInteractions(guard, importDeliveryRepository, auditService);
    }

    private LandedLogisticsEvent event(LandedLogisticsEventType type, LocalDateTime eventDate, String description) {
        LandedLogisticsEvent event = new LandedLogisticsEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId("default");
        event.setCompanyId(COMPANY_ID);
        event.setCode("LLE-" + event.getId().toString().substring(0, 8));
        event.setName(type.name());
        event.setStatus("RECORDED");
        event.setDescription(description);
        event.setImportDeliveryId(UUID.randomUUID());
        event.setEventType(type.name());
        event.setEventDate(eventDate);
        event.setExceptionFlag(type == LandedLogisticsEventType.EXCEPTION);
        return event;
    }

    private ImportDelivery delivery(ImportDeliveryStatus status, String milestone, LocalDateTime lastEventAt) {
        ImportDelivery delivery = new ImportDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setTenantId("default");
        delivery.setCompanyId(COMPANY_ID);
        delivery.setStatus(status.name());
        delivery.setCurrentMilestone(milestone);
        delivery.setLastEventAt(lastEventAt);
        delivery.setRiskFlag(true);
        delivery.setRiskReason("Existing risk");
        return delivery;
    }

    private LandedLogisticsEventCreateRequest createRequest(UUID importDeliveryId, LandedLogisticsEventType type, LocalDateTime eventDate) {
        LandedLogisticsEventCreateRequest request = new LandedLogisticsEventCreateRequest();
        request.setName(type.name());
        request.setDescription(type.name() + " details");
        request.setImportDeliveryId(importDeliveryId);
        request.setEventType(type.name());
        request.setEventDate(eventDate);
        return request;
    }

    private LandedLogisticsEventUpdateRequest updateRequest(LandedLogisticsEvent event) {
        LandedLogisticsEventUpdateRequest request = new LandedLogisticsEventUpdateRequest();
        request.setCode(event.getCode());
        request.setName(event.getName());
        request.setDescription(event.getDescription());
        request.setImportDeliveryId(event.getImportDeliveryId());
        request.setEventType(event.getEventType());
        request.setEventDate(event.getEventDate());
        return request;
    }

    private void existing(LandedLogisticsEvent event) {
        when(repository.findByIdAndIsDeletedFalse(event.getId())).thenReturn(Optional.of(event));
    }
}
