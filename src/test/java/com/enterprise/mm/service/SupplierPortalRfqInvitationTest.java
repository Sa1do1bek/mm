package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RFQCreateRequest;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.payload.RFQUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountCreateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountResponse;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationItemRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationRequest;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "supplier-manager", roles = "SUPPLIER_MANAGER")
class SupplierPortalRfqInvitationTest {
    private static final String INVITED = "INVITED";
    private static final String WITHDRAWN = "WITHDRAWN";

    @Autowired private SupplierRepository supplierRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private MaterialGroupRepository materialGroupRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private RFQRepository rfqRepository;
    @Autowired private RFQItemRepository rfqItemRepository;
    @Autowired private RfqSupplierInvitationRepository invitationRepository;
    @Autowired private SupplierAccreditationApplicationRepository accreditationRepository;
    @Autowired private SupplierAccreditationDecisionRepository accreditationDecisionRepository;
    @Autowired private SupplierAccreditationDocumentRepository accreditationDocumentRepository;
    @Autowired private SupplierPortalAccountService portalAccountService;
    @Autowired private SupplierPortalAccountRepository portalAccountRepository;
    @Autowired private SupplierPortalExternalService portalExternalService;
    @Autowired private RFQService rfqService;

    @Test
    void portalListsOnlyInvitedRfqs() {
        Supplier supplierA = supplier("SUP-INVITED-A");
        Supplier supplierB = supplier("SUP-INVITED-B");
        MaterialGroup groupA = materialGroup("GROUP-INVITED-A");
        MaterialGroup groupB = materialGroup("GROUP-INVITED-B");
        RFQ rfqA = rfqWithLine("RFQ-INVITED-A", material("MAT-INVITED-A", groupA));
        RFQ rfqB = rfqWithLine("RFQ-INVITED-B", material("MAT-INVITED-B", groupB));
        accredit(supplierA, List.of(groupA.getId()));
        accredit(supplierB, List.of(groupB.getId()));
        invite(rfqA, supplierA);
        invite(rfqB, supplierB);

        assertThat(portalExternalService.listRfqs(activePortalToken(supplierA, true)))
            .extracting(item -> item.id())
            .containsExactly(rfqA.getId());
    }

    @Test
    void invitedEligibleSupplierCanSeeDetailsAndQuote() {
        Supplier supplier = supplier("SUP-ELIGIBLE-QUOTE");
        MaterialGroup group = materialGroup("GROUP-ELIGIBLE-QUOTE");
        Material material = material("MAT-ELIGIBLE-QUOTE", group);
        RFQ rfq = rfq("RFQ-ELIGIBLE-QUOTE");
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        invite(rfq, supplier);
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqItems(token, rfq.getId()))
            .extracting(value -> value.id())
            .containsExactly(item.getId());
        assertThat(portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)).status())
            .isEqualTo("SUBMITTED");
    }

    @Test
    void invitedSupplierWithServiceAccreditationCanSeeAndQuoteServiceRfq() {
        Supplier supplier = supplier("SUP-SERVICE-QUOTE");
        RFQ rfq = rfq("RFQ-SERVICE-QUOTE");
        RFQItem item = typedRfqItem(rfq, ProcurementLineType.SERVICE);
        accreditTyped(supplier, "SERVICES", "Industrial maintenance services");
        invite(rfq, supplier);
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.listRfqItems(token, rfq.getId()))
            .extracting(value -> value.id())
            .containsExactly(item.getId());
        assertThat(portalExternalService.createQuotation(token, rfq.getId(), typedQuotation(List.of(item))).status())
            .isEqualTo("SUBMITTED");
    }

    @Test
    void invitedSupplierWithWorksAccreditationCanSeeAndQuoteWorksRfq() {
        Supplier supplier = supplier("SUP-WORKS-QUOTE");
        RFQ rfq = rfq("RFQ-WORKS-QUOTE");
        RFQItem item = typedRfqItem(rfq, ProcurementLineType.WORK);
        accreditTyped(supplier, "WORKS", "Civil construction works");
        invite(rfq, supplier);
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.createQuotation(token, rfq.getId(), typedQuotation(List.of(item))).status())
            .isEqualTo("SUBMITTED");
    }

    @Test
    void mixedRfqPortalAccessDoesNotRequireEveryAccreditationScope() {
        Supplier supplier = supplier("SUP-MIXED-QUOTE");
        MaterialGroup group = materialGroup("GROUP-MIXED-QUOTE");
        Material material = material("MAT-MIXED-QUOTE", group);
        RFQ rfq = rfq("RFQ-MIXED-QUOTE");
        RFQItem materialItem = rfqItem(rfq, material);
        RFQItem serviceItem = typedRfqItem(rfq, ProcurementLineType.SERVICE);
        RFQItem workItem = typedRfqItem(rfq, ProcurementLineType.WORK);
        accredit(supplier, List.of(group.getId()));
        accreditTyped(supplier, "SERVICES", "Industrial maintenance services");
        invite(rfq, supplier);
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.createQuotation(
            token,
            rfq.getId(),
            mixedQuotation(materialItem, material, serviceItem, workItem)
        ).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void requestForQuotationRfqIsHiddenWithoutInvitation() {
        Supplier supplier = supplier("SUP-PUBLIC-RFQ");
        MaterialGroup group = materialGroup("GROUP-PUBLIC-RFQ");
        Material material = material("MAT-PUBLIC-RFQ", group);
        RFQ rfq = rfq("RFQ-PUBLIC");
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .doesNotContain(rfq.getId());
        assertThatThrownBy(() -> portalExternalService.listRfqItems(token, rfq.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invitation");
        assertThatThrownBy(() -> portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invitation");
    }

    @Test
    void openCompetitionRfqIsVisibleAndQuotableWithoutInvitation() {
        Supplier supplier = supplier("SUP-OPEN-COMPETITION");
        MaterialGroup group = materialGroup("GROUP-OPEN-COMPETITION");
        Material material = material("MAT-OPEN-COMPETITION", group);
        RFQ rfq = rfq("RFQ-OPEN-COMPETITION");
        rfq.setTenderProcedureType("OPEN_COMPETITION");
        rfqRepository.save(rfq);
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.listRfqItems(token, rfq.getId()))
            .extracting(value -> value.id())
            .containsExactly(item.getId());
        assertThat(portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)).status())
            .isEqualTo("SUBMITTED");
        assertThat(invitationRepository.existsActiveInvitation(
            rfq.getTenantId(), rfq.getCompanyId(), rfq.getId(), supplier.getId())).isFalse();
    }

    @Test
    void openTenderIsVisibleAndQuotableWithoutInvitation() {
        Supplier supplier = supplier("SUP-OPEN-TENDER");
        MaterialGroup group = materialGroup("GROUP-OPEN-TENDER");
        Material material = material("MAT-OPEN-TENDER", group);
        RFQ rfq = rfq("RFQ-OPEN-TENDER");
        rfq.setTenderProcedureType("OPEN_TENDER");
        rfqRepository.save(rfq);
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.listRfqItems(token, rfq.getId()))
            .extracting(value -> value.id())
            .containsExactly(item.getId());
        assertThat(portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)).status())
            .isEqualTo("SUBMITTED");
        assertThat(invitationRepository.existsActiveInvitation(
            rfq.getTenantId(), rfq.getCompanyId(), rfq.getId(), supplier.getId())).isFalse();
    }

    @Test
    void closedTenderWithoutInvitationRemainsHiddenAndBlocked() {
        Supplier supplier = supplier("SUP-CLOSED-TENDER");
        MaterialGroup group = materialGroup("GROUP-CLOSED-TENDER");
        Material material = material("MAT-CLOSED-TENDER", group);
        RFQ rfq = rfq("RFQ-CLOSED-TENDER");
        rfq.setTenderProcedureType("CLOSED_TENDER");
        rfqRepository.save(rfq);
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .doesNotContain(rfq.getId());
        assertThatThrownBy(() -> portalExternalService.listRfqItems(token, rfq.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invitation");
        assertThatThrownBy(() -> portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invitation");
    }

    @Test
    void supplierCannotOpenOrQuoteClosedCompetitionWithoutInvitation() {
        Supplier supplier = supplier("SUP-QUOTE-NOT-INVITED");
        MaterialGroup group = materialGroup("GROUP-QUOTE-NOT-INVITED");
        Material material = material("MAT-QUOTE-NOT-INVITED", group);
        RFQ draftRfq = rfq("RFQ-QUOTE-NOT-INVITED");
        draftRfq.setTenderProcedureType("CLOSED_COMPETITION");
        RFQ rfq = rfqRepository.save(draftRfq);
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .doesNotContain(rfq.getId());
        assertThatThrownBy(() -> portalExternalService.listRfqItems(token, rfq.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invitation");
        assertThatThrownBy(() -> portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invitation");
    }

    @Test
    void invitedButBlockedSupplierCannotQuote() {
        Supplier supplier = supplier("SUP-BLOCKED-QUOTE");
        MaterialGroup group = materialGroup("GROUP-BLOCKED-QUOTE");
        Material material = material("MAT-BLOCKED-QUOTE", group);
        RFQ rfq = rfq("RFQ-BLOCKED-QUOTE");
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        invite(rfq, supplier);
        String token = activePortalToken(supplier, true);
        supplier.setStatus(SupplierStatus.BLOCKED.name());
        supplierRepository.save(supplier);

        assertThat(portalExternalService.listRfqs(token)).isEmpty();
        assertThatThrownBy(() -> portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("blocked");
    }

    @Test
    void invitedSupplierWithLegacyLimitedStatusCanStillQuoteWhenContextEligible() {
        Supplier supplier = supplier("SUP-NO-LONGER-ACTIVE");
        MaterialGroup group = materialGroup("GROUP-NO-LONGER-ACTIVE");
        Material material = material("MAT-NO-LONGER-ACTIVE", group);
        RFQ rfq = rfq("RFQ-NO-LONGER-ACTIVE");
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        invite(rfq, supplier);
        String token = activePortalToken(supplier, true);
        supplier.setStatus(SupplierStatus.LIMITED.name());
        supplierRepository.save(supplier);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(rfqSummary -> rfqSummary.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)).status())
            .isEqualTo("SUBMITTED");
    }

    @Test
    void invitationAllowsPortalAccessWhenEveryLineMaterialGroupIsNotAccredited() {
        Supplier supplier = supplier("SUP-PARTIAL-SCOPE");
        MaterialGroup covered = materialGroup("GROUP-COVERED");
        MaterialGroup uncovered = materialGroup("GROUP-UNCOVERED");
        RFQ rfq = rfq("RFQ-PARTIAL-SCOPE");
        Material coveredMaterial = material("MAT-COVERED", covered);
        RFQItem coveredItem = rfqItem(rfq, coveredMaterial);
        Material uncoveredMaterial = material("MAT-UNCOVERED", uncovered);
        RFQItem uncoveredItem = rfqItem(rfq, uncoveredMaterial);
        accredit(supplier, List.of(covered.getId()));
        invite(rfq, supplier);
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.listRfqItems(token, rfq.getId())).hasSize(2);
        assertThat(portalExternalService.createQuotation(
            token,
            rfq.getId(),
            quotation(List.of(coveredItem, uncoveredItem), List.of(coveredMaterial, uncoveredMaterial))
        ).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void malformedOrInactiveAccreditationDecisionDoesNotBlockPortal() {
        Supplier supplier = supplier("SUP-INVALID-DECISION");
        MaterialGroup group = materialGroup("GROUP-INVALID-DECISION");
        Material material = material("MAT-INVALID-DECISION", group);
        RFQ rfq = rfq("RFQ-INVALID-DECISION");
        RFQItem item = rfqItem(rfq, material);
        SupplierAccreditationDecision decision = accredit(supplier, List.of(group.getId()));
        invite(rfq, supplier);
        String token = activePortalToken(supplier, true);

        decision.setAllowedMaterialGroupIds(List.of());
        decision = accreditationDecisionRepository.saveAndFlush(decision);
        assertPortalAccreditationAllowed(token, rfq, item, material);

        decision.setAllowedMaterialGroupIds(List.of(group.getId()));
        decision.setValidFrom(LocalDate.now().plusDays(1));
        decision.setValidTo(LocalDate.now().plusMonths(6));
        decision = accreditationDecisionRepository.saveAndFlush(decision);
        assertPortalAccreditationAllowed(token, rfq, item, material);

        decision.setValidFrom(LocalDate.now().minusMonths(6));
        decision.setValidTo(LocalDate.now().minusDays(1));
        decision = accreditationDecisionRepository.saveAndFlush(decision);
        assertPortalAccreditationAllowed(token, rfq, item, material);

        decision.setDecision(SupplierAccreditationStatus.REJECTED.name());
        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().plusMonths(6));
        accreditationDecisionRepository.saveAndFlush(decision);
        assertPortalAccreditationAllowed(token, rfq, item, material);
    }

    @Test
    void invitationListRemainsVisibleBeforeTermsButActionsAreBlocked() {
        Supplier supplier = supplier("SUP-NO-TERMS");
        MaterialGroup group = materialGroup("GROUP-NO-TERMS");
        Material material = material("MAT-NO-TERMS", group);
        RFQ rfq = rfq("RFQ-NO-TERMS");
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        invite(rfq, supplier);
        String token = activePortalToken(supplier, false);

        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .containsExactly(rfq.getId());
        assertThatThrownBy(() -> portalExternalService.listRfqItems(token, rfq.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("terms");
        assertThatThrownBy(() -> portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("terms");
    }

    @Test
    void withdrawnInvitationFailsClosedForListDetailAndQuotation() {
        Supplier supplier = supplier("SUP-WITHDRAWN");
        MaterialGroup group = materialGroup("GROUP-WITHDRAWN");
        Material material = material("MAT-WITHDRAWN", group);
        RFQ rfq = rfq("RFQ-WITHDRAWN");
        RFQItem item = rfqItem(rfq, material);
        accredit(supplier, List.of(group.getId()));
        RfqSupplierInvitation invitation = invite(rfq, supplier);
        invitation.setStatus(WITHDRAWN);
        invitationRepository.save(invitation);
        String token = activePortalToken(supplier, true);

        assertThat(portalExternalService.listRfqs(token)).isEmpty();
        assertThatThrownBy(() -> portalExternalService.listRfqItems(token, rfq.getId()))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> portalExternalService.createQuotation(token, rfq.getId(), quotation(item, material)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void createAndUpdateSynchronizeDeduplicatedInvitationsWithoutDeletingHistory() {
        Supplier supplierA = supplier("SUP-SYNC-A");
        Supplier supplierB = supplier("SUP-SYNC-B");
        Supplier supplierC = supplier("SUP-SYNC-C");
        RFQCreateRequest create = rfqRequest("RFQ-SYNC", List.of(supplierA.getId(), supplierA.getId(), supplierB.getId()));

        RFQResponse created = rfqService.create(create);
        assertThat(created.getInvitedSupplierIds()).containsExactlyInAnyOrder(supplierA.getId(), supplierB.getId());

        RFQUpdateRequest preserve = updateRequest(created, null);
        RFQResponse preserved = rfqService.update(created.getId(), preserve);
        assertThat(preserved.getInvitedSupplierIds()).containsExactlyInAnyOrder(supplierA.getId(), supplierB.getId());

        RFQUpdateRequest replace = updateRequest(preserved, List.of(supplierB.getId(), supplierC.getId(), supplierC.getId()));
        RFQResponse replaced = rfqService.update(created.getId(), replace);
        assertThat(replaced.getInvitedSupplierIds()).containsExactlyInAnyOrder(supplierB.getId(), supplierC.getId());

        List<RfqSupplierInvitation> history = invitationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                created.getTenantId(), created.getCompanyId(), created.getId());
        assertThat(history).filteredOn(item -> item.getSupplierId().equals(supplierA.getId()))
            .singleElement()
            .satisfies(item -> {
                assertThat(item.getStatus()).isEqualTo(WITHDRAWN);
                assertThat(item.isDeleted()).isFalse();
            });
        assertThat(history).filteredOn(item -> INVITED.equals(item.getStatus()))
            .extracting(RfqSupplierInvitation::getSupplierId)
            .containsExactlyInAnyOrder(supplierB.getId(), supplierC.getId());

        RFQResponse cleared = rfqService.update(replaced.getId(), updateRequest(replaced, List.of()));
        assertThat(cleared.getInvitedSupplierIds()).isEmpty();
        assertThat(invitationRepository.existsActiveInvitation(
            cleared.getTenantId(), cleared.getCompanyId(), cleared.getId(), supplierB.getId())).isFalse();
        assertThat(invitationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                cleared.getTenantId(), cleared.getCompanyId(), cleared.getId()))
            .allSatisfy(item -> {
                assertThat(item.getStatus()).isEqualTo(WITHDRAWN);
                assertThat(item.isDeleted()).isFalse();
            });
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void invitationSynchronizationRejectsInactiveAndCrossCompanySuppliersTransactionally() {
        Supplier inactive = supplier("SUP-SYNC-INACTIVE");
        inactive.setStatus(SupplierStatus.BLOCKED.name());
        supplierRepository.save(inactive);
        Supplier otherCompany = supplier("SUP-SYNC-OTHER-COMPANY");
        otherCompany.setCompanyId(UUID.randomUUID());
        supplierRepository.saveAndFlush(otherCompany);
        long before = rfqRepository.count();

        assertThatThrownBy(() -> rfqService.create(rfqRequest("RFQ-INACTIVE", List.of(inactive.getId()))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active supplier");
        assertThatThrownBy(() -> rfqService.create(rfqRequest("RFQ-OTHER-COMPANY", List.of(otherCompany.getId()))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active supplier");

        assertThat(rfqRepository.count()).isEqualTo(before);
    }



    private RFQCreateRequest rfqRequest(String prefix, List<UUID> supplierIds) {
        RFQCreateRequest request = new RFQCreateRequest();
        request.setCode(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        request.setName(prefix);
        request.setDueDate(LocalDate.now().plusDays(10));
        request.setTenderProcedureType("REQUEST_FOR_QUOTATION");
        request.setSelectionRule("LOWEST_PRICE");
        request.setMinQuotationsRequired(1);
        request.setInvitedSupplierIds(supplierIds);
        return request;
    }

    private RFQUpdateRequest updateRequest(RFQResponse current, List<UUID> supplierIds) {
        RFQUpdateRequest request = new RFQUpdateRequest();
        request.setName(current.getName());
        request.setDescription(current.getDescription());
        request.setPurchaseRequestId(current.getPurchaseRequestId());
        request.setDueDate(current.getDueDate());
        request.setTenderProcedureType(current.getTenderProcedureType());
        request.setSelectionRule(current.getSelectionRule());
        request.setMinQuotationsRequired(current.getMinQuotationsRequired());
        request.setInvitedSupplierIds(supplierIds);
        return request;
    }

    private String activePortalToken(Supplier supplier, boolean acceptTerms) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SupplierPortalAccountCreateRequest request = new SupplierPortalAccountCreateRequest();
        request.setSupplierId(supplier.getId());
        request.setName("Portal contact " + suffix);
        request.setLoginEmail("rfq-" + suffix + "@example.uz");
        request.setContactPerson("RFQ contact");
        SupplierPortalAccountResponse account = portalAccountService.create(request);
        String token = UUID.randomUUID().toString();
        var entity = portalAccountRepository.findByIdAndIsDeletedFalse(account.getId()).orElseThrow();
        entity.setStatus("ACTIVE");
        entity.setActivatedAt(LocalDateTime.now());
        entity.setLastLoginAt(LocalDateTime.now());
        entity.setPortalTokenHash(sha256(token));
        entity.setPortalTokenExpiresAt(LocalDateTime.now().plusHours(24));
        entity.setTermsAcceptedAt(acceptTerms ? LocalDateTime.now() : null);
        portalAccountRepository.saveAndFlush(entity);
        return token;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RfqSupplierInvitation invite(RFQ rfq, Supplier supplier) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("RFQ-INV-" + UUID.randomUUID().toString().substring(0, 8));
        invitation.setName("Invitation for " + rfq.getCode());
        invitation.setStatus(INVITED);
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        return invitationRepository.save(invitation);
    }

    private SupplierAccreditationDecision accredit(Supplier supplier, List<UUID> materialGroupIds) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setCode("ACC-" + UUID.randomUUID().toString().substring(0, 8));
        application.setName("Goods accreditation");
        application.setStatus(SupplierAccreditationStatus.APPROVED.name());
        application.setSupplierId(supplier.getId());
        application.setAccreditationType("GOODS");
        application.setRequestedScope("RFQ material groups");
        application.setRequestedMaterialGroupIds(materialGroupIds);
        application.setValidFrom(LocalDate.now().minusDays(1));
        application.setValidTo(LocalDate.now().plusMonths(6));
        application = accreditationRepository.save(application);

        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setCode("ACC-DEC-" + UUID.randomUUID().toString().substring(0, 8));
        decision.setName("Goods accreditation decision");
        decision.setStatus("RECORDED");
        decision.setApplicationId(application.getId());
        decision.setDecision(SupplierAccreditationStatus.APPROVED.name());
        decision.setApprovedScope("RFQ material groups");
        decision.setAllowedMaterialGroupIds(materialGroupIds);
        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().plusMonths(6));
        decision = accreditationDecisionRepository.save(decision);

        SupplierAccreditationDocument evidence = new SupplierAccreditationDocument();
        evidence.setCode("ACC-DOC-" + UUID.randomUUID().toString().substring(0, 8));
        evidence.setName("RFQ accreditation evidence");
        evidence.setStatus("VERIFIED");
        evidence.setApplicationId(application.getId());
        evidence.setDocumentType("REGISTRATION_CERTIFICATE");
        evidence.setVerifiedAt(LocalDateTime.now());
        evidence.setVerifiedBy("supplier-manager");
        evidence.setValidFrom(LocalDate.now().minusDays(1));
        evidence.setValidTo(LocalDate.now().plusMonths(6));
        evidence = accreditationDocumentRepository.save(evidence);
        Attachment attachment = createAccreditationAttachment(evidence.getId());
        evidence.setAttachmentId(attachment.getId());
        accreditationDocumentRepository.save(evidence);
        return decision;
    }

    private SupplierAccreditationDecision accreditTyped(Supplier supplier, String type, String approvedScope) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setCode("ACC-" + UUID.randomUUID().toString().substring(0, 8));
        application.setName(type + " accreditation");
        application.setStatus(SupplierAccreditationStatus.APPROVED.name());
        application.setSupplierId(supplier.getId());
        application.setAccreditationType(type);
        application.setRequestedScope(approvedScope);
        application.setRequestedMaterialGroupIds(List.of());
        application.setValidFrom(LocalDate.now().minusDays(1));
        application.setValidTo(LocalDate.now().plusMonths(6));
        application = accreditationRepository.save(application);

        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setCode("ACC-DEC-" + UUID.randomUUID().toString().substring(0, 8));
        decision.setName(type + " accreditation decision");
        decision.setStatus("RECORDED");
        decision.setApplicationId(application.getId());
        decision.setDecision(SupplierAccreditationStatus.APPROVED.name());
        decision.setApprovedScope(approvedScope);
        decision.setAllowedMaterialGroupIds(List.of());
        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().plusMonths(6));
        decision = accreditationDecisionRepository.save(decision);

        SupplierAccreditationDocument evidence = new SupplierAccreditationDocument();
        evidence.setCode("ACC-DOC-" + UUID.randomUUID().toString().substring(0, 8));
        evidence.setName(type + " accreditation evidence");
        evidence.setStatus("VERIFIED");
        evidence.setApplicationId(application.getId());
        evidence.setDocumentType("REGISTRATION_CERTIFICATE");
        evidence.setVerifiedAt(LocalDateTime.now());
        evidence.setVerifiedBy("supplier-manager");
        evidence.setValidFrom(LocalDate.now().minusDays(1));
        evidence.setValidTo(LocalDate.now().plusMonths(6));
        evidence = accreditationDocumentRepository.save(evidence);
        Attachment attachment = createAccreditationAttachment(evidence.getId());
        evidence.setAttachmentId(attachment.getId());
        accreditationDocumentRepository.save(evidence);
        return decision;
    }

    private Attachment createAccreditationAttachment(UUID evidenceId) {
        Attachment attachment = new Attachment();
        attachment.setCode("ATT-" + UUID.randomUUID().toString().substring(0, 8));
        attachment.setName("rfq-accreditation.pdf");
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("SUPPLIER_ACCREDITATION_DOCUMENT");
        attachment.setEntityId(evidenceId);
        attachment.setAttachmentType("REGISTRATION_CERTIFICATE");
        attachment.setOriginalFileName("rfq-accreditation.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(128L);
        attachment.setChecksumSha256("b".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("tests/rfq-accreditation/" + UUID.randomUUID());
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setDownloadable(true);
        return attachmentRepository.save(attachment);
    }

    private void assertPortalAccreditationAllowed(
        String token,
        RFQ rfq,
        RFQItem item,
        Material material
    ) {
        assertThat(portalExternalService.listRfqs(token))
            .extracting(value -> value.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.listRfqItems(token, rfq.getId())).hasSize(1);
    }

    private Supplier supplier(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Supplier supplier = new Supplier();
        supplier.setCode(prefix + "-" + suffix);
        supplier.setName(prefix + "-" + suffix);
        supplier.setLegalName(prefix + " LLC");
        supplier.setStatus(SupplierStatus.ACTIVE.name());
        supplier.setCategory("A");
        supplier.setEmail(prefix.toLowerCase(Locale.ROOT) + "-" + suffix + "@example.uz");
        return supplierRepository.save(supplier);
    }

    private MaterialGroup materialGroup(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MaterialGroup group = new MaterialGroup();
        group.setCode(prefix + "-" + suffix);
        group.setName(prefix + "-" + suffix);
        group.setStatus("ACTIVE");
        return materialGroupRepository.save(group);
    }

    private Material material(String prefix, MaterialGroup group) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Material material = new Material();
        material.setCode(prefix + "-" + suffix);
        material.setName(prefix + "-" + suffix);
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(group.getId());
        return materialRepository.save(material);
    }

    private RFQ rfqWithLine(String prefix, Material material) {
        RFQ rfq = rfq(prefix);
        rfqItem(rfq, material);
        return rfq;
    }

    private RFQ rfq(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RFQ rfq = new RFQ();
        rfq.setCode(prefix + "-" + suffix);
        rfq.setName(prefix + "-" + suffix);
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(10));
        rfq.setPurpose("PROCUREMENT");
        rfq.setTenderProcedureType("REQUEST_FOR_QUOTATION");
        rfq.setSelectionRule("LOWEST_PRICE");
        rfq.setMinQuotationsRequired(1);
        return rfqRepository.save(rfq);
    }

    private RFQItem rfqItem(RFQ rfq, Material material) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-" + UUID.randomUUID().toString().substring(0, 8));
        item.setName("RFQ item");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setMaterialId(material.getId());
        item.setUnit("EA");
        item.setQuantity(BigDecimal.TEN);
        return rfqItemRepository.save(item);
    }

    private RFQItem typedRfqItem(RFQ rfq, ProcurementLineType lineType) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-" + UUID.randomUUID().toString().substring(0, 8));
        item.setName(lineType + " RFQ item");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setLineType(lineType);
        item.setServiceDescription(lineType + " scope of work");
        item.setServiceCategory(lineType.name());
        item.setUnit("LOT");
        item.setQuantity(BigDecimal.ONE);
        return rfqItemRepository.save(item);
    }

    private SupplierPortalQuotationRequest quotation(RFQItem item, Material material) {
        return quotation(List.of(item), List.of(material));
    }

    private SupplierPortalQuotationRequest quotation(List<RFQItem> items, List<Material> materials) {
        List<SupplierPortalQuotationItemRequest> requestItems = java.util.stream.IntStream.range(0, items.size())
            .mapToObj(index -> new SupplierPortalQuotationItemRequest(
                items.get(index).getId(),
                materials.get(index).getId(),
                BigDecimal.ONE,
                BigDecimal.TEN,
                3
            ))
            .toList();
        return new SupplierPortalQuotationRequest("Portal quotation", "UZS", requestItems);
    }

    private SupplierPortalQuotationRequest typedQuotation(List<RFQItem> items) {
        return new SupplierPortalQuotationRequest(
            "Portal quotation",
            "UZS",
            items.stream()
                .map(item -> new SupplierPortalQuotationItemRequest(
                    item.getId(), null, BigDecimal.ONE, BigDecimal.TEN, 3))
                .toList()
        );
    }

    private SupplierPortalQuotationRequest mixedQuotation(
        RFQItem materialItem,
        Material material,
        RFQItem serviceItem,
        RFQItem workItem
    ) {
        return new SupplierPortalQuotationRequest(
            "Mixed portal quotation",
            "UZS",
            List.of(
                new SupplierPortalQuotationItemRequest(
                    materialItem.getId(), material.getId(), BigDecimal.ONE, BigDecimal.TEN, 3),
                new SupplierPortalQuotationItemRequest(
                    serviceItem.getId(), null, BigDecimal.ONE, BigDecimal.TEN, 3),
                new SupplierPortalQuotationItemRequest(
                    workItem.getId(), null, BigDecimal.ONE, BigDecimal.TEN, 3)
            )
        );
    }
}
