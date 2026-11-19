package com.enterprise.mm;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseRequestLinkedDocumentsWorkspaceTest {
    @Autowired MockMvc mockMvc;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired ProcurementMethodDecisionRepository decisionRepository;
    @Autowired ProcurementProcedureRepository procedureRepository;
    @Autowired ProcurementCampaignRepository campaignRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired CommissionRepository commissionRepository;

    private String tenantId;
    private Department department;

    @BeforeEach
    void setUp() {
        tenantId = "pr-linked-documents-" + UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        department = department();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void workspaceLinkedDocumentsCarryMethodProcedureAndCampaignFields() throws Exception {
        PurchaseRequest request = purchaseRequest();
        Commission commission = commission();
        Supplier supplier = supplier();
        RFQ rfq = rfq(request);
        ProcurementMethodDecision decision = decision(request, commission);
        ProcurementProcedure procedure = procedure(request, decision, rfq, supplier);
        ProcurementCampaign campaign = campaign(request, decision, procedure);
        procedure.setCampaignId(campaign.getId());
        procedureRepository.save(procedure);

        mockMvc.perform(get("/api/purchase-requests/{id}/workspace", request.getId())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("PROCUREMENT_METHOD_DECISION")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("PROCUREMENT_PROCEDURE")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("PROCUREMENT_CAMPAIGN")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_METHOD_DECISION')].fields[?(@.key == 'method')].value", hasItem("OPEN_TENDER")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_METHOD_DECISION')].fields[?(@.key == 'amount')].value", hasItem("12500 UZS")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_METHOD_DECISION')].fields[?(@.key == 'supplierCount')].value", hasItem("5")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_METHOD_DECISION')].fields[?(@.key == 'commission')].value", hasItem("Tender commission")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_METHOD_DECISION')].fields[?(@.key == 'decision')].value", hasItem("APPROVED")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_PROCEDURE')].fields[?(@.key == 'procedureType')].value", hasItem("REQUEST_FOR_QUOTATION")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_PROCEDURE')].fields[?(@.key == 'status')].value", hasItem("LAUNCHED")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_PROCEDURE')].fields[?(@.key == 'priceDiscovery')].value", hasItem("Yes")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_PROCEDURE')].fields[?(@.key == 'linkedRfq')].value", hasItem("RFQ-LD-1")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_PROCEDURE')].fields[?(@.key == 'awardedSupplier')].value", hasItem("Awarded supplier")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_PROCEDURE')].fields[?(@.key == 'awardReference')].value", hasItem("AWD-42")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_PROCEDURE')].fields[?(@.key == 'finalizedAmount')].value", hasItem("11900 UZS")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_PROCEDURE')].fields[?(@.key == 'dates')].value", hasItem("2026-08-01 - 2026-08-03")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_CAMPAIGN')].fields[?(@.key == 'campaignType')].value", hasItem("ANNUAL_TENDER")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_CAMPAIGN')].fields[?(@.key == 'targetSupplierCount')].value", hasItem("4")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_CAMPAIGN')].fields[?(@.key == 'plannedSpend')].value", hasItem("12500 UZS")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_CAMPAIGN')].fields[?(@.key == 'approvedSpend')].value", hasItem("12000 UZS")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_CAMPAIGN')].fields[?(@.key == 'savingsTarget')].value", hasItem("500 UZS")))
            .andExpect(jsonPath("$.data.relatedDocuments[?(@.documentType == 'PROCUREMENT_CAMPAIGN')].fields[?(@.key == 'plannedDates')].value", hasItem("2026-08-05 - 2026-08-20")));
    }

    private Department department() {
        Department entity = new Department();
        entity.setCode("DEP-LD-" + suffix());
        entity.setName("Linked documents department");
        entity.setStatus("ACTIVE");
        entity.setDepartmentType("OPERATIONS");
        return departmentRepository.save(entity);
    }

    private PurchaseRequest purchaseRequest() {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-LD-" + suffix());
        request.setName("Linked documents purchase request");
        request.setStatus("APPROVED");
        request.setDepartmentId(department.getId());
        request.setRequestedBy("requester");
        request.setRequestType(RequestType.GOODS);
        request.setRequiredDate(LocalDate.of(2026, 8, 30));
        request.setTotalAmount(new BigDecimal("12500"));
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private Commission commission() {
        Commission commission = new Commission();
        commission.setCode("COM-LD-" + suffix());
        commission.setName("Tender commission");
        commission.setStatus("ACTIVE");
        commission.setType(CommissionType.TENDER);
        return commissionRepository.save(commission);
    }

    private Supplier supplier() {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-LD-" + suffix());
        supplier.setName("Awarded supplier");
        supplier.setShortName("Awarded supplier");
        supplier.setStatus("ACTIVE");
        return supplierRepository.save(supplier);
    }

    private RFQ rfq(PurchaseRequest request) {
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-LD-1");
        rfq.setName("Linked documents RFQ");
        rfq.setStatus("ACTIVE");
        rfq.setPurchaseRequestId(request.getId());
        rfq.setPurpose("PROCUREMENT");
        rfq.setCurrencyCode("UZS");
        return rfqRepository.save(rfq);
    }

    private ProcurementMethodDecision decision(PurchaseRequest request, Commission commission) {
        ProcurementMethodDecision decision = new ProcurementMethodDecision();
        decision.setCode("PMD-LD-" + suffix());
        decision.setName("Linked documents method decision");
        decision.setStatus("APPROVED");
        decision.setDecisionNumber("PMD-LD-1");
        decision.setPurchaseRequestId(request.getId());
        decision.setSelectedMethod("OPEN_TENDER");
        decision.setEstimatedAmount(new BigDecimal("12500"));
        decision.setCurrencyCode("UZS");
        decision.setPotentialSupplierCount(5);
        decision.setCommissionId(commission.getId());
        return decisionRepository.save(decision);
    }

    private ProcurementProcedure procedure(PurchaseRequest request, ProcurementMethodDecision decision, RFQ rfq, Supplier supplier) {
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setCode("PROC-LD-" + suffix());
        procedure.setName("Linked documents procedure");
        procedure.setStatus("LAUNCHED");
        procedure.setProcedureNumber("PROC-LD-1");
        procedure.setMethodDecisionId(decision.getId());
        procedure.setPurchaseRequestId(request.getId());
        procedure.setMethod("OPEN_TENDER");
        procedure.setExecutionPath("REQUEST_FOR_QUOTATION");
        procedure.setRfqId(rfq.getId());
        procedure.setRequiresPriceDiscovery(true);
        procedure.setAwardedSupplierId(supplier.getId());
        procedure.setAwardReference("AWD-42");
        procedure.setFinalizedAmount(new BigDecimal("11900"));
        procedure.setFinalizedCurrencyCode("UZS");
        procedure.setLaunchedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        procedure.setCompletedAt(LocalDateTime.of(2026, 8, 3, 17, 0));
        return procedureRepository.save(procedure);
    }

    private ProcurementCampaign campaign(
        PurchaseRequest request,
        ProcurementMethodDecision decision,
        ProcurementProcedure procedure
    ) {
        ProcurementCampaign campaign = new ProcurementCampaign();
        campaign.setCode("CAMP-LD-" + suffix());
        campaign.setName("Linked documents campaign");
        campaign.setStatus("PLANNED");
        campaign.setCampaignNumber("CAMP-LD-1");
        campaign.setPurchaseRequestId(request.getId());
        campaign.setMethodDecisionId(decision.getId());
        campaign.setProcurementProcedureId(procedure.getId());
        campaign.setCampaignType("ANNUAL_TENDER");
        campaign.setProcedureType("REQUEST_FOR_QUOTATION");
        campaign.setTargetSupplierCount(4);
        campaign.setPlannedSpendAmount(new BigDecimal("12500"));
        campaign.setApprovedSpendAmount(new BigDecimal("12000"));
        campaign.setSavingsTargetAmount(new BigDecimal("500"));
        campaign.setCurrencyCode("UZS");
        campaign.setPlannedStartDate(LocalDate.of(2026, 8, 5));
        campaign.setPlannedEndDate(LocalDate.of(2026, 8, 20));
        return campaignRepository.save(campaign);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
