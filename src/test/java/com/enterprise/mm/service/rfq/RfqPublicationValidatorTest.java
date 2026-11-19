package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.payload.RfqPublicationDtos.ValidationIssue;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.service.attachment.AttachmentStorage;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;

class RfqPublicationValidatorTest {
    private static final String TENANT = "tenant-publication-validator";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000005501");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 18);

    private RFQItemRepository itemRepository;
    private RfqSupplierInvitationRepository invitationRepository;
    private SupplierPortalAccountRepository accountRepository;
    private AttachmentRepository attachmentRepository;
    private ProcurementProcedureRepository procedureRepository;
    private AttachmentStorage storage;
    private RfqMailProperties mailProperties;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        itemRepository = mock(RFQItemRepository.class);
        invitationRepository = mock(RfqSupplierInvitationRepository.class);
        accountRepository = mock(SupplierPortalAccountRepository.class);
        attachmentRepository = mock(AttachmentRepository.class);
        procedureRepository = mock(ProcurementProcedureRepository.class);
        storage = mock(AttachmentStorage.class);
        mailProperties = new RfqMailProperties();
        mailProperties.setEnabled(true);
        mailProperties.setFrom("rfq@buyer.test");
        mailProperties.setReplyTo("procurement@buyer.test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void reportsStableIssuesForIncompleteDraft() {
        RFQ rfq = draft();
        rfq.setTenderProcedureType("REQUEST_FOR_QUOTATION");
        rfq.setDueDate(TODAY.minusDays(1));
        mailProperties.setEnabled(false);
        when(itemRepository.findByTenantIdAndRfqIdOrderById(TENANT, rfq.getId())).thenReturn(List.of());
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TENANT, COMPANY_ID, rfq.getId()
        )).thenReturn(List.of());
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, "RFQ", rfq.getId()
        )).thenReturn(List.of());

        assertThat(codes(validator().validate(rfq))).containsExactlyInAnyOrder(
            "RFQ_DUE_DATE_EXPIRED",
            "RFQ_ITEMS_REQUIRED",
            "RFQ_SUPPLIERS_REQUIRED",
            "RFQ_MAIL_NOT_CONFIGURED"
        );
    }

    @Test
    void openTenderDoesNotRequireInvitedSuppliersForPublication() {
        RFQ rfq = draft();
        rfq.setPurpose("PROCUREMENT");
        rfq.setTenderProcedureType("OPEN_TENDER");
        stubComplete(rfq, List.of());

        assertThat(codes(validator().validate(rfq))).doesNotContain("RFQ_SUPPLIERS_REQUIRED");
    }

    @Test
    void openCompetitionDoesNotRequireInvitedSuppliersForPublication() {
        RFQ rfq = draft();
        rfq.setPurpose("PROCUREMENT");
        rfq.setTenderProcedureType("OPEN_COMPETITION");
        stubComplete(rfq, List.of());

        assertThat(codes(validator().validate(rfq))).doesNotContain("RFQ_SUPPLIERS_REQUIRED");
    }

    @Test
    void priceDiscoveryStillRequiresInvitedSuppliersEvenWhenLinkedMethodIsOpenTender() {
        RFQ rfq = draft();
        rfq.setPurpose("PRICE_DISCOVERY");
        rfq.setTenderProcedureType("OPEN_TENDER");
        stubComplete(rfq, List.of());

        assertThat(codes(validator().validate(rfq))).contains("RFQ_SUPPLIERS_REQUIRED");
    }

    @Test
    void closedTenderStillRequiresInvitedSuppliersForPublication() {
        RFQ rfq = draft();
        rfq.setTenderProcedureType("CLOSED_TENDER");
        stubComplete(rfq, List.of());

        assertThat(codes(validator().validate(rfq))).contains("RFQ_SUPPLIERS_REQUIRED");
    }

    @Test
    void reportsInvalidSupplierEmailAndPortalConflict() {
        RFQ rfq = draft();
        Supplier invalid = supplier("invalid-address");
        Supplier conflict = supplier("sales@supplier.test");
        RfqSupplierInvitation invalidInvitation = invitation(invalid);
        RfqSupplierInvitation conflictInvitation = invitation(conflict);
        SupplierPortalAccount suspended = new SupplierPortalAccount();
        suspended.setStatus("SUSPENDED");
        suspended.setLoginEmail(conflict.getEmail());
        stubComplete(rfq, List.of(invalidInvitation, conflictInvitation));
        when(accountRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, conflict.getId()
        )).thenReturn(List.of(suspended));

        assertThat(codes(validator().validate(rfq))).containsExactlyInAnyOrder(
            "RFQ_SUPPLIER_EMAIL_INVALID",
            "RFQ_SUPPLIER_PORTAL_EMAIL_CONFLICT"
        );
    }

    @Test
    void supplierEmailIssuesDoNotBlockPublicationWhenAnotherInvitedSupplierCanReceiveLetter() {
        RFQ rfq = draft();
        Supplier invalid = supplier("invalid-address");
        Supplier eligible = supplier("sales@supplier.test");
        stubComplete(rfq, List.of(invitation(invalid), invitation(eligible)));

        assertThat(codes(validator().blockingIssues(rfq))).doesNotContain(
            "RFQ_SUPPLIER_EMAIL_INVALID",
            "RFQ_NO_ELIGIBLE_SUPPLIERS"
        );
    }

    @Test
    void supplierEmailIssuesBlockPublicationWhenNoInvitedSupplierCanReceiveLetter() {
        RFQ rfq = draft();
        Supplier invalid = supplier("invalid-address");
        stubComplete(rfq, List.of(invitation(invalid)));

        assertThat(codes(validator().blockingIssues(rfq))).containsExactly("RFQ_NO_ELIGIBLE_SUPPLIERS");
    }

    @Test
    void reportsSupportingDocumentWhoseStoredBytesAreUnavailable() {
        RFQ rfq = draft();
        Supplier supplier = supplier("sales@supplier.test");
        stubComplete(rfq, List.of(invitation(supplier)));
        Attachment document = new Attachment();
        document.setStorageProvider("LOCAL");
        document.setStorageKey("missing/specification.pdf");
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, "RFQ", rfq.getId()
        )).thenReturn(List.of(document));
        when(storage.supports("LOCAL")).thenReturn(true);
        when(storage.read(document.getStorageKey())).thenThrow(new IllegalStateException("missing"));

        assertThat(codes(validator().validate(rfq))).containsExactly("RFQ_DOCUMENT_MISSING");
    }

    @Test
    void acceptsCompleteDraftWithReadableSupportingDocument() {
        RFQ rfq = draft();
        Supplier supplier = supplier("sales@supplier.test");
        stubComplete(rfq, List.of(invitation(supplier)));
        Attachment document = new Attachment();
        document.setStorageProvider("LOCAL");
        document.setStorageKey("rfq/specification.pdf");
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, "RFQ", rfq.getId()
        )).thenReturn(List.of(document));
        when(storage.supports("LOCAL")).thenReturn(true);
        when(storage.read(document.getStorageKey())).thenReturn(new InputStreamResource(
            new ByteArrayInputStream(new byte[] {1, 2, 3})
        ));

        assertThat(validator().validate(rfq)).isEmpty();
    }

    private RfqPublicationValidator validator() {
        return new RfqPublicationValidator(
            itemRepository,
            invitationRepository,
            new SupplierPortalEligibilityValidator(accountRepository),
            attachmentRepository,
            procedureRepository,
            List.of(storage),
            mailProperties,
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private void stubComplete(RFQ rfq, List<RfqSupplierInvitation> invitations) {
        RFQItem item = new RFQItem();
        item.setId(UUID.randomUUID());
        when(itemRepository.findByTenantIdAndRfqIdOrderById(TENANT, rfq.getId())).thenReturn(List.of(item));
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TENANT, COMPANY_ID, rfq.getId()
        )).thenReturn(invitations);
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, "RFQ", rfq.getId()
        )).thenReturn(List.of());
        for (RfqSupplierInvitation invitation : invitations) {
            Supplier supplier = invitation.getSupplier();
            if (supplier == null) {
                continue;
            }
            when(accountRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY_ID, supplier.getId()
            )).thenReturn(List.of());
            when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY_ID, supplier.getEmail()
            )).thenReturn(List.of());
        }
    }

    private RFQ draft() {
        RFQ rfq = new RFQ();
        rfq.setId(UUID.randomUUID());
        rfq.setStatus("DRAFT");
        rfq.setDueDate(TODAY.plusDays(5));
        rfq.setPackageLanguage("ru");
        return rfq;
    }

    private Supplier supplier(String email) {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setEmail(email);
        return supplier;
    }

    private RfqSupplierInvitation invitation(Supplier supplier) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setSupplierId(supplier.getId());
        invitation.setSupplier(supplier);
        invitation.setStatus("INVITED");
        return invitation;
    }

    private List<String> codes(List<ValidationIssue> issues) {
        return issues.stream().map(ValidationIssue::code).toList();
    }
}
