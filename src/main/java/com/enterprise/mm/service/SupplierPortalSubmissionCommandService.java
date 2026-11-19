package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalReviewCommand;
import com.enterprise.mm.payload.SupplierPortalSubmissionResponse;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierPortalSubmissionCommandService {
    private static final String STALE_VERSION_MESSAGE =
        "Supplier portal submission was changed by another user; refresh and retry";

    private final SupplierPortalSubmissionService lifecycleService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public SupplierPortalSubmissionResponse accept(
        UUID submissionId,
        SupplierPortalReviewCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_PORTAL_SUBMISSION_ACCEPT",
            submissionId,
            command,
            idempotencyKey,
            () -> lifecycleService.accept(
                submissionId,
                new SupplierPortalActionRequest(command.comment().trim(), null)
            )
        );
    }

    @Transactional
    public SupplierPortalSubmissionResponse reject(
        UUID submissionId,
        SupplierPortalReviewCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_PORTAL_SUBMISSION_REJECT",
            submissionId,
            command,
            idempotencyKey,
            () -> lifecycleService.reject(
                submissionId,
                new SupplierPortalActionRequest(command.comment().trim(), null)
            )
        );
    }

    private SupplierPortalSubmissionResponse execute(
        String operation,
        UUID submissionId,
        SupplierPortalReviewCommand command,
        String idempotencyKey,
        Supplier<SupplierPortalSubmissionResponse> action
    ) {
        validate(command, idempotencyKey);
        requireMeaningful(command.comment(), "comment");
        return idempotencyService.execute(
            operation,
            submissionId,
            idempotencyKey,
            command,
            SupplierPortalSubmissionResponse.class,
            () -> {
                requireVersion(submissionId, command.expectedVersion());
                return action.get();
            }
        );
    }

    private void validate(SupplierPortalReviewCommand command, String idempotencyKey) {
        if (command == null || command.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required for supplier portal review commands");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for supplier portal review commands");
        }
    }

    private void requireVersion(UUID submissionId, Long expectedVersion) {
        Long actualVersion = lifecycleService.get(submissionId).getVersion();
        if (!Objects.equals(actualVersion, expectedVersion)) {
            throw new ConflictException(STALE_VERSION_MESSAGE);
        }
    }

    private void requireMeaningful(String value, String field) {
        if (value == null || value.trim().length() < 3) {
            throw new BadRequestException(field + " must contain a meaningful operator explanation");
        }
    }
}
