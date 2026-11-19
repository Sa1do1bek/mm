package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierAccreditationDocumentActionRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentResponse;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierAccreditationDocumentCommandService {
    private final SupplierAccreditationDocumentService documentService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public SupplierAccreditationDocumentResponse verify(
        UUID id,
        SupplierAccreditationDocumentActionRequest request,
        String idempotencyKey
    ) {
        return execute("SUPPLIER_ACCREDITATION_EVIDENCE_VERIFY", id, request, idempotencyKey,
            () -> documentService.verify(id, request));
    }

    @Transactional
    public SupplierAccreditationDocumentResponse reject(
        UUID id,
        SupplierAccreditationDocumentActionRequest request,
        String idempotencyKey
    ) {
        return execute("SUPPLIER_ACCREDITATION_EVIDENCE_REJECT", id, request, idempotencyKey,
            () -> documentService.reject(id, request));
    }

    private SupplierAccreditationDocumentResponse execute(
        String operation,
        UUID id,
        SupplierAccreditationDocumentActionRequest request,
        String idempotencyKey,
        Supplier<SupplierAccreditationDocumentResponse> action
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required for evidence decisions");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for evidence decisions");
        }
        return idempotencyService.execute(
            operation,
            id,
            idempotencyKey,
            request,
            SupplierAccreditationDocumentResponse.class,
            () -> {
                Long actualVersion = documentService.get(id).getVersion();
                if (!Objects.equals(actualVersion, request.expectedVersion())) {
                    throw new ConflictException(
                        "Accreditation evidence was changed by another user; refresh and retry");
                }
                return action.get();
            }
        );
    }
}
