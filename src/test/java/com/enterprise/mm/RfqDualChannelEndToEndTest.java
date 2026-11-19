package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.QuotationSource;
import com.enterprise.mm.enums.RfqDeliveryStatus;
import com.enterprise.mm.payload.RfqProposalDtos.ManualProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalLineRequest;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.RfqWorkspaceQueryService;
import com.enterprise.mm.service.SupplierQuotationRevisionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "mixed-channel-manager", roles = {"ADMIN", "MM_MANAGER"})
class RfqDualChannelEndToEndTest {
    @Autowired SupplierQuotationRevisionService quotationService;
    @Autowired RfqWorkspaceQueryService workspaceService;
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
    void portalAndEmailedProposalsReachOneComparison() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RFQ rfq = rfq(suffix);
        RFQItem item = item(rfq, suffix);
        Supplier portalSupplier = supplier("PORTAL", suffix);
        Supplier emailSupplier = supplier("EMAIL", suffix);
        RfqPublishedVersion version = version(rfq, suffix);
        rfq.setActivePublishedVersionId(version.getId());
        rfqRepository.saveAndFlush(rfq);
        inviteAndDeliver(rfq, version, portalSupplier, suffix);
        inviteAndDeliver(rfq, version, emailSupplier, suffix);

        PortalProposalRequest portalRequest = new PortalProposalRequest(
            "UZS", "30 days", "DAP Tashkent", LocalDate.now().plusDays(30), true,
            List.of(line(item.getId(), "10", "1200"))
        );
        var portalDraft = quotationService.createPortalDraft(rfq.getId(), portalSupplier.getId(), portalRequest);
        var portalSubmitted = quotationService.submitPortalRevision(
            rfq.getId(), portalSupplier.getId(), portalDraft.id()
        );

        ManualProposalRequest emailRequest = new ManualProposalRequest(
            emailSupplier.getId(),
            QuotationSource.EMAIL,
            LocalDateTime.now().minusMinutes(10),
            "UZS",
            "15 days",
            "FCA Tashkent",
            LocalDate.now().plusDays(20),
            "Received in the procurement mailbox",
            null,
            List.of(line(item.getId(), "10", "1250"))
        );
        var evidence = new MockMultipartFile(
            "originalFile", "supplier-email-proposal.pdf", "application/pdf", "%PDF evidence".getBytes()
        );
        var emailSubmitted = quotationService.recordManualProposal(
            rfq.getId(), emailRequest, evidence, List.of()
        );

        RFQ opened = rfqRepository.findByIdAndIsDeletedFalse(rfq.getId()).orElseThrow();
        opened.setStatus("BIDS_OPENED");
        rfqRepository.saveAndFlush(opened);
        var comparison = workspaceService.comparison(rfq.getId());
        assertThat(comparison.proposals()).hasSize(2);
        assertThat(comparison.proposals()).extracting(value -> value.current().source())
            .containsExactlyInAnyOrder(QuotationSource.SUPPLIER_PORTAL, QuotationSource.EMAIL);
        assertThat(portalSubmitted.currentRevision()).isTrue();
        assertThat(emailSubmitted.currentRevision()).isTrue();
        assertThat(emailSubmitted.originalAttachmentId()).isNotNull();
        assertThat(comparison.currencyCodes()).containsExactly("UZS");
        assertThat(comparison.comparable()).isTrue();
        assertThat(meterRegistry.get("mm_rfq_proposal_total")
            .tag("source", "SUPPLIER_PORTAL").counter().count()).isGreaterThanOrEqualTo(1d);
        assertThat(meterRegistry.get("mm_rfq_proposal_total")
            .tag("source", "EMAIL").counter().count()).isGreaterThanOrEqualTo(1d);
        assertThat(meterRegistry.get("mm_rfq_response_latency").timer().count()).isGreaterThanOrEqualTo(2L);
    }

    private RFQ rfq(String suffix) {
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-E2E-" + suffix);
        rfq.setName("Mixed channel RFQ " + suffix);
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(7));
        rfq.setPackageLanguage("ru");
        rfq.setPublishedAt(LocalDateTime.now().minusHours(1));
        return rfqRepository.saveAndFlush(rfq);
    }

    private RFQItem item(RFQ rfq, String suffix) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-E2E-" + suffix);
        item.setName("Mixed channel line");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setQuantity(new BigDecimal("10"));
        item.setUnit("EA");
        return itemRepository.saveAndFlush(item);
    }

    private Supplier supplier(String channel, String suffix) {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-E2E-" + channel + "-" + suffix);
        supplier.setName(channel + " Supplier " + suffix);
        supplier.setLegalName(channel + " Supplier LLC " + suffix);
        supplier.setStatus("ACTIVE");
        supplier.setEmail(channel.toLowerCase() + "-" + suffix + "@supplier.test");
        return supplierRepository.saveAndFlush(supplier);
    }

    private RfqPublishedVersion version(RFQ rfq, String suffix) {
        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setCode("RFQV-E2E-" + suffix);
        version.setName("Mixed channel version");
        version.setStatus("ACTIVE");
        version.setRfqId(rfq.getId());
        version.setVersionNumber(1);
        version.setSnapshotJson("{}");
        version.setPackageLanguage("ru");
        version.setTemplateVersion("rfq-package-v1");
        version.setPublishedAt(rfq.getPublishedAt());
        version.setPublishedBy("mixed-channel-manager");
        return versionRepository.saveAndFlush(version);
    }

    private void inviteAndDeliver(RFQ rfq, RfqPublishedVersion version, Supplier supplier, String suffix) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("RFI-E2E-" + supplier.getCode());
        invitation.setName("Mixed channel invitation " + supplier.getName());
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        invitation = invitationRepository.saveAndFlush(invitation);

        RfqInvitationDelivery delivery = new RfqInvitationDelivery();
        delivery.setCode("RFD-E2E-" + suffix + "-" + supplier.getId().toString().substring(0, 4));
        delivery.setName("Mixed channel delivery");
        delivery.setDeliveryStatus(RfqDeliveryStatus.SENT);
        delivery.setInvitationId(invitation.getId());
        delivery.setPublishedVersionId(version.getId());
        delivery.setSupplierId(supplier.getId());
        delivery.setRecipientEmail(supplier.getEmail());
        deliveryRepository.saveAndFlush(delivery);
    }

    private ProposalLineRequest line(UUID rfqItemId, String quantity, String price) {
        return new ProposalLineRequest(
            rfqItemId,
            new BigDecimal(quantity),
            new BigDecimal(price),
            5,
            BigDecimal.ZERO,
            false,
            null,
            "Available"
        );
    }
}
