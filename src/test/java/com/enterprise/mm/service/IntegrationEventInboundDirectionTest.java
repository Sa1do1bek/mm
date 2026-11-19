package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.enums.IntegrationEventDirection;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.IntegrationEventMapper;
import com.enterprise.mm.payload.IntegrationEventCreateRequest;
import com.enterprise.mm.payload.IntegrationEventUpdateRequest;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.BusinessDocumentReference;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationEventInboundDirectionTest {
    @Mock private IntegrationEventRepository repository;
    @Mock private AuditService auditService;
    @Mock private IntegrationBusinessDocumentResolver documentResolver;

    private IntegrationEventService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-inbound-callback");
        CompanyContext.setCompany(companyId);
        lenient().when(documentResolver.resolve(any())).thenAnswer(invocation -> {
            IntegrationEvent event = invocation.getArgument(0);
            return new BusinessDocumentReference(
                event.getAggregateType(),
                event.getAggregateId(),
                event.getCode(),
                event.getName(),
                null,
                null,
                event.getCode(),
                event.getAggregateId() != null
            );
        });
        service = new IntegrationEventService(
            repository,
            new IntegrationEventMapper(documentResolver),
            new ObjectMapper(),
            auditService
        );
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void inboundCallbackIsTerminalAndCannotEnterTheNewRetryOutbox() {
        IntegrationEventCreateRequest request = new IntegrationEventCreateRequest();
        request.setCode("TREASURY.PAYMENT_STATUS_PAID-42");
        request.setName("Treasury paid callback");
        request.setStatus(IntegrationEventStatus.NEW.name());
        request.setEventType("TREASURY.PAYMENT_STATUS_PAID");
        request.setAggregateType("TREASURY");
        request.setAggregateId(UUID.randomUUID());
        request.setExternalId("BANK-42");
        when(repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            "tenant-inbound-callback", companyId, "callback-42"
        )).thenReturn(Optional.empty());
        when(repository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> {
            IntegrationEvent event = invocation.getArgument(0);
            event.setId(UUID.randomUUID());
            event.setTenantId("tenant-inbound-callback");
            event.setCreatedAt(LocalDateTime.now());
            event.setVersion(0L);
            return event;
        });

        var response = service.recordInboundCallbackIdempotent(request, " callback-42 ");

        ArgumentCaptor<IntegrationEvent> event = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(repository).save(event.capture());
        assertThat(event.getValue().getDirection()).isEqualTo(IntegrationEventDirection.INBOUND.name());
        assertThat(event.getValue().getStatus()).isEqualTo(IntegrationEventStatus.PUBLISHED.name());
        assertThat(event.getValue().getNextRetryAt()).isNull();
        assertThat(event.getValue().getRetryCount()).isZero();
        assertThat(response.getStatus()).isEqualTo("SUCCEEDED");
        verify(auditService).log(
            "IntegrationEvent",
            event.getValue().getId(),
            "RECEIVE",
            "TREASURY.PAYMENT_STATUS_PAID",
            null,
            null
        );
    }

    @Test
    void reusedCallbackKeyWithDifferentCorrelationFieldsReturnsConflict() {
        UUID aggregateId = UUID.randomUUID();
        IntegrationEvent existing = new IntegrationEvent();
        existing.setId(UUID.randomUUID());
        existing.setTenantId("tenant-inbound-callback");
        existing.setCompanyId(companyId);
        existing.setName("Treasury paid callback");
        existing.setDirection(IntegrationEventDirection.INBOUND.name());
        existing.setStatus(IntegrationEventStatus.PUBLISHED.name());
        existing.setEventType("TREASURY.PAYMENT_STATUS_PAID");
        existing.setAggregateType("TREASURY");
        existing.setAggregateId(aggregateId);
        existing.setExternalId("BANK-ORIGINAL");
        existing.setDescription("PAID");
        existing.setPayload("{\"status\":\"PAID\"}");
        when(repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            "tenant-inbound-callback", companyId, "callback-reused"
        )).thenReturn(Optional.of(existing));

        IntegrationEventCreateRequest changed = new IntegrationEventCreateRequest();
        changed.setEventType("TREASURY.PAYMENT_STATUS_PAID");
        changed.setAggregateType("TREASURY");
        changed.setAggregateId(aggregateId);
        changed.setExternalId("BANK-DIFFERENT");
        changed.setDescription("PAID");
        changed.setPayload("{\"status\":\"PAID\"}");

        assertThatThrownBy(() -> service.recordInboundCallbackIdempotent(changed, "callback-reused"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("different integration callback");
        verify(repository, never()).save(any(IntegrationEvent.class));
    }

    @Test
    void outboundEventUsesCallerProvidedIdempotencyKeyInsideCurrentTransaction() {
        UUID aggregateId = UUID.randomUUID();
        when(repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            "tenant-inbound-callback", companyId, "award-event-42"
        )).thenReturn(Optional.empty());
        when(repository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> {
            IntegrationEvent event = invocation.getArgument(0);
            event.setId(UUID.randomUUID());
            event.setTenantId("tenant-inbound-callback");
            event.setCompanyId(companyId);
            event.setCreatedAt(LocalDateTime.now());
            event.setVersion(0L);
            return event;
        });

        IntegrationEvent saved = service.emitIdempotentInCurrentTransaction(
            "PROCUREMENT.AWARDED",
            "ProcurementProcedure",
            aggregateId,
            Map.of("procedureId", aggregateId),
            " award-event-42 "
        );

        ArgumentCaptor<IntegrationEvent> event = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(repository).save(event.capture());
        assertThat(saved).isSameAs(event.getValue());
        assertThat(event.getValue().getDirection()).isEqualTo(IntegrationEventDirection.OUTBOUND.name());
        assertThat(event.getValue().getStatus()).isEqualTo(IntegrationEventStatus.NEW.name());
        assertThat(event.getValue().getRetryCount()).isZero();
        assertThat(event.getValue().getIdempotencyKey()).isEqualTo("award-event-42");
        assertThat(event.getValue().getPayload()).contains("\"procedureId\"");
        verify(auditService).logInCurrentTransaction(
            "IntegrationEvent",
            event.getValue().getId(),
            "EMIT",
            "PROCUREMENT.AWARDED",
            null,
            null
        );
    }

    @Test
    void outboundEventReusesMatchingIdempotencyKeyWithoutDuplicatingOutboxRows() {
        UUID aggregateId = UUID.randomUUID();
        IntegrationEvent existing = new IntegrationEvent();
        existing.setId(UUID.randomUUID());
        existing.setTenantId("tenant-inbound-callback");
        existing.setCompanyId(companyId);
        existing.setName("Procurement awarded");
        existing.setDirection(IntegrationEventDirection.OUTBOUND.name());
        existing.setStatus(IntegrationEventStatus.NEW.name());
        existing.setEventType("PROCUREMENT.AWARDED");
        existing.setAggregateType("ProcurementProcedure");
        existing.setAggregateId(aggregateId);
        existing.setPayload("{\"procedureId\":\"" + aggregateId + "\"}");
        when(repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            "tenant-inbound-callback", companyId, "award-event-42"
        )).thenReturn(Optional.of(existing));

        IntegrationEvent result = service.emitIdempotentInCurrentTransaction(
            "PROCUREMENT.AWARDED",
            "ProcurementProcedure",
            aggregateId,
            Map.of("procedureId", aggregateId.toString()),
            "award-event-42"
        );

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any(IntegrationEvent.class));
    }

    @Test
    void reusedOutboundKeyWithDifferentPayloadReturnsConflict() {
        UUID aggregateId = UUID.randomUUID();
        IntegrationEvent existing = new IntegrationEvent();
        existing.setId(UUID.randomUUID());
        existing.setTenantId("tenant-inbound-callback");
        existing.setCompanyId(companyId);
        existing.setName("Procurement awarded");
        existing.setDirection(IntegrationEventDirection.OUTBOUND.name());
        existing.setStatus(IntegrationEventStatus.NEW.name());
        existing.setEventType("PROCUREMENT.AWARDED");
        existing.setAggregateType("ProcurementProcedure");
        existing.setAggregateId(aggregateId);
        existing.setPayload("{\"procedureId\":\"" + aggregateId + "\"}");
        when(repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            "tenant-inbound-callback", companyId, "award-event-42"
        )).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.emitIdempotentInCurrentTransaction(
            "PROCUREMENT.AWARDED",
            "ProcurementProcedure",
            aggregateId,
            Map.of("procedureId", aggregateId.toString(), "supplierId", UUID.randomUUID().toString()),
            "award-event-42"
        )).isInstanceOf(ConflictException.class)
            .hasMessageContaining("different outbound integration event");

        verify(repository, never()).save(any(IntegrationEvent.class));
    }

    @Test
    void inboundCallbackCannotBeMutatedThroughGenericLifecycleMethods() {
        UUID eventId = UUID.randomUUID();
        IntegrationEvent inbound = new IntegrationEvent();
        inbound.setId(eventId);
        inbound.setTenantId("tenant-inbound-callback");
        inbound.setCompanyId(companyId);
        inbound.setDirection(IntegrationEventDirection.INBOUND.name());
        inbound.setName("Treasury paid callback");
        inbound.setStatus(IntegrationEventStatus.PUBLISHED.name());
        when(repository.findByIdAndIsDeletedFalse(eventId)).thenReturn(Optional.of(inbound));
        when(repository.findAnyById(eventId)).thenReturn(Optional.of(inbound));

        IntegrationEventUpdateRequest update = new IntegrationEventUpdateRequest();
        update.setName("Rewritten callback");
        update.setEventType("TREASURY.PAYMENT_STATUS_FAILED");

        assertThatThrownBy(() -> service.update(eventId, update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Inbound integration callback is immutable");
        assertThatThrownBy(() -> service.softDelete(eventId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Inbound integration callback is immutable");
        assertThatThrownBy(() -> service.restore(eventId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Inbound integration callback is immutable");
        assertThatThrownBy(() -> service.markFailed(eventId, "operator override"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Inbound integration callback is immutable");
        verify(repository, never()).save(any(IntegrationEvent.class));
    }
}
