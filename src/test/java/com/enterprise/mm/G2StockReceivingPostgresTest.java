package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.QualityControlDtos.QualityActionRequest;
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
@SpringBootTest
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
    "app.seed.enabled=false", "mm.pilot.enabled-capabilities="
})
class G2StockReceivingPostgresTest {
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
        // Seeding is disabled in this suite; Faktura receipt evidence requires a real company FK.
        jdbcTemplate.update("""
            insert into companies (id, tenant_id, company_id, created_at, version, is_delete, name, status)
            values (?, ?, ?, current_timestamp, 0, false, 'G2 test company', 'ACTIVE')
            on conflict (id) do nothing
            """, DEFAULT_COMPANY_ID, TENANT, DEFAULT_COMPANY_ID);
        authenticate("stage1be.warehouse", "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "QUALITY_MANAGER");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Autowired OpeningStockService opening;
    @Autowired MaterialService materialService;
    @Autowired MaterialReservationService reservations;
    @org.springframework.boot.test.mock.mockito.SpyBean StockMovementService stock;
    @Autowired ImportService imports;
    @Autowired QualityClaimService claims;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    private OpeningStockService.Command openingCommand(Material material, Warehouse warehouse, StorageLocation location, String quantity) {
        return new OpeningStockService.Command(UUID.randomUUID(), "Signed factory opening register G2", LocalDate.now(),
            List.of(new OpeningStockService.Row("1", material.getId(), warehouse.getId(), location.getId(), null, "EA", new BigDecimal(quantity), new BigDecimal("100"))));
    }
    private StockMovement movement(OpeningStockService.Result result) { return stockMovementRepository.findByIdAndIsDeletedFalse(result.movements().getFirst().getId()).orElseThrow(); }
    private MaterialReservationCreateRequest reservation(StockMovement m, String quantity) {
        var r=new MaterialReservationCreateRequest(); r.setName("G2 reserve"); r.setMaterialId(m.getMaterialId());r.setWarehouseId(m.getWarehouseId());
        r.setStorageLocationId(m.getStorageLocationId());r.setStockBatchId(m.getStockBatchId());r.setQuantity(new BigDecimal(quantity));r.setReservationType("MANUAL");return r;
    }
    private StockMovementResponse issue(StockMovement origin, String quantity) {
        var m=new StockMovement();m.setMaterialId(origin.getMaterialId());m.setWarehouseId(origin.getWarehouseId());m.setStorageLocationId(origin.getStorageLocationId());
        m.setStockBatchId(origin.getStockBatchId());m.setQuantity(new BigDecimal(quantity));m.setMovementType(MovementType.ISSUE);return stock.createAndPost(m);
    }
    private ReceivePoRequest versioned(ReceivingFixture f,String candidate,String rejected) {
        var r=receiveRequest(f,candidate,rejected,"0",null,new BigDecimal(rejected).signum() > 0 ? List.of(purchaseOrderEvidence(f.purchaseOrder(), "receipt").getId()) : List.of());
        return new ReceivePoRequest(r.warehouseId(),r.storageLocationId(),r.asnId(),r.lines(),r.storageBinId(),purchaseOrderService.get(f.purchaseOrder().getId()).getVersion(),r.evidenceAttachmentIds());
    }
    @Test void openingIsValuedExecutableRetrySafeAndCannotEraseReservations() {
        var mat=material("MAT-OPEN-01");var wh=warehouse("Main");var loc=storageLocation(wh,"Rack A-01");var command=openingCommand(mat,wh,loc,"20");
        var posted=opening.post(command);var m=movement(posted);var b=stockBalance(m);
        assertThat(b.getQuantity()).isEqualByComparingTo("20");assertThat(b.getAvailableQuantity()).isEqualByComparingTo("20");assertThat(b.getReservedQuantity()).isZero();assertThat(b.getTotalValue()).isEqualByComparingTo("2000");
        assertThat(m.getStockBatchId()).isNotNull();assertThat(m.getUomIdSnapshot()).isEqualTo(mat.getUnitOfMeasureId());
        var changedUnit = new MaterialUpdateRequest();changedUnit.setUnitOfMeasureId(UUID.randomUUID());
        assertThatThrownBy(() -> materialService.update(mat.getId(), changedUnit)).hasMessageContaining("Stock UOM cannot change");
        assertThat(opening.post(command).movements().getFirst().getId()).isEqualTo(m.getId());
        var changed=new OpeningStockService.Command(command.importId(),"changed source",command.postingDate(),command.rows());
        assertThatThrownBy(()->opening.post(changed)).hasMessageContaining("different request");
        reservations.create(reservation(m,"5"),"reserve-"+mat.getId());
        assertThat(stockBalance(m).getReservedQuantity()).isEqualByComparingTo("5");
        assertThatThrownBy(()->imports.importStockBalances(null)).hasMessageContaining("overwrite is disabled");
        assertThatThrownBy(()->opening.post(openingCommand(mat,wh,loc,"20"))).hasMessageContaining("cannot overwrite");
        issue(m,"2");b=stockBalance(m);assertThat(b.getQuantity()).isEqualByComparingTo("18");assertThat(b.getAvailableQuantity()).isEqualByComparingTo("13");assertThat(b.getReservedQuantity()).isEqualByComparingTo("5");assertThat(b.getTotalValue()).isEqualByComparingTo("1800");
        transfer(m,"3",false);b=stockBalance(m);assertThat(b.getQuantity()).isEqualByComparingTo("15");assertThat(b.getAvailableQuantity()).isEqualByComparingTo("10");assertThat(b.getReservedQuantity()).isEqualByComparingTo("5");assertThat(b.getTotalValue()).isEqualByComparingTo("1500");
        countRemaining(m,"15");
    }
    @Test void openingRollbackDuplicateRowsAndRetry() {
        var mat=material("ROLLBACK");var wh=warehouse("Rollback");var loc=storageLocation(wh,"Rack");var command=openingCommand(mat,wh,loc,"20");var row=command.rows().getFirst();
        assertThatThrownBy(()->opening.post(new OpeningStockService.Command(command.importId(),command.sourceEvidence(),command.postingDate(),List.of(row,row)))).hasMessageContaining("unique");
        org.mockito.Mockito.doAnswer(invocation -> { invocation.callRealMethod(); throw new BusinessException("Controlled failure after valued posting"); })
            .when(stock).createAndPost(org.mockito.ArgumentMatchers.any());
        try { assertThatThrownBy(()->opening.post(command)).hasMessageContaining("Controlled failure"); }
        finally { org.mockito.Mockito.reset(stock); }
        assertThat(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TENANT,mat.getId(),wh.getId())).isEmpty();
        assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(TENANT,"OPENING_STOCK",command.importId())).isEmpty();
        assertThat(opening.post(command).quantity()).isEqualByComparingTo("20");
    }
    @Test void openingConcurrentRetryProducesOneMovement() throws Exception {
        var mat=material("RACE");var wh=warehouse("Race");var loc=storageLocation(wh,"Rack");var c=openingCommand(mat,wh,loc,"20");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<UUID> call=()->{setUp();try{ready.await();return opening.post(c).movements().getFirst().getId();}finally{clearContext();}};
            var a=pool.submit(call);var b=pool.submit(call);assertThat(a.get(45,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(45,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(TENANT,"OPENING_STOCK",c.importId())).hasSize(1);
    }
    @Test void wrongUomReceiptHasNoPartialMutationAndCorrectUnitCanReceive() {
        var f=confirmedAsnFixture("UOM","10");var item=purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow();
        item.setUnit("BOX");purchaseOrderItemRepository.saveAndFlush(item);
        var req=versioned(f,"10","0");
        assertThatThrownBy(()->purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),req,"uom-key")).hasMessageContaining("must match");
        assertThat(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow().getReceivedQuantity()).isZero();
        assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(TENANT,"PurchaseOrder",f.purchaseOrder().getId())).isEmpty();
        item=purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow();item.setUnit("EA");purchaseOrderItemRepository.saveAndFlush(item);
        var result=purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),req,"uom-key");
        assertThat(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow().getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),req,"uom-key").getId()).isEqualTo(result.getId());
        assertThatThrownBy(()->purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),versioned(f,"9","0"),"uom-key")).hasMessageContaining("different request");
    }
    @Test void receiptPreservesPurchaseOrderUnitLongerThanFortyCharacters() {
        var f = confirmedAsnFixture("LONG-UOM", "1");
        String longUnit = "MEASUREMENT_UNIT_WITH_A_LONG_DESCRIPTION_OVER_FORTY_CHARS";
        assertThat(longUnit.length()).isBetween(41, 80);
        var uom = unitOfMeasureRepository.findById(f.material().getUnitOfMeasureId()).orElseThrow();
        uom.setMeasurementNameShort(longUnit);
        unitOfMeasureRepository.saveAndFlush(uom);
        var item = purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow();
        item.setUnit(longUnit);
        purchaseOrderItemRepository.saveAndFlush(item);

        var result = purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(), versioned(f, "1", "0"), "long-uom-receipt");

        assertThat(result.getReceiptOutcome().goodsReceiptId()).isNotNull();
        assertThat(goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndGoodsReceiptIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TENANT, DEFAULT_COMPANY_ID, result.getReceiptOutcome().goodsReceiptId()))
            .singleElement().extracting(GoodsReceiptLine::getUomCode).isEqualTo(longUnit);
    }
    @Test void mandatoryQcHoldsAllCandidatesAndAuthorizedDecisionReleasesOnlyFour() {
        var f=confirmedAsnFixture("QC","5");var mat=materialRepository.findByIdAndIsDeletedFalse(f.material().getId()).orElseThrow();mat.setIncomingInspectionRequired(true);materialRepository.saveAndFlush(mat);
        var result=purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),versioned(f,"5","0"),"qc");
        var qi=result.getReceiptOutcome().qualityInspections().getFirst();var m=stockMovementRepository.findByIdAndIsDeletedFalse(qi.stockMovementId()).orElseThrow();
        assertThat(stockBalance(m).getQuantity()).isEqualByComparingTo("5");assertThat(stockBalance(m).getAvailableQuantity()).isZero();assertThat(stockBalance(m).getQuarantineQuantity()).isEqualByComparingTo("5");
        assertThat(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow().getAcceptedQuantity()).isZero();
        assertThatThrownBy(()->reservations.create(reservation(m,"1"),"held-reserve")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->issue(m,"1")).isInstanceOf(RuntimeException.class);
        passInspectionThroughLab(qi.qualityInspectionId(),"QC");
        authenticate("receiver","WAREHOUSE_OPERATOR");
        assertThatThrownBy(()->qualityInspectionService.release(qi.qualityInspectionId(),new QualityDecisionRequest(new BigDecimal("4"),BigDecimal.ONE,"Decision"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        authenticate("quality","MM_MANAGER");
        qualityInspectionService.release(qi.qualityInspectionId(),new QualityDecisionRequest(new BigDecimal("4"),BigDecimal.ONE,"4 conforming, 1 rejected"));
        assertThat(stockBalance(m).getAvailableQuantity()).isEqualByComparingTo("4");assertThat(stockBalance(m).getQuarantineQuantity()).isEqualByComparingTo("1");assertThat(stockBalance(m).getTotalValue()).isEqualByComparingTo("500");
        var item=purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow();assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("4");assertThat(item.getRejectedQuantity()).isEqualByComparingTo("1");assertThat(FulfillmentQuantities.acceptedFulfillmentRemaining(item)).isEqualByComparingTo("1");
        assertThatThrownBy(()->qualityInspectionService.release(qi.qualityInspectionId(),new QualityDecisionRequest(new BigDecimal("4"),BigDecimal.ONE,"retry"))).isInstanceOf(RuntimeException.class);
        issue(m,"1");assertThat(stockBalance(m).getAvailableQuantity()).isEqualByComparingTo("3");
    }
    @Test void partialSixThenFourSeparatesPhysicalCompletionAndClaimShortfall() {
        var f=confirmedAsnFixture("PARTIAL","10");
        var first=purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),versioned(f,"5","1"),"first");
        var item=purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow();assertThat(item.getReceivedQuantity()).isEqualByComparingTo("6");assertThat(FulfillmentQuantities.physicalReceiptRemaining(item)).isEqualByComparingTo("4");
        assertThat(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(TENANT,DEFAULT_COMPANY_ID,f.commitment().getId()).orElseThrow().getReceivedQuantity()).isEqualByComparingTo("6");
        var qi=first.getReceiptOutcome().qualityInspections().getFirst();passInspectionThroughLab(qi.qualityInspectionId(),"PARTIAL");qualityInspectionService.release(qi.qualityInspectionId(),new QualityDecisionRequest(new BigDecimal("5"),BigDecimal.ZERO,"Pass"));
        purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),versioned(f,"4","0"),"second");
        item=purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow();assertThat(item.getReceivedQuantity()).isEqualByComparingTo("10");assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("9");assertThat(item.getRejectedQuantity()).isEqualByComparingTo("1");assertThat(FulfillmentQuantities.physicalReceiptRemaining(item)).isZero();assertThat(FulfillmentQuantities.acceptedFulfillmentRemaining(item)).isEqualByComparingTo("1");
        assertThat(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(TENANT,DEFAULT_COMPANY_ID,f.commitment().getId()).orElseThrow().getReceivedQuantity()).isEqualByComparingTo("10");
        assertThatThrownBy(()->purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),versioned(f,"1","0"),"unauthorized-eleventh")).isInstanceOf(RuntimeException.class);
        var claim=new QualityClaimCreateRequest();claim.setName("Credit for rejected original unit");claim.setSupplierId(f.supplier().getId());claim.setPurchaseOrderId(f.purchaseOrder().getId());claim.setMaterialId(f.material().getId());claim.setQualityInspectionId(qi.qualityInspectionId());claim.setClaimAmount(new BigDecimal("100"));claim.setCurrencyCode("UZS");claim.setRequestedAction("CREDIT");
        var created=claims.create(claim);claims.send(created.getId());claims.acknowledge(created.getId(),new QualityActionRequest("Supplier credit agreed"));claims.accept(created.getId(),new QualityActionRequest("100 UZS credit evidence"));assertThat(claims.close(created.getId(),new QualityActionRequest("Commercial shortfall recorded for credit settlement")).getStatus()).isEqualTo("CLOSED");
        assertThat(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow().getRejectedQuantity()).isEqualByComparingTo("1");
    }
    @Autowired TransferDocumentService transfers;
    @Autowired TransferDocumentLineRepository transferLines;
    @Autowired com.enterprise.mm.service.transfer.TransferExceptionService exceptions;
    @Autowired InventoryDocumentService counts;
    @Autowired InventoryItemService countItems;
    @Autowired CommissionRepository commissions;
    @Autowired CommissionMemberRepository commissionMembers;

    @Autowired StockBatchRepository pickingBatches;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.enterprise.mm.enums.PickingMethod.class)
    void pickingPreviewDispatchAndReplayPreserveExactSources(com.enterprise.mm.enums.PickingMethod method) {
        var f = pickingFixture();
        var command = pickingRequest(f.transfer(), "10", method);
        var preview = transfers.dispatchPreview(f.transfer().getId(), command);
        var expectedFirst = method == com.enterprise.mm.enums.PickingMethod.FIFO ? f.oldStock() : f.newStock();
        assertThat(preview.lines().getFirst().allocations().getFirst().stockBatchId()).isEqualTo(expectedFirst.getStockBatchId());
        assertThat(stockBalance(f.oldStock()).getQuantity()).isEqualByComparingTo("6");
        assertThat(transfers.dispatchContext(f.transfer().getId()).lines().getFirst().maxDispatchQuantity()).isEqualByComparingTo("10");
        assertThat(transfers.stockOptions(f.transfer().getId(), command.getLines().getFirst().getTransferLineId(), method, 0, 25)
            .positions().getFirst().stockBatchId()).isEqualTo(expectedFirst.getStockBatchId());
        var posted = transfers.dispatch(f.transfer().getId(), command, "picking-post");
        var replay = transfers.dispatch(f.transfer().getId(), command, "picking-post");
        assertThat(replay.getShipments()).hasSize(1);
        assertThat(replay.getShipments().getFirst().id()).isEqualTo(posted.getShipments().getFirst().id());
        var decision = posted.getShipments().getFirst().pickingDecisions().getFirst();
        assertThat(decision.pickingMethod()).isEqualTo(method);
        assertThat(decision.allocationMode()).isEqualTo(com.enterprise.mm.enums.StockAllocationMode.AUTO);
        assertThat(decision.allocations()).isEqualTo(preview.lines().getFirst().allocations());
        assertThat(stockBalance(f.oldStock()).getQuantity()).isEqualByComparingTo(method == com.enterprise.mm.enums.PickingMethod.FIFO ? "0" : "4");
        assertThat(stockBalance(f.newStock()).getQuantity()).isEqualByComparingTo(method == com.enterprise.mm.enums.PickingMethod.FIFO ? "4" : "0");
        assertThat(posted.getShipments().getFirst().allocations().stream().map(TransferShipmentDtos.Allocation::dispatchedQuantity)
            .reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("10");
        assertThat(transfers.get(posted.getId()).getShipments().getFirst().pickingDecisions()).containsExactly(decision);
        var changed = pickingRequest(f.transfer(), "9", method);
        assertThatThrownBy(() -> transfers.dispatch(f.transfer().getId(), changed, "picking-post")).isInstanceOf(com.enterprise.mm.error.ConflictException.class);
    }

    @Test void pickingManualStaleStockFailsAtomicallyAndRetryUsesExactSelection() {
        var f = pickingFixture();
        var command = pickingRequest(f.transfer(), "5", com.enterprise.mm.enums.PickingMethod.FIFO);
        var row = command.getLines().getFirst(); row.setAllocationMode(com.enterprise.mm.enums.StockAllocationMode.MANUAL);
        var a = new TransferDispatchRequest.Allocation(); a.setStockBalanceId(stockBalance(f.newStock()).getId()); a.setQuantity(new BigDecimal("5"));
        row.setAllocations(List.of(a));
        var preview = transfers.dispatchPreview(f.transfer().getId(), command);
        issue(f.newStock(), "4");
        assertThatThrownBy(() -> transfers.dispatch(f.transfer().getId(), command, "manual-picking")).hasMessageContaining("exceeds executable");
        assertThat(transfers.get(f.transfer().getId()).getShipments()).isEmpty();
        assertThat(stockBalance(f.oldStock()).getQuantity()).isEqualByComparingTo("6");
        assertThat(stockBalance(f.newStock()).getQuantity()).isEqualByComparingTo("4");
        row.setDispatchedQuantity(new BigDecimal("3")); a.setQuantity(new BigDecimal("3"));
        var posted = transfers.dispatch(f.transfer().getId(), command, "manual-picking");
        assertThat(posted.getShipments().getFirst().allocations()).allSatisfy(x -> assertThat(x.sourceStockBatchId()).isEqualTo(f.newStock().getStockBatchId()));
        assertThat(posted.getShipments().getFirst().pickingDecisions().getFirst().allocationMode()).isEqualTo(com.enterprise.mm.enums.StockAllocationMode.MANUAL);
        assertThat(stockBalance(f.oldStock()).getQuantity()).isEqualByComparingTo("6");
        assertThat(stockBalance(f.newStock()).getQuantity()).isEqualByComparingTo("1");
        assertThat(preview.lines().getFirst().allocations().getFirst().quantity()).isEqualByComparingTo("5");
    }

    @Test void pickingCompetingTransfersCannotOverdrawSharedStock() throws Exception {
        var f = pickingFixture();
        var other = pickingTransfer(f.oldStock(), "10");
        var first = pickingRequest(f.transfer(), "10", com.enterprise.mm.enums.PickingMethod.FIFO);
        var second = pickingRequest(other, "10", com.enterprise.mm.enums.PickingMethod.LIFO);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.function.BiFunction<TransferDocumentResponse,TransferDispatchRequest,Boolean> dispatch = (t,r) -> {
                setUp();
                try { barrier.await(10,java.util.concurrent.TimeUnit.SECONDS); transfers.dispatch(t.getId(),r,"parallel-picking"); return true; }
                catch (BusinessException expected) { return false; }
                catch (Exception error) { throw new RuntimeException(error); }
                finally { clearContext(); }
            };
            var a=pool.submit(() -> dispatch.apply(f.transfer(),first)); var b=pool.submit(() -> dispatch.apply(other,second));
            assertThat(List.of(a.get(45,java.util.concurrent.TimeUnit.SECONDS),b.get(45,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(stockBalance(f.oldStock()).getAvailableQuantity().add(stockBalance(f.newStock()).getAvailableQuantity())).isEqualByComparingTo("4");
    }

    @Test void pickingHttpContractSupportsQuantityAliasAndRejectsViewer() throws Exception {
        var f = pickingFixture();
        var request = pickingRequest(f.transfer(), "10", com.enterprise.mm.enums.PickingMethod.LIFO);
        String url = "/api/transfers/" + f.transfer().getId();
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("picking.http").roles("ADMIN");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url + "/dispatch-preview")
            .with(admin).header("X-Company-Id", DEFAULT_COMPANY_ID).contentType("application/json")
            .content(json.writeValueAsString(request).replace("dispatchedQuantity", "quantity")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.lines[0].pickingMethod").value("LIFO"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url + "/dispatch-context")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("picking.viewer").roles("VIEWER"))
            .header("X-Company-Id", DEFAULT_COMPANY_ID))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        setUp();
        assertThat(transfers.get(f.transfer().getId()).getShipments()).isEmpty();
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager pickingTransactions;
    @Autowired InventoryAccountingSettingsRepository pickingSettings;
    @Autowired InventoryValuationPolicyService pickingValuation;
    @org.springframework.boot.test.mock.mockito.SpyBean InventoryCostingService pickingCosting;

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> pickingValuationCases() {
        return java.util.stream.Stream.of("FIFO","LIFO","SPECIFIC_BATCH","WEIGHTED_AVERAGE","MOVING_AVERAGE","ACTUAL_COST")
            .flatMap(v -> java.util.Arrays.stream(com.enterprise.mm.enums.PickingMethod.values())
                .map(p -> org.junit.jupiter.params.provider.Arguments.of(com.enterprise.mm.enums.ValuationMethod.valueOf(v),p)));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("pickingValuationCases")
    void pickingPreservesPhysicalSourcesAndMoneyAcrossValuationMethods(com.enterprise.mm.enums.ValuationMethod valuation,
        com.enterprise.mm.enums.PickingMethod picking) {
        var f=pickingFixture();
        var previous=pickingSettings.findByTenantIdAndCompanyIdAndIsDeletedFalse(TENANT,DEFAULT_COMPANY_ID);
        var settings=previous.orElseGet(() -> {var s=new com.enterprise.mm.entity.InventoryAccountingSettings();s.setName("Picking review settings");return s;});
        var original=settings.getDefaultValuationMethod();boolean override=settings.isAllowWarehouseValuationOverride();
        settings.setDefaultValuationMethod(valuation.name());settings.setAllowWarehouseValuationOverride(false);
        var saved=pickingSettings.saveAndFlush(settings);
        try {
            assertThat(pickingValuation.resolveMethod(f.oldStock().getMaterialId(),f.oldStock().getWarehouseId())).isEqualTo(valuation);
            var posted=transfers.dispatch(f.transfer().getId(),pickingRequest(f.transfer(),"10",picking),"valuation-picking");
            BigDecimal sent=posted.getShipments().getFirst().allocations().stream().map(TransferShipmentDtos.Allocation::remainingBookValue).reduce(BigDecimal.ZERO,BigDecimal::add);
            assertThat(sent).isEqualByComparingTo(picking==com.enterprise.mm.enums.PickingMethod.FIFO?"1400":"1800");
            var old=stockBalance(f.oldStock());var newer=stockBalance(f.newStock());
            assertThat(old.getQuantity()).isEqualByComparingTo(picking==com.enterprise.mm.enums.PickingMethod.FIFO?"0":"4");
            assertThat(newer.getQuantity()).isEqualByComparingTo(picking==com.enterprise.mm.enums.PickingMethod.FIFO?"4":"0");
            assertThat(old.getTotalValue().add(newer.getTotalValue()).add(sent)).isEqualByComparingTo("2200");
            assertThat(materialRepository.findById(f.oldStock().getMaterialId()).orElseThrow().getValuationMethod())
                .isEqualTo(com.enterprise.mm.enums.ValuationMethod.WEIGHTED_AVERAGE);
        } finally {
            if(previous.isEmpty()) pickingSettings.deleteById(saved.getId());
            else {saved.setDefaultValuationMethod(original);saved.setAllowWarehouseValuationOverride(override);pickingSettings.saveAndFlush(saved);}
        }
    }

    @Test void pickingAndOrdinaryIssueCanOverlapWithoutInvertingCostAndBalanceLocks() throws Exception {
        var f=pickingFixture();
        var costsHeld=new java.util.concurrent.CountDownLatch(1);
        var transferAtCosts=new java.util.concurrent.CountDownLatch(1);
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv -> {if(armed.get())transferAtCosts.countDown();return inv.callRealMethod();})
            .when(pickingCosting).lockPickingSources(org.mockito.ArgumentMatchers.eq(f.oldStock().getWarehouseId()),org.mockito.ArgumentMatchers.anyList());
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ordinary=pool.submit(() -> {
                setUp();
                try {return new org.springframework.transaction.support.TransactionTemplate(pickingTransactions).execute(tx -> {
                    // The ordinary issue already owns its cost layers before the transfer starts.
                    pickingCosting.lockPickingSources(f.oldStock().getWarehouseId(),List.of(f.oldStock().getMaterialId()));
                    armed.set(true);costsHeld.countDown();
                    try {if(!transferAtCosts.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Transfer did not reach cost locks");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                    return issue(f.oldStock(),"2");
                });} finally {clearContext();}
            });
            assertThat(costsHeld.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var dispatch=pool.submit(() -> {setUp();try {return transfers.dispatch(f.transfer().getId(),
                pickingRequest(f.transfer(),"10",com.enterprise.mm.enums.PickingMethod.FIFO),"issue-race");}finally{clearContext();}});
            assertThat(ordinary.get(20,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
            assertThat(dispatch.get(20,java.util.concurrent.TimeUnit.SECONDS).getShipments()).hasSize(1);
        } finally {org.mockito.Mockito.reset(pickingCosting);}
        assertThat(stockBalance(f.oldStock()).getQuantity().add(stockBalance(f.newStock()).getQuantity())).isEqualByComparingTo("2");
    }

    @ParameterizedTest
    @ValueSource(strings={"BALANCE","BATCH"})
    void pickingBusySourceRollsBackAndSameKeyCanRetry(String kind) throws Exception {
        var f=pickingFixture();var r=pickingRequest(f.transfer(),"10",com.enterprise.mm.enums.PickingMethod.FIFO);
        var balanceId=stockBalance(f.oldStock()).getId();
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(() -> {setUp();try {
                new org.springframework.transaction.support.TransactionTemplate(pickingTransactions).executeWithoutResult(tx -> {
                    if(kind.equals("BALANCE"))stockBalanceRepository.findByIdAndIsDeletedFalseForUpdate(balanceId).orElseThrow();
                    else pickingBatches.findByIdAndIsDeletedFalseForUpdate(f.oldStock().getStockBatchId()).orElseThrow();
                    held.countDown();try{if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Release timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                });}finally{clearContext();}});
            try {
                assertThat(held.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> transfers.dispatch(f.transfer().getId(),r,"busy-retry"))
                    .isInstanceOf(com.enterprise.mm.error.ConflictException.class).hasMessageContaining("refresh and retry");
            } finally {release.countDown();}
            holder.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(transfers.get(f.transfer().getId()).getShipments()).isEmpty();
        assertThat(stockBalance(f.oldStock()).getQuantity()).isEqualByComparingTo("6");
        assertThat(stockBalance(f.newStock()).getQuantity()).isEqualByComparingTo("8");
        assertThat(transfers.dispatch(f.transfer().getId(),r,"busy-retry").getShipments()).hasSize(1);
    }

    private PickingFixture pickingFixture() {
        var mat=material("PICK"); var wh=warehouse("Picking"); var oldLoc=storageLocation(wh,"Old rack"); var newLoc=storageLocation(wh,"New rack");
        var result=opening.post(new OpeningStockService.Command(UUID.randomUUID(),"Signed two-position picking fixture",LocalDate.now(),List.of(
            new OpeningStockService.Row("old",mat.getId(),wh.getId(),oldLoc.getId(),null,"EA",new BigDecimal("6"),new BigDecimal("100")),
            new OpeningStockService.Row("new",mat.getId(),wh.getId(),newLoc.getId(),null,"EA",new BigDecimal("8"),new BigDecimal("200")))));
        var old=stockMovementRepository.findByIdAndIsDeletedFalse(result.movements().get(0).getId()).orElseThrow();
        var newer=stockMovementRepository.findByIdAndIsDeletedFalse(result.movements().get(1).getId()).orElseThrow();
        var oldBatch=pickingBatches.findByIdAndIsDeletedFalse(old.getStockBatchId()).orElseThrow(); oldBatch.setReceivedDate(LocalDate.now().minusDays(2)); pickingBatches.saveAndFlush(oldBatch);
        var newBatch=pickingBatches.findByIdAndIsDeletedFalse(newer.getStockBatchId()).orElseThrow(); newBatch.setReceivedDate(LocalDate.now().minusDays(1)); pickingBatches.saveAndFlush(newBatch);
        return new PickingFixture(pickingTransfer(old,"10"),old,newer);
    }
    private TransferDocumentResponse pickingTransfer(StockMovement origin,String qty) {
        authenticate("picking.preparer","ADMIN","MM_MANAGER");
        var dest=warehouse("Picking destination");
        var r=new TransferCreateRequest();r.setName("FIFO LIFO picking");r.setSourceWarehouseId(origin.getWarehouseId());r.setDestinationWarehouseId(dest.getId());r.setPlannedDate(LocalDate.now());
        var l=new TransferCreateRequest.Line();l.setMaterialId(origin.getMaterialId());l.setPlannedQuantity(new BigDecimal(qty));r.setLines(List.of(l));
        var created=transfers.create(r);var submitted=transfers.submit(created.getId());authenticate("picking.approver","ADMIN","MM_MANAGER");
        return com.enterprise.mm.service.approval.ApprovedOperationContext.call(com.enterprise.mm.service.approval.TransferApproveOperationHandler.MODULE_KEY,created.getId(),
            () -> transfers.approve(created.getId(),new TransferApproveRequest(submitted.getVersion())));
    }
    private TransferDispatchRequest pickingRequest(TransferDocumentResponse transfer,String qty,com.enterprise.mm.enums.PickingMethod method) {
        var r=new TransferDispatchRequest();r.setExpectedVersion(transfer.getVersion());r.setDispatchedDate(LocalDate.now());
        var l=new TransferDispatchRequest.Line();l.setTransferLineId(transfer.getLines().getFirst().getId());l.setDispatchedQuantity(new BigDecimal(qty));
        l.setAllocationMode(com.enterprise.mm.enums.StockAllocationMode.AUTO);l.setPickingMethod(method);r.setLines(List.of(l));return r;
    }
    private record PickingFixture(TransferDocumentResponse transfer,StockMovement oldStock,StockMovement newStock) {}

    private TransferDocumentResponse transfer(StockMovement origin, String quantity, boolean damaged) {
        Warehouse dest=warehouse("Destination");StorageLocation location=storageLocation(dest,"Rack B");
        var r=new TransferCreateRequest();r.setName("G2 transfer");r.setSourceWarehouseId(origin.getWarehouseId());r.setSourceLocationId(origin.getStorageLocationId());r.setDestinationWarehouseId(dest.getId());r.setDestinationLocationId(location.getId());r.setPlannedDate(LocalDate.now());
        var line=new TransferCreateRequest.Line();line.setMaterialId(origin.getMaterialId());line.setStockBatchId(origin.getStockBatchId());line.setStorageLocationId(origin.getStorageLocationId());line.setPlannedQuantity(new BigDecimal(quantity));r.setLines(List.of(line));
        var created=transfers.create(r);var submitted=transfers.submit(created.getId());authenticate("independent.approver","ADMIN","MM_MANAGER");var approved=com.enterprise.mm.service.approval.ApprovedOperationContext.call(com.enterprise.mm.service.approval.TransferApproveOperationHandler.MODULE_KEY,created.getId(),()->transfers.approve(created.getId(),new TransferApproveRequest(submitted.getVersion())));
        var tl=transferLines.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT,created.getId()).getFirst();
        var d=new TransferDispatchRequest();d.setExpectedVersion(approved.getVersion());d.setDispatchedDate(LocalDate.now());var dl=new TransferDispatchRequest.Line();dl.setTransferLineId(tl.getId());dl.setDispatchedQuantity(new BigDecimal(quantity));dl.setStockBatchId(origin.getStockBatchId());dl.setStorageLocationId(origin.getStorageLocationId());d.setLines(List.of(dl));
        var dispatched=transfers.dispatch(created.getId(),d,"dispatch");var receive=new TransferReceiveRequest();receive.setExpectedVersion(dispatched.getVersion());receive.setReceivedDate(LocalDate.now());
        receive.setLines(dispatched.getShipments().stream().flatMap(sh->sh.allocations().stream()).map(a->{var l=new TransferReceiveRequest.Line();l.setTransferLineId(tl.getId());l.setShipmentAllocationId(a.id());l.setReceivedQuantity(damaged?BigDecimal.ZERO:a.unresolvedQuantity());l.setDamagedQuantity(damaged?a.unresolvedQuantity():BigDecimal.ZERO);l.setConditionEvidence(damaged?"Damage identified during physical inspection":null);l.setStorageLocationId(location.getId());return l;}).toList());
        return transfers.receive(created.getId(),receive,"receive");
    }
    private void countRemaining(StockMovement origin, String quantity) {
        var c=new com.enterprise.mm.entity.Commission();c.setName("G2 inventory commission");c.setCode("COUNT-"+suffix());c=commissions.saveAndFlush(c);
        var member=new com.enterprise.mm.entity.CommissionMember();member.setName("G2 count member");member.setCommissionId(c.getId());member.setMemberName("Pilot counter");commissionMembers.saveAndFlush(member);
        var r=new InventoryDocumentCreateRequest();r.setName("Count executable opening");r.setWarehouseId(origin.getWarehouseId());r.setStorageLocationId(origin.getStorageLocationId());r.setMaterialIds(List.of(origin.getMaterialId()));r.setCommissionId(c.getId());
        var document=counts.create(r);document=counts.start(document.getId());var items=counts.items(document.getId());assertThat(items).hasSize(1);
        var item=items.getFirst();assertThat(item.getSystemQuantity()).isEqualByComparingTo(quantity);var command=new InventoryCountRequest();command.setExpectedDocumentVersion(document.getVersion());command.setExpectedLineVersion(item.getVersion());command.setCountedQuantity(new BigDecimal(quantity));command.setComment("Physical count reconciles opening, issue and dispatch");
        var result=countItems.count(item.getId(),command,"count");assertThat(result.getCountedQuantity()).isEqualByComparingTo(quantity);
        assertThat(stockBalance(origin).getQuantity()).isEqualByComparingTo(quantity);
        counts.completeCounting(document.getId());
    }
    @Test void damagedReverseReturnStaysHeldUntilExplicitDisposition() {
        var mat=material("RETURN");var wh=warehouse("Source");var loc=storageLocation(wh,"Source rack");var m=movement(opening.post(openingCommand(mat,wh,loc,"1")));
        var t=transfer(m,"1",true);var c=exceptions.list(t.getId()).getFirst();
        c=exceptions.review(c.id(),new TransferExceptionDtos.Review(c.version(),"RETURN_TO_SOURCE","Return damaged unit for quality review"),"return-review");
        c=exceptions.dispatchReturn(c.id(),new TransferExceptionDtos.Post(c.version(),LocalDate.now()),"return-dispatch");var reverseId=c.reverseTransferId();
        exceptions.confirmReturn(c.id(),new TransferExceptionDtos.ReturnReceipt(c.version(),BigDecimal.ONE,loc.getId(),null,LocalDate.now()),"return-receive");
        var reverseCase=exceptions.list(reverseId).getFirst();var returned=stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(TENANT,"TRANSFER_QUARANTINE_RECEIPT",reverseId).getFirst();
        assertThat(stockBalance(returned).getQuantity()).isEqualByComparingTo("1");assertThat(stockBalance(returned).getAvailableQuantity()).isZero();assertThat(stockBalance(returned).getQuarantineQuantity()).isEqualByComparingTo("1");assertThat(stockBalance(returned).getTotalValue()).isEqualByComparingTo("100");
        assertThatThrownBy(()->issue(returned,"1")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->reservations.create(reservation(returned,"1"),"return-reserve")).isInstanceOf(RuntimeException.class);
        reverseCase=exceptions.review(reverseCase.id(),new TransferExceptionDtos.Review(reverseCase.version(),"RELEASE_AFTER_QUALITY","Quality inspected and released repaired unit"),"quality-review");
        exceptions.post(reverseCase.id(),new TransferExceptionDtos.Post(reverseCase.version(),LocalDate.now()),"quality-release");
        assertThat(stockBalance(returned).getAvailableQuantity()).isEqualByComparingTo("1");assertThat(stockBalance(returned).getTotalValue()).isEqualByComparingTo("100");issue(returned,"1");
    }
    @Test void fractionalOpeningAndSerializedBoundary() {
        var mat=material("FRACTION");var u=unitOfMeasureRepository.findById(mat.getUnitOfMeasureId()).orElseThrow();u.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.WEIGHT);u.setMeasurementNameShort("KG");unitOfMeasureRepository.saveAndFlush(u);
        var wh=warehouse("Weight");var loc=storageLocation(wh,"Weight rack");
        var badDraft=new StockMovementCreateRequest();badDraft.setName("Must not round before posting");badDraft.setMaterialId(mat.getId());badDraft.setWarehouseId(wh.getId());badDraft.setMovementType(MovementType.RECEIPT);badDraft.setQuantity(new BigDecimal("1.23456"));badDraft.setUnitCost(BigDecimal.TEN);
        assertThatThrownBy(()->stock.create(badDraft)).hasMessageContaining("not rounded");
var row=new OpeningStockService.Row("1",mat.getId(),wh.getId(),loc.getId(),null,"KG",new BigDecimal("1.2345"),new BigDecimal("100"));
        assertThat(opening.post(new OpeningStockService.Command(UUID.randomUUID(),"Signed weight register",LocalDate.now(),List.of(row))).quantity()).isEqualByComparingTo("1.2345");
        var serial=material("SERIAL");serial.setSerialTrackingPolicy(com.enterprise.mm.enums.SerialTrackingPolicy.REQUIRED);serial=materialRepository.saveAndFlush(serial);var command=openingCommand(serial,wh,loc,"1");
        assertThatThrownBy(()->opening.post(command)).hasMessageContaining("SERIALIZED_EXECUTION");
    }
    @Test void concurrentQualityDecisionReleasesOnce() throws Exception {
        var f=confirmedAsnFixture("QC-RACE","5");var mat=materialRepository.findByIdAndIsDeletedFalse(f.material().getId()).orElseThrow();mat.setIncomingInspectionRequired(true);materialRepository.saveAndFlush(mat);
        var received=purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),versioned(f,"5","0"),"qc-race");var qi=received.getReceiptOutcome().qualityInspections().getFirst();passInspectionThroughLab(qi.qualityInspectionId(),"RACE");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var barrier=new java.util.concurrent.CyclicBarrier(2);java.util.concurrent.Callable<Boolean> call=()->{setUp();try{barrier.await();qualityInspectionService.release(qi.qualityInspectionId(),new QualityDecisionRequest(new BigDecimal("4"),BigDecimal.ONE,"Concurrent decision"));return true;}catch(BusinessException expected){return false;}finally{clearContext();}};
            var a=pool.submit(call);var b=pool.submit(call);assertThat(List.of(a.get(45,java.util.concurrent.TimeUnit.SECONDS),b.get(45,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        var m=stockMovementRepository.findByIdAndIsDeletedFalse(qi.stockMovementId()).orElseThrow();assertThat(stockBalance(m).getAvailableQuantity()).isEqualByComparingTo("4");assertThat(stockBalance(m).getTotalValue()).isEqualByComparingTo("500");
    }
    @Test void concurrentOrdinaryReceiptsCannotOverReceive() throws Exception {
        var f=confirmedAsnFixture("RECEIVE-RACE","10");var request=versioned(f,"6","0");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var barrier=new java.util.concurrent.CyclicBarrier(2);java.util.concurrent.Callable<Boolean> call=()->{setUp();try{barrier.await();purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),request,UUID.randomUUID().toString());return true;}catch(com.enterprise.mm.error.ConflictException expected){return false;}finally{clearContext();}};
            var a=pool.submit(call);var b=pool.submit(call);assertThat(List.of(a.get(45,java.util.concurrent.TimeUnit.SECONDS),b.get(45,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow().getReceivedQuantity()).isEqualByComparingTo("6");
    }

    @org.springframework.boot.test.mock.mockito.SpyBean com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService receiptPosting;
    @Test void httpReceivingRetryAndChangedPayloadAreAuthoritative() throws Exception {
        var f=confirmedAsnFixture("HTTP","10");var request=versioned(f,"10","0");var url="/api/purchase-orders/"+f.purchaseOrder().getId()+"/receive";
        var builder=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("http-admin").roles("ADMIN"))
            .header("X-Company-Id",DEFAULT_COMPANY_ID).header("Idempotency-Key","http-retry").contentType("application/json").content(json.writeValueAsString(request));
        var first=mvc.perform(builder).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        mvc.perform(builder).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        setUp();
        var changed=versioned(f,"9","0");mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("http-admin").roles("ADMIN"))
            .header("X-Company-Id",DEFAULT_COMPANY_ID).header("Idempotency-Key","http-retry").contentType("application/json").content(json.writeValueAsString(changed)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        setUp();assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(TENANT,"PurchaseOrder",f.purchaseOrder().getId())).hasSize(1);
    }
    @Test void receiptFailureAfterStockMutationRollsBackAndRetryPostsOnce() {
        var f=confirmedAsnFixture("RECEIPT-ROLLBACK","10");var request=versioned(f,"10","0");
        com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(receiptPosting);
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new BusinessException("Controlled receipt failure after evidence");}).when(target).record(org.mockito.ArgumentMatchers.any());
        try { assertThatThrownBy(()->purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),request,"rollback-receive")).hasMessageContaining("Controlled receipt failure"); }
        finally { org.mockito.Mockito.reset(target); }
        assertThat(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow().getReceivedQuantity()).isZero();
        assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(TENANT,"PurchaseOrder",f.purchaseOrder().getId())).isEmpty();
        purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),request,"rollback-receive");purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),request,"rollback-receive");
        assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(TENANT,"PurchaseOrder",f.purchaseOrder().getId())).hasSize(1);
    }

    @Autowired com.enterprise.mm.repository.StorageBinRepository b09Bins;
    @Test void binnedIncomingReceiptCanBeQuarantinedAndReleasedAtItsExactPosition() {
        var f=confirmedAsnFixture("BIN-QC","5");
        var material=materialRepository.findByIdAndIsDeletedFalse(f.material().getId()).orElseThrow();
        material.setIncomingInspectionRequired(true);materialRepository.saveAndFlush(material);
        var bin=new com.enterprise.mm.entity.StorageBin();bin.setName("Inspection bin");bin.setBinCode("QC-"+suffix());
        bin.setWarehouseId(f.warehouse().getId());bin.setStorageLocationId(f.location().getId());bin=b09Bins.saveAndFlush(bin);
        var request=versioned(f,"5","0");
        request=new ReceivePoRequest(request.warehouseId(),request.storageLocationId(),request.asnId(),request.lines(),bin.getId(),request.version(),request.evidenceAttachmentIds());
        var result=purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),request,"binned-receipt");
        var qi=result.getReceiptOutcome().qualityInspections().getFirst();
        var movement=stockMovementRepository.findByIdAndIsDeletedFalse(qi.stockMovementId()).orElseThrow();
        assertThat(movement.getStorageBinId()).isEqualTo(bin.getId());
        var balances=stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TENANT,f.material().getId(),f.warehouse().getId());
        assertThat(balances).hasSize(1);assertThat(balances.getFirst().getAvailableQuantity()).isZero();
        assertThat(balances.getFirst().getQuarantineQuantity()).isEqualByComparingTo("5");
        passInspectionThroughLab(qi.qualityInspectionId(),"BIN-QC");authenticate("quality","MM_MANAGER");
        qualityInspectionService.release(qi.qualityInspectionId(),new QualityDecisionRequest(new BigDecimal("4"),BigDecimal.ONE,"Binned quality decision"));
        balances=stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TENANT,f.material().getId(),f.warehouse().getId());
        assertThat(balances.getFirst().getStorageBinId()).isEqualTo(bin.getId());
        assertThat(balances.getFirst().getAvailableQuantity()).isEqualByComparingTo("4");
        assertThat(balances.getFirst().getQuarantineQuantity()).isEqualByComparingTo("1");
        assertThat(balances.getFirst().getTotalValue()).isEqualByComparingTo("500");
    }

    @Test void everyIncomingBatchIsHeldAndOneReleaseCannotClearAnotherBatch() {
        var f=confirmedAsnFixture("QC-BATCHES","5");var mat=materialRepository.findByIdAndIsDeletedFalse(f.material().getId()).orElseThrow();mat.setIncomingInspectionRequired(true);mat.setBatchTrackingPolicy(com.enterprise.mm.enums.BatchTrackingPolicy.OPTIONAL);materialRepository.saveAndFlush(mat);
        var item=purchaseOrderItemRepository.findByIdAndIsDeletedFalse(f.item().getId()).orElseThrow();item.setBatchTrackingSnapshot(com.enterprise.mm.enums.BatchTrackingPolicy.OPTIONAL);purchaseOrderItemRepository.saveAndFlush(item);
        var batches=List.of(new com.enterprise.mm.payload.BusinessActionDtos.ReceivePoBatchRequest("A","BATCH-A",null,null,new BigDecimal("2"),List.of()),new com.enterprise.mm.payload.BusinessActionDtos.ReceivePoBatchRequest("B","BATCH-B",null,null,new BigDecimal("3"),List.of()));
        var line=new ReceivePoLineRequest(item.getId(),new BigDecimal("5"),BigDecimal.ZERO,BigDecimal.ZERO,null,null,f.location().getId(),null,null,new BigDecimal("5"),List.of(),batches);
        var response=purchaseOrderService.receiveIdempotent(f.purchaseOrder().getId(),new ReceivePoRequest(f.warehouse().getId(),f.location().getId(),f.asn().getId(),List.of(line),null,purchaseOrderService.get(f.purchaseOrder().getId()).getVersion(),List.of()),"batches");
        var inspections=response.getReceiptOutcome().qualityInspections();assertThat(inspections).hasSize(2);
        for(var qi:inspections){var m=stockMovementRepository.findByIdAndIsDeletedFalse(qi.stockMovementId()).orElseThrow();assertThat(stockBalance(m).getAvailableQuantity()).isZero();}
        var first=inspections.getFirst();passInspectionThroughLab(first.qualityInspectionId(),"BATCH-A");qualityInspectionService.release(first.qualityInspectionId(),new QualityDecisionRequest(first.quantity().subtract(BigDecimal.ONE),BigDecimal.ONE,"First batch partially released"));
        assertThat(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(item.getId()).orElseThrow().getQualityStatus()).isEqualTo("PENDING_INSPECTION");
        var second=inspections.getLast();var secondMovement=stockMovementRepository.findByIdAndIsDeletedFalse(second.stockMovementId()).orElseThrow();assertThat(stockBalance(secondMovement).getAvailableQuantity()).isZero();
        passInspectionThroughLab(second.qualityInspectionId(),"BATCH-B");qualityInspectionService.release(second.qualityInspectionId(),new QualityDecisionRequest(second.quantity(),BigDecimal.ZERO,"Second batch released"));
        var completed=purchaseOrderItemRepository.findByIdAndIsDeletedFalse(item.getId()).orElseThrow();
        assertThat(completed.getAcceptedQuantity()).isEqualByComparingTo("4");
        assertThat(completed.getRejectedQuantity()).isEqualByComparingTo("1");
        assertThat(completed.getQualityStatus()).isEqualTo("ACCEPTED_WITH_DEVIATION");
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
        unit.setCode("S1BE-UOM-" + label + "-" + suffix());
        unit.setName("Stage 1B-E unit " + label);
        unit.setStatus("ACTIVE");
        unit.setMeasurementNameShort("EA");
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);
        unit.setMeasurementNameLong("Pieces");
        UnitOfMeasure savedUnit = unitOfMeasureRepository.save(unit);

        Material material = new Material();
        material.setCode("S1BE-MAT-" + label + "-" + suffix());
        material.setName("Stage 1B-E material " + label);
        material.setStatus("ACTIVE");
        material.setMaterialType(MaterialType.TMB);
        material.setMaterialGroupId(materialGroup(label).getId());
        material.setUnitOfMeasureId(savedUnit.getId());
        material.setUnitOfMeasure(savedUnit);
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
