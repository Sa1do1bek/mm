package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Buyer;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Position;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupplierRecipient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RfqSupplierPortalIsolationTest {
    private static final String TENANT = "default";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired RfqSupplierPortalQueryService service;
    @Autowired SupplierRepository supplierRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired RfqPublishedVersionRepository versionRepository;
    @Autowired RfqSupplierInvitationRepository invitationRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired SupplierQuotationRepository quotationRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void supplierCannotReadAnotherSuppliersRfq() throws Exception {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        Supplier supplierA = supplier("A");
        Supplier supplierB = supplier("B");
        RFQ rfq = rfq();
        RfqPackageSnapshot snapshot = new RfqPackageSnapshot(
            rfq.getId(),
            rfq.getCode(),
            rfq.getName(),
            "Supplier-safe description",
            "ru",
            rfq.getDueDate(),
            new Buyer("Buyer LLC", "Tashkent", "Buyer", "buyer@example.uz", "+998900000000"),
            "Reply by email or portal",
            List.of(new Position(
                UUID.randomUUID(), "MAT-SAFE", "Safe material", "Specification", "EA",
                BigDecimal.TEN, LocalDate.now().plusDays(5)
            )),
            List.of(),
            List.of(new SupplierRecipient(
                supplierB.getId(), supplierB.getName(), "Contact B", supplierB.getEmail()
            )),
            List.of()
        );
        RfqPublishedVersion version = version(rfq, objectMapper.writeValueAsString(snapshot));
        rfq.setActivePublishedVersionId(version.getId());
        rfqRepository.saveAndFlush(rfq);
        invitation(rfq, supplierB);

        assertThatThrownBy(() -> service.detail(TENANT, COMPANY_ID, supplierA.getId(), rfq.getId()))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("RFQ not found");

        var detail = service.detail(TENANT, COMPANY_ID, supplierB.getId(), rfq.getId());
        assertThat(detail.rfqId()).isEqualTo(rfq.getId());
        assertThat(detail.positions()).singleElement().satisfies(position -> {
            assertThat(position.materialCode()).isEqualTo("MAT-SAFE");
            assertThat(position.quantity()).isEqualByComparingTo("10");
        });

        SupplierQuotation draft = new SupplierQuotation();
        draft.setCode("SQ-DRAFT-" + shortId());
        draft.setName("Recoverable supplier draft");
        draft.setStatus("DRAFT");
        draft.setRfqId(rfq.getId());
        draft.setSupplierId(supplierB.getId());
        draft.setPublishedVersionId(version.getId());
        draft.setCurrencyCode("UZS");
        draft.setRevisionNumber(1);
        quotationRepository.saveAndFlush(draft);

        var detailWithDraft = service.detail(TENANT, COMPANY_ID, supplierB.getId(), rfq.getId());
        assertThat(detailWithDraft.responseState()).isEqualTo("DRAFT");
        assertThat(detailWithDraft.currentProposal()).isNotNull();
        assertThat(detailWithDraft.currentProposal().quotationId()).isEqualTo(draft.getId());
        assertThat(detailWithDraft.currentProposal().status()).isEqualTo("DRAFT");

        Attachment unrelated = unrelatedAttachment();
        assertThatThrownBy(() -> service.download(
            TENANT, COMPANY_ID, supplierB.getId(), rfq.getId(), unrelated.getId()
        )).isInstanceOf(NotFoundException.class).hasMessage("RFQ not found");
    }

    @Test
    void detailLocalizesBuyerCompanyNameFromCurrentRequestLocale() throws Exception {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        Company company = companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, COMPANY_ID)
            .orElseThrow();
        company.setLegalName(LocalizedText.format(
            "Акционерное общество «Навоийазот»",
            "Navoiyazot joint-stock company",
            "Navoiyazot aksiyadorlik jamiyati"
        ));
        company.setLegalAddress(LocalizedText.format(
            "210105, Республика Узбекистан, город Навои",
            "210105, Republic of Uzbekistan, Navoi city",
            "210105, O'zbekiston Respublikasi, Navoiy shahri"
        ));
        companyRepository.saveAndFlush(company);

        Supplier supplier = supplier("LOCALIZED-BUYER");
        RFQ rfq = rfq();
        RfqPackageSnapshot snapshot = new RfqPackageSnapshot(
            rfq.getId(),
            rfq.getCode(),
            rfq.getName(),
            "Supplier-safe description",
            "uz",
            rfq.getDueDate(),
            new Buyer(
                LocalizedText.format(
                    "Акционерное общество «Навоийазот»",
                    "Navoiyazot joint-stock company",
                    "Navoiyazot aksiyadorlik jamiyati"
                ),
                "Snapshot address",
                "Buyer",
                "buyer@example.uz",
                "+998900000000"
            ),
            "Reply by email or portal",
            List.of(new Position(
                UUID.randomUUID(), "MAT-SAFE", "Safe material", "Specification", "EA",
                BigDecimal.TEN, LocalDate.now().plusDays(5)
            )),
            List.of(),
            List.of(new SupplierRecipient(
                supplier.getId(), supplier.getName(), "Contact", supplier.getEmail()
            )),
            List.of()
        );
        RfqPublishedVersion version = version(rfq, objectMapper.writeValueAsString(snapshot));
        rfq.setActivePublishedVersionId(version.getId());
        rfqRepository.saveAndFlush(rfq);
        invitation(rfq, supplier);

        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        var ruDetail = service.detail(TENANT, COMPANY_ID, supplier.getId(), rfq.getId());
        LocaleContextHolder.setLocale(Locale.forLanguageTag("uz"));
        var uzDetail = service.detail(TENANT, COMPANY_ID, supplier.getId(), rfq.getId());

        assertThat(ruDetail.buyer().companyName()).isEqualTo("Акционерное общество «Навоийазот»");
        assertThat(ruDetail.buyer().address()).isEqualTo("210105, Республика Узбекистан, город Навои");
        assertThat(uzDetail.buyer().companyName()).isEqualTo("Navoiyazot aksiyadorlik jamiyati");
        assertThat(uzDetail.language()).isEqualTo("uz");
    }

    private Supplier supplier(String suffix) {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-SAFE-" + suffix + "-" + shortId());
        supplier.setName("Supplier " + suffix);
        supplier.setStatus("ACTIVE");
        supplier.setEmail("supplier-" + suffix.toLowerCase() + "-" + shortId() + "@example.uz");
        return supplierRepository.saveAndFlush(supplier);
    }

    private RFQ rfq() {
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-SAFE-" + shortId());
        rfq.setName("Supplier-safe RFQ");
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(7));
        return rfqRepository.saveAndFlush(rfq);
    }

    private RfqPublishedVersion version(RFQ rfq, String snapshotJson) {
        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setCode("RFQV-SAFE-" + shortId());
        version.setName("Supplier-safe version");
        version.setStatus("ACTIVE");
        version.setRfqId(rfq.getId());
        version.setVersionNumber(1);
        version.setSnapshotJson(snapshotJson);
        version.setPackageLanguage("ru");
        version.setTemplateVersion("rfq-package-v1");
        version.setPublishedAt(java.time.LocalDateTime.now());
        version.setPublishedBy("buyer");
        return versionRepository.saveAndFlush(version);
    }

    private void invitation(RFQ rfq, Supplier supplier) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("INV-SAFE-" + shortId());
        invitation.setName("Supplier-safe invitation");
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        invitationRepository.saveAndFlush(invitation);
    }

    private Attachment unrelatedAttachment() {
        Attachment attachment = new Attachment();
        attachment.setCode("ATT-SAFE-" + shortId());
        attachment.setName("Unrelated confidential document");
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("RFQ_PUBLISHED_VERSION");
        attachment.setEntityId(UUID.randomUUID());
        attachment.setAttachmentType("OFFICIAL_PDF");
        attachment.setOriginalFileName("confidential.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(12L);
        attachment.setChecksumSha256("a".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("tests/unrelated/" + UUID.randomUUID());
        attachment.setUploadedAt(java.time.LocalDateTime.now());
        attachment.setDownloadable(true);
        return attachmentRepository.saveAndFlush(attachment);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
