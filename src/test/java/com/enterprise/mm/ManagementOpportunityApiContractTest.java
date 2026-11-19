package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.entity.ManagementOpportunityCalculationRun;
import com.enterprise.mm.entity.ManagementOpportunityEvidence;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.ManagementOpportunityConfidence;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityEvidenceSourceEntityType;
import com.enterprise.mm.enums.ManagementOpportunityRunStatus;
import com.enterprise.mm.enums.ManagementOpportunityStatus;
import com.enterprise.mm.enums.ManagementOpportunityType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.ManagementOpportunityCalculationRunRepository;
import com.enterprise.mm.repository.ManagementOpportunityEvidenceRepository;
import com.enterprise.mm.repository.ManagementOpportunityRepository;
import com.enterprise.mm.service.approval.ManagementOpportunityApprovalAdapter;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ManagementOpportunityApiContractTest {
    @Autowired MockMvc mockMvc;
    @Autowired ManagementOpportunityCalculationRunRepository runRepository;
    @Autowired ManagementOpportunityRepository opportunityRepository;
    @Autowired ManagementOpportunityEvidenceRepository evidenceRepository;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired ApprovalInstanceRepository approvalInstanceRepository;
    @Autowired ApprovalTaskRepository approvalTaskRepository;

    @Test
    void runStartReturnsCompletedRunShapeForAuthorizedController() throws Exception {
        String payload = """
            {
              "asOfDate": "%s",
              "types": ["PROCUREMENT_PRICE_VARIANCE"],
              "targetCurrencyCode": "UZS"
            }
            """.formatted(LocalDate.of(2026, 7, 12));

        mockMvc.perform(post("/api/management-opportunities/runs")
                .with(user("controller").roles("FINANCE_CONTROLLER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.asOfDate").value("2026-07-12"))
            .andExpect(jsonPath("$.data.targetCurrencyCode").value("UZS"))
            .andExpect(jsonPath("$.data.candidateCount").isNumber())
            .andExpect(jsonPath("$.data.skippedCount").exists());
    }

    @Test
    void runHistoryDetailAndCasesAreReadableForFrontendCalculationPage() throws Exception {
        ManagementOpportunity opportunity = completeDraftOpportunity();
        UUID runId = opportunity.getCalculationRunId();

        mockMvc.perform(get("/api/management-opportunities/runs")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("requestedType", ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE.name())
                .param("targetCurrencyCode", "UZS")
                .param("hasSkipped", "false")
                .param("page", "0")
                .param("size", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[?(@.id == '%s')]".formatted(runId)).exists())
            .andExpect(jsonPath("$.data.content[?(@.id == '%s')].requestedTypes".formatted(runId)).exists())
            .andExpect(jsonPath("$.data.content[?(@.id == '%s')].targetCurrencyCode".formatted(runId)).exists());

        mockMvc.perform(get("/api/management-opportunities/runs/{runId}", runId)
                .with(user("auditor").roles("AUDITOR"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(runId.toString()))
            .andExpect(jsonPath("$.data.status").value(ManagementOpportunityRunStatus.COMPLETED.name()))
            .andExpect(jsonPath("$.data.requestedTypes").value(ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE.name()));

        mockMvc.perform(get("/api/management-opportunities/runs/{runId}/cases", runId)
                .with(user("viewer").roles("VIEWER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[?(@.id == '%s')]".formatted(opportunity.getId())).exists())
            .andExpect(jsonPath("$.data.content[?(@.id == '%s')].calculationRunId".formatted(opportunity.getId())).exists())
            .andExpect(jsonPath("$.data.content[?(@.id == '%s')].formula.expression".formatted(opportunity.getId()))
                .value(hasItem("max(actualUnitPrice - approvedMarketUnitPrice, 0) * acceptedQuantity")))
            .andExpect(jsonPath("$.data.content[?(@.id == '%s')].formula.periodBasis".formatted(opportunity.getId()))
                .value(hasItem("Snapshot at the run asOfDate; no weekly proration is applied.")));

    }

    @Test
    void viewerCannotStartRunButCanReadSummaryAndBreakdown() throws Exception {
        String payload = """
            {"asOfDate":"2026-07-12","types":["PROCUREMENT_PRICE_VARIANCE"]}
            """;

        mockMvc.perform(post("/api/management-opportunities/runs")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/management-opportunities/summary")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.approvedCaseCount").exists());

        mockMvc.perform(get("/api/management-opportunities/breakdown")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.byType").isArray())
            .andExpect(jsonPath("$.data.cases").isArray());

        mockMvc.perform(get("/api/management-opportunities/summary")
                .with(user("executive").roles("EXECUTIVE"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void summaryAndBreakdownAcceptTheSameCompleteFilterContractAsCases() throws Exception {
        UUID sourceId = UUID.randomUUID();

        mockMvc.perform(get("/api/management-opportunities/summary")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("type", ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE.name())
                .param("status", ManagementOpportunityStatus.DRAFT.name())
                .param("completeness", ManagementOpportunityDataCompleteness.DATA_INCOMPLETE.name())
                .param("sourceAggregateType", "PurchaseOrder")
                .param("sourceAggregateId", sourceId.toString())
                .param("supplierId", UUID.randomUUID().toString())
                .param("materialId", UUID.randomUUID().toString())
                .param("warehouseId", UUID.randomUUID().toString())
                .param("confidence", ManagementOpportunityConfidence.LOW.name())
                .param("assignedRole", "PROCUREMENT_MANAGER")
                .param("asOfDateFrom", "2026-07-01")
                .param("asOfDateTo", "2026-07-15")
                .param("includeArchived", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.generatedAt").exists())
            .andExpect(jsonPath("$.data.approvedCurrencyBuckets").isArray())
            .andExpect(jsonPath("$.data.detectedSourceCurrencyBuckets").isArray())
            .andExpect(jsonPath("$.data.waterfallByCurrency").isArray())
            .andExpect(jsonPath("$.data.aging").isArray())
            .andExpect(jsonPath("$.data.conversion.aggregationBasis").value("STORED_CASE_NORMALIZATION"));

        mockMvc.perform(get("/api/management-opportunities/breakdown")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("status", ManagementOpportunityStatus.DRAFT.name())
                .param("completeness", ManagementOpportunityDataCompleteness.DATA_INCOMPLETE.name())
                .param("sourceAggregateType", "PurchaseOrder")
                .param("sourceAggregateId", sourceId.toString())
                .param("assignedRole", "PROCUREMENT_MANAGER")
                .param("includeArchived", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.byType").isArray())
            .andExpect(jsonPath("$.data.byConfidence").isArray())
            .andExpect(jsonPath("$.data.cases").isArray());
    }

    @Test
    void policyValidationRejectsInvalidRates() throws Exception {
        String payload = """
            {
              "name": "Invalid management opportunity policy",
              "opportunityType": "EXCESS_CARRYING_COST",
              "effectiveFrom": "2026-07-12",
              "annualCarryingCostRate": 2.0
            }
            """;

        mockMvc.perform(post("/api/management-opportunity-policies")
                .with(user("controller").roles("FINANCE_CONTROLLER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/management-opportunity-policies")
                .with(user("accountant").roles("ACCOUNTANT"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitReviewCreatesUnifiedApprovalTaskAndApprovalFinalizesOpportunity() throws Exception {
        ensureManagementOpportunityApprovalFlow();
        ManagementOpportunity opportunity = completeDraftOpportunity();

        mockMvc.perform(post("/api/management-opportunities/{id}/submit-review", opportunity.getId())
                .with(user("source-owner").roles("PROCUREMENT_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("READY_FOR_REVIEW"));

        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.DEFAULT_TENANT,
                CompanyContext.DEFAULT_COMPANY_ID,
                ManagementOpportunityApprovalAdapter.MODULE_KEY,
                opportunity.getId()
            )
            .orElseThrow();
        List<ApprovalTask> tasks = approvalTaskRepository
            .findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(TenantContext.DEFAULT_TENANT, instance.getId());

        mockMvc.perform(get("/api/approval-tasks/my")
                .with(user("finance-controller").roles("FINANCE_CONTROLLER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[?(@.entityId == '%s')]".formatted(opportunity.getId())).exists());

        mockMvc.perform(post("/api/management-opportunities/{id}/approve", opportunity.getId())
                .with(user("finance-controller").roles("FINANCE_CONTROLLER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"approved for management reporting\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPROVED"));

        ManagementOpportunity approved = opportunityRepository.findByIdAndIsDeletedFalse(opportunity.getId()).orElseThrow();
        ApprovalTask approvedTask = approvalTaskRepository.findByIdAndIsDeletedFalse(tasks.getFirst().getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(ManagementOpportunityStatus.APPROVED.name());
        assertThat(approvedTask.getTaskStatus()).isEqualTo(ApprovalTaskStatus.APPROVED);
    }

    private ManagementOpportunity completeDraftOpportunity() {
        ManagementOpportunityCalculationRun run = new ManagementOpportunityCalculationRun();
        run.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        run.setCode("MO-RUN-API-" + UUID.randomUUID().toString().substring(0, 8));
        run.setName("API contract management opportunity run");
        run.setStatus(ManagementOpportunityRunStatus.COMPLETED.name());
        run.setAsOfDate(LocalDate.of(2026, 7, 12));
        run.setTargetCurrencyCode("UZS");
        run.setRequestedTypes(ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE.name());
        run.setStartedAt(LocalDateTime.now());
        run.setCompletedAt(LocalDateTime.now());
        ManagementOpportunityCalculationRun savedRun = runRepository.save(run);

        ManagementOpportunity opportunity = new ManagementOpportunity();
        opportunity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        opportunity.setCode("MO-API-" + UUID.randomUUID().toString().substring(0, 8));
        opportunity.setName("API contract complete management estimate");
        opportunity.setStatus(ManagementOpportunityStatus.DRAFT.name());
        opportunity.setOpportunityType(ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE);
        opportunity.setCalculationRunId(savedRun.getId());
        opportunity.setSourceAggregateType("PurchaseOrder");
        opportunity.setSourceAggregateId(UUID.randomUUID());
        opportunity.setSourceLineId(UUID.randomUUID());
        opportunity.setAsOfDate(LocalDate.of(2026, 7, 12));
        opportunity.setSourceCurrencyCode("UZS");
        opportunity.setNormalizedCurrencyCode("UZS");
        opportunity.setSourceAmount(new BigDecimal("125.0000"));
        opportunity.setNormalizedAmount(new BigDecimal("125.0000"));
        opportunity.setExchangeRate(BigDecimal.ONE);
        opportunity.setNormalizationQuality("SAME_CURRENCY");
        opportunity.setFormulaCode("API_CONTRACT_TEST");
        opportunity.setFormulaSnapshot("{\"formula\":\"test\"}");
        opportunity.setConfidence(ManagementOpportunityConfidence.HIGH);
        opportunity.setDataCompleteness(ManagementOpportunityDataCompleteness.COMPLETE);
        ManagementOpportunity savedOpportunity = opportunityRepository.save(opportunity);

        ManagementOpportunityEvidence evidence = new ManagementOpportunityEvidence();
        evidence.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        evidence.setCode("MO-EV-API-" + UUID.randomUUID().toString().substring(0, 8));
        evidence.setName("API contract evidence");
        evidence.setStatus("CAPTURED");
        evidence.setOpportunityId(savedOpportunity.getId());
        evidence.setEvidenceType("PO_ITEM");
        evidence.setSourceEntityType(ManagementOpportunityEvidenceSourceEntityType.PURCHASE_ORDER_ITEM);
        evidence.setSourceEntityId(savedOpportunity.getSourceLineId());
        evidence.setCapturedSnapshot("{\"acceptedQuantity\":1}");
        evidenceRepository.save(evidence);

        return savedOpportunity;
    }

    private void ensureManagementOpportunityApprovalFlow() {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        flow.setCode("MO-APPROVAL-API-" + UUID.randomUUID().toString().substring(0, 8));
        flow.setName("Management opportunity API approval");
        flow.setStatus("ACTIVE");
        flow.setModuleKey(ManagementOpportunityApprovalAdapter.MODULE_KEY);
        flow.setActive(true);
        ApprovalFlow savedFlow = approvalFlowRepository.save(flow);

        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        step.setCode("MO-APPROVAL-API-STEP-" + UUID.randomUUID().toString().substring(0, 8));
        step.setName("Finance controller review");
        step.setStatus("ACTIVE");
        step.setApprovalFlowId(savedFlow.getId());
        step.setStepOrder(1);
        step.setAssigneeType(ApprovalAssigneeType.ROLE);
        step.setAssigneeRole("FINANCE_CONTROLLER");
        step.setCanReturn(true);
        step.setCommentRequired(false);
        approvalFlowStepRepository.save(step);
    }
}
