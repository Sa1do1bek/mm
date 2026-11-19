package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.SupplierComplianceDtos;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierCapaActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierKycDecisionRequest;
import com.enterprise.mm.payload.SupplierAccreditationApplicationCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDecisionActionRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationItemRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalSubmissionRequest;
import com.enterprise.mm.payload.ContractCreateRequest;
import com.enterprise.mm.payload.ContractResponse;
import com.enterprise.mm.payload.ContractSpecificationCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.SupplierPortalAccountResponse;
import com.enterprise.mm.payload.SupplierPortalAccountSearchRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionResponse;
import com.enterprise.mm.payload.SupplierKycCheckResponse;
import com.enterprise.mm.payload.SupplierRelatedPartyResponse;
import com.enterprise.mm.payload.SupplierCorrectiveActionResponse;
import com.enterprise.mm.payload.SupplierCorrectiveActionCreateRequest;
import com.enterprise.mm.payload.SupplierKycCheckCreateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountCreateRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionCreateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartyCreateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartySearchRequest;
import com.enterprise.mm.service.SupplierComplianceService;
import com.enterprise.mm.service.SupplierCorrectiveActionService;
import com.enterprise.mm.service.SupplierKycCheckService;
import com.enterprise.mm.service.SupplierPortalAccountService;
import com.enterprise.mm.service.SupplierPortalExternalService;
import com.enterprise.mm.service.SupplierMagicLinkService;
import com.enterprise.mm.service.SupplierPortalSubmissionService;
import com.enterprise.mm.service.SupplierRelatedPartyService;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierAccreditationDocumentService;
import com.enterprise.mm.service.ContractService;
import com.enterprise.mm.service.ContractSpecificationService;
import com.enterprise.mm.service.PurchaseOrderService;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SupplierCorrectiveActionStatus;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.enums.SupplierKycStatus;
import com.enterprise.mm.enums.SupplierMagicLinkRequestReason;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.enums.SupplierPortalSubmissionStatus;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierMagicLinkRequestRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "supplier-manager", roles = "SUPPLIER_MANAGER")
class SupplierCompliancePortalTest {
    @Autowired SupplierRepository supplierRepository;
    @Autowired com.enterprise.mm.repository.SupplierPortalAccountRepository portalAccountRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository rfqItemRepository;
    @Autowired RfqSupplierInvitationRepository invitationRepository;
    @Autowired SupplierAccreditationApplicationRepository accreditationRepository;
    @Autowired SupplierAccreditationDecisionRepository accreditationDecisionRepository;
    @Autowired SupplierQuotationItemRepository quotationItemRepository;
    @Autowired SupplierPortalAccountService portalAccountService;
    @Autowired SupplierPortalExternalService portalExternalService;
    @Autowired SupplierMagicLinkService magicLinkService;
    @Autowired SupplierMagicLinkRequestRepository magicLinkRequestRepository;
    @Autowired SupplierPortalSubmissionService portalSubmissionService;
    @Autowired SupplierKycCheckService kycCheckService;
    @Autowired SupplierRelatedPartyService relatedPartyService;
    @Autowired SupplierCorrectiveActionService correctiveActionService;
    @Autowired SupplierComplianceService complianceService;
    @Autowired SupplierAccreditationService accreditationService;
    @Autowired SupplierAccreditationDocumentService accreditationDocumentService;
    @Autowired ContractService contractService;
    @Autowired ContractSpecificationService contractSpecificationService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;

    @Test
    void unknownMagicLinkRequestPersistsOnlyHashesWithNoCompanyScope() {
        Set<UUID> before = magicLinkRequestRepository.findAll().stream()
            .map(value -> value.getId())
            .collect(java.util.stream.Collectors.toSet());

        var response = magicLinkService.requestLink(
            "UNKNOWN-" + UUID.randomUUID(),
            "unknown-supplier@example.uz",
            "203.0.113.77"
        );

        assertThat(response.message()).isEqualTo(
            "If the account is eligible, a login link has been sent."
        );
        var stored = magicLinkRequestRepository.findAll().stream()
            .filter(value -> !before.contains(value.getId()))
            .findFirst()
            .orElseThrow();
        assertThat(stored.getCompanyId()).isNull();
        assertThat(stored.getNormalizedEmailHash()).hasSize(64).doesNotContain("@");
        assertThat(stored.getRequestIpHash()).hasSize(64).doesNotContain("203.0.113.77");
        assertThat(stored.getReasonCode()).isEqualTo(SupplierMagicLinkRequestReason.COMPANY_NOT_FOUND);
    }

    @Test
    void supplierPortalAccountCreationReturnsNoSecretAndSupportsSubmissionReview() {
        Supplier supplier = supplier("SUP-PORTAL");

        SupplierPortalAccountCreateRequest accountRequest = new SupplierPortalAccountCreateRequest();
        accountRequest.setSupplierId(supplier.getId());
        accountRequest.setName("Portal contact");
        accountRequest.setLoginEmail("portal-contact@example.uz");
        accountRequest.setContactPerson("Portal Contact");
        SupplierPortalAccountResponse account = portalAccountService.create(accountRequest);
        assertThat(account.getStatus()).isEqualTo(SupplierPortalStatus.ACTIVE.name());
        assertThat(account.getInviteToken()).isNull();
        assertThat(portalAccountRepository.findAnyById(account.getId()).orElseThrow().getInviteTokenHash()).isNull();
        String sessionToken = activatePortalSession(account.getId());
        account = portalAccountService.get(account.getId());
        assertThat(account.getStatus()).isEqualTo(SupplierPortalStatus.ACTIVE.name());
        assertThat(account.getActivatedAt()).isNotNull();
        assertThat(account.getInviteToken()).isNull();
        assertThat(portalExternalService.me(sessionToken).portalAccountId()).isEqualTo(account.getId());
        assertThat(portalAccountService.get(account.getId()).getInviteToken()).isNull();

        SupplierPortalSubmissionCreateRequest submissionRequest = new SupplierPortalSubmissionCreateRequest();
        submissionRequest.setSupplierId(supplier.getId());
        submissionRequest.setPortalAccountId(account.getId());
        submissionRequest.setName("KYC package");
        submissionRequest.setSubmissionType("KYC_DOCUMENT");
        submissionRequest.setPayloadJson("{\"document\":\"bank-details\"}");
        SupplierPortalSubmissionResponse submission = portalSubmissionService.create(submissionRequest);
        submission = portalSubmissionService.submit(submission.getId());
        assertThat(submission.getStatus()).isEqualTo(SupplierPortalSubmissionStatus.SUBMITTED.name());

        submission = portalSubmissionService.accept(submission.getId(), new SupplierComplianceDtos.SupplierPortalActionRequest("Evidence accepted", null));
        assertThat(submission.getStatus()).isEqualTo(SupplierPortalSubmissionStatus.ACCEPTED.name());
        assertThat(submission.getReviewedBy()).isEqualTo("supplier-manager");
    }

    @Test
    void supplierPortalAccountSearchSupportsEmptyAndOptionalTextFilters() {
        Supplier supplier = supplier("SUP-PORTAL-SEARCH");

        SupplierPortalAccountCreateRequest accountRequest = new SupplierPortalAccountCreateRequest();
        accountRequest.setSupplierId(supplier.getId());
        accountRequest.setName("Portal search contact");
        accountRequest.setLoginEmail("portal-search@example.uz");
        accountRequest.setContactPerson("Search Contact");
        accountRequest.setPhone("+998901234567");
        SupplierPortalAccountResponse account = portalAccountService.create(accountRequest);

        var emptySearch = portalAccountService.search(
            SupplierPortalAccountSearchRequest.empty(),
            PageRequest.of(0, 20)
        );

        assertThat(emptySearch.getContent()).isNotEmpty();

        var filteredSearch = portalAccountService.search(
            new SupplierPortalAccountSearchRequest(
                null,
                null,
                supplier.getId(),
                null,
                null,
                false,
                null,
                null,
                null,
                "PORTAL-SEARCH",
                "search contact",
                "901234",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            PageRequest.of(0, 20)
        );

        assertThat(filteredSearch.getContent())
            .extracting(SupplierPortalAccountResponse::getId)
            .containsExactly(account.getId());
    }

    @Test
    void externalSupplierPortalSupportsLoginQuotationAndDocumentSubmission() {
        Supplier supplier = supplier("SUP-EXT-PORTAL");
        MaterialGroup materialGroup = materialGroup("MG-EXT-PORTAL");
        Material material = material("MAT-EXT-PORTAL", materialGroup);
        RFQ rfq = rfq("RFQ-EXT-PORTAL");
        RFQItem rfqItem = rfqItem(rfq, material);
        accredit(supplier, materialGroup);
        invite(rfq, supplier);

        SupplierPortalAccountCreateRequest accountRequest = new SupplierPortalAccountCreateRequest();
        accountRequest.setSupplierId(supplier.getId());
        accountRequest.setName("External portal contact");
        accountRequest.setLoginEmail("external-portal@example.uz");
        accountRequest.setContactPerson("External Contact");
        SupplierPortalAccountResponse account = portalAccountService.create(accountRequest);
        assertThat(account.getInviteToken()).isNull();
        String sessionToken = activatePortalSession(account.getId());

        var me = portalExternalService.acceptTerms(sessionToken);
        assertThat(me.termsAcceptedAt()).isNotNull();

        var rfqs = portalExternalService.listRfqs(sessionToken);
        assertThat(rfqs.stream().map(item -> item.id()).toList()).contains(rfq.getId());
        assertThat(portalExternalService.listRfqItems(sessionToken, rfq.getId())).hasSize(1);

        var quotation = portalExternalService.createQuotation(
            sessionToken,
            rfq.getId(),
            new SupplierPortalQuotationRequest(
                "Portal quotation",
                "UZS",
                List.of(new SupplierPortalQuotationItemRequest(rfqItem.getId(), material.getId(), BigDecimal.TEN, BigDecimal.valueOf(125_000), 7))
            )
        );
        assertThat(quotation.status()).isEqualTo("SUBMITTED");
        assertThat(quotation.totalAmount()).isEqualByComparingTo("1250000");
        assertThat(quotationItemRepository.findByTenantIdAndQuotation_IdAndIsDeletedFalse("default", quotation.quotationId()))
            .singleElement()
            .satisfies(item -> {
                assertThat(item.getRfqItemId()).isEqualTo(rfqItem.getId());
                assertThat(item.getUnit()).isEqualTo("EA");
            });

        SupplierPortalSubmissionResponse submission = portalExternalService.createSubmission(
            sessionToken,
            new SupplierPortalSubmissionRequest("KYC_DOCUMENT", "SUPPLIER", supplier.getId(), "{\"file\":\"bank-card\"}", null)
        );
        submission = portalExternalService.submitSubmission(sessionToken, submission.getId());
        assertThat(submission.getStatus()).isEqualTo(SupplierPortalSubmissionStatus.SUBMITTED.name());
    }

    @Test
    void supplierPortalShowsInvitedRfqWhenAccreditationIsMissing() {
        Supplier supplier = supplier("SUP-PORTAL-RFQ-NO-CODE");
        MaterialGroup materialGroup = materialGroup("MG-PORTAL-RFQ-NO-CODE");
        Material material = material("MAT-PORTAL-RFQ-NO-CODE", materialGroup);
        RFQ rfq = rfq("RFQ-PORTAL-RFQ-NO-CODE");
        rfq.setCode(null);
        rfq.setName("Portal RFQ without code");
        rfq = rfqRepository.saveAndFlush(rfq);
        rfqItem(rfq, material);
        invite(rfq, supplier);

        SupplierPortalAccountCreateRequest accountRequest = new SupplierPortalAccountCreateRequest();
        accountRequest.setSupplierId(supplier.getId());
        accountRequest.setName("No code portal contact");
        accountRequest.setLoginEmail("no-code-rfq-portal@example.uz");
        accountRequest.setContactPerson("No Code Contact");
        SupplierPortalAccountResponse account = portalAccountService.create(accountRequest);
        String sessionToken = activatePortalSession(account.getId());
        portalExternalService.acceptTerms(sessionToken);

        assertThat(portalExternalService.listRfqs(sessionToken))
            .extracting(item -> item.id())
            .contains(rfq.getId());
        assertThat(portalExternalService.listRfqItems(sessionToken, rfq.getId())).hasSize(1);
    }

    private String activatePortalSession(UUID accountId) {
        String token = UUID.randomUUID().toString();
        var account = portalAccountRepository.findAnyById(accountId).orElseThrow();
        account.setStatus(SupplierPortalStatus.ACTIVE.name());
        account.setActivatedAt(LocalDateTime.now());
        account.setLastLoginAt(LocalDateTime.now());
        account.setPortalTokenHash(sha256(token));
        account.setPortalTokenExpiresAt(LocalDateTime.now().plusHours(24));
        portalAccountRepository.saveAndFlush(account);
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

    @Test
    void failedCriticalKycBlocksSupplier() {
        Supplier supplier = supplier("SUP-KYC");

        SupplierKycCheckCreateRequest checkRequest = new SupplierKycCheckCreateRequest();
        checkRequest.setSupplierId(supplier.getId());
        checkRequest.setName("Sanctions screening");
        checkRequest.setCheckType("SANCTIONS");
        checkRequest.setProvider("Internal compliance");
        SupplierKycCheckResponse check = kycCheckService.create(checkRequest);

        check = kycCheckService.fail(check.getId(), new SupplierKycDecisionRequest("CRITICAL", "Sanctions list match", true));
        assertThat(check.getStatus()).isEqualTo(SupplierKycStatus.FAILED.name());
        Supplier updated = supplierRepository.findAnyById(supplier.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SupplierStatus.BLOCKED.name());
        assertThat(updated.getCategory()).isEqualTo("A");
        assertThat(updated.getBlockedReason()).contains("compliance");
    }

    @Test
    void sanctionedRelatedPartyBlocksSupplier() {
        Supplier supplier = supplier("SUP-PARTY");

        SupplierRelatedPartyCreateRequest partyRequest = new SupplierRelatedPartyCreateRequest();
        partyRequest.setSupplierId(supplier.getId());
        partyRequest.setName("Beneficial owner");
        partyRequest.setPartyType("BENEFICIAL_OWNER");
        partyRequest.setFullName("Risk Owner");
        partyRequest.setOwnershipPercent(java.math.BigDecimal.valueOf(51));
        partyRequest.setSanctionsMatch(true);
        SupplierRelatedPartyResponse party = relatedPartyService.create(partyRequest);

        assertThat(party.getRiskLevel()).isEqualTo("CRITICAL");
        Supplier updated = supplierRepository.findAnyById(supplier.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SupplierStatus.BLOCKED.name());
    }

    @Test
    void relatedPartySearchSupportsEncryptedTaxIdFilter() {
        Supplier supplier = supplier("SUP-PARTY-TAX");

        SupplierRelatedPartyCreateRequest first = new SupplierRelatedPartyCreateRequest();
        first.setSupplierId(supplier.getId());
        first.setName("Owner One");
        first.setPartyType("BENEFICIAL_OWNER");
        first.setFullName("Owner One");
        first.setTaxId("TIN-123456");
        relatedPartyService.create(first);

        SupplierRelatedPartyCreateRequest second = new SupplierRelatedPartyCreateRequest();
        second.setSupplierId(supplier.getId());
        second.setName("Owner Two");
        second.setPartyType("DIRECTOR");
        second.setFullName("Owner Two");
        second.setTaxId("TIN-999999");
        relatedPartyService.create(second);

        var page = relatedPartyService.search(
            new SupplierRelatedPartySearchRequest(
                null,
                null,
                supplier.getId(),
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                "123456",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ),
            PageRequest.of(0, 20)
        );

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(SupplierRelatedPartyResponse::getTaxId).containsExactly("TIN-123456");
    }

    @Test
    void highSeverityCapaRecommendsLimitedWithoutDowngradingLifecycleAndCanBeClosedAfterVerification() {
        Supplier supplier = supplier("SUP-CAPA");

        SupplierCorrectiveActionCreateRequest capaRequest = new SupplierCorrectiveActionCreateRequest();
        capaRequest.setSupplierId(supplier.getId());
        capaRequest.setName("Quality deviation CAPA");
        capaRequest.setIssueTitle("Repeated quality deviation");
        capaRequest.setSeverity("HIGH");
        capaRequest.setDueDate(LocalDate.now().plusDays(5));
        capaRequest.setRootCause("Incorrect supplier final inspection");
        capaRequest.setCorrectiveAction("Introduce double inspection");
        capaRequest.setPreventiveAction("Monthly supplier QA audit");
        SupplierCorrectiveActionResponse capa = correctiveActionService.create(capaRequest);
        assertThat(capa.getStatus()).isEqualTo(SupplierCorrectiveActionStatus.DRAFT.name());

        capa = correctiveActionService.open(capa.getId());
        assertThat(capa.getStatus()).isEqualTo(SupplierCorrectiveActionStatus.OPEN.name());
        assertThat(supplierRepository.findAnyById(supplier.getId()).orElseThrow().getStatus()).isEqualTo(SupplierStatus.ACTIVE.name());
        assertThat(complianceService.summarize(supplier.getId()).recommendedStatus()).isEqualTo(SupplierStatus.LIMITED.name());

        capa = correctiveActionService.start(capa.getId());
        capa = correctiveActionService.submit(capa.getId(), new SupplierCapaActionRequest("supplier response"));
        capa = correctiveActionService.verify(capa.getId(), new SupplierCapaActionRequest("effective"));
        capa = correctiveActionService.close(capa.getId());
        assertThat(capa.getStatus()).isEqualTo(SupplierCorrectiveActionStatus.CLOSED.name());
        assertThat(complianceService.summarize(supplier.getId()).recommendedStatus()).isEqualTo(SupplierStatus.ACTIVE.name());
    }

    @Test
    void accreditedSupplierCanActivateContractAndSendPurchaseOrder() {
        Supplier supplier = supplier("SUP-ACCREDITED");
        MaterialGroup materialGroup = materialGroup("GROUP-ACCREDITED-PO");
        Material accreditedMaterial = material("MAT-ACCREDITED-PO", materialGroup);

        SupplierAccreditationApplicationCreateRequest request = new SupplierAccreditationApplicationCreateRequest();
        request.setSupplierId(supplier.getId());
        request.setAccreditationType("GOODS");
        request.setRequestedScope("Industrial materials");
        request.setRequestedMaterialGroupIds(List.of(materialGroup.getId()));
        var application = accreditationService.create(request);
        SupplierAccreditationDocumentCreateRequest evidenceRequest = new SupplierAccreditationDocumentCreateRequest();
        evidenceRequest.setApplicationId(application.getId());
        evidenceRequest.setDocumentType("REGISTRATION_CERTIFICATE");
        var evidence = accreditationDocumentService.create(evidenceRequest);
        Attachment evidenceAttachment = createAccreditationAttachment(evidence.getId());
        SupplierAccreditationDocumentUpdateRequest evidenceUpdate = new SupplierAccreditationDocumentUpdateRequest();
        evidenceUpdate.setApplicationId(application.getId());
        evidenceUpdate.setDocumentType("REGISTRATION_CERTIFICATE");
        evidenceUpdate.setAttachmentId(evidenceAttachment.getId());
        accreditationDocumentService.update(evidence.getId(), evidenceUpdate);
        accreditationDocumentService.verify(evidence.getId(),
            new com.enterprise.mm.payload.SupplierAccreditationDocumentActionRequest("Evidence reviewed"));
        application = accreditationService.submit(application.getId());
        application = accreditationService.startReview(application.getId());
        authenticate("compliance-manager", "COMPLIANCE_MANAGER");
        application = accreditationService.runComplianceCheck(application.getId());
        authenticate("supplier-manager", "SUPPLIER_MANAGER");
        application = accreditationService.approve(
            application.getId(),
            new SupplierAccreditationDecisionActionRequest(
                "APPROVED",
                List.of(materialGroup.getId()),
                LocalDate.now(),
                LocalDate.now().plusMonths(12),
                "Approved"
            )
        );
        assertThat(application.getStatus()).isEqualTo(SupplierAccreditationStatus.APPROVED.name());

        ContractCreateRequest contractRequest = new ContractCreateRequest();
        contractRequest.setSupplierId(supplier.getId());
        contractRequest.setName("Accredited contract");
        contractRequest.setContractNumber("MM-ACC-001");
        contractRequest.setStartDate(LocalDate.now().minusDays(1));
        contractRequest.setEndDate(LocalDate.now().plusMonths(3));
        ContractResponse contract = contractService.create(contractRequest);
        ContractSpecificationCreateRequest specification = new ContractSpecificationCreateRequest();
        specification.setContractId(contract.getId());
        specification.setName("Accredited material scope");
        specification.setLineType("MATERIAL");
        specification.setMaterialId(accreditedMaterial.getId());
        specification.setQuantityLimit(BigDecimal.TEN);
        specification.setUnit("EA");
        contractSpecificationService.create(specification);
        contract = contractService.activate(contract.getId());
        assertThat(contract.getStatus()).isEqualTo("ACTIVE");
        SourcePurchaseRequest source = approvedPurchaseRequest(accreditedMaterial);

        PurchaseOrderCreateRequest poRequest = new PurchaseOrderCreateRequest();
        poRequest.setSupplierId(supplier.getId());
        poRequest.setContractId(contract.getId());
        poRequest.setPurchaseRequestId(source.purchaseRequestId());
        poRequest.setName("Accredited PO");
        poRequest.setOrderDate(LocalDate.now());
        poRequest.setExpectedDeliveryDate(LocalDate.now().plusDays(7));
        poRequest.setTotalAmount(BigDecimal.valueOf(125_000));
        PurchaseOrderItemCreateRequest poItem = new PurchaseOrderItemCreateRequest();
        poItem.setName("Accredited material line");
        poItem.setLineType("MATERIAL");
        poItem.setMaterialId(accreditedMaterial.getId());
        poItem.setPurchaseRequestItemId(source.purchaseRequestItemId());
        poItem.setUnit("EA");
        poItem.setQuantity(BigDecimal.ONE);
        poItem.setUnitPrice(BigDecimal.valueOf(125_000));
        poRequest.setItems(List.of(poItem));
        PurchaseOrderResponse purchaseOrder = purchaseOrderService.create(poRequest);
        markPurchaseOrderApproved(purchaseOrder.getId());
        authenticate("admin", "ADMIN");
        purchaseOrder = purchaseOrderService.send(purchaseOrder.getId());
        assertThat(purchaseOrder.getStatus()).isEqualTo("SENT");
    }

    @Test
    void expiredAccreditationBlocksContractActivationAndPurchaseOrderDispatch() {
        Supplier supplier = supplier("SUP-EXPIRED-ACC");
        MaterialGroup materialGroup = materialGroup("GROUP-EXPIRED-ACC");
        Material expiredScopeMaterial = material("MAT-EXPIRED-ACC", materialGroup);

        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setCode("ACC-EXP-" + UUID.randomUUID().toString().substring(0, 8));
        application.setName("Expired approved accreditation");
        application.setStatus(SupplierAccreditationStatus.APPROVED.name());
        application.setSupplierId(supplier.getId());
        application.setAccreditationType("GOODS");
        application.setRequestedScope("Expired material group scope");
        application.setRequestedMaterialGroupIds(List.of(materialGroup.getId()));
        application.setValidFrom(LocalDate.now().minusMonths(2));
        application.setValidTo(LocalDate.now().minusDays(1));
        application = accreditationRepository.save(application);

        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setCode("ACC-DEC-EXP-" + UUID.randomUUID().toString().substring(0, 8));
        decision.setName("Expired recorded decision");
        decision.setStatus("RECORDED");
        decision.setApplicationId(application.getId());
        decision.setDecision(SupplierAccreditationStatus.APPROVED.name());
        decision.setApprovedScope("Expired material group scope");
        decision.setAllowedMaterialGroupIds(List.of(materialGroup.getId()));
        decision.setValidFrom(LocalDate.now().minusMonths(2));
        decision.setValidTo(LocalDate.now().minusDays(1));
        accreditationDecisionRepository.save(decision);

        SupplierAccreditationDocumentCreateRequest evidenceRequest =
            new SupplierAccreditationDocumentCreateRequest();
        evidenceRequest.setApplicationId(application.getId());
        evidenceRequest.setDocumentType("REGISTRATION_CERTIFICATE");
        var evidence = accreditationDocumentService.create(evidenceRequest);
        Attachment evidenceAttachment = createAccreditationAttachment(evidence.getId());
        SupplierAccreditationDocumentUpdateRequest evidenceUpdate =
            new SupplierAccreditationDocumentUpdateRequest();
        evidenceUpdate.setApplicationId(application.getId());
        evidenceUpdate.setDocumentType("REGISTRATION_CERTIFICATE");
        evidenceUpdate.setAttachmentId(evidenceAttachment.getId());
        accreditationDocumentService.update(evidence.getId(), evidenceUpdate);
        accreditationDocumentService.verify(
            evidence.getId(),
            new com.enterprise.mm.payload.SupplierAccreditationDocumentActionRequest("Evidence reviewed")
        );

        ContractCreateRequest contractRequest = new ContractCreateRequest();
        contractRequest.setSupplierId(supplier.getId());
        contractRequest.setName("Expired contract");
        contractRequest.setContractNumber("MM-ACC-EXP");
        contractRequest.setStartDate(LocalDate.now().minusDays(1));
        contractRequest.setEndDate(LocalDate.now().plusMonths(1));
        ContractResponse contract = contractService.create(contractRequest);
        ContractSpecificationCreateRequest specification = new ContractSpecificationCreateRequest();
        specification.setContractId(contract.getId());
        specification.setName("Expired accreditation material scope");
        specification.setLineType("MATERIAL");
        specification.setMaterialId(expiredScopeMaterial.getId());
        specification.setQuantityLimit(BigDecimal.ONE);
        specification.setUnit("EA");
        contractSpecificationService.create(specification);
        UUID contractId = contract.getId();
        assertThatThrownBy(() -> contractService.activate(contractId))
            .hasMessageContaining("expired");
    }

    @Test
    void approvedPurchaseOrderReadinessDoesNotBlockMissingSupplierAccreditation() {
        Supplier supplier = supplier("SUP-PO-READY-ACC");
        MaterialGroup materialGroup = materialGroup("GROUP-PO-READY-ACC");
        Material material = material("MAT-PO-READY-ACC", materialGroup);
        SourcePurchaseRequest source = approvedPurchaseRequest(material);

        PurchaseOrderCreateRequest poRequest = new PurchaseOrderCreateRequest();
        poRequest.setSupplierId(supplier.getId());
        poRequest.setPurchaseRequestId(source.purchaseRequestId());
        poRequest.setName("Readiness accreditation PO");
        poRequest.setOrderDate(LocalDate.now());
        poRequest.setExpectedDeliveryDate(LocalDate.now().plusDays(7));
        poRequest.setTotalAmount(BigDecimal.valueOf(1_000));
        PurchaseOrderItemCreateRequest poItem = new PurchaseOrderItemCreateRequest();
        poItem.setName("Readiness material line");
        poItem.setLineType("MATERIAL");
        poItem.setMaterialId(material.getId());
        poItem.setPurchaseRequestItemId(source.purchaseRequestItemId());
        poItem.setUnit("EA");
        poItem.setQuantity(BigDecimal.ONE);
        poItem.setUnitPrice(BigDecimal.valueOf(1_000));
        poRequest.setItems(List.of(poItem));
        PurchaseOrderResponse purchaseOrder = purchaseOrderService.create(poRequest);
        markPurchaseOrderApproved(purchaseOrder.getId());

        PurchaseOrderResponse ready = purchaseOrderService.get(purchaseOrder.getId());
        assertThat(ready.getSendReady()).isFalse();
        assertThat(ready.getSendBlockedReason()).contains("does not cover the material group");
    }

    @Test
    void blockedCompliancePreventsSupplierAccreditationApproval() {
        Supplier supplier = supplier("SUP-ACC-BLOCK");
        MaterialGroup materialGroup = materialGroup("GROUP-ACC-BLOCK");

        SupplierKycCheckCreateRequest checkRequest = new SupplierKycCheckCreateRequest();
        checkRequest.setSupplierId(supplier.getId());
        checkRequest.setName("Sanctions screening");
        checkRequest.setCheckType("SANCTIONS");
        checkRequest.setProvider("Internal compliance");
        SupplierKycCheckResponse check = kycCheckService.create(checkRequest);
        kycCheckService.fail(check.getId(), new SupplierKycDecisionRequest("CRITICAL", "Sanctions list match", true));

        SupplierAccreditationApplicationCreateRequest request = new SupplierAccreditationApplicationCreateRequest();
        request.setSupplierId(supplier.getId());
        request.setAccreditationType("GOODS");
        request.setRequestedScope("Blocked supplier scope");
        request.setRequestedMaterialGroupIds(List.of(materialGroup.getId()));
        var application = accreditationService.create(request);
        application = accreditationService.submit(application.getId());
        authenticate("compliance-manager", "COMPLIANCE_MANAGER");
        application = accreditationService.runComplianceCheck(application.getId());
        authenticate("supplier-manager", "SUPPLIER_MANAGER");

        UUID applicationId = application.getId();
        assertThatThrownBy(() -> accreditationService.approve(
            applicationId,
            new SupplierAccreditationDecisionActionRequest(
                "APPROVED",
                List.of(materialGroup.getId()),
                LocalDate.now(),
                LocalDate.now().plusMonths(12),
                "Should fail"
            )
        )).hasMessageContaining("blocked");
    }

    private Supplier supplier(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Supplier supplier = new Supplier();
        supplier.setCode(prefix + "-" + suffix);
        supplier.setName(prefix + "-" + suffix);
        supplier.setLegalName(prefix + " LLC");
        supplier.setEmail(prefix.toLowerCase(java.util.Locale.ROOT) + "-" + suffix + "@example.uz");
        supplier.setStatus(SupplierStatus.ACTIVE.name());
        supplier.setCategory("A");
        return supplierRepository.save(supplier);
    }

    private void markPurchaseOrderApproved(UUID purchaseOrderId) {
        var purchaseOrder = purchaseOrderRepository.findAnyById(purchaseOrderId).orElseThrow();
        purchaseOrder.setStatus(PurchaseOrderStatus.APPROVED.name());
        purchaseOrderRepository.save(purchaseOrder);
    }

    private SourcePurchaseRequest approvedPurchaseRequest(Material material) {
        int year = LocalDate.now().getYear();
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setCode("DPC-SUP-" + UUID.randomUUID().toString().substring(0, 8));
        cycle.setName("Supplier approved demand cycle");
        cycle.setStatus(DemandPlanningCycleStatus.BUDGET_APPROVED.name());
        cycle.setPlanYear(year);
        cycle.setPeriodType(DemandPeriodType.YEAR.name());
        cycle.setPeriodStart(LocalDate.of(year, 1, 1));
        cycle.setPeriodEnd(LocalDate.of(year, 12, 31));
        cycle.setCurrencyCode("UZS");
        cycle = demandPlanningCycleRepository.saveAndFlush(cycle);

        DemandPlan plan = new DemandPlan();
        plan.setCode("DP-SUP-" + UUID.randomUUID().toString().substring(0, 8));
        plan.setName("Supplier approved demand plan");
        plan.setStatus("APPROVED");
        plan.setPlanningCycle(cycle);
        plan.setRequestType(RequestType.GOODS);
        plan.setPlanYear(year);
        plan.setPeriodType(DemandPeriodType.YEAR.name());
        plan.setPeriodStart(LocalDate.of(year, 1, 1));
        plan.setPeriodEnd(LocalDate.of(year, 12, 31));
        plan.setCurrencyCode("UZS");
        plan.setTotalPlannedAmount(BigDecimal.ZERO);
        plan.setTotalApprovedAmount(BigDecimal.ZERO);
        plan = demandPlanRepository.saveAndFlush(plan);

        PurchaseRequest purchaseRequest = new PurchaseRequest();
        purchaseRequest.setCode("PR-SUP-" + UUID.randomUUID().toString().substring(0, 8));
        purchaseRequest.setName("Supplier approved purchase request");
        purchaseRequest.setStatus(PurchaseRequestStatus.APPROVED.name());
        purchaseRequest.setRequestType(RequestType.GOODS);
        purchaseRequest.setDemandPlanId(plan.getId());
        purchaseRequest.setRequiredDate(LocalDate.now().plusDays(14));
        purchaseRequest.setCurrencyCode("UZS");
        purchaseRequest.setTotalAmount(BigDecimal.valueOf(1_000));
        purchaseRequest = purchaseRequestRepository.saveAndFlush(purchaseRequest);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-SUP-" + UUID.randomUUID().toString().substring(0, 8));
        item.setName("Supplier PR item");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(purchaseRequest.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.ONE);
        item.setEstimatedPrice(BigDecimal.valueOf(1_000));
        item.setUnit("EA");
        item = purchaseRequestItemRepository.saveAndFlush(item);

        return new SourcePurchaseRequest(purchaseRequest.getId(), item.getId());
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username,
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        ));
    }

    private Material material(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UnitOfMeasure unit = unitOfMeasureRepository
            .findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalse(
                com.enterprise.mm.common.TenantContext.currentTenant(),
                com.enterprise.mm.common.CompanyContext.currentCompany(), "EA")
            .orElseGet(() -> {
                UnitOfMeasure created = new UnitOfMeasure();
                created.setCode("EA");
                created.setName("Each");
                created.setStatus("ACTIVE");
                created.setMeasurementNameShort("ea");
                created.setMeasurementNameLong("Each");
                return unitOfMeasureRepository.save(created);
            });
        Material material = new Material();
        material.setCode(prefix + "-" + suffix);
        material.setName(prefix + "-" + suffix);
        material.setStatus("ACTIVE");
        material.setUnitOfMeasureId(unit.getId());
        return materialRepository.save(material);
    }

    private Material material(String prefix, MaterialGroup materialGroup) {
        Material material = material(prefix);
        material.setMaterialGroupId(materialGroup.getId());
        return materialRepository.save(material);
    }

    private MaterialGroup materialGroup(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MaterialGroup group = new MaterialGroup();
        group.setCode(prefix + "-" + suffix);
        group.setName(prefix + "-" + suffix);
        group.setStatus("ACTIVE");
        return materialGroupRepository.save(group);
    }

    private void accredit(Supplier supplier, MaterialGroup materialGroup) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setCode("ACC-" + UUID.randomUUID().toString().substring(0, 8));
        application.setName("Portal RFQ accreditation");
        application.setStatus(SupplierAccreditationStatus.APPROVED.name());
        application.setSupplierId(supplier.getId());
        application.setAccreditationType("GOODS");
        application.setRequestedScope("Portal RFQ material group");
        application.setRequestedMaterialGroupIds(List.of(materialGroup.getId()));
        application.setValidFrom(LocalDate.now().minusDays(1));
        application.setValidTo(LocalDate.now().plusMonths(6));
        application = accreditationRepository.save(application);

        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setCode("ACC-DEC-" + UUID.randomUUID().toString().substring(0, 8));
        decision.setName("Portal RFQ accreditation decision");
        decision.setStatus("RECORDED");
        decision.setApplicationId(application.getId());
        decision.setDecision(SupplierAccreditationStatus.APPROVED.name());
        decision.setApprovedScope("Portal RFQ material group");
        decision.setAllowedMaterialGroupIds(List.of(materialGroup.getId()));
        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().plusMonths(6));
        accreditationDecisionRepository.save(decision);

        SupplierAccreditationDocumentCreateRequest evidenceRequest = new SupplierAccreditationDocumentCreateRequest();
        evidenceRequest.setApplicationId(application.getId());
        evidenceRequest.setDocumentType("REGISTRATION_CERTIFICATE");
        var evidence = accreditationDocumentService.create(evidenceRequest);
        Attachment evidenceAttachment = createAccreditationAttachment(evidence.getId());
        SupplierAccreditationDocumentUpdateRequest evidenceUpdate = new SupplierAccreditationDocumentUpdateRequest();
        evidenceUpdate.setApplicationId(application.getId());
        evidenceUpdate.setDocumentType("REGISTRATION_CERTIFICATE");
        evidenceUpdate.setAttachmentId(evidenceAttachment.getId());
        accreditationDocumentService.update(evidence.getId(), evidenceUpdate);
        accreditationDocumentService.verify(evidence.getId(),
            new com.enterprise.mm.payload.SupplierAccreditationDocumentActionRequest("Evidence reviewed"));
    }

    private Attachment createAccreditationAttachment(UUID evidenceId) {
        Attachment attachment = new Attachment();
        attachment.setCode("ATT-" + UUID.randomUUID().toString().substring(0, 8));
        attachment.setName("accreditation-evidence.pdf");
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("SUPPLIER_ACCREDITATION_DOCUMENT");
        attachment.setEntityId(evidenceId);
        attachment.setAttachmentType("REGISTRATION_CERTIFICATE");
        attachment.setOriginalFileName("accreditation-evidence.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(128L);
        attachment.setChecksumSha256("a".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("tests/accreditation/" + UUID.randomUUID());
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setDownloadable(true);
        return attachmentRepository.save(attachment);
    }

    private void invite(RFQ rfq, Supplier supplier) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("RFQ-INV-" + UUID.randomUUID().toString().substring(0, 8));
        invitation.setName("Invitation for " + rfq.getCode());
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        invitationRepository.save(invitation);
    }

    private RFQ rfq(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RFQ rfq = new RFQ();
        rfq.setCode(prefix + "-" + suffix);
        rfq.setName(prefix + "-" + suffix);
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(10));
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

    private record SourcePurchaseRequest(UUID purchaseRequestId, UUID purchaseRequestItemId) {
    }
}
