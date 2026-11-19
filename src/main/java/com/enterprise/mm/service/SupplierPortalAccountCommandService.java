package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalLifecycleCommand;
import com.enterprise.mm.payload.SupplierPortalAccountResponse;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierPortalAccountCommandService {
    private static final String STALE_VERSION_MESSAGE =
        "Supplier portal account was changed by another user; refresh and retry";

    private final SupplierPortalAccountService lifecycleService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public SupplierPortalAccountResponse suspend(
        UUID accountId,
        SupplierPortalLifecycleCommand command,
        String idempotencyKey
    ) {
        requireMeaningful(command == null ? null : command.comment(), "comment");
        return execute(
            "SUPPLIER_PORTAL_SUSPEND",
            accountId,
            command,
            idempotencyKey,
            () -> lifecycleService.suspend(
                accountId,
                new SupplierPortalActionRequest(trimToNull(command.comment()), null)
            )
        );
    }

    @Transactional
    public SupplierPortalAccountResponse revoke(
        UUID accountId,
        SupplierPortalLifecycleCommand command,
        String idempotencyKey
    ) {
        requireMeaningful(command == null ? null : command.comment(), "comment");
        return execute(
            "SUPPLIER_PORTAL_REVOKE",
            accountId,
            command,
            idempotencyKey,
            () -> lifecycleService.revoke(
                accountId,
                new SupplierPortalActionRequest(trimToNull(command.comment()), null)
            )
        );
    }

    @Transactional
    public SupplierPortalAccountResponse reinvite(
        UUID accountId,
        SupplierPortalLifecycleCommand command,
        String idempotencyKey
    ) {
        validate(command, idempotencyKey);
        requireMeaningful(command.comment(), "comment");
        return idempotencyService.execute(
            "SUPPLIER_PORTAL_REINVITE",
            accountId,
            idempotencyKey,
            command,
            SupplierPortalAccountResponse.class,
            () -> lifecycleService.reinvite(
                accountId,
                command.expectedVersion(),
                trimToNull(command.comment())
            )
        );
    }

    private SupplierPortalAccountResponse execute(
        String operation,
        UUID accountId,
        SupplierPortalLifecycleCommand command,
        String idempotencyKey,
        Supplier<SupplierPortalAccountResponse> action
    ) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            operation,
            accountId,
            idempotencyKey,
            command,
            SupplierPortalAccountResponse.class,
            () -> {
                requireVersion(accountId, command.expectedVersion());
                return action.get();
            }
        );
    }

    private void validate(SupplierPortalLifecycleCommand command, String idempotencyKey) {
        if (command == null || command.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required for supplier portal commands");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for supplier portal commands");
        }
    }

    private void requireVersion(UUID accountId, Long expectedVersion) {
        Long actualVersion = lifecycleService.get(accountId).getVersion();
        if (!Objects.equals(actualVersion, expectedVersion)) {
            throw new ConflictException(STALE_VERSION_MESSAGE);
        }
    }

    private void requireMeaningful(String value, String field) {
        if (value == null || value.trim().length() < 3) {
            throw new BadRequestException(field + " must contain a meaningful operator explanation");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
