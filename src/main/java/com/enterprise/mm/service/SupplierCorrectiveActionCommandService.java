package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierCapaActionCommand;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierCapaActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.VersionedSupplierComplianceCommand;
import com.enterprise.mm.payload.SupplierCorrectiveActionResponse;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierCorrectiveActionCommandService {
    private static final String STALE_VERSION_MESSAGE =
        "Supplier CAPA was changed by another user; refresh and retry";

    private final SupplierCorrectiveActionService lifecycleService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public SupplierCorrectiveActionResponse open(
        UUID actionId,
        VersionedSupplierComplianceCommand command,
        String idempotencyKey
    ) {
        return execute("SUPPLIER_CAPA_OPEN", actionId, command, expectedVersion(command), idempotencyKey,
            () -> lifecycleService.open(actionId));
    }

    @Transactional
    public SupplierCorrectiveActionResponse start(
        UUID actionId,
        VersionedSupplierComplianceCommand command,
        String idempotencyKey
    ) {
        return execute("SUPPLIER_CAPA_START", actionId, command, expectedVersion(command), idempotencyKey,
            () -> lifecycleService.start(actionId));
    }

    @Transactional
    public SupplierCorrectiveActionResponse submit(
        UUID actionId,
        SupplierCapaActionCommand command,
        String idempotencyKey
    ) {
        return executeAction("SUPPLIER_CAPA_SUBMIT", actionId, command, idempotencyKey, false,
            request -> lifecycleService.submit(actionId, request));
    }

    @Transactional
    public SupplierCorrectiveActionResponse verify(
        UUID actionId,
        SupplierCapaActionCommand command,
        String idempotencyKey
    ) {
        return executeAction("SUPPLIER_CAPA_VERIFY", actionId, command, idempotencyKey, true,
            request -> lifecycleService.verify(actionId, request));
    }

    @Transactional
    public SupplierCorrectiveActionResponse close(
        UUID actionId,
        VersionedSupplierComplianceCommand command,
        String idempotencyKey
    ) {
        return execute("SUPPLIER_CAPA_CLOSE", actionId, command, expectedVersion(command), idempotencyKey,
            () -> lifecycleService.close(actionId));
    }

    @Transactional
    public SupplierCorrectiveActionResponse cancel(
        UUID actionId,
        SupplierCapaActionCommand command,
        String idempotencyKey
    ) {
        return executeAction("SUPPLIER_CAPA_CANCEL", actionId, command, idempotencyKey, true,
            request -> lifecycleService.cancel(actionId, request));
    }

    private SupplierCorrectiveActionResponse executeAction(
        String operation,
        UUID actionId,
        SupplierCapaActionCommand command,
        String idempotencyKey,
        boolean commentRequired,
        java.util.function.Function<SupplierCapaActionRequest, SupplierCorrectiveActionResponse> action
    ) {
        Long expectedVersion = expectedVersion(command);
        validate(expectedVersion, idempotencyKey);
        if (commentRequired) {
            requireMeaningful(command == null ? null : command.comment());
        }
        SupplierCapaActionRequest request = command == null
            ? null
            : new SupplierCapaActionRequest(trimToNull(command.comment()));
        return executeValidated(
            operation,
            actionId,
            command,
            expectedVersion,
            idempotencyKey,
            () -> action.apply(request)
        );
    }

    private SupplierCorrectiveActionResponse execute(
        String operation,
        UUID actionId,
        Object command,
        Long expectedVersion,
        String idempotencyKey,
        Supplier<SupplierCorrectiveActionResponse> action
    ) {
        validate(expectedVersion, idempotencyKey);
        return executeValidated(operation, actionId, command, expectedVersion, idempotencyKey, action);
    }

    private SupplierCorrectiveActionResponse executeValidated(
        String operation,
        UUID actionId,
        Object command,
        Long expectedVersion,
        String idempotencyKey,
        Supplier<SupplierCorrectiveActionResponse> action
    ) {
        return idempotencyService.execute(
            operation,
            actionId,
            idempotencyKey,
            command,
            SupplierCorrectiveActionResponse.class,
            () -> {
                requireVersion(actionId, expectedVersion);
                return action.get();
            }
        );
    }

    private Long expectedVersion(VersionedSupplierComplianceCommand command) {
        return command == null ? null : command.expectedVersion();
    }

    private Long expectedVersion(SupplierCapaActionCommand command) {
        return command == null ? null : command.expectedVersion();
    }

    private void validate(Long expectedVersion, String idempotencyKey) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required for supplier CAPA commands");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for supplier CAPA commands");
        }
    }

    private void requireVersion(UUID actionId, Long expectedVersion) {
        Long actualVersion = lifecycleService.get(actionId).getVersion();
        if (!Objects.equals(actualVersion, expectedVersion)) {
            throw new ConflictException(STALE_VERSION_MESSAGE);
        }
    }

    private void requireMeaningful(String value) {
        if (value == null || value.trim().length() < 3) {
            throw new BadRequestException("comment must contain a meaningful operator explanation");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
