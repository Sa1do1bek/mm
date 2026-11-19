package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RfqPackageBuilderTest {
    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void buildsSupplierSafeSnapshotFromCurrentScopedRfq() {
        String tenantId = "tenant-rfq-package";
        UUID companyId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();
        UUID rfqItemId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        RFQRepository rfqRepository = mock(RFQRepository.class);
        RFQItemRepository itemRepository = mock(RFQItemRepository.class);
        RFQPurchaseRequestSourceRepository sourceRepository = mock(RFQPurchaseRequestSourceRepository.class);
        RfqSupplierInvitationRepository invitationRepository = mock(RfqSupplierInvitationRepository.class);
        CompanyRepository companyRepository = mock(CompanyRepository.class);
        AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);

        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId(tenantId);
        rfq.setCompanyId(companyId);
        rfq.setCode("RFQ-SAFE-1");
        rfq.setName("ru: Безопасный пакет\nen: Safe package\nuz: Xavfsiz paket");
        rfq.setDescription("ru: Описание\nen: Supplier-visible description\nuz: Tavsif");
        rfq.setDueDate(LocalDate.of(2026, 8, 20));
        rfq.setPackageLanguage("en");
        rfq.setResponsibleBuyer("Buyer Name");
        rfq.setResponseInstructions("ru: Ответьте по почте\nen: Use portal or reply by email\nuz: Portal orqali javob bering");

        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode("MAT-SAFE-1");
        material.setName("ru: Безопасный материал\nen: Safe material\nuz: Xavfsiz material");
        material.setDescription("ru: Спецификация\nen: Supplier specification\nuz: Yetkazib beruvchi spetsifikatsiyasi");
        RFQItem item = new RFQItem();
        item.setId(rfqItemId);
        item.setRfqId(rfqId);
        item.setMaterial(material);
        item.setMaterialId(material.getId());
        item.setUnit("kg");
        item.setQuantity(new BigDecimal("12.5"));

        PurchaseRequest purchaseRequest = new PurchaseRequest();
        purchaseRequest.setId(UUID.randomUUID());
        purchaseRequest.setCode("PR-SAFE-1");
        purchaseRequest.setName("Safe purchase request");
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setRfqItemId(rfqItemId);
        source.setPurchaseRequestId(purchaseRequest.getId());
        source.setPurchaseRequest(purchaseRequest);
        source.setSourceRequiredDate(LocalDate.of(2026, 8, 15));

        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setName("Supplier Safe");
        supplier.setDirectorName("Sales Contact");
        supplier.setEmail("sales@safe.example");
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setStatus("INVITED");
        invitation.setSupplierId(supplierId);
        invitation.setSupplier(supplier);

        Company company = new Company();
        company.setId(companyId);
        company.setName("Buyer Safe LLC");
        company.setLegalAddress("Tashkent");
        company.setEmail("buyer@safe.example");
        company.setPhone("+998710000000");

        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID());
        attachment.setOriginalFileName("specification.pdf");
        attachment.setContentType("application/pdf");

        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(itemRepository.findByTenantIdAndRfqIdOrderById(tenantId, rfqId)).thenReturn(List.of(item));
        when(sourceRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            tenantId, companyId, rfqId)).thenReturn(List.of(source));
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            tenantId, companyId, rfqId)).thenReturn(List.of(invitation));
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, companyId))
            .thenReturn(Optional.of(company));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, "RFQ", rfqId)).thenReturn(List.of(attachment));

        RfqPackageSnapshot snapshot = new RfqPackageBuilder(
            rfqRepository,
            itemRepository,
            sourceRepository,
            invitationRepository,
            companyRepository,
            attachmentRepository
        ).build(rfqId);

        assertThat(snapshot.positions()).singleElement().satisfies(position -> {
            assertThat(position.rfqItemId()).isEqualTo(rfqItemId);
            assertThat(position.materialCode()).isEqualTo("MAT-SAFE-1");
            assertThat(position.materialName()).isEqualTo("Safe material");
            assertThat(position.specification()).isEqualTo("Supplier specification");
            assertThat(position.requiredDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        });
        assertThat(snapshot.title()).isEqualTo("Safe package");
        assertThat(snapshot.description()).isEqualTo("Supplier-visible description");
        assertThat(snapshot.responseInstructions()).isEqualTo("Use portal or reply by email");
        assertThat(snapshot.toString()).doesNotContain("ru:", "en:", "uz:");
        assertThat(snapshot.suppliers()).singleElement().satisfies(recipient -> {
            assertThat(recipient.supplierId()).isEqualTo(supplierId);
            assertThat(recipient.email()).isEqualTo("sales@safe.example");
        });
        assertThat(snapshot.sources()).extracting(RfqPackageSnapshot.SourceReference::purchaseRequestCode)
            .containsExactly("PR-SAFE-1");
        assertThat(snapshot.documents()).extracting(RfqPackageSnapshot.SupportingDocument::fileName)
            .containsExactly("specification.pdf");
        assertThat(snapshot.buyer().companyName()).isEqualTo("Buyer Safe LLC");
    }
}
