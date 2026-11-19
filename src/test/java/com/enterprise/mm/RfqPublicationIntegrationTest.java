package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqRequest;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqResponse;
import com.enterprise.mm.payload.RfqPublicationDtos.PublicationPreview;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.rfq.RfqPublicationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "mm.rfq-mail.enabled=true",
    "mm.rfq-mail.from=rfq@buyer.test",
    "mm.rfq-mail.reply-to=procurement@buyer.test"
})
@ActiveProfiles("test")
@WithMockUser(username = "rfq-publisher", roles = {"ADMIN", "MM_MANAGER"})
class RfqPublicationIntegrationTest {
    @Autowired RfqPublicationService publicationService;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository itemRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired RfqSupplierInvitationRepository invitationRepository;
    @Autowired RfqPublishedVersionRepository versionRepository;
    @Autowired RfqInvitationDeliveryRepository deliveryRepository;
    @Autowired SupplierPortalAccountRepository accountRepository;
    @Autowired AttachmentRepository attachmentRepository;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void publishFreezesOneVersionDispatchesSupplierAndReplaysExactlyOnce() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-PUB-" + suffix);
        rfq.setName("Published pumps " + suffix);
        rfq.setStatus("DRAFT");
        rfq.setDescription("Complete pump supply");
        rfq.setDueDate(LocalDate.now().plusDays(10));
        rfq.setPackageLanguage("ru");
        rfq.setResponsibleBuyer("Procurement manager");
        rfq.setResponseInstructions("Reply by email or submit through the supplier portal.");
        rfq = rfqRepository.saveAndFlush(rfq);

        RFQItem item = new RFQItem();
        item.setCode("RFQI-PUB-" + suffix);
        item.setName("Industrial pump");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setQuantity(new BigDecimal("3"));
        item.setUnit("EA");
        itemRepository.saveAndFlush(item);

        Supplier supplier = new Supplier();
        supplier.setCode("SUP-PUB-" + suffix);
        supplier.setName("Supplier " + suffix);
        supplier.setLegalName("Supplier LLC " + suffix);
        supplier.setStatus("ACTIVE");
        supplier.setEmail("sales-" + suffix + "@supplier.test");
        supplier.setDirectorName("Sales manager");
        supplier = supplierRepository.saveAndFlush(supplier);

        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("RFI-PUB-" + suffix);
        invitation.setName("RFQ invitation " + suffix);
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        invitationRepository.saveAndFlush(invitation);
        UUID rfqId = rfq.getId();
        UUID supplierId = supplier.getId();
        String supplierEmail = supplier.getEmail();

        PublicationPreview preview = publicationService.preview(rfqId);
        assertThat(preview.publishable()).isTrue();
        assertThat(preview.positionCount()).isOne();
        assertThat(preview.supplierCount()).isOne();
        assertThat(versionRepository.countByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, rfqId
        )).isZero();

        PublishRfqRequest request = new PublishRfqRequest(rfq.getVersion());
        PublishRfqResponse first = publicationService.publish(rfqId, request, "publish-" + suffix);
        PublishRfqResponse replay = publicationService.publish(rfqId, request, "publish-" + suffix);

        assertThat(first.status()).isEqualTo("OPEN");
        assertThat(first.versionNumber()).isOne();
        assertThat(first.queuedDeliveryCount()).isOne();
        assertThat(replay).isEqualTo(first);
        assertThat(versionRepository.countByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, rfqId
        )).isOne();
        assertThat(deliveryRepository.findByTenantIdAndCompanyIdAndPublishedVersionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, first.publishedVersionId()
        )).singleElement().satisfies(delivery -> {
            assertThat(delivery.getSupplierId()).isEqualTo(supplierId);
            assertThat(delivery.getRecipientEmail()).isEqualTo(supplierEmail);
            assertThat(delivery.getStatus()).isEqualTo("FAILED");
            assertThat(delivery.getLastErrorCategory()).isEqualTo("SUPPLIER_ACCREDITATION_BLOCKED");
        });
        assertThat(accountRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, supplierId
        )).get().satisfies(account -> {
            assertThat(account.getStatus()).isEqualTo("INVITED");
            assertThat(account.getLoginEmail()).isEqualTo(supplierEmail);
            assertThat(account.getInviteTokenHash()).isNull();
        });
        assertThat(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, "RFQ_PUBLISHED_VERSION", first.publishedVersionId()
        )).extracting(attachment -> attachment.getAttachmentType())
            .containsExactlyInAnyOrder("RFQ_OFFICIAL_PDF", "RFQ_QUOTATION_TEMPLATE");
        RFQ published = rfqRepository.findById(rfqId).orElseThrow();
        assertThat(published.getStatus()).isEqualTo("OPEN");
        assertThat(published.getActivePublishedVersionId()).isEqualTo(first.publishedVersionId());
    }

    @Test
    void openCompetitionPublishesSupplierCommunicationWithoutManualSupplierSelection() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-OPEN-COMM-" + suffix);
        rfq.setName("Open competition communications " + suffix);
        rfq.setStatus("DRAFT");
        rfq.setPurpose("PROCUREMENT");
        rfq.setTenderProcedureType("OPEN_COMPETITION");
        rfq.setDueDate(LocalDate.now().plusDays(10));
        rfq.setPackageLanguage("ru");
        rfq.setResponsibleBuyer("Procurement manager");
        rfq = rfqRepository.saveAndFlush(rfq);

        RFQItem item = new RFQItem();
        item.setCode("RFQI-OPEN-COMM-" + suffix);
        item.setName("Open competition pump");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setQuantity(new BigDecimal("2"));
        item.setUnit("EA");
        itemRepository.saveAndFlush(item);

        Supplier supplier = new Supplier();
        supplier.setCode("SUP-OPEN-COMM-" + suffix);
        supplier.setName("Open audience supplier " + suffix);
        supplier.setLegalName("Open audience supplier LLC " + suffix);
        supplier.setStatus("ACTIVE");
        supplier.setEmail("open-" + suffix + "@supplier.test");
        supplier = supplierRepository.saveAndFlush(supplier);
        Supplier persistedSupplier = supplier;

        PublicationPreview preview = publicationService.preview(rfq.getId());
        assertThat(preview.publishable()).isTrue();
        assertThat(preview.supplierCount()).isZero();

        PublishRfqResponse published = publicationService.publish(
            rfq.getId(),
            new PublishRfqRequest(rfq.getVersion()),
            "publish-open-communication-" + suffix
        );

        assertThat(published.queuedDeliveryCount()).isGreaterThanOrEqualTo(1);
        assertThat(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, rfq.getId()
        )).anySatisfy(invitation -> assertThat(invitation.getSupplierId()).isEqualTo(persistedSupplier.getId()));
        assertThat(deliveryRepository.findByTenantIdAndCompanyIdAndPublishedVersionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, published.publishedVersionId()
        )).anySatisfy(delivery -> {
            assertThat(delivery.getSupplierId()).isEqualTo(persistedSupplier.getId());
            assertThat(delivery.getRecipientEmail()).isEqualTo(persistedSupplier.getEmail());
            assertThat(delivery.getStatus()).isIn("PENDING", "SENT", "DELIVERED", "FAILED");
        });
    }
}
