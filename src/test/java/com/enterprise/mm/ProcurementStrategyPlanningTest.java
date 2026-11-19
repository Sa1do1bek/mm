package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.DemandPlanningCycleResponse;
import com.enterprise.mm.payload.ProcurementCampaignResponse;
import com.enterprise.mm.payload.ProcurementCategoryStrategyResponse;
import com.enterprise.mm.payload.ProcurementPlanResponse;
import com.enterprise.mm.payload.ProcurementMethodDecisionCreateRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionResponse;
import com.enterprise.mm.payload.DemandPlanningCycleCreateRequest;
import com.enterprise.mm.payload.ProcurementCampaignCreateRequest;
import com.enterprise.mm.payload.ProcurementCategoryStrategyCreateRequest;
import com.enterprise.mm.payload.ProcurementPlanCreateRequest;
import com.enterprise.mm.service.DemandPlanningCycleService;
import com.enterprise.mm.service.ProcurementCampaignService;
import com.enterprise.mm.service.ProcurementCategoryStrategyService;
import com.enterprise.mm.service.ProcurementPlanService;
import com.enterprise.mm.service.ProcurementMethodDecisionService;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.CommissionMemberRole;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.enums.ProcurementCampaignStatus;
import com.enterprise.mm.enums.ProcurementMethodCapabilityState;
import com.enterprise.mm.enums.ProcurementPlanStatus;
import com.enterprise.mm.enums.ProcurementStrategyStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.entity.ConsolidatedDemand;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.CommissionMember;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.ProcurementMethodCapability;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.repository.ConsolidatedDemandRepository;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.ProcurementMethodCapabilityRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ProcurementMethodDecisionApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "procurement-strategist", roles = "PROCUREMENT_MANAGER")
class ProcurementStrategyPlanningTest {
    @Autowired ProcurementCategoryStrategyService strategyService;
    @Autowired ProcurementPlanService procurementPlanService;
    @Autowired ProcurementMethodDecisionService methodDecisionService;
    @Autowired ProcurementCampaignService campaignService;
    @Autowired DemandPlanningCycleService demandPlanningCycleService;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired ConsolidatedDemandRepository consolidatedDemandRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired ProcurementProcedureRepository procedureRepository;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired ApprovalEngineService approvalEngineService;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired ProcurementMethodCapabilityRepository procurementMethodCapabilityRepository;
    @Autowired CommissionRepository commissionRepository;
    @Autowired CommissionMemberRepository commissionMemberRepository;

    @Test
    void categoryStrategyCreatesAnnualProcurementPlanCampaignAndRfq() {
        authenticate("strategy-owner");
        int nextYear = Year.now().plusYears(1).getValue();
        MaterialGroup materialGroup = materialGroup("MG-STR-" + suffix());
        Material material = material("MAT-STR-" + suffix(), materialGroup.getId());

        DemandPlanningCycleCreateRequest cycleRequest = new DemandPlanningCycleCreateRequest();
        cycleRequest.setName("Цикл потребности для стратегии " + nextYear);
        cycleRequest.setPlanYear(nextYear);
        cycleRequest.setPeriodType(DemandPeriodType.YEAR.name());
        DemandPlanningCycleResponse cycle = demandPlanningCycleService.create(cycleRequest);
        var cycleEntity = demandPlanningCycleRepository.findById(cycle.getId()).orElseThrow();
        cycleEntity.setStatus(DemandPlanningCycleStatus.BUDGET_APPROVED.name());
        demandPlanningCycleRepository.saveAndFlush(cycleEntity);

        DemandPlan demandPlan = new DemandPlan();
        demandPlan.setCode("DP-STR-" + suffix());
        demandPlan.setName("Demand plan for procurement strategy");
        demandPlan.setStatus("ACTIVE");
        demandPlan.setPlanningCycle(demandPlanningCycleRepository.getReferenceById(cycle.getId()));
        demandPlan.setPlanYear(nextYear);
        demandPlan.setPeriodType(DemandPeriodType.YEAR.name());
        demandPlan.setPeriodStart(LocalDate.of(nextYear, 1, 1));
        demandPlan.setPeriodEnd(LocalDate.of(nextYear, 12, 31));
        demandPlan = demandPlanRepository.save(demandPlan);

        ConsolidatedDemand consolidatedDemand = new ConsolidatedDemand();
        consolidatedDemand.setCode("CD-STR-" + suffix());
        consolidatedDemand.setName("Consolidated strategic demand");
        consolidatedDemand.setStatus("READY_FOR_PR");
        consolidatedDemand.setDemandPlanId(demandPlan.getId());
        consolidatedDemand.setMaterialId(material.getId());
        consolidatedDemand.setPeriodStart(LocalDate.of(nextYear, 1, 1));
        consolidatedDemand.setPeriodEnd(LocalDate.of(nextYear, 12, 31));
        consolidatedDemand.setHighestPriority("HIGH");
        consolidatedDemand.setTotalRequestedQuantity(BigDecimal.valueOf(100));
        consolidatedDemand.setTotalApprovedQuantity(BigDecimal.valueOf(100));
        consolidatedDemand.setTotalEstimatedAmount(BigDecimal.valueOf(15_000));
        consolidatedDemand.setSourceItemCount(3);
        consolidatedDemandRepository.save(consolidatedDemand);

        ProcurementCategoryStrategyCreateRequest strategyRequest = new ProcurementCategoryStrategyCreateRequest();
        strategyRequest.setName("Стратегия закупки металлопроката");
        strategyRequest.setMaterialGroupId(materialGroup.getId());
        strategyRequest.setStrategyYear(nextYear);
        strategyRequest.setStrategyType("COST_REDUCTION");
        strategyRequest.setSourcingApproach("COMPETITIVE_TENDER");
        strategyRequest.setRiskLevel("MEDIUM");
        strategyRequest.setTargetSavingsPercent(BigDecimal.valueOf(5));
        strategyRequest.setPlannedSpendAmount(BigDecimal.valueOf(20_000));
        strategyRequest.setApprovedSpendLimit(BigDecimal.valueOf(18_000));
        strategyRequest.setCurrencyCode("UZS");
        ProcurementCategoryStrategyResponse strategy = strategyService.create(strategyRequest);
        strategy = strategyService.submit(strategy.getId(), "strategy ready");
        authenticate("strategy-approver");
        strategy = strategyService.approve(strategy.getId(), "approved");
        strategy = strategyService.activate(strategy.getId());
        assertThat(strategy.getStatus()).isEqualTo(ProcurementStrategyStatus.ACTIVE.name());

        ProcurementPlanCreateRequest planRequest = new ProcurementPlanCreateRequest();
        planRequest.setName("Годовой план закупок по категории");
        planRequest.setDemandPlanningCycleId(cycle.getId());
        planRequest.setCategoryStrategyId(strategy.getId());
        planRequest.setCurrencyCode("UZS");
        ProcurementPlanResponse plan = procurementPlanService.create(planRequest);

        var consolidation = procurementPlanService.consolidate(plan.getId());
        assertThat(consolidation.demandCount()).isEqualTo(1);
        assertThat(consolidation.plannedSpendAmount()).isEqualByComparingTo("15000");

        plan = procurementPlanService.get(plan.getId());
        assertThat(plan.getExpectedSavingsAmount()).isEqualByComparingTo("750");
        authenticate("budget-submitter");
        assertThat(procurementPlanService.submitBudget(plan.getId(), "budget review").getStatus()).isEqualTo(ProcurementPlanStatus.BUDGET_REVIEW.name());
        authenticate("budget-approver");
        plan = procurementPlanService.approve(plan.getId(), "budget approved");
        assertThat(plan.getApprovedBudgetAmount()).isEqualByComparingTo("18000");
        plan = procurementPlanService.activate(plan.getId());
        assertThat(plan.getStatus()).isEqualTo(ProcurementPlanStatus.ACTIVE.name());

        Attachment marketResearch = evidence("MARKET-" + suffix(), plan.getId(), "MARKET_RESEARCH");
        Attachment technicalSpecification = evidence("TECH-" + suffix(), plan.getId(), "TECHNICAL_SPECIFICATION");
        PurchaseRequest purchaseRequest = approvedPurchaseRequest(demandPlan, material);
        ProcurementMethodDecisionCreateRequest methodRequest = new ProcurementMethodDecisionCreateRequest();
        methodRequest.setName("Способ закупки металлопроката");
        methodRequest.setPurchaseRequestId(purchaseRequest.getId());
        methodRequest.setSelectedMethod("OPEN_COMPETITION");
        methodRequest.setJustification("На рынке доступно несколько квалифицированных поставщиков");
        methodRequest.setEstimatedAmount(BigDecimal.valueOf(18_000));
        methodRequest.setCurrencyCode("UZS");
        methodRequest.setPotentialSupplierCount(4);
        methodRequest.setCommissionId(processCommissionId("OPEN_COMPETITION", suffix()));
        methodRequest.setMarketResearchAttachmentId(marketResearch.getId());
        methodRequest.setTechnicalSpecificationAttachmentId(technicalSpecification.getId());
        ensureMethodCapability("OPEN_COMPETITION");
        ensureMethodDecisionApprovalFlow();
        ProcurementMethodDecisionResponse methodDecision = methodDecisionService.create(methodRequest);
        methodDecision = methodDecisionService.submit(methodDecision.getId());
        authenticate("method-approver");
        approveAll(ProcurementMethodDecisionApprovalAdapter.MODULE_KEY, methodDecision.getId());
        methodDecision = methodDecisionService.get(methodDecision.getId());
        var procedure = procedureRepository.findByMethodDecisionIdAndIsDeletedFalse(methodDecision.getId()).orElseThrow();

        ProcurementCampaignCreateRequest campaignRequest = new ProcurementCampaignCreateRequest();
        campaignRequest.setName("Годовой тендер по металлопрокату");
        campaignRequest.setProcurementProcedureId(procedure.getId());
        campaignRequest.setTargetSupplierCount(4);
        campaignRequest.setSavingsTargetAmount(BigDecimal.valueOf(750));
        ProcurementCampaignResponse campaign = campaignService.create(campaignRequest);
        campaign = campaignService.plan(campaign.getId());
        campaign = campaignService.launch(campaign.getId());
        campaign = campaignService.createRfq(campaign.getId(), null);

        assertThat(campaign.getStatus()).isEqualTo(ProcurementCampaignStatus.RFQ_CREATED.name());
        assertThat(campaign.getRfqId()).isNotNull();
        var rfq = rfqRepository.findAnyById(campaign.getRfqId()).orElseThrow();
        assertThat(rfq.getTenderProcedureType()).isEqualTo("OPEN_COMPETITION");
        assertThat(rfq.getSelectionRule()).isEqualTo("BEST_SCORE");
        assertThat(rfq.getMinQuotationsRequired()).isEqualTo(4);

        assertThat(campaignService.complete(campaign.getId()).getStatus()).isEqualTo(ProcurementCampaignStatus.COMPLETED.name());
        assertThat(procurementPlanService.get(plan.getId()).getCampaignCount()).isZero();
        assertThat(integrationEventRepository.findAll())
            .anyMatch(event -> "PROCUREMENT.CATEGORY_STRATEGY_APPROVED".equals(event.getEventType()))
            .anyMatch(event -> "BUDGETING.PROCUREMENT_PLAN_SUBMITTED".equals(event.getEventType()))
            .anyMatch(event -> "PROCUREMENT.CAMPAIGN_RFQ_CREATED".equals(event.getEventType()));
    }

    private MaterialGroup materialGroup(String code) {
        MaterialGroup group = new MaterialGroup();
        group.setCode(code);
        group.setName(code);
        group.setStatus("ACTIVE");
        return materialGroupRepository.save(group);
    }

    private Material material(String code, UUID materialGroupId) {
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(materialGroupId);
        return materialRepository.save(material);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID processCommissionId(String method, String suffix) {
        CommissionType type = switch (method) {
            case "OPEN_TENDER", "CLOSED_TENDER" -> CommissionType.TENDER;
            case "OPEN_COMPETITION", "CLOSED_COMPETITION" -> CommissionType.COMPETITION;
            default -> null;
        };
        if (type == null) {
            return null;
        }
        Commission commission = new Commission();
        commission.setCode("COM-" + type.name() + "-" + suffix);
        commission.setName(type.name() + " commission " + suffix);
        commission.setStatus("ACTIVE");
        commission.setDescription("Process commission for " + method);
        commission.setType(type);
        commission.setActive(true);
        commission.setValidFrom(LocalDate.now().minusDays(1));
        commission.setValidTo(LocalDate.now().plusYears(1));
        commission = commissionRepository.save(commission);
        for (int i = 1; i <= 3; i++) {
            CommissionMember member = new CommissionMember();
            member.setCode("COM-MEMBER-" + suffix + "-" + i);
            member.setName("Commission member " + i);
            member.setStatus("ACTIVE");
            member.setCommissionId(commission.getId());
            member.setMemberRole(i == 1 ? CommissionMemberRole.CHAIRMAN : i == 2 ? CommissionMemberRole.SECRETARY : CommissionMemberRole.MEMBER);
            member.setMemberName("Commission member " + i);
            member.setActive(true);
            commissionMemberRepository.save(member);
        }
        return commission.getId();
    }

    private Attachment evidence(String code, UUID entityId, String type) {
        Attachment attachment = new Attachment();
        attachment.setCode(code);
        attachment.setName(type + " evidence");
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("ProcurementPlan");
        attachment.setEntityId(entityId);
        attachment.setAttachmentType(type);
        attachment.setOriginalFileName(code + ".pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(1L);
        attachment.setChecksumSha256("0".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("test/" + code + ".pdf");
        attachment.setUploadedAt(LocalDateTime.now());
        return attachmentRepository.save(attachment);
    }

    private PurchaseRequest approvedPurchaseRequest(DemandPlan demandPlan, Material material) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-STR-" + suffix());
        request.setName("Approved strategy purchase request");
        request.setStatus(PurchaseRequestStatus.APPROVED.name());
        request.setRequestType(RequestType.GOODS);
        request.setDemandPlanId(demandPlan.getId());
        request.setRequiredDate(LocalDate.now().plusDays(30));
        request.setCurrencyCode("UZS");
        request.setTotalAmount(BigDecimal.valueOf(18_000));
        request = purchaseRequestRepository.saveAndFlush(request);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-STR-" + suffix());
        item.setName("Strategy PR item");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(request.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.valueOf(100));
        item.setEstimatedPrice(BigDecimal.valueOf(180));
        item.setUnit("EA");
        purchaseRequestItemRepository.saveAndFlush(item);
        return request;
    }

    private void ensureMethodCapability(String method) {
        ProcurementMethodCapability capability = procurementMethodCapabilityRepository
            .findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse("default", com.enterprise.mm.common.CompanyContext.currentCompany(), method)
            .orElseGet(ProcurementMethodCapability::new);
        capability.setMethodCode(method);
        capability.setCode("TEST-CAP-" + method + "-" + suffix());
        capability.setName("Test capability " + method);
        capability.setState(ProcurementMethodCapabilityState.ENABLED);
        capability.setEffectiveFrom(LocalDate.now().minusDays(1));
        capability.setEffectiveTo(LocalDate.now().plusYears(1));
        capability.setCurrencyCode("UZS");
        capability.setAllowedRequestTypes(java.util.Set.of("GOODS"));
        capability.setApprovalRequired(true);
        capability.setConfigurationVersion(capability.getConfigurationVersion() == null ? 1 : capability.getConfigurationVersion());
        capability.setChangeReason("Strategy planning test capability seed");
        capability.setConfiguredAt(LocalDateTime.now());
        capability.setConfiguredBy("test");
        procurementMethodCapabilityRepository.saveAndFlush(capability);
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username,
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_PROCUREMENT_MANAGER"))
        ));
    }

    private void ensureMethodDecisionApprovalFlow() {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setCode("PMD-STRATEGY-" + suffix());
        flow.setName("Strategy method decision approval");
        flow.setStatus("ACTIVE");
        flow.setModuleKey(ProcurementMethodDecisionApprovalAdapter.MODULE_KEY);
        flow.setActive(true);
        flow = approvalFlowRepository.save(flow);

        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setApprovalFlowId(flow.getId());
        step.setCode("PMD-STRATEGY-STEP-" + suffix());
        step.setName("Approve procurement method");
        step.setStatus("ACTIVE");
        step.setStepOrder(1);
        step.setAssigneeType(ApprovalAssigneeType.ROLE);
        step.setAssigneeRole("PROCUREMENT_MANAGER");
        step.setCanReturn(true);
        approvalFlowStepRepository.save(step);
    }

    private void approveAll(String moduleKey, UUID documentId) {
        var instance = approvalEngineService.currentInstance(moduleKey, documentId);
        while (instance.approvalStatus() != ApprovalInstanceStatus.APPROVED) {
            var task = instance.tasks().stream()
                .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
                .findFirst()
                .orElseThrow();
            instance = approvalEngineService.approveTask(task.id(), "approved");
        }
    }
}
