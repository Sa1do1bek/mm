package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierKycDecisionCommand;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierKycDecisionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.VersionedSupplierComplianceCommand;
import com.enterprise.mm.payload.SupplierKycCheckResponse;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierKycCheckCommandService {
    private static final String STALE_VERSION_MESSAGE =
        "Supplier KYC check was changed by another user; refresh and retry";

    private final SupplierKycCheckService lifecycleService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public SupplierKycCheckResponse request(
        UUID checkId,
        VersionedSupplierComplianceCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_KYC_REQUEST",
            checkId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> lifecycleService.request(checkId)
        );
    }

    @Transactional
    public SupplierKycCheckResponse pass(
        UUID checkId,
        SupplierKycDecisionCommand command,
        String idempotencyKey
    ) {
        return executeDecision("SUPPLIER_KYC_PASS", checkId, command, idempotencyKey, false,
            request -> lifecycleService.pass(checkId, request));
    }

    @Transactional
    public SupplierKycCheckResponse fail(
        UUID checkId,
        SupplierKycDecisionCommand command,
        String idempotencyKey
    ) {
        return executeDecision("SUPPLIER_KYC_FAIL", checkId, command, idempotencyKey, true,
            request -> lifecycleService.fail(checkId, request));
    }

    @Transactional
    public SupplierKycCheckResponse waive(
        UUID checkId,
        SupplierKycDecisionCommand command,
        String idempotencyKey
    ) {
        return executeDecision("SUPPLIER_KYC_WAIVE", checkId, command, idempotencyKey, true,
            request -> lifecycleService.waive(checkId, request));
    }

    @Transactional
    public SupplierKycCheckResponse expire(
        UUID checkId,
        VersionedSupplierComplianceCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_KYC_EXPIRE",
            checkId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> lifecycleService.expire(checkId)
        );
    }

    private SupplierKycCheckResponse executeDecision(
        String operation,
        UUID checkId,
        SupplierKycDecisionCommand command,
        String idempotencyKey,
        boolean findingsRequired,
        java.util.function.Function<SupplierKycDecisionRequest, SupplierKycCheckResponse> action
    ) {
        Long expectedVersion = expectedVersion(command);
        validate(expectedVersion, idempotencyKey);
        if (findingsRequired) {
            requireMeaningful(command == null ? null : command.findings(), "findings");
        }
        SupplierKycDecisionRequest decision = command == null
            ? null
            : new SupplierKycDecisionRequest(
                command.riskLevel(),
                trimToNull(command.findings()),
                command.remediationRequired()
            );
        return executeValidated(
            operation,
            checkId,
            command,
            expectedVersion,
            idempotencyKey,
            () -> action.apply(decision)
        );
    }

    private SupplierKycCheckResponse execute(
        String operation,
        UUID checkId,
        Object command,
        Long expectedVersion,
        String idempotencyKey,
        Supplier<SupplierKycCheckResponse> action
    ) {
        validate(expectedVersion, idempotencyKey);
        return executeValidated(operation, checkId, command, expectedVersion, idempotencyKey, action);
    }

    private SupplierKycCheckResponse executeValidated(
        String operation,
        UUID checkId,
        Object command,
        Long expectedVersion,
        String idempotencyKey,
        Supplier<SupplierKycCheckResponse> action
    ) {
        return idempotencyService.execute(
            operation,
            checkId,
            idempotencyKey,
            command,
            SupplierKycCheckResponse.class,
            () -> {
                requireVersion(checkId, expectedVersion);
                return action.get();
            }
        );
    }

    private Long expectedVersion(VersionedSupplierComplianceCommand command) {
        return command == null ? null : command.expectedVersion();
    }

    private Long expectedVersion(SupplierKycDecisionCommand command) {
        return command == null ? null : command.expectedVersion();
    }

    private void validate(Long expectedVersion, String idempotencyKey) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required for supplier KYC commands");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for supplier KYC commands");
        }
    }

    private void requireVersion(UUID checkId, Long expectedVersion) {
        Long actualVersion = lifecycleService.get(checkId).getVersion();
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
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
