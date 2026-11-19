package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.IntegrationHealthCommandDtos.OperatorCommand;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.CommandResult;
import com.enterprise.mm.repository.IntegrationEventRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntegrationHealthCommandService {
    private static final Set<String> RETRY_STATES = Set.of("NEW", "FAILED", "RETRY", "DEAD");
    private static final Set<String> REPLAY_STATES = Set.of("PUBLISHED", "FAILED", "RETRY", "DEAD", "CANCELLED");
    private static final Set<String> CANCEL_STATES = Set.of("NEW", "FAILED", "RETRY", "DEAD");

    private final IntegrationEventRepository eventRepository;
    private final IntegrationDispatchService dispatchService;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final IntegrationHealthActionPolicy actionPolicy;

    @Transactional
    public CommandResult retry(UUID eventId, OperatorCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "INTEGRATION_HEALTH_RETRY",
            eventId,
            idempotencyKey,
            command,
            CommandResult.class,
            () -> retryNow(eventId, command)
        );
    }

    @Transactional
    public CommandResult replay(UUID eventId, OperatorCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "INTEGRATION_HEALTH_REPLAY",
            eventId,
            idempotencyKey,
            command,
            CommandResult.class,
            () -> replayNow(eventId, command, idempotencyKey)
        );
    }

    @Transactional
    public CommandResult cancel(UUID eventId, OperatorCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "INTEGRATION_HEALTH_CANCEL",
            eventId,
            idempotencyKey,
            command,
            CommandResult.class,
            () -> cancelNow(eventId, command)
        );
    }

    private CommandResult retryNow(UUID eventId, OperatorCommand command) {
        IntegrationEvent event = commandEvent(eventId, command.expectedVersion());
        actionPolicy.requireActionAllowed(event, "RETRY");
        requireState(event, RETRY_STATES, "retry");
        if (IntegrationEventStatus.DEAD.name().equals(normalize(event.getStatus()))) {
            event.setRetryCount(0);
        }
        event.setStatus(IntegrationEventStatus.NEW.name());
        event.setNextRetryAt(null);
        event.setManualReason(command.reason().trim());
        eventRepository.saveAndFlush(event);
        auditService.log("IntegrationEvent", event.getId(), "OPERATOR_RETRY_REQUESTED", command.reason().trim(), null, null);
        IntegrationEvent dispatched = dispatchService.dispatch(event.getId());
        eventRepository.flush();
        return result(dispatched, null, "Retry executed with the original transport idempotency identity");
    }

    private CommandResult replayNow(UUID eventId, OperatorCommand command, String idempotencyKey) {
        IntegrationEvent original = commandEvent(eventId, command.expectedVersion());
        actionPolicy.requireActionAllowed(original, "REPLAY");
        requireState(original, REPLAY_STATES, "replay");

        IntegrationEvent replay = new IntegrationEvent();
        replay.setCode(replayCode(original));
        replay.setName("Replay of " + display(original.getName(), null, "integration event"));
        replay.setDescription(original.getDescription());
        replay.setStatus(IntegrationEventStatus.NEW.name());
        replay.setEventType(original.getEventType());
        replay.setAggregateType(original.getAggregateType());
        replay.setAggregateId(original.getAggregateId());
        replay.setPayload(original.getPayload());
        replay.setRetryCount(0);
        replay.setIdempotencyKey(replayTransportKey(original, idempotencyKey));
        replay.setReplayOfEventId(original.getReplayOfEventId() == null ? original.getId() : original.getReplayOfEventId());
        replay.setManualReason(command.reason().trim());
        replay.setCompanyId(original.getCompanyId());
        replay.setTenantId(original.getTenantId());
        replay = eventRepository.saveAndFlush(replay);

        auditService.log("IntegrationEvent", original.getId(), "OPERATOR_REPLAY_REQUESTED", replay.getId() + ": " + command.reason().trim(), null, null);
        auditService.log("IntegrationEvent", replay.getId(), "REPLAY_CREATED", original.getId() + ": " + command.reason().trim(), null, null);
        IntegrationEvent dispatched = dispatchService.dispatch(replay.getId());
        eventRepository.flush();
        return result(original, dispatched.getId(), "Replay created and dispatched as a linked event");
    }

    private CommandResult cancelNow(UUID eventId, OperatorCommand command) {
        IntegrationEvent event = commandEvent(eventId, command.expectedVersion());
        actionPolicy.requireActionAllowed(event, "CANCEL");
        requireState(event, CANCEL_STATES, "cancel");
        event.setStatus(IntegrationEventStatus.CANCELLED.name());
        event.setNextRetryAt(null);
        event.setManualReason(command.reason().trim());
        event = eventRepository.saveAndFlush(event);
        auditService.log("IntegrationEvent", event.getId(), "OPERATOR_CANCELLED", command.reason().trim(), null, null);
        return result(event, null, "Event cancelled by operator decision");
    }

    private IntegrationEvent commandEvent(UUID eventId, Long expectedVersion) {
        IntegrationEvent event = eventRepository.findCommandEventForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            eventId
        ).orElseThrow(() -> new NotFoundException("Integration event not found: " + eventId));
        if (!Objects.equals(event.getVersion(), expectedVersion)) {
            throw new BusinessException(
                "Integration event version conflict: expected=" + expectedVersion + ", actual=" + event.getVersion()
            );
        }
        return event;
    }

    private void requireState(IntegrationEvent event, Set<String> allowed, String action) {
        if (!allowed.contains(normalize(event.getStatus()))) {
            throw new BusinessException("Integration event cannot " + action + " from status " + event.getStatus());
        }
    }

    private void validate(OperatorCommand command, String idempotencyKey) {
        if (command == null || command.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required for integration commands");
        }
        if (command.reason() == null || command.reason().trim().length() < 3) {
            throw new BadRequestException("A meaningful reason is required for integration commands");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for integration commands");
        }
    }

    private CommandResult result(IntegrationEvent event, UUID replayEventId, String message) {
        String status = normalize(event.getStatus());
        return new CommandResult(
            event.getId(),
            event.getStatus(),
            event.getVersion(),
            replayEventId,
            message,
            nextStage(status),
            nextOwner(status));
    }

    private String nextStage(String status) {
        return switch (status) {
            case "NEW" -> "DISPATCH_PENDING";
            case "PUBLISHED" -> "PUBLISHED";
            case "FAILED", "RETRY" -> "AUTOMATIC_RECOVERY";
            case "DEAD" -> "MANUAL_DECISION";
            case "CANCELLED" -> "CANCELLED";
            default -> status;
        };
    }

    private String nextOwner(String status) {
        return switch (status) {
            case "FAILED", "DEAD" -> "INTEGRATION_OPERATOR";
            case "PUBLISHED" -> "SYSTEM";
            default -> "SYSTEM";
        };
    }

    private String replayCode(IntegrationEvent original) {
        String base = display(original.getCode(), "INT");
        String suffix = "-R" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        int maxBase = Math.max(1, 80 - suffix.length());
        return (base.length() > maxBase ? base.substring(0, maxBase) : base) + suffix;
    }

    private String replayTransportKey(IntegrationEvent original, String commandKey) {
        UUID stable = UUID.nameUUIDFromBytes(commandKey.trim().getBytes(StandardCharsets.UTF_8));
        return "replay:" + original.getId() + ":" + stable;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String display(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "integration event";
    }
}
