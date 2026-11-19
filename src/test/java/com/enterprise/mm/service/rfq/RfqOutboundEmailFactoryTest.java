package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.attachment.AttachmentStorage;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Buyer;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Position;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupplierRecipient;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupportingDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;

@ExtendWith(MockitoExtension.class)
class RfqOutboundEmailFactoryTest {
    private static final String TENANT = "tenant-package-mail";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000004402");

    @Mock
    private AttachmentRepository attachmentRepository;
    @Mock
    private AttachmentStorage storage;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createsCompleteEmailFromFrozenSnapshotAndPromotedAttachments() throws Exception {
        UUID supplierId = UUID.randomUUID();
        UUID pdfId = UUID.randomUUID();
        UUID excelId = UUID.randomUUID();
        UUID supportId = UUID.randomUUID();
        RfqPackageSnapshot snapshot = new RfqPackageSnapshot(
            UUID.randomUUID(),
            "RFQ-26-010",
            "Industrial pumps",
            "Supply complete pump assemblies",
            "ru",
            LocalDate.of(2026, 8, 1),
            new Buyer("Buyer LLC", "Tashkent", "Aziza Karimova", "buyer@example.uz", "+998900000000"),
            "Reply by email or submit in the portal.",
            List.of(new Position(
                UUID.randomUUID(),
                "MAT-PUMP-01",
                "Centrifugal pump",
                "IP68",
                "EA",
                new BigDecimal("12"),
                LocalDate.of(2026, 9, 1)
            )),
            List.of(),
            List.of(new SupplierRecipient(supplierId, "Supplier LLC", "Samir", "original@example.uz")),
            List.of(new SupportingDocument(supportId, "specification.pdf", "application/pdf"))
        );
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setId(UUID.randomUUID());
        version.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
        version.setPdfAttachmentId(pdfId);
        version.setExcelAttachmentId(excelId);
        RfqInvitationDelivery delivery = new RfqInvitationDelivery();
        delivery.setPublishedVersion(version);
        delivery.setSupplierId(supplierId);
        delivery.setRecipientName("Samir");
        delivery.setRecipientEmail("corrected@example.uz");

        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TENANT, COMPANY_ID, pdfId))
            .thenReturn(Optional.of(attachment(pdfId, "RFQ-26-010.pdf", "pdf")));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TENANT, COMPANY_ID, excelId))
            .thenReturn(Optional.of(attachment(excelId, "RFQ-26-010.xlsx", "xlsx")));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TENANT, COMPANY_ID, supportId))
            .thenReturn(Optional.of(attachment(supportId, "specification.pdf", "support")));
        when(storage.supports("LOCAL")).thenReturn(true);
        when(storage.read(any())).thenAnswer(invocation -> new InputStreamResource(
            new ByteArrayInputStream(invocation.<String>getArgument(0).getBytes())
        ));
        RfqMailProperties properties = new RfqMailProperties();
        properties.setFrom("rfq@example.uz");
        properties.setReplyTo("procurement@example.uz");
        RfqOutboundEmailFactory factory = new RfqOutboundEmailFactory(
            objectMapper,
            new RfqEmailRenderer(768000),
            attachmentRepository,
            List.of(storage),
            properties
        );

        RfqOutboundEmail email = factory.create(
            delivery,
            URI.create("https://portal.example.uz/supplier-portal/magic-link?token=one-time")
        );

        assertThat(email.recipientEmail()).isEqualTo("corrected@example.uz");
        assertThat(email.replyTo()).isEqualTo("procurement@example.uz");
        assertThat(email.htmlBody()).contains(
            "Centrifugal pump",
            "Защищённая ссылка действительна 24 часа",
            "Reply by email"
        );
        assertThat(email.attachments()).extracting(RfqOutboundEmail.Attachment::fileName)
            .containsExactly("RFQ-26-010.pdf", "RFQ-26-010.xlsx", "specification.pdf");
    }

    @Test
    void renewalEmailContainsOnlyMagicLinkAndDoesNotReadAttachments() {
        RfqInvitationDelivery delivery = new RfqInvitationDelivery();
        delivery.setRecipientEmail("supplier@example.uz");
        delivery.setRecipientName("Supplier Contact");
        RfqMailProperties properties = new RfqMailProperties();
        properties.setFrom("rfq@example.uz");
        properties.setReplyTo("procurement@example.uz");
        RfqOutboundEmailFactory factory = new RfqOutboundEmailFactory(
            new ObjectMapper(),
            new RfqEmailRenderer(768000),
            attachmentRepository,
            List.of(storage),
            properties
        );

        RfqOutboundEmail email = factory.createRenewal(
            delivery,
            URI.create("https://portal.example.uz/supplier-portal/magic-link?token=renew")
        );

        assertThat(email.recipientEmail()).isEqualTo("supplier@example.uz");
        assertThat(email.subject()).isEqualTo("Supplier portal login link");
        assertThat(email.htmlBody()).contains("Open supplier portal", "token=renew");
        assertThat(email.textBody()).contains("token=renew");
        assertThat(email.attachments()).isEmpty();
        verifyNoInteractions(attachmentRepository, storage);
    }

    private Attachment attachment(UUID id, String fileName, String storageKey) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey(storageKey);
        attachment.setOriginalFileName(fileName);
        attachment.setContentType(fileName.endsWith(".xlsx")
            ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            : "application/pdf");
        return attachment;
    }
}
