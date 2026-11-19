package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.SupplierAccreditationDocumentResponse;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class SupplierAccreditationEvidenceAggregateServiceTest {

    @Mock SupplierAccreditationDocumentService documentService;
    @Mock AttachmentService attachmentService;
    @Mock IdempotencyService idempotencyService;

    private SupplierAccreditationEvidenceAggregateService service;

    @BeforeEach
    void setUp() {
        service = new SupplierAccreditationEvidenceAggregateService(
            documentService, attachmentService, idempotencyService);
    }

    @Test
    void createsDocumentFileAndLinkInsideOneIdempotentAggregate() {
        UUID applicationId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
            "file", "license.pdf", "application/pdf", "evidence".getBytes());
        SupplierAccreditationDocumentResponse created = new SupplierAccreditationDocumentResponse();
        created.setId(documentId);
        AttachmentDto attachment = new AttachmentDto();
        attachment.setId(attachmentId);
        SupplierAccreditationDocumentResponse linked = new SupplierAccreditationDocumentResponse();
        linked.setId(documentId);
        linked.setAttachmentId(attachmentId);

        when(idempotencyService.execute(
            eq("SUPPLIER_ACCREDITATION_EVIDENCE_CREATE"),
            eq(applicationId),
            eq("evidence-key"),
            any(),
            eq(SupplierAccreditationDocumentResponse.class),
            any()))
            .thenAnswer(invocation -> ((Supplier<SupplierAccreditationDocumentResponse>) invocation.getArgument(5)).get());
        when(documentService.create(any())).thenReturn(created);
        when(attachmentService.upload(
            eq("SUPPLIER_ACCREDITATION_DOCUMENT"),
            eq(documentId),
            eq("LICENSE"),
            eq(file),
            eq(null)))
            .thenReturn(attachment);
        when(documentService.update(eq(documentId), any())).thenReturn(linked);

        var result = service.create(
            applicationId,
            "LICENSE",
            "Supplier license",
            LocalDate.now(),
            LocalDate.now().plusYears(1),
            file,
            "evidence-key");

        assertThat(result.getAttachmentId()).isEqualTo(attachmentId);
        ArgumentCaptor<com.enterprise.mm.payload.SupplierAccreditationDocumentUpdateRequest> update =
            ArgumentCaptor.forClass(com.enterprise.mm.payload.SupplierAccreditationDocumentUpdateRequest.class);
        verify(documentService).update(eq(documentId), update.capture());
        assertThat(update.getValue().getApplicationId()).isEqualTo(applicationId);
        assertThat(update.getValue().getAttachmentId()).isEqualTo(attachmentId);
    }

    @Test
    void refusesBlankIdempotencyKeyBeforeCreatingAnything() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "license.pdf", "application/pdf", "evidence".getBytes());

        assertThatThrownBy(() -> service.create(
            UUID.randomUUID(), "LICENSE", null, null, null, file, " "))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");

        verifyNoInteractions(documentService, attachmentService, idempotencyService);
    }
}
