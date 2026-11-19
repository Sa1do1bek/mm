package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierMagicLink;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.SupplierMagicLinkRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.ServiceAcceptanceActRepairService;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.service.rfq.RfqEmailTransport;
import com.enterprise.mm.service.rfq.RfqOutboundEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "portal-test", roles = "ADMIN")
class SupplierPortalMagicLinkSessionFlowIntegrationTest {
    private static final String TENANT = "default";
    private static final String TOKEN_HEADER = "X-Supplier-Portal-Token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierPortalAccountRepository accountRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private PurchaseOrderItemRepository itemRepository;
    @Autowired private SupplierPoDispatchRepository dispatchRepository;
    @Autowired private SupplierMagicLinkRepository magicLinkRepository;
    @Autowired private RFQRepository rfqRepository;
    @Autowired private RfqSupplierInvitationRepository invitationRepository;
    @Autowired private ServiceOrderRepository serviceOrderRepository;
    @Autowired private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Autowired private ApprovalFlowRepository approvalFlowRepository;
    @Autowired private ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired private ApprovalInstanceRepository approvalInstanceRepository;
    @Autowired private ApprovalTaskRepository approvalTaskRepository;
    @Autowired private AttachmentService attachmentService;
    @Autowired private ApprovalEngineService approvalEngineService;
    @Autowired private ServiceAcceptanceActRepairService repairService;
    @MockBean private RfqEmailTransport emailTransport;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void purchaseOrderMagicLinkExchangeTokenWorksForMeListAndResponseDraft() throws Exception {
        Fixture fixture = fixture();
        String sessionToken = sessionTokenFor(fixture, "po-magic-");

        SupplierPortalAccount persisted = accountRepository.findByIdAndIsDeletedFalse(fixture.account().getId()).orElseThrow();
        assertThat(persisted.getPortalTokenHash()).isEqualTo(sha256(sessionToken));
        assertThat(persisted.getPortalTokenExpiresAt()).isAfter(LocalDateTime.now());

        mockMvc.perform(get("/api/public/supplier-portal/me")
                .header(TOKEN_HEADER, sessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.portalAccountId").value(fixture.account().getId().toString()));

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders")
                .header(TOKEN_HEADER, sessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].id").value(fixture.purchaseOrder().getId().toString()));

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}", fixture.purchaseOrder().getId())
                .header(TOKEN_HEADER, "  " + sessionToken + "  "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(fixture.purchaseOrder().getId().toString()));

        String draftBody = objectMapper.writeValueAsString(Map.of(
            "supplierComment", "Accepted from portal integration test",
            "lines", java.util.List.of(Map.of(
                "purchaseOrderItemId", fixture.item().getId().toString(),
                "decision", "ACCEPTED",
                "acceptedQuantity", new BigDecimal("25"),
                "commitments", java.util.List.of(Map.of(
                    "proposedQuantity", new BigDecimal("25"),
                    "proposedDate", LocalDate.now().plusDays(5).toString()
                ))
            ))
        ));

        mockMvc.perform(post("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/responses/drafts", fixture.purchaseOrder().getId())
                .header(TOKEN_HEADER, sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.responseStatus").value("DRAFT"));
    }

    @Test
    void acceptedTermsPersistForPurchaseOrderDetailReloadAndResponseDraft() throws Exception {
        Fixture fixture = fixture();
        fixture.account().setTermsAcceptedAt(null);
        accountRepository.saveAndFlush(fixture.account());
        String sessionToken = sessionTokenFor(fixture, "po-terms-magic-");

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}", fixture.purchaseOrder().getId())
                .header(TOKEN_HEADER, sessionToken))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value("Supplier portal terms must be accepted before submissions"));

        mockMvc.perform(post("/api/public/supplier-portal/terms/accept")
                .header(TOKEN_HEADER, sessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.portalAccountId").value(fixture.account().getId().toString()))
            .andExpect(jsonPath("$.data.termsAcceptedAt").isNotEmpty());

        mockMvc.perform(get("/api/public/supplier-portal/me")
                .header(TOKEN_HEADER, sessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.termsAcceptedAt").isNotEmpty());

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}", fixture.purchaseOrder().getId())
                .header(TOKEN_HEADER, sessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(fixture.purchaseOrder().getId().toString()))
            .andExpect(jsonPath("$.data.allowedActions[?(@ == 'SUBMIT_RESPONSE')]").exists());

        String draftBody = objectMapper.writeValueAsString(Map.of(
            "supplierComment", "Accepted after explicit terms persistence",
            "lines", java.util.List.of(Map.of(
                "purchaseOrderItemId", fixture.item().getId().toString(),
                "decision", "ACCEPTED",
                "acceptedQuantity", new BigDecimal("25"),
                "commitments", java.util.List.of(Map.of(
                    "proposedQuantity", new BigDecimal("25"),
                    "proposedDate", LocalDate.now().plusDays(5).toString()
                ))
            ))
        ));

        mockMvc.perform(post("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/responses/drafts", fixture.purchaseOrder().getId())
                .header(TOKEN_HEADER, sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.responseStatus").value("DRAFT"));
    }

    @Test
    void teamInviteCreatesUsableAccountScopedActivationLink() throws Exception {
        Fixture fixture = fixture();
        String adminSessionToken = sessionTokenFor(fixture, "team-admin-magic-");
        when(emailTransport.send(any(RfqOutboundEmail.class)))
            .thenReturn(new RfqEmailTransport.SendResult("team-invite-provider"));

        String invitedEmail = "team-" + shortId().toLowerCase() + "@example.uz";
        mockMvc.perform(post("/api/public/supplier-portal/team")
                .header(TOKEN_HEADER, adminSessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "loginEmail", invitedEmail,
                    "contactPerson", "Portal Team Member",
                    "phone", "+998901234567",
                    "portalRole", "SUPPLIER_USER"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.loginEmail").value(invitedEmail))
            .andExpect(jsonPath("$.data.status").value("INVITED"));

        ArgumentCaptor<RfqOutboundEmail> email = ArgumentCaptor.forClass(RfqOutboundEmail.class);
        verify(emailTransport).send(email.capture());
        assertThat(email.getValue().recipientEmail()).isEqualTo(invitedEmail);
        assertThat(email.getValue().attachments()).isEmpty();
        String activationToken = activationTokenFrom(email.getValue());

        String exchangeJson = mockMvc.perform(post("/api/public/supplier-portal/magic-links/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", activationToken))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.supplierId").value(fixture.supplier().getId().toString()))
            .andExpect(jsonPath("$.data.returnPath").value("/supplier-portal"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode exchange = objectMapper.readTree(exchangeJson);
        String teamSessionToken = exchange.path("data").path("token").asText();
        String teamAccountId = exchange.path("data").path("portalAccountId").asText();
        assertThat(teamAccountId).isNotEqualTo(fixture.account().getId().toString());

        mockMvc.perform(get("/api/public/supplier-portal/me")
                .header(TOKEN_HEADER, teamSessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.portalRole").value("SUPPLIER_USER"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders")
                .header(TOKEN_HEADER, teamSessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].id").value(fixture.purchaseOrder().getId().toString()));
    }

    @Test
    void supplierPortalRejectsCrossSupplierObjectSubstitution() throws Exception {
        Fixture supplierA = fixture();
        Fixture supplierB = fixture(supplierA.company());
        String supplierASession = sessionTokenFor(supplierA, "supplier-a-magic-");
        RfqSupplierInvitation supplierBInvitation = invitation(supplierB);
        AttachmentDto supplierBArtifact = addPoArtifact(supplierB.company().getId(), supplierB.purchaseOrder().getId());

        mockMvc.perform(get("/api/public/supplier-portal/rfq/{rfqId}", supplierBInvitation.getRfqId())
                .header(TOKEN_HEADER, supplierASession))
            .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/public/supplier-portal/rfq/{rfqId}/quotations/drafts", supplierBInvitation.getRfqId())
                .header(TOKEN_HEADER, supplierASession)
                .header("Idempotency-Key", "cross-rfq-draft-" + shortId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "currencyCode", "UZS",
                    "items", List.of(Map.of(
                        "rfqItemId", supplierB.item().getId().toString(),
                        "offeredQuantity", new BigDecimal("1"),
                        "unitPrice", new BigDecimal("1")
                    ))
                ))))
            .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}", supplierB.purchaseOrder().getId())
                .header(TOKEN_HEADER, supplierASession))
            .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/responses/drafts", supplierB.purchaseOrder().getId())
                .header(TOKEN_HEADER, supplierASession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "supplierComment", "Cross supplier response attempt",
                    "lines", List.of(Map.of(
                        "purchaseOrderItemId", supplierB.item().getId().toString(),
                        "decision", "ACCEPTED",
                        "acceptedQuantity", new BigDecimal("1"),
                        "commitments", List.of()
                    ))
                ))))
            .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/commitments", supplierB.purchaseOrder().getId())
                .header(TOKEN_HEADER, supplierASession))
            .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/asns/drafts", supplierB.purchaseOrder().getId())
                .header(TOKEN_HEADER, supplierASession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "asnNumber", "ASN-CROSS-" + shortId(),
                    "noticeDate", LocalDate.now().toString(),
                    "expectedShipDate", LocalDate.now().plusDays(1).toString(),
                    "expectedArrivalDate", LocalDate.now().plusDays(3).toString(),
                    "lines", List.of(Map.of(
                        "supplierPoCommitmentId", UUID.randomUUID().toString(),
                        "quantity", new BigDecimal("1")
                    ))
                ))))
            .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/artifacts/{attachmentId}/download",
                supplierB.purchaseOrder().getId(), supplierBArtifact.getId())
                .header(TOKEN_HEADER, supplierASession))
            .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/public/supplier-portal/team")
                .header(TOKEN_HEADER, supplierASession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "loginEmail", supplierB.account().getLoginEmail(),
                    "contactPerson", "Cross Supplier Invite",
                    "portalRole", "SUPPLIER_USER"
                ))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void servicePurchaseOrderPortalFlowSubmitsServiceCompletion() throws Exception {
        Fixture fixture = fixture();
        ensureApprovalFlow(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, "Portal service act approval", "PORTAL-SAA", "ADMIN");
        fixture.purchaseOrder().setRequestType(RequestType.SERVICE);
        fixture.purchaseOrder().setTotalAmount(new BigDecimal("300000"));
        purchaseOrderRepository.saveAndFlush(fixture.purchaseOrder());
        fixture.item().setLineType(ProcurementLineType.SERVICE);
        fixture.item().setServiceDescription("Portal service completion item");
        itemRepository.saveAndFlush(fixture.item());

        String rawMagicToken = "service-magic-" + UUID.randomUUID();
        magicLinkRepository.saveAndFlush(magicLink(fixture, rawMagicToken));
        String exchangeJson = mockMvc.perform(post("/api/public/supplier-portal/magic-links/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", rawMagicToken))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String sessionToken = objectMapper.readTree(exchangeJson).path("data").path("token").asText();

        String draftJson = mockMvc.perform(post("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/responses/drafts", fixture.purchaseOrder().getId())
                .header(TOKEN_HEADER, sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "supplierComment", "Accepted service response",
                    "lines", java.util.List.of(Map.of(
                        "purchaseOrderItemId", fixture.item().getId().toString(),
                        "decision", "ACCEPTED",
                        "acceptedQuantity", new BigDecimal("25"),
                        "commitments", java.util.List.of()
                    ))
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.responseStatus").value("DRAFT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String responseId = objectMapper.readTree(draftJson).path("data").path("id").asText();
        mockMvc.perform(post("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/responses/{responseId}/submit",
                fixture.purchaseOrder().getId(), responseId)
                .header(TOKEN_HEADER, sessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.responseStatus").value("SUBMITTED"));

        mockMvc.perform(post("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}/service-completions", fixture.purchaseOrder().getId())
                .header(TOKEN_HEADER, sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "completedAmount", new BigDecimal("300000"),
                    "completionDate", LocalDate.now().toString(),
                    "comment", "Completed service from supplier portal"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.completedAmount").value(300000));
        var acts = serviceAcceptanceActRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                fixture.company().getId(),
                fixture.purchaseOrder().getId()
        );
        assertThat(acts).hasSize(1);
        assertThat(acts.get(0).getStatus()).isEqualTo("DRAFT");
        assertThat(approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            fixture.company().getId(),
            ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
            acts.get(0).getId()
        )).isEmpty();

        mockMvc.perform(post("/api/workspaces/services-acceptance/acts/{actId}/submit", acts.get(0).getId())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Company-Id", fixture.company().getId().toString())
                .header("Idempotency-Key", "portal-service-act-submit-" + shortId())
                .content(objectMapper.writeValueAsString(Map.of(
                    "expectedVersion", acts.get(0).getVersion(),
                    "reason", "Buyer submits supplier completion"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("TECHNICAL_REVIEW"));

        ServiceAcceptanceAct submittedAct = serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(acts.get(0).getId())
            .orElseThrow();
        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                fixture.company().getId(),
                ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
                submittedAct.getId()
            )
            .orElseThrow();
        assertThat(instance.getApprovalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        var tasks = approvalTaskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instance.getId()
        );
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);

        addActAttachment(fixture.company().getId(), submittedAct.getId());
        approveTaskAsCompany(fixture.company().getId(), tasks.get(0).getId(), "technical ok");
        assertThat(serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(submittedAct.getId()).orElseThrow().getStatus())
            .isEqualTo("DOCUMENT_REVIEW");

        mockMvc.perform(get("/api/public/supplier-portal/purchase-orders/{purchaseOrderId}", fixture.purchaseOrder().getId())
                .header(TOKEN_HEADER, sessionToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fulfillmentMode").value("SERVICE_CONFIRMATION"))
            .andExpect(jsonPath("$.data.completionStatus").value("SUBMITTED"))
            .andExpect(jsonPath("$.data.currentCompletion.completedAmount").value(300000));
    }

    @Test
    void technicalReviewServiceActWithoutApprovalIsRepairedIntoBuyerTaskAndCanAdvance() {
        Fixture fixture = fixture();
        ensureApprovalFlow(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, "Repair service act approval", "REPAIR-SAA", "ADMIN");
        fixture.purchaseOrder().setRequestType(RequestType.SERVICE);
        fixture.purchaseOrder().setTotalAmount(new BigDecimal("125000"));
        purchaseOrderRepository.saveAndFlush(fixture.purchaseOrder());

        ServiceOrder order = new ServiceOrder();
        order.setCode("SO-REPAIR-" + shortId());
        order.setName("Repair service order");
        order.setStatus("ACTIVE");
        order.setPurchaseOrderId(fixture.purchaseOrder().getId());
        order.setSupplierId(fixture.supplier().getId());
        order.setTotalAmount(new BigDecimal("125000"));
        order.setCurrencyCode("UZS");
        order = serviceOrderRepository.saveAndFlush(order);

        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setCode("SAA-REPAIR-" + shortId());
        act.setName("Stuck supplier service completion");
        act.setStatus("TECHNICAL_REVIEW");
        act.setServiceOrderId(order.getId());
        act.setPurchaseOrderId(fixture.purchaseOrder().getId());
        act.setAmount(new BigDecimal("125000"));
        act = serviceAcceptanceActRepository.saveAndFlush(act);

        assertThat(approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
            act.getId()
        )).isEmpty();

        repairService.repairMissingTechnicalReviewApproval(act.getId());

        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
                act.getId()
            )
            .orElseThrow();
        assertThat(instance.getApprovalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        var tasks = approvalTaskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instance.getId()
        );
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
        assertThat(serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(act.getId()).orElseThrow().getStatus())
            .isEqualTo("TECHNICAL_REVIEW");

        addActAttachment(fixture.company().getId(), act.getId());
        approveTaskAsCompany(fixture.company().getId(), tasks.get(0).getId(), "technical ok");
        assertThat(serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(act.getId()).orElseThrow().getStatus())
            .isEqualTo("DOCUMENT_REVIEW");
    }

    private void approveTaskAsCompany(UUID companyId, UUID taskId, String comment) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        Authentication previousAuthentication = SecurityContextHolder.getContext().getAuthentication();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(buyerAdminAuthentication());
        try {
            approvalEngineService.approveTask(taskId, comment);
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
            SecurityContextHolder.getContext().setAuthentication(previousAuthentication);
        }
    }

    private Authentication buyerAdminAuthentication() {
        return new UsernamePasswordAuthenticationToken(
            "portal-test",
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private void ensureApprovalFlow(String moduleKey, String name, String stepCodePrefix, String approverRole) {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setCode("TEST-FLOW-" + shortId());
        flow.setName(name);
        flow.setStatus("ACTIVE");
        flow.setModuleKey(moduleKey);
        flow.setActive(true);
        ApprovalFlow saved = approvalFlowRepository.save(flow);

        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setApprovalFlowId(saved.getId());
        step.setCode(stepCodePrefix + "-" + shortId());
        step.setName(name + " step");
        step.setStepOrder(1);
        step.setStatus("ACTIVE");
        step.setAssigneeType(ApprovalAssigneeType.ROLE);
        step.setAssigneeRole(approverRole);
        step.setAssigneeUserId(null);
        step.setAssigneeUsername(null);
        step.setCanReturn(true);
        step.setCommentRequired(false);
        approvalFlowStepRepository.save(step);
    }

    private void addActAttachment(UUID companyId, UUID actId) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        Authentication previousAuthentication = SecurityContextHolder.getContext().getAuthentication();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(buyerAdminAuthentication());
        try {
            attachmentService.saveBytes(
                ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
                actId,
                "ACT",
                "service-act.pdf",
                "application/pdf",
                "service-act".getBytes(StandardCharsets.UTF_8)
            );
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
            SecurityContextHolder.getContext().setAuthentication(previousAuthentication);
        }
    }

    private AttachmentDto addPoArtifact(UUID companyId, UUID purchaseOrderId) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        Authentication previousAuthentication = SecurityContextHolder.getContext().getAuthentication();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(buyerAdminAuthentication());
        try {
            return attachmentService.saveBytes(
                "PURCHASEORDER",
                purchaseOrderId,
                "SUPPLIER_PO",
                "supplier-b-po.pdf",
                "application/pdf",
                "supplier-b-po".getBytes(StandardCharsets.UTF_8)
            );
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
            SecurityContextHolder.getContext().setAuthentication(previousAuthentication);
        }
    }

    @Test
    void rfqMagicLinkExchangeStillCreatesSupplierPortalSession() throws Exception {
        Fixture fixture = fixture();
        RfqSupplierInvitation invitation = invitation(fixture);
        String rawMagicToken = "rfq-magic-" + UUID.randomUUID();
        SupplierMagicLink link = magicLink(fixture, rawMagicToken);
        link.setPurchaseOrderId(null);
        link.setInvitationId(invitation.getId());
        link.setReturnPath("/supplier-portal/rfq/" + invitation.getRfqId());
        magicLinkRepository.saveAndFlush(link);

        mockMvc.perform(post("/api/public/supplier-portal/magic-links/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", rawMagicToken))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.returnPath").value("/supplier-portal/rfq/" + invitation.getRfqId()));
    }

    @Test
    void expiredRandomAndInactiveMagicLinksKeepSafeErrors() throws Exception {
        Fixture expiredFixture = fixture();
        String expiredToken = "expired-magic-" + UUID.randomUUID();
        SupplierMagicLink expired = magicLink(expiredFixture, expiredToken);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        magicLinkRepository.saveAndFlush(expired);

        assertMagicLinkInvalid(expiredToken);
        assertMagicLinkInvalid("random-magic-" + UUID.randomUUID());

        Fixture inactiveFixture = fixture();
        inactiveFixture.account().setStatus("SUSPENDED");
        accountRepository.saveAndFlush(inactiveFixture.account());
        String inactiveToken = "inactive-magic-" + UUID.randomUUID();
        magicLinkRepository.saveAndFlush(magicLink(inactiveFixture, inactiveToken));

        assertMagicLinkInvalid(inactiveToken);
    }

    private Fixture fixture() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);

        Company company = new Company();
        company.setTenantId(TENANT);
        company.setCode("SPC-" + shortId());
        company.setName("Supplier portal company " + shortId());
        company.setStatus("ACTIVE");
        company = companyRepository.saveAndFlush(company);
        CompanyContext.setCompany(company.getId());
        return fixture(company);
    }

    private Fixture fixture(Company company) {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(company.getId());

        Supplier supplier = new Supplier();
        supplier.setCode("SUP-PORTAL-" + shortId());
        supplier.setName("Portal Supplier");
        supplier.setLegalName("Portal Supplier LLC");
        supplier.setStatus("ACTIVE");
        supplier.setCategory("A");
        supplier.setEmail("portal-" + shortId().toLowerCase() + "@example.uz");
        supplier = supplierRepository.saveAndFlush(supplier);

        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setCode("SPA-" + shortId());
        account.setName("Portal account");
        account.setSupplierId(supplier.getId());
        account.setLoginEmail("portal-account-" + shortId().toLowerCase() + "@example.uz");
        account.setStatus(SupplierPortalStatus.INVITED.name());
        account.setInvitedAt(LocalDateTime.now());
        account.setTermsAcceptedAt(LocalDateTime.now());
        account = accountRepository.saveAndFlush(account);

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setCode("PO-MAGIC-" + shortId());
        purchaseOrder.setName("PO magic session");
        purchaseOrder.setStatus("SENT");
        purchaseOrder.setSupplierId(supplier.getId());
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setOrderDate(LocalDate.now());
        purchaseOrder.setExpectedDeliveryDate(LocalDate.now().plusDays(10));
        purchaseOrder.setTotalAmount(new BigDecimal("2500"));
        purchaseOrder.setCurrencyCode("UZS");
        purchaseOrder = purchaseOrderRepository.saveAndFlush(purchaseOrder);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("POI-MAGIC-" + shortId());
        item.setName("Portal PO item");
        item.setStatus("ACTIVE");
        item.setPurchaseOrderId(purchaseOrder.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(new BigDecimal("25"));
        item.setUnitPrice(new BigDecimal("100"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        item = itemRepository.saveAndFlush(item);

        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setCode("POD-MAGIC-" + shortId());
        dispatch.setName("Portal PO dispatch");
        dispatch.setPurchaseOrderId(purchaseOrder.getId());
        dispatch.setPurchaseOrderVersion(purchaseOrder.getVersion());
        dispatch.setSupplierId(supplier.getId());
        dispatch.setPortalAccountId(account.getId());
        dispatch.setRecipientEmail(account.getLoginEmail());
        dispatch.setDispatchStatus("QUEUED");
        dispatch.setQueuedAt(LocalDateTime.now());
        dispatch.setLastDeliveryError("Previous self-service renewal retry");
        dispatch.setResponseDueDate(LocalDate.now().plusDays(7));
        dispatch.setSnapshotJson("{}");
        dispatch.setSnapshotHash("a".repeat(64));
        dispatch.setCurrent(true);
        dispatch = dispatchRepository.saveAndFlush(dispatch);

        return new Fixture(company, supplier, account, purchaseOrder, item, dispatch);
    }

    private RfqSupplierInvitation invitation(Fixture fixture) {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(fixture.company().getId());
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        RFQ rfq = rfq();
        invitation.setCode("RFQ-INV-" + shortId());
        invitation.setName("RFQ invitation");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(fixture.supplier().getId());
        invitation.setStatus("INVITED");
        return invitationRepository.saveAndFlush(invitation);
    }

    private RFQ rfq() {
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-MAGIC-" + shortId());
        rfq.setName("RFQ magic exchange");
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(7));
        rfq.setPackageLanguage("ru");
        rfq.setPublishedAt(LocalDateTime.now().minusHours(1));
        return rfqRepository.saveAndFlush(rfq);
    }

    private SupplierMagicLink magicLink(Fixture fixture, String rawToken) {
        SupplierMagicLink link = new SupplierMagicLink();
        link.setTenantId(TENANT);
        link.setCompanyId(fixture.company().getId());
        link.setCode("SML-" + shortId());
        link.setName("PO magic link");
        link.setStatus("ISSUED");
        link.setPortalAccountId(fixture.account().getId());
        link.setSupplierId(fixture.supplier().getId());
        link.setPurchaseOrderId(fixture.purchaseOrder().getId());
        link.setTokenHash(sha256(rawToken));
        link.setReturnPath("/supplier-portal/purchase-orders/" + fixture.purchaseOrder().getId());
        link.setExpiresAt(LocalDateTime.now().plusHours(1));
        return link;
    }

    private String sessionTokenFor(Fixture fixture, String tokenPrefix) throws Exception {
        String rawMagicToken = tokenPrefix + UUID.randomUUID();
        SupplierMagicLink link = magicLink(fixture, rawMagicToken);
        magicLinkRepository.saveAndFlush(link);
        String exchangeJson = mockMvc.perform(post("/api/public/supplier-portal/magic-links/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", rawMagicToken))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.returnPath").value("/supplier-portal/purchase-orders/" + fixture.purchaseOrder().getId()))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(exchangeJson).path("data").path("token").asText();
    }

    private String activationTokenFrom(RfqOutboundEmail email) {
        String body = email.textBody() == null ? email.htmlBody() : email.textBody();
        var matcher = Pattern.compile("token=([^\\s\"<]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private void assertMagicLinkInvalid(String rawMagicToken) throws Exception {
        mockMvc.perform(post("/api/public/supplier-portal/magic-links/exchange")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("token", rawMagicToken))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("rfq.portal.link_invalid"));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private record Fixture(
        Company company,
        Supplier supplier,
        SupplierPortalAccount account,
        PurchaseOrder purchaseOrder,
        PurchaseOrderItem item,
        SupplierPoDispatch dispatch
    ) {
    }
}
