package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.IntegrationHealthCommandDtos.OperatorCommand;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.CommandResult;
import com.enterprise.mm.repository.IntegrationEventRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationHealthCommandServiceTest {
    @Mock IntegrationEventRepository eventRepository;
    @Mock IntegrationDispatchService dispatchService;
    @Mock IdempotencyService idempotencyService;
    @Mock AuditService auditService;

    private IntegrationHealthCommandService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-integration-health");
        CompanyContext.setCompany(companyId);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.TestingAuthenticationToken(
                "operator", "n/a", "ROLE_ADMIN"));
        service = new IntegrationHealthCommandService(
            eventRepository, dispatchService, idempotencyService, auditService, new IntegrationHealthActionPolicy());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void retryUsesVersionReasonReplayBoundaryAndOriginalTransportIdentity() {
        IntegrationEvent event = event(IntegrationEventStatus.RETRY, 4L);
        String transportKey = event.getIdempotencyKey();
        OperatorCommand command = new OperatorCommand(4L, "Provider route restored");
        executeIdempotently("INTEGRATION_HEALTH_RETRY", event.getId(), "cmd-retry", command);
        when(eventRepository.findCommandEventForUpdate("tenant-integration-health", companyId, event.getId()))
            .thenReturn(Optional.of(event));
        when(eventRepository.saveAndFlush(event)).thenReturn(event);
        when(dispatchService.dispatch(event.getId())).thenReturn(event);

        CommandResult result = service.retry(event.getId(), command, "cmd-retry");

        assertThat(result.eventId()).isEqualTo(event.getId());
        assertThat(event.getStatus()).isEqualTo(IntegrationEventStatus.NEW.name());
        assertThat(event.getManualReason()).isEqualTo("Provider route restored");
        assertThat(event.getIdempotencyKey()).isEqualTo(transportKey);
        verify(auditService).log("IntegrationEvent", event.getId(), "OPERATOR_RETRY_REQUESTED", "Provider route restored", null, null);
        verify(dispatchService).dispatch(event.getId());
    }

    @Test
    void replayCreatesLinkedEventWithNewStableTransportIdentityAndLeavesOriginalResultUntouched() {
        IntegrationEvent original = event(IntegrationEventStatus.PUBLISHED, 7L);
        OperatorCommand command = new OperatorCommand(7L, "Provider requested a controlled replay");
        executeIdempotently("INTEGRATION_HEALTH_REPLAY", original.getId(), "cmd-replay", command);
        when(eventRepository.findCommandEventForUpdate("tenant-integration-health", companyId, original.getId()))
            .thenReturn(Optional.of(original));
        when(eventRepository.saveAndFlush(any(IntegrationEvent.class))).thenAnswer(invocation -> {
            IntegrationEvent saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return saved;
        });
        when(dispatchService.dispatch(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            IntegrationEvent dispatched = new IntegrationEvent();
            dispatched.setId(id);
            dispatched.setStatus(IntegrationEventStatus.NEW.name());
            return dispatched;
        });

        CommandResult result = service.replay(original.getId(), command, "cmd-replay");

        ArgumentCaptor<IntegrationEvent> replayCaptor = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(eventRepository).saveAndFlush(replayCaptor.capture());
        IntegrationEvent replay = replayCaptor.getValue();
        assertThat(replay.getReplayOfEventId()).isEqualTo(original.getId());
        assertThat(replay.getIdempotencyKey()).startsWith("replay:" + original.getId()).isNotEqualTo(original.getIdempotencyKey());
        assertThat(replay.getPayload()).isEqualTo(original.getPayload());
        assertThat(replay.getManualReason()).isEqualTo(command.reason());
        assertThat(result.replayEventId()).isEqualTo(replay.getId());
        assertThat(original.getStatus()).isEqualTo(IntegrationEventStatus.PUBLISHED.name());
    }

    @Test
    void cancelFailsOnStaleVersionBeforeMutationOrDispatch() {
        IntegrationEvent event = event(IntegrationEventStatus.RETRY, 9L);
        OperatorCommand command = new OperatorCommand(8L, "Duplicate event confirmed");
        executeIdempotently("INTEGRATION_HEALTH_CANCEL", event.getId(), "cmd-cancel", command);
        when(eventRepository.findCommandEventForUpdate("tenant-integration-health", companyId, event.getId()))
            .thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.cancel(event.getId(), command, "cmd-cancel"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("version conflict");
        verify(eventRepository, never()).saveAndFlush(any());
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void everyOperatorCommandRequiresMeaningfulReasonAndIdempotencyKey() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.retry(id, new OperatorCommand(1L, " "), "key"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("reason");
        assertThatThrownBy(() -> service.replay(id, new OperatorCommand(1L, "valid reason"), " "))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");
    }

    @SuppressWarnings("unchecked")
    private void executeIdempotently(String operation, UUID id, String key, OperatorCommand command) {
        when(idempotencyService.execute(eq(operation), eq(id), eq(key), eq(command), eq(CommandResult.class), any()))
            .thenAnswer(invocation -> ((Supplier<CommandResult>) invocation.getArgument(5)).get());
    }

    private IntegrationEvent event(IntegrationEventStatus status, long version) {
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId("tenant-integration-health");
        event.setCompanyId(companyId);
        event.setCode("INT-100");
        event.setName("Finance posting");
        event.setStatus(status.name());
        event.setVersion(version);
        event.setEventType("FI.STOCK_RECEIPT_POSTED");
        event.setAggregateType("FI");
        event.setAggregateId(UUID.randomUUID());
        event.setPayload("{\"purchaseOrderId\":\"" + UUID.randomUUID() + "\"}");
        event.setRetryCount(2);
        event.setIdempotencyKey("transport-identity");
        return event;
    }
}
