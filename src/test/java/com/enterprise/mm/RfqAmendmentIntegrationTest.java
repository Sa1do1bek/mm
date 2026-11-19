package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqRequest;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.rfq.RfqAmendmentService;
import com.enterprise.mm.service.rfq.RfqAmendmentService.CancelAmendmentRequest;
import com.enterprise.mm.service.rfq.RfqAmendmentService.StartAmendmentRequest;
import com.enterprise.mm.service.rfq.RfqPublicationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
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
@WithMockUser(username = "rfq-amendment-manager", roles = {"ADMIN", "MM_MANAGER"})
class RfqAmendmentIntegrationTest {
    @Autowired RfqAmendmentService amendmentService;
    @Autowired RfqPublicationService publicationService;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository itemRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired RfqSupplierInvitationRepository invitationRepository;
    @Autowired RfqPublishedVersionRepository versionRepository;
    @Autowired RfqInvitationDeliveryRepository deliveryRepository;
    @Autowired MeterRegistry meterRegistry;

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
    void amendmentPreservesVersionOneAndRedistributesVersionTwo() {
        Fixture fixture = publishedFixture();
        RFQ published = rfqRepository.findById(fixture.rfqId()).orElseThrow();

        var draft = amendmentService.start(
            fixture.rfqId(),
            new StartAmendmentRequest("Deadline extended by buyer", published.getVersion())
        );
        assertThat(draft.status()).isEqualTo("AMENDMENT_DRAFT");
        assertThat(draft.nextPublishedVersionNumber()).isEqualTo(2);

        RFQ amended = rfqRepository.findById(fixture.rfqId()).orElseThrow();
        amended.setDueDate(fixture.originalDueDate().plusDays(14));
        amended = rfqRepository.saveAndFlush(amended);
        var versionTwo = publicationService.publish(
            fixture.rfqId(),
            new PublishRfqRequest(amended.getVersion()),
            "publish-amendment-" + fixture.suffix()
        );

        var versions = versionRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByVersionNumberAsc(
                "default", CompanyContext.DEFAULT_COMPANY_ID, fixture.rfqId()
            );
        assertThat(versions).extracting(value -> value.getVersionNumber()).containsExactly(1, 2);
        assertThat(versions.getFirst().getAmendmentReason()).isNull();
        assertThat(versions.getLast().getAmendmentReason()).isEqualTo("Deadline extended by buyer");
        assertThat(versionTwo.queuedDeliveryCount()).isOne();
        assertThat(deliveryRepository
            .findByTenantIdAndCompanyIdAndPublishedVersionIdAndIsDeletedFalseOrderByCreatedAtAsc(
                "default", CompanyContext.DEFAULT_COMPANY_ID, versions.getFirst().getId()
            )).hasSize(1);
        assertThat(deliveryRepository
            .findByTenantIdAndCompanyIdAndPublishedVersionIdAndIsDeletedFalseOrderByCreatedAtAsc(
                "default", CompanyContext.DEFAULT_COMPANY_ID, versions.getLast().getId()
            )).hasSize(1);
        assertThat(meterRegistry.get("mm_rfq_publication_total").counter().count()).isGreaterThanOrEqualTo(2d);
    }

    @Test
    void cancellingAmendmentRestoresTheActivePublishedSnapshot() {
        Fixture fixture = publishedFixture();
        RFQ published = rfqRepository.findById(fixture.rfqId()).orElseThrow();
        amendmentService.start(
            fixture.rfqId(),
            new StartAmendmentRequest("Draft change that will be cancelled", published.getVersion())
        );

        RFQ edited = rfqRepository.findById(fixture.rfqId()).orElseThrow();
        edited.setName("Unpublished changed title");
        edited.setDescription("Unpublished changed description");
        edited.setDueDate(fixture.originalDueDate().plusMonths(1));
        edited.setPackageLanguage("uz");
        edited.setResponseInstructions("Unpublished changed instructions");
        edited = rfqRepository.saveAndFlush(edited);

        var restored = amendmentService.cancel(
            fixture.rfqId(), new CancelAmendmentRequest(edited.getVersion())
        );

        assertThat(restored.status()).isEqualTo("OPEN");
        RFQ reloaded = rfqRepository.findById(fixture.rfqId()).orElseThrow();
        assertThat(reloaded.getName()).contains(fixture.originalTitle());
        assertThat(reloaded.getDescription()).isEqualTo("Original published description");
        assertThat(reloaded.getDueDate()).isEqualTo(fixture.originalDueDate());
        assertThat(reloaded.getPackageLanguage()).isEqualTo("ru");
        assertThat(reloaded.getResponseInstructions()).isEqualTo("Reply by email or use the supplier portal.");
        assertThat(versionRepository.countByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, fixture.rfqId()
        )).isOne();
    }

    private Fixture publishedFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LocalDate dueDate = LocalDate.now().plusDays(10);
        String title = "Published amendment RFQ " + suffix;
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-AMD-" + suffix);
        rfq.setName(title);
        rfq.setStatus("DRAFT");
        rfq.setDescription("Original published description");
        rfq.setDueDate(dueDate);
        rfq.setPackageLanguage("ru");
        rfq.setResponseInstructions("Reply by email or use the supplier portal.");
        rfq = rfqRepository.saveAndFlush(rfq);

        RFQItem item = new RFQItem();
        item.setCode("RFQI-AMD-" + suffix);
        item.setName("Amendment line");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setQuantity(new BigDecimal("5"));
        item.setUnit("EA");
        itemRepository.saveAndFlush(item);

        Supplier supplier = new Supplier();
        supplier.setCode("SUP-AMD-" + suffix);
        supplier.setName("Amendment Supplier " + suffix);
        supplier.setLegalName("Amendment Supplier LLC " + suffix);
        supplier.setStatus("ACTIVE");
        supplier.setEmail("amendment-" + suffix + "@supplier.test");
        supplier = supplierRepository.saveAndFlush(supplier);

        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("RFI-AMD-" + suffix);
        invitation.setName("Amendment invitation " + suffix);
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        invitationRepository.saveAndFlush(invitation);

        publicationService.publish(
            rfq.getId(), new PublishRfqRequest(rfq.getVersion()), "publish-original-" + suffix
        );
        return new Fixture(rfq.getId(), suffix, title, dueDate);
    }

    private record Fixture(UUID rfqId, String suffix, String originalTitle, LocalDate originalDueDate) {
    }
}
