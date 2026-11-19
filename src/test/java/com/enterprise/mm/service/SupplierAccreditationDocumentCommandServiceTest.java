package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierAccreditationDocumentActionRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentResponse;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierAccreditationDocumentCommandServiceTest {

    @Mock SupplierAccreditationDocumentService documentService;
    @Mock IdempotencyService idempotencyService;

    private SupplierAccreditationDocumentCommandService service;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        service = new SupplierAccreditationDocumentCommandService(documentService, idempotencyService);
        documentId = UUID.randomUUID();
        when(idempotencyService.execute(
            anyString(), eq(documentId), anyString(), any(),
            eq(SupplierAccreditationDocumentResponse.class), any()))
            .thenAnswer(invocation -> ((Supplier<SupplierAccreditationDocumentResponse>) invocation.getArgument(5)).get());
    }

    @Test
    void staleEvidenceDecisionFailsBeforeMutation() {
        SupplierAccreditationDocumentResponse current = new SupplierAccreditationDocumentResponse();
        current.setVersion(8L);
        when(documentService.get(documentId)).thenReturn(current);
        SupplierAccreditationDocumentActionRequest request =
            new SupplierAccreditationDocumentActionRequest("Verified against source", 7L);

        assertThatThrownBy(() -> service.verify(documentId, request, "verify-key"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(documentService, never()).verify(documentId, request);
    }

    @Test
    void validDecisionDelegatesInsideIdempotencyAction() {
        SupplierAccreditationDocumentResponse current = new SupplierAccreditationDocumentResponse();
        current.setVersion(8L);
        SupplierAccreditationDocumentResponse rejected = new SupplierAccreditationDocumentResponse();
        rejected.setVersion(9L);
        when(documentService.get(documentId)).thenReturn(current);
        SupplierAccreditationDocumentActionRequest request =
            new SupplierAccreditationDocumentActionRequest("Certificate is expired", 8L);
        when(documentService.reject(documentId, request)).thenReturn(rejected);

        service.reject(documentId, request, "reject-key");

        verify(documentService).reject(documentId, request);
    }
}
