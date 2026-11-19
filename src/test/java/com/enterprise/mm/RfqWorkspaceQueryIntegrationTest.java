package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.enums.RfqDeliveryStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceFilter;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.RfqWorkspaceQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RfqWorkspaceQueryIntegrationTest {
    private static final String TENANT = "default";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired RfqWorkspaceQueryService service;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository itemRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired RfqPublishedVersionRepository versionRepository;
    @Autowired RfqSupplierInvitationRepository invitationRepository;
    @Autowired RfqInvitationDeliveryRepository deliveryRepository;
    @Autowired SupplierQuotationRepository quotationRepository;
    @Autowired SupplierQuotationItemRepository quotationItemRepository;
    @Autowired AuditLogRepository auditLogRepository;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void workspaceFiltersOverdueFailedDeliveryAndPartialResponse() {
        setContext();
        PurchaseRequest purchaseRequest = purchaseRequest();
        MaterialGroup group = materialGroup();
        Material material = material(group);
        RFQ rfq = rfq(purchaseRequest);
        RFQItem rfqItem = item(rfq, material);
        Supplier respondingSupplier = supplier("RESPONDING");
        Supplier pendingSupplier = supplier("PENDING");
        Supplier withdrawnSupplier = supplier("WITHDRAWN");
        RfqPublishedVersion version = version(rfq);
        rfq.setActivePublishedVersionId(version.getId());
        rfqRepository.saveAndFlush(rfq);
        RfqSupplierInvitation respondingInvitation = invitation(rfq, respondingSupplier);
        RfqSupplierInvitation pendingInvitation = invitation(rfq, pendingSupplier);
        RfqSupplierInvitation withdrawnInvitation = invitation(rfq, withdrawnSupplier);
        withdrawnInvitation.setStatus("WITHDRAWN");
        invitationRepository.saveAndFlush(withdrawnInvitation);
        RfqInvitationDelivery failedDelivery = delivery(
            respondingInvitation, version, respondingSupplier, RfqDeliveryStatus.FAILED);
        delivery(pendingInvitation, version, pendingSupplier, RfqDeliveryStatus.SENT);
        SupplierQuotation quotation = quotation(rfq, respondingSupplier, version);
        quotationItem(quotation, rfqItem, "Supplier-specific line label");
        auditLog(rfq.getId(), "RFQ UPDATE");

        RfqWorkspaceFilter filter = new RfqWorkspaceFilter(
            List.of("OPEN"),
            null,
            null,
            true,
            null,
            purchaseRequest.getId(),
            material.getId(),
            group.getId(),
            respondingSupplier.getId(),
            "PARTIAL",
            List.of("FAILED"),
            null,
            null,
            null,
            null,
            material.getCode()
        );

        var rows = service.search(filter, PageRequest.of(0, 20));

        assertThat(rows).extracting(row -> row.rfqId()).containsExactly(rfq.getId());
        assertThat(rows.getContent().getFirst().name()).isEqualTo(rfq.getName());
        assertThat(rows.getContent().getFirst().title()).isEqualTo(rfq.getName());
        assertThat(rows.getContent().getFirst().sourcePurchaseRequestCount()).isEqualTo(1);
        assertThat(rows.getContent().getFirst().positionCount()).isEqualTo(1);
        assertThat(rows.getContent().getFirst().proposalCount()).isEqualTo(1);
        assertThat(rows.getContent().getFirst().invitedSupplierCount()).isEqualTo(2);
        assertThat(rows.getContent().getFirst().failedDeliveryCount()).isEqualTo(1);
        assertThat(rows.getContent().getFirst().overdue()).isTrue();

        RFQ opened = rfqRepository.findByIdAndIsDeletedFalse(rfq.getId()).orElseThrow();
        opened.setStatus("BIDS_OPENED");
        rfqRepository.saveAndFlush(opened);
        var detail = service.detail(rfq.getId());
        assertThat(detail.overview().name()).isEqualTo(rfq.getName());
        assertThat(detail.overview().title()).isEqualTo(rfq.getName());
        assertThat(detail.overview().responseProgress()).isEqualTo("PARTIAL");
        assertThat(detail.purchaseRequests()).extracting(value -> value.purchaseRequestId())
            .containsExactly(purchaseRequest.getId());
        assertThat(detail.positions()).singleElement().satisfies(position ->
            assertThat(position.materialCode()).isEqualTo(material.getCode()));
        assertThat(detail.invitedSuppliers()).hasSize(2);
        assertThat(detail.invitedSuppliers())
            .extracting(supplier -> supplier.supplierId())
            .doesNotContain(withdrawnSupplier.getId());
        assertThat(detail.invitedSuppliers())
            .filteredOn(supplier -> supplier.supplierId().equals(respondingSupplier.getId()))
            .singleElement()
            .satisfies(supplier -> assertThat(supplier.deliveries())
                .singleElement()
                .satisfies(delivery -> assertThat(delivery.version()).isEqualTo(failedDelivery.getVersion())));
        assertThat(detail.proposals()).singleElement().satisfies(proposal ->
            assertThat(proposal.current().supplierId()).isEqualTo(respondingSupplier.getId()));
        assertThat(detail.audit()).singleElement().satisfies(audit ->
            assertThat(audit.name()).contains("RFQ UPDATE"));

        var comparison = service.comparison(rfq.getId());
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.currencyCodes()).containsExactly("UZS");
        assertThat(comparison.proposals()).hasSize(1);
        assertThat(comparison.proposals().getFirst().current().lines()).singleElement()
            .satisfies(line -> {
                assertThat(line.name()).isNotBlank();
                assertThat(line.materialId()).isEqualTo(material.getId());
                assertThat(line.materialCode()).isEqualTo(material.getCode());
                assertThat(line.materialName()).isEqualTo(material.getName());
            });
    }

    @Test
    void workspaceSealsProposalContentsUntilBidsAreOpened() {
        setContext();
        PurchaseRequest purchaseRequest = purchaseRequest();
        MaterialGroup group = materialGroup();
        Material material = material(group);
        RFQ rfq = rfq(purchaseRequest);
        RFQItem rfqItem = item(rfq, material);
        Supplier supplier = supplier("SEALED");
        RfqPublishedVersion version = version(rfq);
        rfq.setActivePublishedVersionId(version.getId());
        rfqRepository.saveAndFlush(rfq);
        RfqSupplierInvitation invitation = invitation(rfq, supplier);
        delivery(invitation, version, supplier, RfqDeliveryStatus.SENT);
        SupplierQuotation quotation = quotation(rfq, supplier, version);
        quotationItem(quotation, rfqItem, "Sealed line label");

        var detail = service.detail(rfq.getId());

        assertThat(detail.proposals()).isEmpty();
        assertThat(detail.documents()).noneMatch(document ->
            List.of("ORIGINAL_SUPPLIER_PROPOSAL", "PROPOSAL_SUPPORTING_DOCUMENT")
                .contains(document.documentType()));
        assertThatThrownBy(() -> service.comparison(rfq.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("sealed");
    }

    @Test
    void workspaceShowsOpenTenderProposalsDuringActiveTender() {
        setContext();
        PurchaseRequest purchaseRequest = purchaseRequest();
        MaterialGroup group = materialGroup();
        Material material = material(group);
        RFQ rfq = rfq(purchaseRequest);
        rfq.setTenderProcedureType("OPEN_TENDER");
        rfq.setPurpose("PROCUREMENT");
        rfq.setDueDate(LocalDate.now().plusDays(7));
        rfq = rfqRepository.saveAndFlush(rfq);
        RFQItem rfqItem = item(rfq, material);
        Supplier supplier = supplier("OPEN-TENDER");
        RfqPublishedVersion version = version(rfq);
        rfq.setActivePublishedVersionId(version.getId());
        rfqRepository.saveAndFlush(rfq);
        RfqSupplierInvitation invitation = invitation(rfq, supplier);
        delivery(invitation, version, supplier, RfqDeliveryStatus.SENT);
        SupplierQuotation quotation = quotation(rfq, supplier, version);
        quotationItem(quotation, rfqItem, "Open tender line label");

        var detail = service.detail(rfq.getId());
        var comparison = service.comparison(rfq.getId());

        assertThat(detail.proposals()).singleElement().satisfies(proposal ->
            assertThat(proposal.current().supplierId()).isEqualTo(supplier.getId()));
        assertThat(comparison.proposals()).hasSize(1);
    }

    @Test
    void workspaceListsNewestRfqsFirstAndKeepsNameInRows() throws Exception {
        setContext();
        String buyer = "newest-rfq-" + suffix();
        PurchaseRequest oldRequest = purchaseRequest();
        RFQ oldOverdue = rfq(oldRequest);
        oldOverdue.setResponsibleBuyer(buyer);
        oldOverdue = rfqRepository.saveAndFlush(oldOverdue);
        Thread.sleep(10);
        RFQ newest = rfq(purchaseRequest());
        newest.setName("Newest workspace RFQ");
        newest.setDueDate(LocalDate.now().plusDays(10));
        newest.setStatus("DRAFT");
        newest.setResponsibleBuyer(buyer);
        newest = rfqRepository.saveAndFlush(newest);

        RfqWorkspaceFilter filter = new RfqWorkspaceFilter(
            List.of(),
            null,
            null,
            null,
            buyer,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null
        );
        var rows = service.search(filter, PageRequest.of(0, 20));

        assertThat(rows.getContent())
            .extracting(row -> row.rfqId())
            .containsExactly(newest.getId(), oldOverdue.getId());
        assertThat(rows.getContent().getFirst().name()).isEqualTo(newest.getName());
        assertThat(rows.getContent().getFirst().title()).isEqualTo(newest.getName());
    }

    private void setContext() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "workspace-admin",
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }

    private PurchaseRequest purchaseRequest() {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-WS-" + suffix());
        request.setName("Workspace source request");
        request.setStatus("APPROVED");
        request.setRequestedBy("workspace-admin");
        request.setRequiredDate(LocalDate.now().plusDays(5));
        return purchaseRequestRepository.saveAndFlush(request);
    }

    private MaterialGroup materialGroup() {
        MaterialGroup group = new MaterialGroup();
        group.setCode("MG-WS-" + suffix());
        group.setName("Workspace material group");
        group.setStatus("ACTIVE");
        return materialGroupRepository.saveAndFlush(group);
    }

    private Material material(MaterialGroup group) {
        Material material = new Material();
        material.setCode("MAT-WS-" + suffix());
        material.setName("Workspace pump");
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(group.getId());
        return materialRepository.saveAndFlush(material);
    }

    private RFQ rfq(PurchaseRequest request) {
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-WS-" + suffix());
        rfq.setName("Overdue workspace RFQ");
        rfq.setStatus("OPEN");
        rfq.setPurchaseRequestId(request.getId());
        rfq.setResponsibleBuyer("workspace-admin");
        rfq.setDueDate(LocalDate.now().minusDays(1));
        rfq.setPublishedAt(LocalDateTime.now().minusDays(3));
        return rfqRepository.saveAndFlush(rfq);
    }

    private RFQItem item(RFQ rfq, Material material) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-WS-" + suffix());
        item.setName("Workspace line");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setMaterialId(material.getId());
        item.setUnit("EA");
        item.setQuantity(BigDecimal.TEN);
        return itemRepository.saveAndFlush(item);
    }

    private Supplier supplier(String role) {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-WS-" + role + "-" + suffix());
        supplier.setName("Workspace supplier " + role);
        supplier.setStatus("ACTIVE");
        supplier.setEmail(role.toLowerCase() + "-" + suffix() + "@example.uz");
        return supplierRepository.saveAndFlush(supplier);
    }

    private RfqPublishedVersion version(RFQ rfq) {
        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setCode("RFQV-WS-" + suffix());
        version.setName("Workspace version");
        version.setStatus("ACTIVE");
        version.setRfqId(rfq.getId());
        version.setVersionNumber(1);
        version.setSnapshotJson("{}");
        version.setPackageLanguage("ru");
        version.setTemplateVersion("rfq-package-v1");
        version.setPublishedAt(rfq.getPublishedAt());
        version.setPublishedBy("workspace-admin");
        return versionRepository.saveAndFlush(version);
    }

    private RfqSupplierInvitation invitation(RFQ rfq, Supplier supplier) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("INV-WS-" + suffix());
        invitation.setName("Workspace invitation");
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        return invitationRepository.saveAndFlush(invitation);
    }

    private RfqInvitationDelivery delivery(
        RfqSupplierInvitation invitation,
        RfqPublishedVersion version,
        Supplier supplier,
        RfqDeliveryStatus status
    ) {
        RfqInvitationDelivery delivery = new RfqInvitationDelivery();
        delivery.setCode("DEL-WS-" + suffix());
        delivery.setName("Workspace delivery");
        delivery.setDeliveryStatus(status);
        delivery.setInvitationId(invitation.getId());
        delivery.setPublishedVersionId(version.getId());
        delivery.setSupplierId(supplier.getId());
        delivery.setRecipientEmail(supplier.getEmail());
        return deliveryRepository.saveAndFlush(delivery);
    }

    private SupplierQuotation quotation(RFQ rfq, Supplier supplier, RfqPublishedVersion version) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("SQ-WS-" + suffix());
        quotation.setName("Workspace proposal");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setPublishedVersionId(version.getId());
        quotation.setCurrencyCode("UZS");
        quotation.setTotalAmount(new BigDecimal("1000"));
        quotation.setCurrentRevision(true);
        quotation.setSubmittedAt(LocalDateTime.now().minusDays(2));
        return quotationRepository.saveAndFlush(quotation);
    }

    private void quotationItem(SupplierQuotation quotation, RFQItem rfqItem, String name) {
        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setCode("SQI-WS-" + suffix());
        item.setName(name);
        item.setStatus("SUBMITTED");
        item.setQuotationId(quotation.getId());
        item.setRfqItemId(rfqItem.getId());
        item.setMaterialId(rfqItem.getMaterialId());
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(new BigDecimal("100"));
        item.setDeliveryDays(5);
        quotationItemRepository.saveAndFlush(item);
    }

    private void auditLog(UUID entityId, String name) {
        AuditLog audit = new AuditLog();
        audit.setCode("AUD-WS-" + suffix());
        audit.setName(name);
        audit.setStatus("RECORDED");
        audit.setEntityType("RFQ");
        audit.setEntityId(entityId);
        audit.setAction("UPDATE");
        audit.setUsername("workspace-admin");
        audit.setDetails("{\"details\":\"workspace audit\"}");
        audit.setOccurredAt(LocalDateTime.now().minusHours(1));
        auditLogRepository.saveAndFlush(audit);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
