package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationThread;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.QuotationSource;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RfqProposalDtos.ManualProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalLineRequest;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierQuotationThreadRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class SupplierQuotationRevisionServiceTest {
    private static final String TENANT = "tenant-revisions";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000007701");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 18, 10, 0);

    @Mock private SupplierQuotationRepository quotationRepository;
    @Mock private SupplierQuotationItemRepository itemRepository;
    @Mock private SupplierQuotationThreadRepository threadRepository;
    @Mock private RFQRepository rfqRepository;
    @Mock private RFQItemRepository rfqItemRepository;
    @Mock private RfqSupplierInvitationRepository invitationRepository;
    @Mock private RfqPublishedVersionRepository versionRepository;
    @Mock private RfqInvitationDeliveryRepository deliveryRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private AuditService auditService;

    private SupplierQuotationRevisionService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        Clock clock = Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC);
        service = new SupplierQuotationRevisionService(
            quotationRepository,
            itemRepository,
            threadRepository,
            rfqRepository,
            rfqItemRepository,
            invitationRepository,
            versionRepository,
            deliveryRepository,
            supplierRepository,
            attachmentService,
            auditService,
            new TenderSupplierAccessService(invitationRepository, clock),
            clock,
            "Asia/Tashkent"
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void manualEmailProposalRequiresOriginalEvidence() {
        ManualProposalRequest request = manualRequest(UUID.randomUUID(), UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.recordManualProposal(
            UUID.randomUUID(), request, null, List.of()
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Original supplier proposal attachment is required");
    }

    @Test
    void portalThenEmailCreatesRevisionTwoAndSupersedesRevisionOne() {
        UUID rfqId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        RFQ rfq = rfq(rfqId);
        Supplier supplier = supplier(supplierId);
        RFQItem rfqItem = rfqItem(rfqId, itemId);
        RfqSupplierInvitation invitation = invitation(rfqId, supplierId);
        RfqPublishedVersion version = version(rfqId, rfq.getActivePublishedVersionId());
        SupplierQuotation previous = previous(rfqId, supplierId, version.getId());
        SupplierQuotationThread thread = thread(rfqId, supplierId, previous.getId());
        previous.setThreadId(thread.getId());
        ManualProposalRequest request = manualRequest(
            supplierId,
            itemId,
            "Supplier sent signed revision"
        );
        MockMultipartFile original = new MockMultipartFile(
            "originalFile", "signed-offer.pdf", "application/pdf", "signed".getBytes()
        );
        Attachment evidence = new Attachment();
        evidence.setId(UUID.randomUUID());

        when(rfqRepository.lockScopedById(TENANT, COMPANY_ID, rfqId)).thenReturn(Optional.of(rfq));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.of(supplier));
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TENANT, COMPANY_ID, rfqId
        )).thenReturn(List.of(invitation));
        when(versionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TENANT, COMPANY_ID, version.getId()
        )).thenReturn(Optional.of(version));
        when(threadRepository.lockByRfqIdAndSupplierId(TENANT, COMPANY_ID, rfqId, supplierId))
            .thenReturn(Optional.of(thread));
        when(quotationRepository.findByIdAndIsDeletedFalse(previous.getId()))
            .thenReturn(Optional.of(previous));
        when(rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TENANT, rfqId))
            .thenReturn(List.of(rfqItem));
        when(quotationRepository.save(any(SupplierQuotation.class))).thenAnswer(invocation -> {
            SupplierQuotation quotation = invocation.getArgument(0);
            if (quotation.getId() == null) {
                quotation.setId(UUID.randomUUID());
            }
            return quotation;
        });
        when(attachmentService.uploadForCommand(
            any(), any(), any(), any()
        )).thenReturn(evidence);

        var response = service.recordManualProposal(rfqId, request, original, List.of());

        assertThat(response.revisionNumber()).isEqualTo(2);
        assertThat(response.currentRevision()).isTrue();
        assertThat(response.source()).isEqualTo(QuotationSource.EMAIL);
        assertThat(response.originalAttachmentId()).isEqualTo(evidence.getId());
        assertThat(previous.isCurrentRevision()).isFalse();
        assertThat(thread.getCurrentQuotationId()).isEqualTo(response.id());
        assertThat(response.totalAmount()).isEqualByComparingTo("1400000.0000");
        assertThat(response.items().getFirst().totalAmount()).isEqualByComparingTo("1400000.0000");
    }

    @Test
    void portalDraftCannotBeBackdatedAfterTheDeadline() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = rfq(rfqId);
        rfq.setDueDate(LocalDate.of(2026, 7, 17));
        when(rfqRepository.lockScopedById(TENANT, COMPANY_ID, rfqId)).thenReturn(Optional.of(rfq));

        PortalProposalRequest request = new PortalProposalRequest(
            "UZS",
            null,
            null,
            null,
            null,
            List.of(new ProposalLineRequest(
                UUID.randomUUID(),
                BigDecimal.ONE,
                BigDecimal.TEN,
                1,
                null,
                false,
                null,
                null
            ))
        );

        assertThatThrownBy(() -> service.createPortalDraft(rfqId, UUID.randomUUID(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("RFQ response deadline has passed");
    }

    @Test
    void portalDraftGrandTotalAddsTaxWhenUnitPriceExcludesTax() {
        PortalDraftFixture fixture = portalDraftFixture(1);
        stubPortalDraft(fixture);

        var response = service.createPortalDraft(
            fixture.rfqId(),
            fixture.supplierId(),
            portalRequest(false, List.of(offer(fixture.itemId(0), "5", "300000", "12")))
        );

        assertThat(response.totalAmount()).isEqualByComparingTo("1680000.0000");
        assertThat(response.items().getFirst().totalAmount()).isEqualByComparingTo("1680000.0000");
    }

    @Test
    void portalDraftGrandTotalKeepsGrossAmountWhenTaxIncluded() {
        PortalDraftFixture fixture = portalDraftFixture(1);
        stubPortalDraft(fixture);

        var response = service.createPortalDraft(
            fixture.rfqId(),
            fixture.supplierId(),
            portalRequest(true, List.of(offer(fixture.itemId(0), "5", "300000", "12")))
        );

        assertThat(response.totalAmount()).isEqualByComparingTo("1500000.0000");
    }

    @Test
    void portalDraftGrandTotalTreatsNullTaxRateAsZero() {
        PortalDraftFixture fixture = portalDraftFixture(1);
        stubPortalDraft(fixture);

        var response = service.createPortalDraft(
            fixture.rfqId(),
            fixture.supplierId(),
            portalRequest(false, List.of(offer(fixture.itemId(0), "5", "300000", null)))
        );

        assertThat(response.totalAmount()).isEqualByComparingTo("1500000.0000");
    }

    @Test
    void portalDraftGrandTotalSkipsNoBidLines() {
        PortalDraftFixture fixture = portalDraftFixture(2);
        stubPortalDraft(fixture);

        var response = service.createPortalDraft(
            fixture.rfqId(),
            fixture.supplierId(),
            portalRequest(false, List.of(
                offer(fixture.itemId(0), "5", "300000", "12"),
                new ProposalLineRequest(
                    fixture.itemId(1),
                    BigDecimal.ZERO,
                    new BigDecimal("900000"),
                    null,
                    new BigDecimal("12"),
                    true,
                    "Unavailable",
                    null
                )
            ))
        );

        assertThat(response.totalAmount()).isEqualByComparingTo("1680000.0000");
        assertThat(response.items().get(1).totalAmount()).isNull();
    }

    private PortalDraftFixture portalDraftFixture(int itemCount) {
        UUID rfqId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        RFQ rfq = rfq(rfqId);
        rfq.setStatus("OPEN");
        Supplier supplier = supplier(supplierId);
        List<RFQItem> rfqItems = new java.util.ArrayList<>();
        for (int index = 0; index < itemCount; index++) {
            rfqItems.add(rfqItem(rfqId, UUID.randomUUID()));
        }
        RfqSupplierInvitation invitation = invitation(rfqId, supplierId);
        RfqPublishedVersion version = version(rfqId, rfq.getActivePublishedVersionId());
        return new PortalDraftFixture(rfqId, supplierId, rfq, supplier, rfqItems, invitation, version);
    }

    private void stubPortalDraft(PortalDraftFixture fixture) {
        when(rfqRepository.lockScopedById(TENANT, COMPANY_ID, fixture.rfqId()))
            .thenReturn(Optional.of(fixture.rfq()));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, fixture.supplierId()))
            .thenReturn(Optional.of(fixture.supplier()));
        when(invitationRepository.findActiveInvitation(TENANT, COMPANY_ID, fixture.rfqId(), fixture.supplierId()))
            .thenReturn(Optional.of(fixture.invitation()));
        when(versionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TENANT, COMPANY_ID, fixture.version().getId()
        )).thenReturn(Optional.of(fixture.version()));
        when(rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TENANT, fixture.rfqId()))
            .thenReturn(fixture.rfqItems());
        when(threadRepository.lockByRfqIdAndSupplierId(TENANT, COMPANY_ID, fixture.rfqId(), fixture.supplierId()))
            .thenReturn(Optional.empty());
        when(threadRepository.saveAndFlush(any(SupplierQuotationThread.class))).thenAnswer(invocation -> {
            SupplierQuotationThread thread = invocation.getArgument(0);
            thread.setId(UUID.randomUUID());
            return thread;
        });
        when(quotationRepository.save(any(SupplierQuotation.class))).thenAnswer(invocation -> {
            SupplierQuotation quotation = invocation.getArgument(0);
            if (quotation.getId() == null) {
                quotation.setId(UUID.randomUUID());
            }
            return quotation;
        });
        when(quotationRepository.findByTenantIdAndCompanyIdAndThreadIdAndIsDeletedFalseOrderByRevisionNumberAsc(
            any(), any(), any()
        )).thenReturn(List.of());
    }

    private PortalProposalRequest portalRequest(Boolean taxIncluded, List<ProposalLineRequest> items) {
        return new PortalProposalRequest("UZS", null, null, null, taxIncluded, items);
    }

    private ProposalLineRequest offer(UUID itemId, String quantity, String unitPrice, String taxRate) {
        return new ProposalLineRequest(
            itemId,
            new BigDecimal(quantity),
            new BigDecimal(unitPrice),
            1,
            taxRate == null ? null : new BigDecimal(taxRate),
            false,
            null,
            null
        );
    }

    private record PortalDraftFixture(
        UUID rfqId,
        UUID supplierId,
        RFQ rfq,
        Supplier supplier,
        List<RFQItem> rfqItems,
        RfqSupplierInvitation invitation,
        RfqPublishedVersion version
    ) {
        UUID itemId(int index) {
            return rfqItems.get(index).getId();
        }
    }

    private ManualProposalRequest manualRequest(UUID supplierId, UUID itemId, String supersessionReason) {
        return new ManualProposalRequest(
            supplierId,
            QuotationSource.EMAIL,
            NOW.minusHours(1),
            "UZS",
            "30 days",
            "DAP Tashkent",
            LocalDate.of(2026, 8, 30),
            "Received by procurement mailbox",
            supersessionReason,
            List.of(new ProposalLineRequest(
                itemId,
                new BigDecimal("10"),
                new BigDecimal("125000"),
                7,
                new BigDecimal("12"),
                false,
                null,
                "In stock"
            ))
        );
    }

    private RFQ rfq(UUID id) {
        RFQ rfq = new RFQ();
        rfq.setId(id);
        rfq.setTenantId(TENANT);
        rfq.setCompanyId(COMPANY_ID);
        rfq.setCode("RFQ-REV-1");
        rfq.setName("Revision RFQ");
        rfq.setStatus("PUBLISHED");
        rfq.setDueDate(LocalDate.of(2026, 7, 18));
        rfq.setActivePublishedVersionId(UUID.randomUUID());
        return rfq;
    }

    private Supplier supplier(UUID id) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(COMPANY_ID);
        supplier.setCode("SUP-REV-1");
        supplier.setName("Supplier Revision LLC");
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private RFQItem rfqItem(UUID rfqId, UUID id) {
        RFQItem item = new RFQItem();
        item.setId(id);
        item.setTenantId(TENANT);
        item.setCompanyId(COMPANY_ID);
        item.setRfqId(rfqId);
        item.setCode("RFQI-REV-1");
        item.setName("Revision item");
        item.setStatus("ACTIVE");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(new BigDecimal("10"));
        item.setUnit("EA");
        return item;
    }

    private RfqSupplierInvitation invitation(UUID rfqId, UUID supplierId) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setId(UUID.randomUUID());
        invitation.setTenantId(TENANT);
        invitation.setCompanyId(COMPANY_ID);
        invitation.setRfqId(rfqId);
        invitation.setSupplierId(supplierId);
        invitation.setStatus("INVITED");
        return invitation;
    }

    private RfqPublishedVersion version(UUID rfqId, UUID id) {
        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setId(id);
        version.setTenantId(TENANT);
        version.setCompanyId(COMPANY_ID);
        version.setRfqId(rfqId);
        version.setVersionNumber(1);
        return version;
    }

    private SupplierQuotation previous(UUID rfqId, UUID supplierId, UUID versionId) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(UUID.randomUUID());
        quotation.setTenantId(TENANT);
        quotation.setCompanyId(COMPANY_ID);
        quotation.setRfqId(rfqId);
        quotation.setSupplierId(supplierId);
        quotation.setPublishedVersionId(versionId);
        quotation.setRevisionNumber(1);
        quotation.setSource(QuotationSource.SUPPLIER_PORTAL);
        quotation.setStatus("SUBMITTED");
        quotation.setCurrentRevision(true);
        return quotation;
    }

    private SupplierQuotationThread thread(UUID rfqId, UUID supplierId, UUID currentQuotationId) {
        SupplierQuotationThread thread = new SupplierQuotationThread();
        thread.setId(UUID.randomUUID());
        thread.setTenantId(TENANT);
        thread.setCompanyId(COMPANY_ID);
        thread.setRfqId(rfqId);
        thread.setSupplierId(supplierId);
        thread.setCurrentQuotationId(currentQuotationId);
        return thread;
    }
}
