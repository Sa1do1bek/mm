package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.BuyerCollaborationCommand;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.CommitmentView;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.DispatchView;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.ResponseView;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.service.IdempotencyService;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal buyer command layer for the "Supplier collaboration" tab -- same
 * expectedVersion + Idempotency-Key pattern as
 * {@code SupplierAccreditationCommandService}. Wraps the domain services
 * ({@link SupplierPoDispatchService}, {@link SupplierPoBuyerDecisionService}) and
 * converts their entity results to the frontend-facing view DTOs via
 * {@link SupplierPoCollaborationQueryService}.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoCollaborationCommandService {
    private final SupplierPoDispatchService dispatchService;
    private final SupplierPoBuyerDecisionService buyerDecisionService;
    private final SupplierPoCollaborationQueryService queryService;
    private final SupplierPoResponseRepository responseRepository;
    private final IdempotencyService idempotencyService;

    @Transactional
    public DispatchView resendDispatch(UUID dispatchId, BuyerCollaborationCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "PO_SUPPLIER_DISPATCH_RESEND",
            dispatchId,
            idempotencyKey,
            command,
            DispatchView.class,
            () -> queryService.toDispatchView(
                dispatchService.resend(dispatchId, command.expectedVersion(), command.reason())
            )
        );
    }

    @Transactional
    public ResponseView acceptResponse(UUID responseId, BuyerCollaborationCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "PO_SUPPLIER_RESPONSE_ACCEPT",
            responseId,
            idempotencyKey,
            command,
            ResponseView.class,
            () -> requireVersion(responseId, command, () -> queryService.toResponseView(
                buyerDecisionService.accept(responseId, command.comment())
            ))
        );
    }

    @Transactional
    public ResponseView rejectResponse(UUID responseId, BuyerCollaborationCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "PO_SUPPLIER_RESPONSE_REJECT",
            responseId,
            idempotencyKey,
            command,
            ResponseView.class,
            () -> requireVersion(responseId, command, () -> queryService.toResponseView(
                buyerDecisionService.reject(responseId, command.reason())
            ))
        );
    }

    @Transactional
    public ResponseView requestRevision(UUID responseId, BuyerCollaborationCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "PO_SUPPLIER_RESPONSE_REQUEST_REVISION",
            responseId,
            idempotencyKey,
            command,
            ResponseView.class,
            () -> requireVersion(responseId, command, () -> queryService.toResponseView(
                buyerDecisionService.requestRevision(responseId, command.comment())
            ))
        );
    }

    @Transactional
    public CommitmentView cancelCommitment(UUID commitmentId, BuyerCollaborationCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "PO_SUPPLIER_COMMITMENT_CANCEL",
            commitmentId,
            idempotencyKey,
            command,
            CommitmentView.class,
            () -> queryService.toCommitmentView(
                buyerDecisionService.cancelCommitment(commitmentId, command.expectedVersion(), command.reason())
            )
        );
    }

    @Transactional
    public ResponseView requestReconfirmation(UUID commitmentId, BuyerCollaborationCommand command, String idempotencyKey) {
        validate(command, idempotencyKey);
        return idempotencyService.execute(
            "PO_SUPPLIER_RECONFIRMATION_REQUEST",
            commitmentId,
            idempotencyKey,
            command,
            ResponseView.class,
            () -> queryService.toResponseView(
                buyerDecisionService.requestReconfirmation(commitmentId, command.expectedVersion(), command.reason())
            )
        );
    }

    private <T> T requireVersion(UUID responseId, BuyerCollaborationCommand command, Supplier<T> action) {
        Long actualVersion = responseRepository.findByIdAndIsDeletedFalse(responseId)
            .orElseThrow(() -> new NotFoundException("Supplier PO response not found: " + responseId))
            .getVersion();
        if (!Objects.equals(actualVersion, command.expectedVersion())) {
            throw new ConflictException("Supplier response was changed by another user; refresh and retry");
        }
        return action.get();
    }

    private void validate(BuyerCollaborationCommand command, String idempotencyKey) {
        if (command == null || command.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required for supplier collaboration commands");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for supplier collaboration commands");
        }
    }
}
