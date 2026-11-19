package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.ProcurementPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.DemandPriority;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.ProcurementPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseOrderWorkspaceFoundationTest {
    @Autowired MockMvc mockMvc;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired DemandPlanItemRepository demandPlanItemRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    @Autowired ProcurementPlanRepository procurementPlanRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired com.enterprise.mm.repository.ProcurementCampaignRepository procurementCampaignRepository;
    @Autowired com.enterprise.mm.service.ProcurementPlanExecutionService planExecutionService;
    @Autowired com.enterprise.mm.repository.RFQRepository rfqRepository;
    @Autowired com.enterprise.mm.repository.ContractRepository contractRepository;

    private String tenantId;
    private Department department;

    @BeforeEach
    void setUp() {
        tenantId = "po-workspace-" + UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        department = department("OWN");
        employee("department-originator", department);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void contractOrderResolvesPlanWithoutRewritingPurchaseRequestLink() throws Exception {
        Supplier supplier = supplier();
        DemandPlan plan = demandPlan();
        PurchaseRequest request = purchaseRequest(plan);
        com.enterprise.mm.entity.RFQ rfq = new com.enterprise.mm.entity.RFQ();
        rfq.setCode("RFQ-" + suffix());
        rfq.setName("Source RFQ");
        rfq.setStatus("AWARDED");
        rfq.setPurchaseRequestId(request.getId());
        rfq = rfqRepository.saveAndFlush(rfq);
        com.enterprise.mm.entity.Contract contract = new com.enterprise.mm.entity.Contract();
        contract.setCode("CON-" + suffix());
        contract.setName("Source contract");
        contract.setStatus("ACTIVE");
        contract.setSupplierId(supplier.getId());
        contract.setSourceRfqId(rfq.getId());
        contract = contractRepository.saveAndFlush(contract);
        PurchaseOrder order = purchaseOrder(request, supplier);
        order.setPurchaseRequestId(null);
        order.setContractId(contract.getId());
        purchaseOrderRepository.saveAndFlush(order);

        mockMvc.perform(get("/api/purchase-orders/workspace")
                .with(user("admin").roles("ADMIN")).header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].demandPlanId").value(plan.getId().toString()));
        mockMvc.perform(get("/api/purchase-orders/{id}/workspace", order.getId())
                .with(user("admin").roles("ADMIN")).header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.header.demandPlanId").value(plan.getId().toString()));
        org.assertj.core.api.Assertions.assertThat(
            purchaseOrderRepository.findById(order.getId()).orElseThrow().getPurchaseRequestId()).isNull();
    }

    @Test
    void procurementPlanExecutionMatchesOrdersAndCountsOnlyAcceptedReceipts() throws Exception {
        int year = Year.now().getValue();
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setCode("DPC-EXEC-" + suffix());
        cycle.setName("Execution test cycle");
        cycle.setStatus("ACTIVE");
        cycle.setPlanYear(year);
        cycle.setPeriodType("YEAR");
        cycle.setPeriodStart(LocalDate.of(year, 1, 1));
        cycle.setPeriodEnd(LocalDate.of(year, 12, 31));
        cycle.setCurrencyCode("UZS");
        cycle = demandPlanningCycleRepository.saveAndFlush(cycle);

        DemandPlan demandPlan = demandPlan();
        demandPlan.setPlanningCycle(cycle);
        demandPlan = demandPlanRepository.saveAndFlush(demandPlan);
        PurchaseRequest request = purchaseRequest(demandPlan);
        Supplier supplier = supplier();
        PurchaseOrder order = purchaseOrder(request, supplier);
        RFQ sourceRfq = new RFQ();
        sourceRfq.setCode("RFQ-EXEC-" + suffix());
        sourceRfq.setName("Execution test RFQ");
        sourceRfq.setStatus("AWARDED");
        sourceRfq.setPurchaseRequestId(request.getId());
        sourceRfq = rfqRepository.saveAndFlush(sourceRfq);
        Contract contract = new Contract();
        contract.setCode("CON-EXEC-" + suffix());
        contract.setName("Execution test contract");
        contract.setStatus("ACTIVE");
        contract.setSupplierId(supplier.getId());
        contract.setSourceRfqId(sourceRfq.getId());
        contract = contractRepository.saveAndFlush(contract);
        order.setPurchaseRequestId(null);
        order.setContractId(contract.getId());
        order = purchaseOrderRepository.saveAndFlush(order);
        Material material = material();
        PurchaseOrderItem line = purchaseOrderItem(order, material);
        line.setQuantity(new BigDecimal("10"));
        line.setUnitPrice(new BigDecimal("10"));
        line.setReceivedQuantity(new BigDecimal("5"));
        line.setAcceptedQuantity(new BigDecimal("4"));
        purchaseOrderItemRepository.saveAndFlush(line);

        ProcurementPlan procurementPlan = new ProcurementPlan();
        procurementPlan.setCode("PLAN-EXEC-" + suffix());
        procurementPlan.setName("Execution test plan");
        procurementPlan.setStatus("ACTIVE");
        procurementPlan.setPlanNumber(procurementPlan.getCode());
        procurementPlan.setPlanYear(year);
        procurementPlan.setPeriodType("YEAR");
        procurementPlan.setDemandPlanningCycleId(cycle.getId());
        procurementPlan.setPlannedSpendAmount(new BigDecimal("500"));
        procurementPlan.setApprovedBudgetAmount(new BigDecimal("500"));
        procurementPlan.setCurrencyCode("UZS");
        procurementPlan.setPlanStartDate(LocalDate.of(year, 1, 1));
        procurementPlan.setPlanEndDate(LocalDate.of(year, 12, 31));
        procurementPlan = procurementPlanRepository.saveAndFlush(procurementPlan);

        var actual = planExecutionService.get(procurementPlan.getId());
        assertThat(actual.matchedOrderCount()).isEqualTo(1);
        assertThat(actual.matchedLineCount()).isEqualTo(1);
        assertThat(actual.orderedAmount()).isEqualByComparingTo("100.0000");
        assertThat(actual.acceptedAmount()).isEqualByComparingTo("40.0000");
        assertThat(actual.openOrderAmount()).isEqualByComparingTo("60.0000");
        assertThat(actual.remainingBudgetAmount()).isEqualByComparingTo("400.0000");
        assertThat(actual.executionPercent()).isEqualByComparingTo("8.00");
        assertThat(actual.lines().get(0).acceptedQuantity()).isEqualByComparingTo("4");

        mockMvc.perform(get("/api/procurement/plans/{id}/execution", procurementPlan.getId())
                .with(user("admin").roles("ADMIN")).header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderedAmount").value(100.0))
            .andExpect(jsonPath("$.data.acceptedAmount").value(40.0))
            .andExpect(jsonPath("$.data.executionPercent").value(8.0))
            .andExpect(jsonPath("$.data.lines[0].acceptedQuantity").value(4.0));

        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        ProcurementPlan noCycle = new ProcurementPlan();
        noCycle.setCode("PLAN-NOCYCLE-" + suffix());
        noCycle.setName("Plan without cycle");
        noCycle.setStatus("ACTIVE");
        noCycle.setPlanNumber(noCycle.getCode());
        noCycle.setPlanYear(year);
        noCycle.setPeriodType("YEAR");
        noCycle.setCurrencyCode("UZS");
        noCycle = procurementPlanRepository.saveAndFlush(noCycle);
        var unlinked = planExecutionService.get(noCycle.getId());
        assertThat(unlinked.orderedAmount()).isEqualByComparingTo("0");
        assertThat(unlinked.warnings()).isNotEmpty();

        var campaign = new com.enterprise.mm.entity.ProcurementCampaign();
        campaign.setCode("CAMPAIGN-EXEC-" + suffix());
        campaign.setName("Linked procurement campaign");
        campaign.setStatus("ACTIVE");
        campaign.setProcurementPlanId(noCycle.getId());
        campaign.setPurchaseRequestId(request.getId());
        procurementCampaignRepository.saveAndFlush(campaign);
        PurchaseRequest unrelated = purchaseRequest(demandPlan);
        PurchaseOrder unrelatedOrder = purchaseOrder(unrelated, supplier);
        PurchaseOrderItem unrelatedLine = purchaseOrderItem(unrelatedOrder, material);
        unrelatedLine.setAcceptedQuantity(BigDecimal.TEN);
        purchaseOrderItemRepository.saveAndFlush(unrelatedLine);
        var linked = planExecutionService.get(noCycle.getId());
        assertThat(linked.matchedOrderCount()).isEqualTo(1);
        assertThat(linked.orderedAmount()).isEqualByComparingTo("100");
        assertThat(linked.acceptedAmount()).isEqualByComparingTo("40");
    }

    @Test
    void workspaceListsPurchaseOrderWithLineageAndAllocations() throws Exception {
        Material material = material();
        Supplier supplier = supplier();
        DemandPlan demandPlan = demandPlan();
        PurchaseRequest purchaseRequest = purchaseRequest(demandPlan);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        DemandPlanItem demandPlanItem = demandPlanItem(demandPlan, material, purchaseRequestItem);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        sourceAllocation(purchaseOrderItem, purchaseRequestItem, "10");

        mockMvc.perform(get("/api/purchase-orders/workspace")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].purchaseRequestId").value(purchaseRequest.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].demandPlanId").value(demandPlan.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].supplierId").value(supplier.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].currentProcessStage").exists());

        mockMvc.perform(get("/api/purchase-orders/{id}/workspace", purchaseOrder.getId())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.header.purchaseRequestId").value(purchaseRequest.getId().toString()))
            .andExpect(jsonPath("$.data.header.demandPlanId").value(demandPlan.getId().toString()))
            .andExpect(jsonPath("$.data.items[0].sourceAllocationAvailable").value(true))
            .andExpect(jsonPath("$.data.items[0].sourceAllocations[0].demandPlanItemId").value(demandPlanItem.getId().toString()))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("PURCHASE_REQUEST")))
            .andExpect(jsonPath("$.data.relatedDocuments[*].documentType", hasItem("DEMAND_PLAN")))
            .andExpect(jsonPath("$.data.stages[*].key", hasItem("PURCHASE_ORDER")))
            .andExpect(jsonPath("$.data.stages[?(@.key == 'INBOUND_OR_EXECUTION')].current", hasItem(true)))
            .andExpect(jsonPath("$.data.availableActions.primary.key").doesNotExist())
            .andExpect(jsonPath("$.data.availableActions.secondary[0].key").value("OPEN_GOODS_RECEIPT"))
            .andExpect(jsonPath("$.data.availableActions.secondary[0].targetDocument.moduleKey").value("goods-receipt"))
            .andExpect(jsonPath("$.data.availableActions.secondary[0].targetDocument.documentId").value(purchaseOrder.getId().toString()))
            .andExpect(jsonPath("$.data.availableActions.secondary[*].key", not(hasItem("OPEN_PROCUREMENT_COMMAND"))));
    }

    @Test
    void workspaceSummaryAggregatesPurchaseOrdersWithSameFilters() throws Exception {
        Material material = material();
        Supplier supplier = supplier();
        DemandPlan demandPlan = demandPlan();
        PurchaseRequest purchaseRequest = purchaseRequest(demandPlan);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        demandPlanItem(demandPlan, material, purchaseRequestItem);
        PurchaseOrder firstOrder = purchaseOrder(purchaseRequest, supplier);
        PurchaseOrder secondOrder = purchaseOrder(purchaseRequest, supplier);
        PurchaseOrder draftOrder = purchaseOrder(purchaseRequest, supplier);
        draftOrder.setStatus("DRAFT");
        purchaseOrderRepository.save(draftOrder);
        PurchaseOrderItem firstItem = purchaseOrderItem(firstOrder, material);
        PurchaseOrderItem secondItem = purchaseOrderItem(secondOrder, material);
        purchaseOrderItem(draftOrder, material);
        sourceAllocation(firstItem, purchaseRequestItem, "10");
        sourceAllocation(secondItem, purchaseRequestItem, "10");

        mockMvc.perform(get("/api/purchase-orders/workspace/summary")
                .param("supplierId", supplier.getId().toString())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total.count").value(3))
            .andExpect(jsonPath("$.data.total.plannedAmount").value(300))
            .andExpect(jsonPath("$.data.total.currencyCode").value("UZS"))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].count", hasItem(2)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].plannedAmount", hasItem(200.0)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].itemType", hasItem("MATERIAL")))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].materialId", hasItem(material.getId().toString())))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].orderedQuantity", hasItem(20.0)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].plannedAmount", hasItem(200.0)))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].orderCount", hasItem(2)));

        mockMvc.perform(get("/api/purchase-orders/workspace/summary")
                .param("supplierId", supplier.getId().toString())
                .param("status", "DRAFT")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total.count").value(1))
            .andExpect(jsonPath("$.data.rows[0].status").value("DRAFT"));
    }

    @ParameterizedTest
    @CsvSource({
        "кг, AMM-Reference61-imported-code, кг",
        "pcs, EA, pcs",
        "L, Litre, L",
        "м, , м",
        "' ', legacy-unit, legacy-unit",
        ", , UOM-CODE"
    })
    void workspaceSummaryUsesMaterialShortNameBeforeStoredUnit(
        String shortName, String storedUnit, String expectedUnit
    ) throws Exception {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode("UOM-CODE");
        unit.setName("Unit name");
        unit.setMeasurementNameShort(shortName);
        unit = unitOfMeasureRepository.save(unit);
        Material material = material();
        material.setUnitOfMeasureId(unit.getId());
        materialRepository.save(material);
        Supplier supplier = supplier();
        PurchaseOrder order = purchaseOrder(purchaseRequest(demandPlan()), supplier);
        PurchaseOrderItem item = purchaseOrderItem(order, material);
        item.setUnit(storedUnit);
        purchaseOrderItemRepository.save(item);

        mockMvc.perform(get("/api/purchase-orders/workspace/summary")
                .param("supplierId", supplier.getId().toString())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows[0].items[0].unit").value(expectedUnit));
    }

    @Test
    void workspaceSummaryReturnsLegacyWorkLinesAsServices() throws Exception {
        Material material = material();
        Supplier supplier = supplier();
        DemandPlan demandPlan = demandPlan();
        PurchaseRequest purchaseRequest = purchaseRequest(demandPlan);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        PurchaseOrderItem workItem = purchaseOrderItem(purchaseOrder, material);
        workItem.setLineType(ProcurementLineType.WORK);
        workItem.setMaterialId(null);
        workItem.setServiceDescription("Legacy service line");
        workItem.setUnit("hour");
        workItem.setQuantity(new BigDecimal("4"));
        workItem.setUnitPrice(new BigDecimal("25"));
        purchaseOrderItemRepository.save(workItem);

        mockMvc.perform(get("/api/purchase-orders/workspace/summary")
                .param("supplierId", supplier.getId().toString())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].itemType", hasItem("SERVICE")))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].serviceId", hasItem(workItem.getId().toString())))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].serviceName", hasItem("Legacy service line")))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].unit", hasItem("hour")))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].orderCount", hasItem(1)));

        mockMvc.perform(get("/api/purchase-orders/workspace/summary")
                .param("supplierId", supplier.getId().toString())
                .param("materialId", workItem.getId().toString())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total.count").value(1))
            .andExpect(jsonPath("$.data.rows[?(@.status == 'SENT')].items[0].serviceId", hasItem(workItem.getId().toString())));
    }

    @Test
    void workspaceMarksMissingAllocationAsUnavailable() throws Exception {
        Material material = material();
        Supplier supplier = supplier();
        DemandPlan demandPlan = demandPlan();
        PurchaseRequest purchaseRequest = purchaseRequest(demandPlan);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        demandPlanItem(demandPlan, material, purchaseRequestItem);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        purchaseOrderItem(purchaseOrder, material);

        mockMvc.perform(get("/api/purchase-orders/{id}/workspace", purchaseOrder.getId())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].sourceAllocationAvailable").value(false))
            .andExpect(jsonPath("$.data.items[0].coverageStatus").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.data.sourceWarnings[0].code").value("purchase_order.workspace.missing_source_allocation"));
    }

    @Test
    void workspaceExposesInvoicingStageWhenReceiptEvidenceExists() throws Exception {
        Material material = material();
        Supplier supplier = supplier();
        DemandPlan demandPlan = demandPlan();
        PurchaseRequest purchaseRequest = purchaseRequest(demandPlan);
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItem(purchaseRequest, material);
        demandPlanItem(demandPlan, material, purchaseRequestItem);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseRequest, supplier);
        purchaseOrder.setStatus("RECEIVED");
        purchaseOrderRepository.save(purchaseOrder);
        PurchaseOrderItem purchaseOrderItem = purchaseOrderItem(purchaseOrder, material);
        purchaseOrderItem.setReceivedQuantity(new BigDecimal("10"));
        purchaseOrderItem.setAcceptedQuantity(new BigDecimal("10"));
        purchaseOrderItemRepository.save(purchaseOrderItem);
        sourceAllocation(purchaseOrderItem, purchaseRequestItem, "10");

        mockMvc.perform(get("/api/purchase-orders/{id}/workspace", purchaseOrder.getId())
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.payablesSummary.evidenceAvailable").value(true))
            .andExpect(jsonPath("$.data.stages[*].key", hasItem("INVOICING")))
            .andExpect(jsonPath("$.data.stages[*].key", hasItem("PAYMENT")))
            .andExpect(jsonPath("$.data.sourceWarnings[*].code", hasItem("purchase_order.workspace.invoice_required")));
    }

    private Department department(String label) {
        Department entity = new Department();
        entity.setCode("DEP-" + label + "-" + suffix());
        entity.setName(label + " department");
        entity.setStatus("ACTIVE");
        entity.setDepartmentType("OPERATIONS");
        return departmentRepository.save(entity);
    }

    private Employee employee(String username, Department departmentEntity) {
        Employee employee = new Employee();
        employee.setCode("EMP-" + suffix());
        employee.setName(username);
        employee.setStatus("ACTIVE");
        employee.setPersonnelNumber(username);
        employee.setDepartment(departmentEntity);
        return employeeRepository.save(employee);
    }

    private Material material() {
        Material entity = new Material();
        entity.setCode("MAT-" + suffix());
        entity.setName("Workspace material");
        entity.setStatus("ACTIVE");
        return materialRepository.save(entity);
    }

    private Supplier supplier() {
        Supplier entity = new Supplier();
        entity.setCode("SUP-" + suffix());
        entity.setName("Workspace supplier");
        entity.setShortName("Workspace supplier");
        entity.setLegalName("Workspace supplier LLC");
        entity.setStatus("ACTIVE");
        return supplierRepository.save(entity);
    }

    private DemandPlan demandPlan() {
        DemandPlan plan = new DemandPlan();
        plan.setCode("DP-" + suffix());
        plan.setName("Workspace demand plan");
        plan.setStatus("ACTIVE");
        plan.setRequestType(RequestType.GOODS);
        plan.setPlanYear(Year.now().getValue());
        plan.setPeriodType("YEAR");
        plan.setPeriodStart(LocalDate.of(Year.now().getValue(), 1, 1));
        plan.setPeriodEnd(LocalDate.of(Year.now().getValue(), 12, 31));
        plan.setDepartment(department);
        plan.setOwnerUsername("department-originator");
        plan.setCurrencyCode("UZS");
        plan.setTotalPlannedAmount(BigDecimal.ZERO);
        plan.setTotalApprovedAmount(BigDecimal.ZERO);
        plan.setTotalReservedQuantity(BigDecimal.ZERO);
        return demandPlanRepository.save(plan);
    }

    private PurchaseRequest purchaseRequest(DemandPlan demandPlan) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-" + suffix());
        request.setName("Workspace purchase request");
        request.setStatus("APPROVED");
        request.setDepartmentId(department.getId());
        request.setDemandPlanId(demandPlan.getId());
        request.setRequestedBy("department-originator");
        request.setRequestType(RequestType.GOODS);
        request.setRequiredDate(LocalDate.now().plusDays(10));
        request.setTotalAmount(new BigDecimal("100"));
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private PurchaseRequestItem purchaseRequestItem(PurchaseRequest request, Material materialEntity) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-" + suffix());
        item.setName("Workspace purchase request item");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(request.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialEntity.getId());
        item.setQuantity(new BigDecimal("10"));
        item.setEstimatedPrice(BigDecimal.TEN);
        return purchaseRequestItemRepository.save(item);
    }

    private DemandPlanItem demandPlanItem(DemandPlan plan, Material materialEntity, PurchaseRequestItem purchaseRequestItem) {
        DemandPlanItem item = new DemandPlanItem();
        item.setCode("DPI-" + suffix());
        item.setName("Workspace demand item");
        item.setStatus("APPROVED");
        item.setDemandPlanId(plan.getId());
        item.setDepartmentId(department.getId());
        item.setMaterialId(materialEntity.getId());
        item.setDemandType(DemandType.PROJECT.name());
        item.setPriority(DemandPriority.NORMAL.name());
        item.setRequestedQuantity(new BigDecimal("10"));
        item.setApprovedQuantity(new BigDecimal("10"));
        item.setReservedQuantity(BigDecimal.ZERO);
        item.setEstimatedUnitPrice(BigDecimal.TEN);
        item.setEstimatedAmount(new BigDecimal("100"));
        item.setCurrencyCode("UZS");
        item.setRequiredDate(LocalDate.now().plusDays(10));
        item.setPurchaseRequestItemId(purchaseRequestItem.getId());
        return demandPlanItemRepository.save(item);
    }

    private PurchaseOrder purchaseOrder(PurchaseRequest request, Supplier supplierEntity) {
        PurchaseOrder order = new PurchaseOrder();
        order.setCode("PO-" + suffix());
        order.setName("Workspace purchase order");
        order.setStatus("SENT");
        order.setSupplierId(supplierEntity.getId());
        order.setPurchaseRequestId(request.getId());
        order.setRequestType(RequestType.GOODS);
        order.setOrderDate(LocalDate.now());
        order.setCurrencyCode("UZS");
        order.setTotalAmount(new BigDecimal("100"));
        return purchaseOrderRepository.save(order);
    }

    private PurchaseOrderItem purchaseOrderItem(PurchaseOrder order, Material materialEntity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("POI-" + suffix());
        item.setName("Workspace purchase order item");
        item.setStatus("ACTIVE");
        item.setPurchaseOrderId(order.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialEntity.getId());
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(BigDecimal.TEN);
        return purchaseOrderItemRepository.save(item);
    }

    private void sourceAllocation(PurchaseOrderItem purchaseOrderItem, PurchaseRequestItem purchaseRequestItem, String quantity) {
        PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
        allocation.setCode("POSRC-" + suffix());
        allocation.setName("Workspace source allocation");
        allocation.setStatus("ACTIVE");
        allocation.setPurchaseOrderItemId(purchaseOrderItem.getId());
        allocation.setPurchaseRequestItemId(purchaseRequestItem.getId());
        allocation.setOrderedQuantity(new BigDecimal(quantity));
        sourceAllocationRepository.save(allocation);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
