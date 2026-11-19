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
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationThread;
import com.enterprise.mm.enums.ProcurementLineType;
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
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierQuotationThreadRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.SupplierQuotationRevisionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SupplierQuotationRevisionConcurrencyTest {
    private static final String TENANT = "default";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired SupplierQuotationRevisionService service;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository rfqItemRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired RfqPublishedVersionRepository versionRepository;
    @Autowired RfqSupplierInvitationRepository invitationRepository;
    @Autowired RfqInvitationDeliveryRepository deliveryRepository;
    @Autowired SupplierQuotationThreadRepository threadRepository;
    @Autowired SupplierQuotationRepository quotationRepository;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void concurrentPortalAndManagerSubmissionsLeaveOneCurrentRevision() throws Exception {
        setContext();
        Supplier supplier = supplier();
        RFQ rfq = rfq();
        RFQItem item = rfqItem(rfq);
        RfqPublishedVersion version = version(rfq);
        rfq.setActivePublishedVersionId(version.getId());
        rfqRepository.saveAndFlush(rfq);
        RfqSupplierInvitation invitation = invitation(rfq, supplier);
        delivery(invitation, version, supplier);

        ProposalLineRequest line = line(item.getId(), new BigDecimal("100"));
        var draft = service.createPortalDraft(
            rfq.getId(),
            supplier.getId(),
            new PortalProposalRequest("UZS", "30 days", "DAP", null, true, List.of(line))
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> portal = executor.submit(() -> race(ready, start, failures, () ->
                service.submitPortalRevision(rfq.getId(), supplier.getId(), draft.id())));
            Future<?> manager = executor.submit(() -> race(ready, start, failures, () ->
                service.recordManualProposal(
                    rfq.getId(),
                    new ManualProposalRequest(
                        supplier.getId(),
                        QuotationSource.EMAIL,
                        LocalDateTime.now(),
                        "UZS",
                        "15 days",
                        "EXW",
                        null,
                        "Received by procurement email",
                        "Signed commercial proposal supersedes portal response",
                        List.of(line(item.getId(), new BigDecimal("95")))
                    ),
                    new MockMultipartFile(
                        "originalFile",
                        "signed-commercial-proposal.pdf",
                        "application/pdf",
                        "signed proposal".getBytes()
                    ),
                    List.of()
                )));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            portal.get(30, TimeUnit.SECONDS);
            manager.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        setContext();
        SupplierQuotationThread thread = threadRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndSupplierIdAndIsDeletedFalse(
                TENANT, COMPANY_ID, rfq.getId(), supplier.getId()
            ).orElseThrow();
        List<SupplierQuotation> revisions = quotationRepository
            .findByTenantIdAndCompanyIdAndThreadIdAndIsDeletedFalseOrderByRevisionNumberAsc(
                TENANT, COMPANY_ID, thread.getId()
            );

        assertThat(revisions).hasSizeGreaterThanOrEqualTo(2);
        assertThat(revisions.stream().filter(SupplierQuotation::isCurrentRevision)).hasSize(1);
        assertThat(quotationRepository.findCurrentSubmittedByRfq(
            TENANT,
            COMPANY_ID,
            rfq.getId(),
            List.of("SUBMITTED", "SELECTED", "REJECTED")
        )).hasSize(1);
        assertThat(failures).hasSizeLessThanOrEqualTo(1);
    }

    private void race(
        CountDownLatch ready,
        CountDownLatch start,
        List<Throwable> failures,
        Runnable command
    ) {
        setContext();
        try {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            command.run();
        } catch (Throwable throwable) {
            failures.add(throwable);
        } finally {
            clearContext();
        }
    }

    private void setContext() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "rfq-revision-concurrency",
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }

    private Supplier supplier() {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-RACE-" + suffix());
        supplier.setName("Concurrent supplier");
        supplier.setStatus("ACTIVE");
        supplier.setEmail("rfq-race@example.uz");
        return supplierRepository.saveAndFlush(supplier);
    }

    private RFQ rfq() {
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-RACE-" + suffix());
        rfq.setName("Concurrent response RFQ");
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(2));
        return rfqRepository.saveAndFlush(rfq);
    }

    private RFQItem rfqItem(RFQ rfq) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-RACE-" + suffix());
        item.setName("Concurrent RFQ line");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setUnit("EA");
        item.setQuantity(BigDecimal.TEN);
        return rfqItemRepository.saveAndFlush(item);
    }

    private RfqPublishedVersion version(RFQ rfq) {
        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setCode("RFQV-RACE-" + suffix());
        version.setName("Concurrent published version");
        version.setStatus("PUBLISHED");
        version.setRfqId(rfq.getId());
        version.setVersionNumber(1);
        version.setSnapshotJson("{}");
        version.setPackageLanguage("ru");
        version.setTemplateVersion("1");
        version.setPublishedAt(LocalDateTime.now());
        version.setPublishedBy("rfq-revision-concurrency");
        return versionRepository.saveAndFlush(version);
    }

    private RfqSupplierInvitation invitation(RFQ rfq, Supplier supplier) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("RFQI-INV-RACE-" + suffix());
        invitation.setName("Concurrent invitation");
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        return invitationRepository.saveAndFlush(invitation);
    }

    private void delivery(
        RfqSupplierInvitation invitation,
        RfqPublishedVersion version,
        Supplier supplier
    ) {
        RfqInvitationDelivery delivery = new RfqInvitationDelivery();
        delivery.setCode("RFQD-RACE-" + suffix());
        delivery.setName("Concurrent invitation delivery");
        delivery.setDeliveryStatus(RfqDeliveryStatus.SENT);
        delivery.setInvitationId(invitation.getId());
        delivery.setPublishedVersionId(version.getId());
        delivery.setSupplierId(supplier.getId());
        delivery.setRecipientEmail(supplier.getEmail());
        deliveryRepository.saveAndFlush(delivery);
    }

    private ProposalLineRequest line(UUID rfqItemId, BigDecimal unitPrice) {
        return new ProposalLineRequest(
            rfqItemId,
            BigDecimal.TEN,
            unitPrice,
            5,
            new BigDecimal("12"),
            false,
            null,
            "Available"
        );
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
