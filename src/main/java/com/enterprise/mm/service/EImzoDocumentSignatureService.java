package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ExternalIntegrationDtos.EImzoDocumentSignatureRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.EImzoDocumentSignatureResponse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.entity.IntegrationEvent;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EImzoDocumentSignatureService {
    private final EImzoVerificationService verificationService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;

    @Transactional
    public EImzoDocumentSignatureResponse verify(EImzoDocumentSignatureRequest request) {
        return process("E_IMZO_DOCUMENT_VERIFIED", "VERIFY", request);
    }
    @Transactional
    public EImzoDocumentSignatureResponse sign(EImzoDocumentSignatureRequest request) {
        return process("E_IMZO_DOCUMENT_SIGNED", "SIGN", request);
    }
    private EImzoDocumentSignatureResponse process(String eventType, String action, EImzoDocumentSignatureRequest request) {
        if (request == null || request.entityId() == null) {
            throw new BusinessException("entityId is required");
        }
        String entityType = request.entityType() == null || request.entityType().isBlank() ? "DOCUMENT" : request.entityType().trim().toUpperCase();
        verificationService.verifyDocument(entityType, request.entityId(), request);
        IntegrationEvent event = integrationEventService.emit(eventType, "E_IMZO", request.entityId(), Map.of(
            "entityType", entityType,
            "entityId", request.entityId(),
            "certificateSerial", request.certificateSerial() == null ? "" : request.certificateSerial(),
            "signerTin", request.signerTin() == null ? "" : request.signerTin(),
            "action", action
        ));
        auditService.log(entityType, request.entityId(), "E_IMZO_" + action, event.getId().toString(), null, null);
        return new EImzoDocumentSignatureResponse(entityType, request.entityId(), true, "VERIFIED", LocalDateTime.now(), event.getId());
    }
}

