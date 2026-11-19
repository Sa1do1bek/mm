package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.fulfillment.FulfillmentQuantities;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.GoodsReceipt;
import com.enterprise.mm.entity.GoodsReceiptEvidence;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.LaboratoryTest;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.QualityQuarantineLot;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.enums.QualityQuarantineStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.enums.SupplierPortalRole;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoLineRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceiptOutcome;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.BuyerCollaborationCommand;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.QualityControlDtos.LaboratoryResultRequest;
import com.enterprise.mm.payload.QualityControlDtos.QualityDecisionRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnDraftRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnLineRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCommitmentCommand;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalResponseDraftRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalResponseLineRequest;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.GoodsReceiptEvidenceRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.LaboratoryTestRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.QualityQuarantineLotRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.AdvanceShippingNoticeService;
import com.enterprise.mm.service.LaboratoryTestService;
import com.enterprise.mm.service.PurchaseOrderService;
import com.enterprise.mm.service.QualityInspectionService;
import com.enterprise.mm.service.SupplierAccreditationEvidencePolicy;
import com.enterprise.mm.service.SupplierPortalPurchaseOrderExternalService;
import com.enterprise.mm.supplier.po.service.SupplierPoCollaborationCommandService;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeContext;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureObservability
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers(disabledWithoutDocker = false)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.seed.enabled=false", "mm.pilot.enabled-capabilities=", "app.cors.allowed-origins=http://127.0.0.1:5182"
})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="mm.live.rehearsal", matches="true")
class G2LiveStockReceivingRehearsalTest {
    private static final String TENANT = "default";
    private static final UUID DEFAULT_COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("mm_stage_1b_e")
        .withUsername("mm")
        .withPassword("mm");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired SupplierPortalPurchaseOrderExternalService supplierPortalPurchaseOrderExternalService;
    @Autowired SupplierPoCollaborationCommandService collaborationCommandService;
    @Autowired AdvanceShippingNoticeService advanceShippingNoticeService;
    @Autowired GoodsIncomeWorkspaceService goodsIncomeWorkspaceService;
    @Autowired QualityInspectionService qualityInspectionService;
    @Autowired LaboratoryTestService laboratoryTestService;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired BudgetItemRepository budgetItemRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    @Autowired SupplierPoDispatchRepository dispatchRepository;
    @Autowired SupplierPortalAccountRepository portalAccountRepository;
    @Autowired SupplierPoResponseRepository responseRepository;
    @Autowired SupplierPoCommitmentRepository commitmentRepository;
    @Autowired SupplierAccreditationApplicationRepository accreditationApplicationRepository;
    @Autowired SupplierAccreditationDecisionRepository accreditationDecisionRepository;
    @Autowired SupplierAccreditationDocumentRepository accreditationDocumentRepository;
    @Autowired AdvanceShippingNoticeRepository advanceShippingNoticeRepository;
    @Autowired AdvanceShippingNoticeItemRepository advanceShippingNoticeItemRepository;
    @Autowired DeliveryScheduleRepository deliveryScheduleRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StorageLocationRepository storageLocationRepository;
    @Autowired GoodsReceiptRepository goodsReceiptRepository;
    @Autowired GoodsReceiptLineRepository goodsReceiptLineRepository;
    @Autowired GoodsReceiptEvidenceRepository goodsReceiptEvidenceRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired QualityInspectionRepository qualityInspectionRepository;
    @Autowired QualityQuarantineLotRepository qualityQuarantineLotRepository;
    @Autowired LaboratoryTestRepository laboratoryTestRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(DEFAULT_COMPANY_ID);
        authenticate("stage1be.warehouse", "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "QUALITY_MANAGER");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Autowired UserService users;
    @Autowired ApprovalFlowRepository flows;
    @Autowired ApprovalFlowStepRepository flowSteps;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Test void liveApiAndWorkerBrowserRehearsal() throws Exception {
        var flow=new com.enterprise.mm.entity.ApprovalFlow();flow.setCode("G2-STOCK-APPROVAL");flow.setName("Pilot stock approval");flow.setModuleKey("STOCK_MOVEMENT");flow.setStatus("ACTIVE");flow.setActive(true);flow=flows.saveAndFlush(flow);
        var step=new com.enterprise.mm.entity.ApprovalFlowStep();step.setName("Manager stock authorization");step.setApprovalFlowId(flow.getId());step.setStepOrder(1);step.setStatus("ACTIVE");step.setAssigneeType(com.enterprise.mm.enums.ApprovalAssigneeType.ROLE);step.setAssigneeRole("MM_MANAGER");flowSteps.saveAndFlush(step);
        var admin=new UserCreateRequest();admin.setName("G2 live administrator");admin.setFullName("G2 live administrator");admin.setUsername("g2liveadmin");
        String password="Pilot!"+UUID.randomUUID();admin.setPassword(password);admin.setCompanyId(DEFAULT_COMPANY_ID);admin.setRoles(java.util.Set.of("ADMIN","MM_MANAGER"));users.create(admin);
        var openingMaterial=material("MAT-OPEN-01");var main=warehouse("Main warehouse");var rack=storageLocation(main,"Rack A-01");var dest=warehouse("Secondary warehouse");var destRack=storageLocation(dest,"Rack B-01");
        var qc=confirmedAsnFixture("MAT-QC-01","5");var qm=materialRepository.findByIdAndIsDeletedFalse(qc.material().getId()).orElseThrow();qm.setIncomingInspectionRequired(true);materialRepository.saveAndFlush(qm);
        var partial=confirmedAsnFixture("PARTIAL","10");var wrong=confirmedAsnFixture("WRONG-UOM","10");var wrongItem=purchaseOrderItemRepository.findByIdAndIsDeletedFalse(wrong.item().getId()).orElseThrow();wrongItem.setUnit("BOX");purchaseOrderItemRepository.saveAndFlush(wrongItem);
        var evidence=purchaseOrderEvidence(partial.purchaseOrder(),"Rejected first delivery");
        java.util.Map<String,Object> fixture=new java.util.LinkedHashMap<>();fixture.put("company",DEFAULT_COMPANY_ID);fixture.put("api","http://127.0.0.1:"+port);
        fixture.put("opening",java.util.Map.of("materialId",openingMaterial.getId(),"warehouseId",main.getId(),"storageLocationId",rack.getId(),"destinationWarehouseId",dest.getId(),"destinationLocationId",destRack.getId()));
        for(var pair:java.util.Map.of("qc",qc,"partial",partial,"wrong",wrong).entrySet()){var f=pair.getValue();fixture.put(pair.getKey(),java.util.Map.of("poId",f.purchaseOrder().getId(),"itemId",f.item().getId(),"materialId",f.material().getId(),"warehouseId",f.warehouse().getId(),"locationId",f.location().getId(),"asnId",f.asn().getId(),"departmentId",f.request().getDepartmentId()));}
        fixture.put("partialEvidenceId",evidence.getId());
        var dir=java.nio.file.Path.of("../docs/reports/g2-b02-b05-evidence").toAbsolutePath().normalize();java.nio.file.Files.createDirectories(dir);json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("live-fixtures.json").toFile(),fixture);
        var builder=new ProcessBuilder("node",dir.resolve("live-rehearsal.cjs").toString());builder.environment().put("MM_LIVE_USERNAME","g2liveadmin");builder.environment().put("MM_LIVE_PASSWORD",password);builder.redirectErrorStream(true);builder.redirectOutput(dir.resolve("live-run.log").toFile());
        var process=builder.start();boolean finished=process.waitFor(8,java.util.concurrent.TimeUnit.MINUTES);if(!finished)process.destroyForcibly();assertThat(finished).as("Live browser rehearsal completed").isTrue();assertThat(process.exitValue()).as("See live-run.log for exact API/UI evidence").isZero();
    }
    private ReceivingFixture confirmedAsnFixture(String label, String quantity) {
        ReceivingFixture fixture = sentAsnFixture(label, quantity);
        advanceShippingNoticeService.confirm(fixture.asn().getId());
        AdvanceShippingNotice confirmed = advanceShippingNoticeRepository
            .findByIdAndIsDeletedFalse(fixture.asn().getId())
            .orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(AdvanceShippingNoticeStatus.CONFIRMED.name());
        return fixture.withAsn(confirmed);
    }

    private ReceivingFixture sentAsnFixture(String label, String quantity) {
        Supplier supplier = supplier(label);
        Material material = material(label);
        approveSupplierForMaterialGroup(supplier, material.getMaterialGroupId(), label);
        Department department = department(label);
        BudgetItem budgetItem = budgetItem(label);
        Contract contract = contract(supplier, label);
        PurchaseRequest request = purchaseRequest(department, label);
        PurchaseRequestItem requestItem = purchaseRequestItem(request, material, budgetItem, quantity, label);
        PurchaseOrder purchaseOrder = sentPurchaseOrder(supplier, contract, request, label);
        PurchaseOrderItem item = purchaseOrderItem(purchaseOrder, requestItem, material, budgetItem, quantity, label);
        sourceAllocation(item, requestItem, quantity, label);
        SupplierPoDispatch dispatch = dispatch(purchaseOrder, supplier, label);
        String token = activateSupplierPortal(supplier, "stage1be-" + label + "-" + suffix());

        var responseDraft = supplierPortalPurchaseOrderExternalService.createResponseDraft(
            token,
            purchaseOrder.getId(),
            acceptedResponse(item.getId(), quantity, "Stage 1B-E accepted response")
        );
        var submitted = supplierPortalPurchaseOrderExternalService.submitResponse(
            token, purchaseOrder.getId(), responseDraft.id());
        var submittedEntity = responseRepository.findByIdAndIsDeletedFalse(submitted.id()).orElseThrow();
        collaborationCommandService.acceptResponse(
            submitted.id(),
            new BuyerCollaborationCommand(submittedEntity.getVersion(), null, "Stage 1B-E buyer acceptance"),
            "stage1be-accept-" + suffix()
        );
        SupplierPoCommitment commitment = commitmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                TENANT, DEFAULT_COMPANY_ID, item.getId(), List.of("CONFIRMED"))
            .getFirst();

        var draft = supplierPortalPurchaseOrderExternalService.createAsnDraft(
            token,
            purchaseOrder.getId(),
            asnRequest(commitment.getId(), quantity, "ASN-S1BE-" + label + "-" + suffix())
        );
        var sentAsn = supplierPortalPurchaseOrderExternalService.submitAsn(
            token,
            purchaseOrder.getId(),
            draft.id(),
            "stage1be-asn-submit-" + suffix()
        );
        AdvanceShippingNotice asn = advanceShippingNoticeRepository.findByIdAndIsDeletedFalse(sentAsn.id()).orElseThrow();
        assertThat(asn.getStatus()).isEqualTo(AdvanceShippingNoticeStatus.SENT.name());

        Warehouse warehouse = warehouse(label);
        StorageLocation location = storageLocation(warehouse, label);
        return new ReceivingFixture(
            purchaseOrder, item, request, requestItem, material, supplier, contract,
            dispatch, commitment, asn, warehouse, location
        );
    }

    private ReceivePoRequest receiveRequest(
        ReceivingFixture fixture,
        String acceptedQuantity,
        String rejectedQuantity,
        String damagedQuantity,
        String qualityStatus,
        List<UUID> evidenceIds
    ) {
        BigDecimal accepted = new BigDecimal(acceptedQuantity);
        BigDecimal rejected = new BigDecimal(rejectedQuantity);
        BigDecimal damaged = new BigDecimal(damagedQuantity);
        return new ReceivePoRequest(
            fixture.warehouse().getId(),
            fixture.location().getId(),
            fixture.asn().getId(),
            List.of(new ReceivePoLineRequest(
                fixture.item().getId(),
                accepted.add(rejected).add(damaged),
                rejected,
                damaged,
                null,
                "BATCH-S1BE-" + suffix(),
                fixture.location().getId(),
                qualityStatus,
                null,
                accepted
            )),
            null,
            null,
            evidenceIds
        );
    }

    private GoodsIncomeContext goodsIncomeContext(UUID purchaseOrderId) {
        authenticate("stage1be.goods-income", "WAREHOUSE_MANAGER");
        return goodsIncomeWorkspaceService.context(purchaseOrderId);
    }

    private void passInspectionThroughLab(UUID inspectionId, String label) {
        var laboratory = laboratoryTestService.create(laboratoryRequest(inspectionId, "PASS-" + label));
        qualityInspectionService.startSampling(inspectionId);
        laboratoryTestService.start(laboratory.getId());
        laboratoryTestService.recordResult(
            laboratory.getId(),
            new LaboratoryResultRequest(true, "Within specification", "PASSED", null, "Stage 1B-E passed")
        );
        assertThat(qualityInspectionRepository.findByIdAndIsDeletedFalse(inspectionId).orElseThrow().getStatus())
            .isEqualTo(QualityInspectionStatus.PASSED.name());
    }

    private void failInspectionThroughLab(UUID inspectionId, String label) {
        var laboratory = laboratoryTestService.create(laboratoryRequest(inspectionId, "FAIL-" + label));
        qualityInspectionService.startSampling(inspectionId);
        laboratoryTestService.start(laboratory.getId());
        laboratoryTestService.recordResult(
            laboratory.getId(),
            new LaboratoryResultRequest(false, "Out of specification", "FAILED", null, "Stage 1B-E failed")
        );
        assertThat(qualityInspectionRepository.findByIdAndIsDeletedFalse(inspectionId).orElseThrow().getStatus())
            .isEqualTo(QualityInspectionStatus.FAILED.name());
    }

    private com.enterprise.mm.payload.LaboratoryTestCreateRequest laboratoryRequest(UUID inspectionId, String label) {
        com.enterprise.mm.payload.LaboratoryTestCreateRequest request =
            new com.enterprise.mm.payload.LaboratoryTestCreateRequest();
        request.setName("Stage 1B-E laboratory test " + label);
        request.setQualityInspectionId(inspectionId);
        request.setTestType("CHEMICAL_COMPOSITION");
        return request;
    }

    private StockBalance stockBalance(StockMovement movement) {
        return stockBalanceRepository.findCompanyBalance(
            TENANT,
            DEFAULT_COMPANY_ID,
            movement.getMaterialId(),
            movement.getWarehouseId(),
            movement.getStorageLocationId() == null,
            movement.getStorageLocationId(),
            movement.getStockBatchId() == null,
            movement.getStockBatchId()
        ).orElseThrow();
    }

    private void grantScopedAccess(String username, String role, ReceivingFixture fixture) {
        if (role.startsWith("WAREHOUSE_")) {
            Employee employee = new Employee();
            employee.setCode(username);
            employee.setName(username);
            employee.setStatus("ACTIVE");
            employee.setPersonnelNumber(username);
            employee.setWarehouse(fixture.warehouse());
            employee.setEmploymentStatus(EmployeeStatus.ACTIVE);
            employeeRepository.saveAndFlush(employee);
        }
        if (role.startsWith("PROCUREMENT_")) {
            PurchaseRequest request = purchaseRequestRepository.findByIdAndIsDeletedFalse(fixture.request().getId()).orElseThrow();
            request.setRequestedBy(username);
            purchaseRequestRepository.saveAndFlush(request);
        }
    }

    private Department department(String label) {
        Department department = new Department();
        department.setCode("S1BE-DEP-" + label + "-" + suffix());
        department.setName("Stage 1B-E department " + label);
        department.setStatus("ACTIVE");
        department.setDepartmentType("OPERATIONS");
        return departmentRepository.save(department);
    }

    private BudgetItem budgetItem(String label) {
        BudgetItem budgetItem = new BudgetItem();
        budgetItem.setCode("S1BE-BI-" + label + "-" + suffix());
        budgetItem.setName("Stage 1B-E budget " + label);
        budgetItem.setStatus("ACTIVE");
        budgetItem.setCurrencyCode("UZS");
        budgetItem.setAnnualLimit(new BigDecimal("1000000"));
        budgetItem.setReservedAmount(BigDecimal.ZERO);
        budgetItem.setCommittedAmount(BigDecimal.ZERO);
        budgetItem.setUsedAmount(BigDecimal.ZERO);
        return budgetItemRepository.save(budgetItem);
    }

    private PurchaseRequest purchaseRequest(Department department, String label) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("S1BE-PR-" + label + "-" + suffix());
        request.setName("Stage 1B-E approved purchase request " + label);
        request.setStatus("APPROVED");
        request.setDepartmentId(department.getId());
        request.setRequestedBy("stage1be.requester");
        request.setRequestType(RequestType.GOODS);
        request.setRequiredDate(LocalDate.of(2027, 2, 1));
        request.setTotalAmount(new BigDecimal("600"));
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private PurchaseRequestItem purchaseRequestItem(
        PurchaseRequest request,
        Material material,
        BudgetItem budgetItem,
        String quantity,
        String label
    ) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("S1BE-PRI-" + label + "-" + suffix());
        item.setName("Stage 1B-E purchase request item " + label);
        item.setStatus("OPEN");
        item.setPurchaseRequestId(request.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(material.getId());
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setBudgetItemId(budgetItem.getId());
        item.setQuantity(new BigDecimal(quantity));
        item.setEstimatedPrice(new BigDecimal("100"));
        item.setUnit("EA");
        return purchaseRequestItemRepository.save(item);
    }

    private PurchaseOrder sentPurchaseOrder(
        Supplier supplier,
        Contract contract,
        PurchaseRequest request,
        String label
    ) {
        PurchaseOrder order = new PurchaseOrder();
        order.setCode("S1BE-PO-" + label + "-" + suffix());
        order.setName("Stage 1B-E purchase order " + label);
        order.setStatus(PurchaseOrderStatus.SENT.name());
        order.setSupplierId(supplier.getId());
        order.setContractId(contract.getId());
        order.setPurchaseRequestId(request.getId());
        order.setRequestType(RequestType.GOODS);
        order.setOrderDate(LocalDate.of(2027, 1, 5));
        order.setExpectedDeliveryDate(LocalDate.of(2027, 1, 25));
        order.setCurrencyCode("UZS");
        order.setExchangeRate(BigDecimal.ONE);
        order.setTotalAmount(new BigDecimal("600"));
        return purchaseOrderRepository.saveAndFlush(order);
    }

    private PurchaseOrderItem purchaseOrderItem(
        PurchaseOrder purchaseOrder,
        PurchaseRequestItem requestItem,
        Material material,
        BudgetItem budgetItem,
        String quantity,
        String label
    ) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("S1BE-POI-" + label + "-" + suffix());
        item.setName("Stage 1B-E PO item " + label);
        item.setStatus("OPEN");
        item.setPurchaseOrderId(purchaseOrder.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(material.getId());
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setBudgetItemId(budgetItem.getId());
        item.setQuantity(new BigDecimal(quantity));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setAcceptedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(new BigDecimal("100"));
        item.setUnit("EA");
        return purchaseOrderItemRepository.saveAndFlush(item);
    }

    private void sourceAllocation(
        PurchaseOrderItem purchaseOrderItem,
        PurchaseRequestItem purchaseRequestItem,
        String quantity,
        String label
    ) {
        PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
        allocation.setCode("S1BE-POSRC-" + label + "-" + suffix());
        allocation.setName("Stage 1B-E source allocation " + label);
        allocation.setStatus("ACTIVE");
        allocation.setPurchaseOrderItemId(purchaseOrderItem.getId());
        allocation.setPurchaseRequestItemId(purchaseRequestItem.getId());
        allocation.setOrderedQuantity(new BigDecimal(quantity));
        sourceAllocationRepository.save(allocation);
    }

    private SupplierPoDispatch dispatch(PurchaseOrder purchaseOrder, Supplier supplier, String label) {
        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setCode("S1BE-DISP-" + label + "-" + suffix());
        dispatch.setName("Stage 1B-E supplier dispatch " + label);
        dispatch.setStatus("ACTIVE");
        dispatch.setPurchaseOrderId(purchaseOrder.getId());
        dispatch.setPurchaseOrderVersion(purchaseOrder.getVersion() == null ? 0L : purchaseOrder.getVersion());
        dispatch.setSupplierId(supplier.getId());
        dispatch.setRecipientEmail(supplier.getEmail());
        dispatch.setDispatchStatus("QUEUED");
        dispatch.setResponseDueDate(LocalDate.now().plusDays(5));
        dispatch.setQueuedAt(LocalDateTime.now());
        dispatch.setSnapshotJson("{\"po\":\"" + purchaseOrder.getCode() + "\"}");
        dispatch.setSnapshotHash("a".repeat(64));
        dispatch.setCurrent(true);
        return dispatchRepository.saveAndFlush(dispatch);
    }

    private SupplierPortalResponseDraftRequest acceptedResponse(UUID purchaseOrderItemId, String quantity, String comment) {
        return new SupplierPortalResponseDraftRequest(
            comment,
            List.of(new SupplierPortalResponseLineRequest(
                purchaseOrderItemId,
                "ACCEPTED",
                new BigDecimal(quantity),
                null,
                comment,
                List.of(new SupplierPortalCommitmentCommand(new BigDecimal(quantity), LocalDate.of(2027, 1, 25)))
            ))
        );
    }

    private SupplierPortalAsnDraftRequest asnRequest(UUID commitmentId, String quantity, String asnNumber) {
        return new SupplierPortalAsnDraftRequest(
            asnNumber,
            LocalDate.of(2027, 1, 10),
            LocalDate.of(2027, 1, 15),
            LocalDate.of(2027, 1, 20),
            "TRUCK",
            "Stage 1B-E carrier",
            "TRUCK-1",
            null,
            "TRACK-" + suffix(),
            List.of(new SupplierPortalAsnLineRequest(
                commitmentId,
                new BigDecimal(quantity),
                "BATCH-" + suffix(),
                LocalDate.of(2028, 1, 1)
            ))
        );
    }

    private Warehouse warehouse(String label) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode("S1BE-WH-" + label + "-" + suffix());
        warehouse.setName("Stage 1B-E warehouse " + label);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.saveAndFlush(warehouse);
    }

    private StorageLocation storageLocation(Warehouse warehouse, String label) {
        StorageLocation location = new StorageLocation();
        location.setCode("S1BE-LOC-" + label + "-" + suffix());
        location.setName("Stage 1B-E location " + label);
        location.setStatus("ACTIVE");
        location.setWarehouseId(warehouse.getId());
        location.setAllowMixedMaterials(true);
        return storageLocationRepository.saveAndFlush(location);
    }

    private Material material(String label) {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode("EA");
        unit.setName("Stage 1B-E unit " + label);
        unit.setStatus("ACTIVE");
        unit.setMeasurementNameShort("EA");
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);
        unit.setMeasurementNameLong("Pieces");
        UnitOfMeasure savedUnit = unitOfMeasureRepository.findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalse(TENANT, DEFAULT_COMPANY_ID, "EA").orElseGet(() -> unitOfMeasureRepository.saveAndFlush(unit));

        Material material = new Material();
        material.setCode("S1BE-MAT-" + label + "-" + suffix());
        material.setName("Stage 1B-E material " + label);
        material.setStatus("ACTIVE");
        material.setMaterialType(MaterialType.TMB);
        material.setMaterialGroupId(materialGroup(label).getId());
        material.setUnitOfMeasureId(savedUnit.getId());
        material.setValuationMethod(com.enterprise.mm.enums.ValuationMethod.WEIGHTED_AVERAGE);
        return materialRepository.saveAndFlush(material);
    }

    private MaterialGroup materialGroup(String label) {
        MaterialGroup group = new MaterialGroup();
        group.setCode("S1BE-MG-" + label + "-" + suffix());
        group.setName("Stage 1B-E material group " + label);
        group.setStatus("ACTIVE");
        return materialGroupRepository.save(group);
    }

    private Supplier supplier(String label) {
        Supplier supplier = new Supplier();
        supplier.setCode("S1BE-SUP-" + label + "-" + suffix());
        supplier.setName("Stage 1B-E supplier " + label);
        supplier.setLegalName("Stage 1B-E supplier " + label + " LLC");
        supplier.setShortName("S1BE " + label);
        supplier.setEmail("s1be-" + label.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + suffix() + "@supplier.test");
        supplier.setStatus("ACTIVE");
        supplier.setRating(new BigDecimal("90"));
        supplier.setCategory("A");
        return supplierRepository.saveAndFlush(supplier);
    }

    private Contract contract(Supplier supplier, String label) {
        Contract contract = new Contract();
        contract.setCode("S1BE-CON-" + label + "-" + suffix());
        contract.setName("Stage 1B-E contract " + label);
        contract.setStatus("ACTIVE");
        contract.setSupplierId(supplier.getId());
        contract.setContractNumber("S1BE-CN-" + label + "-" + suffix());
        contract.setStartDate(LocalDate.of(2026, 12, 1));
        contract.setEndDate(LocalDate.of(2027, 12, 31));
        contract.setLimitAmount(new BigDecimal("50000"));
        contract.setUsedAmount(BigDecimal.ZERO);
        contract.setCurrencyCode("UZS");
        return contractRepository.save(contract);
    }

    private Attachment purchaseOrderEvidence(PurchaseOrder purchaseOrder, String label) {
        Attachment attachment = new Attachment();
        attachment.setCode("S1BE-PO-ATT-" + label + "-" + suffix());
        attachment.setName("Stage 1B-E PO evidence " + label);
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("PURCHASEORDER");
        attachment.setEntityId(purchaseOrder.getId());
        attachment.setAttachmentType("PURCHASE_ORDER_ATTACHMENT_FOR_EXCEPTIONS");
        attachment.setOriginalFileName("stage1be-receipt-evidence.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(256L);
        attachment.setChecksumSha256("1".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("stage1be/" + purchaseOrder.getId());
        attachment.setUploadedAt(LocalDateTime.now());
        return attachmentRepository.saveAndFlush(attachment);
    }

    private void approveSupplierForMaterialGroup(Supplier supplier, UUID materialGroupId, String label) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setCode("S1BE-ACC-" + label + "-" + suffix());
        application.setName("Stage 1B-E accreditation " + label);
        application.setStatus(SupplierAccreditationStatus.APPROVED.name());
        application.setSupplierId(supplier.getId());
        application.setAccreditationType("GOODS");
        application.setRequestedScope("Stage 1B-E material group");
        application.setRequestedMaterialGroupIds(List.of(materialGroupId));
        application.setSubmittedAt(LocalDateTime.now().minusDays(2));
        application.setValidFrom(LocalDate.now().minusDays(1));
        application.setValidTo(LocalDate.now().plusYears(1));
        SupplierAccreditationApplication savedApplication = accreditationApplicationRepository.saveAndFlush(application);

        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setCode("S1BE-ACC-DEC-" + label + "-" + suffix());
        decision.setName("Stage 1B-E accreditation decision " + label);
        decision.setStatus("RECORDED");
        decision.setApplicationId(savedApplication.getId());
        decision.setDecision(SupplierAccreditationStatus.APPROVED.name());
        decision.setApprovedScope("Stage 1B-E material group");
        decision.setAllowedMaterialGroupIds(List.of(materialGroupId));
        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().plusYears(1));
        accreditationDecisionRepository.saveAndFlush(decision);

        SupplierAccreditationDocument document = new SupplierAccreditationDocument();
        document.setCode("S1BE-ACC-DOC-" + label + "-" + suffix());
        document.setName("Stage 1B-E accreditation evidence " + label);
        document.setStatus("VERIFIED");
        document.setApplicationId(savedApplication.getId());
        document.setDocumentType("CERTIFICATE");
        document.setValidFrom(LocalDate.now().minusDays(1));
        document.setValidTo(LocalDate.now().plusYears(1));
        document.setVerifiedBy("stage1be.warehouse");
        document.setVerifiedAt(LocalDateTime.now());
        SupplierAccreditationDocument savedDocument = accreditationDocumentRepository.saveAndFlush(document);

        Attachment attachment = new Attachment();
        attachment.setCode("S1BE-ACC-ATT-" + label + "-" + suffix());
        attachment.setName("Stage 1B-E accreditation attachment " + label);
        attachment.setStatus("ACTIVE");
        attachment.setEntityType(SupplierAccreditationEvidencePolicy.ATTACHMENT_ENTITY_TYPE);
        attachment.setEntityId(savedDocument.getId());
        attachment.setAttachmentType("ACCREDITATION_EVIDENCE");
        attachment.setOriginalFileName("stage1be-accreditation.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(128L);
        attachment.setChecksumSha256("0".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("stage1be/" + savedDocument.getId());
        attachment.setUploadedAt(LocalDateTime.now());
        Attachment savedAttachment = attachmentRepository.saveAndFlush(attachment);
        savedDocument.setAttachmentId(savedAttachment.getId());
        accreditationDocumentRepository.saveAndFlush(savedDocument);
    }

    private String activateSupplierPortal(Supplier supplier, String token) {
        SupplierPortalAccount account = portalAccountRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TENANT, DEFAULT_COMPANY_ID, supplier.getId()
            )
            .orElseGet(() -> {
                SupplierPortalAccount created = new SupplierPortalAccount();
                created.setCode("S1BE-SPA-" + suffix());
                created.setName("Stage 1B-E portal account " + supplier.getCode());
                created.setSupplierId(supplier.getId());
                created.setLoginEmail(supplier.getEmail());
                return created;
            });
        account.setStatus(SupplierPortalStatus.ACTIVE.name());
        account.setPortalRole(SupplierPortalRole.SUPPLIER_ADMIN.name());
        account.setTermsAcceptedAt(LocalDateTime.now());
        account.setActivatedAt(LocalDateTime.now());
        account.setPortalTokenHash(sha256(token));
        account.setPortalTokenExpiresAt(LocalDateTime.now().plusHours(2));
        portalAccountRepository.saveAndFlush(account);
        return token;
    }

    private void authenticate(String username, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                java.util.Arrays.stream(roles)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList()
            )
        );
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record ReceivingFixture(
        PurchaseOrder purchaseOrder,
        PurchaseOrderItem item,
        PurchaseRequest request,
        PurchaseRequestItem requestItem,
        Material material,
        Supplier supplier,
        Contract contract,
        SupplierPoDispatch dispatch,
        SupplierPoCommitment commitment,
        AdvanceShippingNotice asn,
        Warehouse warehouse,
        StorageLocation location
    ) {
        ReceivingFixture withAsn(AdvanceShippingNotice nextAsn) {
            return new ReceivingFixture(
                purchaseOrder, item, request, requestItem, material, supplier, contract,
                dispatch, commitment, nextAsn, warehouse, location
            );
        }
    }
}
