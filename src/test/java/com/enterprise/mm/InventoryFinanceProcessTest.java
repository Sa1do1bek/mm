package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.ImportDeliveryResponse;
import com.enterprise.mm.payload.InventoryAccountingReconciliationResponse;
import com.enterprise.mm.payload.InventoryFinanceDtos.InventoryPeriodClosePrepareRequest;
import com.enterprise.mm.payload.InventoryFxDifferenceResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseResponse;
import com.enterprise.mm.payload.LandedCostAllocationResponse;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.ImportDeliveryCreateRequest;
import com.enterprise.mm.payload.InventoryAccountingReconciliationCreateRequest;
import com.enterprise.mm.payload.InventoryFxDifferenceCreateRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseCreateRequest;
import com.enterprise.mm.payload.LandedCostAllocationCreateRequest;
import com.enterprise.mm.payload.LandedCostAllocationLineCreateRequest;
import com.enterprise.mm.payload.StockMovementCreateRequest;
import com.enterprise.mm.service.ImportDeliveryService;
import com.enterprise.mm.service.InventoryAccountingReconciliationService;
import com.enterprise.mm.service.InventoryFxDifferenceService;
import com.enterprise.mm.service.InventoryPeriodCloseService;
import com.enterprise.mm.service.LandedCostAllocationLineService;
import com.enterprise.mm.service.LandedCostAllocationService;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.StockMovementApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "finance-controller", roles = "FINANCE_CONTROLLER")
class InventoryFinanceProcessTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired com.enterprise.mm.repository.UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired ImportDeliveryService importDeliveryService;
    @Autowired StockMovementService stockMovementService;
    @Autowired LandedCostAllocationService landedCostAllocationService;
    @Autowired LandedCostAllocationLineService landedCostAllocationLineService;
    @Autowired InventoryFxDifferenceService fxDifferenceService;
    @Autowired InventoryAccountingReconciliationService reconciliationService;
    @Autowired InventoryPeriodCloseService periodCloseService;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalFlowStepRepository approvalFlowStepRepository;

    @BeforeEach
    void ensureStockMovementApprovalFlow() {
        ApprovalFlow saved = approvalFlowRepository
            .findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                StockMovementApprovalAdapter.MODULE_KEY
            ).orElseGet(() -> {
                ApprovalFlow flow = new ApprovalFlow();
                flow.setCode("TEST-SM-FLOW-" + UUID.randomUUID().toString().substring(0, 8));
                flow.setName("Test stock movement approval flow");
                flow.setStatus("ACTIVE");
                flow.setModuleKey(StockMovementApprovalAdapter.MODULE_KEY);
                flow.setActive(true);
                return approvalFlowRepository.save(flow);
            });
        if (approvalFlowStepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(), saved.getId()).isEmpty()) {
            ApprovalFlowStep step = new ApprovalFlowStep();
            step.setApprovalFlowId(saved.getId());
            step.setCompanyId(saved.getCompanyId());
            step.setCode("TEST-SM-STEP-" + UUID.randomUUID().toString().substring(0, 8));
            step.setName("Stock movement approval step");
            step.setStatus("ACTIVE");
            step.setStepOrder(1);
            step.setAssigneeType(ApprovalAssigneeType.ROLE);
            step.setAssigneeRole("ADMIN");
            step.setCanReturn(true);
            approvalFlowStepRepository.save(step);
        }
    }

    @Test
    void inventoryFinanceProcessesCapitalizeLandedCostPostFxAndClosePeriod() {
        Material material = material("MAT-FIN");
        Warehouse warehouse = warehouse("WH-FIN");
        Supplier supplier = supplier("SUP-FIN");
        LocalDate periodStart = LocalDate.now().minusDays(10);
        LocalDate periodEnd = LocalDate.now().minusDays(9);

        StockMovement receipt = new StockMovement();
        receipt.setName("Finance receipt");
        receipt.setMaterialId(material.getId());
        receipt.setWarehouseId(warehouse.getId());
        receipt.setMovementType(MovementType.RECEIPT);
        receipt.setQuantity(BigDecimal.TEN);
        receipt.setUnitCost(BigDecimal.valueOf(100));
        StockMovementResponse postedReceipt = stockMovementService.createAndPost(receipt);
        UUID batchId = stockMovementRepository.findAnyById(postedReceipt.getId()).orElseThrow().getStockBatchId();

        ImportDeliveryCreateRequest deliveryRequest = new ImportDeliveryCreateRequest();
        deliveryRequest.setName("Finance import delivery");
        deliveryRequest.setShipmentNumber("SHIP-FIN-" + UUID.randomUUID().toString().substring(0, 8));
        deliveryRequest.setSupplierId(supplier.getId());
        deliveryRequest.setDestinationWarehouseId(warehouse.getId());
        deliveryRequest.setBaseGoodsAmount(BigDecimal.valueOf(1000));
        deliveryRequest.setCurrencyCode("USD");
        ImportDeliveryResponse delivery = importDeliveryService.create(deliveryRequest);

        LandedCostAllocationCreateRequest allocationRequest = new LandedCostAllocationCreateRequest();
        allocationRequest.setName("Freight capitalization");
        allocationRequest.setImportDeliveryId(delivery.getId());
        allocationRequest.setAllocationMethod("QUANTITY");
        allocationRequest.setDocumentDate(periodStart);
        allocationRequest.setCurrencyCode("USD");
        allocationRequest.setBaseCurrencyCode("UZS");
        allocationRequest.setExchangeRate(BigDecimal.ONE);
        allocationRequest.setTotalAmount(BigDecimal.valueOf(100));
        LandedCostAllocationResponse allocation = landedCostAllocationService.create(allocationRequest);

        LandedCostAllocationLineCreateRequest line = new LandedCostAllocationLineCreateRequest();
        line.setName("Freight line");
        line.setAllocationDocumentId(allocation.getId());
        line.setMaterialId(material.getId());
        line.setWarehouseId(warehouse.getId());
        line.setStockBatchId(batchId);
        line.setQuantity(BigDecimal.TEN);
        line.setAllocationBase(BigDecimal.TEN);
        landedCostAllocationLineService.create(line);

        allocation = landedCostAllocationService.post(allocation.getId());

        assertThat(allocation.getStatus()).isEqualTo("POSTED");
        assertThat(allocation.getBaseTotalAmount()).isEqualByComparingTo("100.0000");
        var balance = stockBalanceRepository.findCompanyBalance(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            material.getId(),
            warehouse.getId(),
            true,
            null,
            false,
            batchId
        ).orElseThrow();
        assertThat(balance.getTotalValue()).isEqualByComparingTo("1100.0000");
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "INVENTORY.LANDED_COST_CAPITALIZED".equals(event.getEventType()));

        InventoryFxDifferenceCreateRequest fxRequest = new InventoryFxDifferenceCreateRequest();
        fxRequest.setName("Import invoice FX difference");
        fxRequest.setPeriodStart(periodStart);
        fxRequest.setPeriodEnd(periodEnd);
        fxRequest.setDocumentDate(periodEnd);
        fxRequest.setReferenceType("IMPORT_DELIVERY");
        fxRequest.setReferenceId(delivery.getId());
        fxRequest.setMaterialId(material.getId());
        fxRequest.setWarehouseId(warehouse.getId());
        fxRequest.setSupplierId(supplier.getId());
        fxRequest.setDocumentCurrencyCode("USD");
        fxRequest.setBaseCurrencyCode("UZS");
        fxRequest.setForeignAmount(BigDecimal.valueOf(100));
        fxRequest.setDocumentRate(BigDecimal.valueOf(12000));
        fxRequest.setClosingRate(BigDecimal.valueOf(12500));
        InventoryFxDifferenceResponse fx = fxDifferenceService.post(fxDifferenceService.create(fxRequest).getId());
        assertThat(fx.getStatus()).isEqualTo("POSTED");
        assertThat(fx.getDifferenceAmount()).isEqualByComparingTo("50000.0000");
        assertThat(fx.getDifferenceType()).isEqualTo("LOSS");
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "INVENTORY.FX_DIFFERENCE_POSTED".equals(event.getEventType()));

        StockMovementCreateRequest openMovement = new StockMovementCreateRequest();
        openMovement.setName("Draft period movement");
        openMovement.setMaterialId(material.getId());
        openMovement.setWarehouseId(warehouse.getId());
        openMovement.setMovementType(MovementType.RECEIPT);
        openMovement.setQuantity(BigDecimal.ONE);
        openMovement.setUnitCost(BigDecimal.TEN);
        openMovement.setMovementDate(periodStart);
        StockMovementResponse draftMovement = stockMovementService.create(openMovement);

        InventoryPeriodCloseCreateRequest blockedByOpenMovementRequest = new InventoryPeriodCloseCreateRequest();
        blockedByOpenMovementRequest.setName("Blocked by open movement");
        blockedByOpenMovementRequest.setPeriodStart(periodStart);
        blockedByOpenMovementRequest.setPeriodEnd(periodEnd);
        blockedByOpenMovementRequest.setWarehouseId(warehouse.getId());
        InventoryPeriodCloseResponse blockedByOpenMovement = periodCloseService.calculate(periodCloseService.create(blockedByOpenMovementRequest).getId());
        assertThat(blockedByOpenMovement.getOpenMovementCount()).isEqualTo(1);
        InventoryPeriodCloseResponse finalBlockedByOpenMovement = blockedByOpenMovement;
        assertThatThrownBy(() -> approvedClose(finalBlockedByOpenMovement.getId()))
            .hasMessageContaining("open stock movements");

        stockMovementRepository.findAnyById(draftMovement.getId()).ifPresent(stockMovementRepository::delete);

        InventoryPeriodCloseCreateRequest blockedByMissingReconciliationRequest = new InventoryPeriodCloseCreateRequest();
        blockedByMissingReconciliationRequest.setName("Blocked by missing FI");
        blockedByMissingReconciliationRequest.setPeriodStart(periodStart);
        blockedByMissingReconciliationRequest.setPeriodEnd(periodEnd);
        blockedByMissingReconciliationRequest.setWarehouseId(warehouse.getId());
        InventoryPeriodCloseResponse blockedByMissingReconciliation = periodCloseService.calculate(periodCloseService.create(blockedByMissingReconciliationRequest).getId());
        InventoryPeriodCloseResponse finalBlockedByMissingReconciliation = blockedByMissingReconciliation;
        assertThatThrownBy(() -> approvedClose(finalBlockedByMissingReconciliation.getId()))
            .hasMessageContaining("Posted FI accounting reconciliation is required");

        InventoryAccountingReconciliationCreateRequest reconciliationRequest = new InventoryAccountingReconciliationCreateRequest();
        reconciliationRequest.setName("Inventory reconciliation");
        reconciliationRequest.setPeriodStart(periodStart);
        reconciliationRequest.setPeriodEnd(periodEnd);
        reconciliationRequest.setWarehouseId(warehouse.getId());
        reconciliationRequest.setAccountingSystem("FI");
        reconciliationRequest.setAccountingValue(balance.getTotalValue());
        reconciliationRequest.setToleranceAmount(BigDecimal.ZERO);
        InventoryAccountingReconciliationResponse reconciliation = reconciliationService.reconcile(reconciliationService.create(reconciliationRequest).getId());
        assertThat(reconciliation.getStatus()).isEqualTo("RECONCILED");
        assertThat(reconciliation.getDifferenceStatus()).isEqualTo("MATCHED");
        reconciliation = reconciliationService.post(reconciliation.getId());
        assertThat(reconciliation.getStatus()).isEqualTo("POSTED");
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "INVENTORY.ACCOUNTING_RECONCILIATION_POSTED".equals(event.getEventType()));

        InventoryPeriodCloseCreateRequest closeRequest = new InventoryPeriodCloseCreateRequest();
        closeRequest.setName("Past inventory close");
        closeRequest.setPeriodStart(periodStart);
        closeRequest.setPeriodEnd(periodEnd);
        closeRequest.setWarehouseId(warehouse.getId());
        closeRequest.setAccountingReconciliationId(reconciliation.getId());
        InventoryPeriodCloseResponse close = approvedClose(periodCloseService.create(closeRequest).getId());

        assertThat(close.getStatus()).isEqualTo("CLOSED");
        assertThat(close.getTotalStockValue()).isEqualByComparingTo("1100.0000");
        assertThat(close.getLandedCostAmount()).isEqualByComparingTo("100.0000");
        assertThat(close.getFxDifferenceAmount()).isEqualByComparingTo("50000.0000");
        assertThat(close.getOpenMovementCount()).isZero();
        assertThat(close.getAccountingReconciliationStatus()).isEqualTo("MATCHED");
        assertThat(close.getAccountingDifferenceAmount()).isEqualByComparingTo("0.0000");
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "INVENTORY.PERIOD_CLOSED".equals(event.getEventType()));

        var prepared = periodCloseService.prepare(new InventoryPeriodClosePrepareRequest(
            LocalDate.now().minusDays(8),
            LocalDate.now().minusDays(7),
            warehouse.getId(),
            "FI",
            "FI-BALANCE-001",
            balance.getTotalValue(),
            BigDecimal.ZERO,
            true,
            false
        ));
        assertThat(prepared.readyToClose()).isTrue();
        assertThat(prepared.periodClose().getStatus()).isEqualTo("CALCULATED");
        assertThat(approvedClose(prepared.periodClose().getId()).getStatus()).isEqualTo("CLOSED");
        assertThat(prepared.accountingReconciliation().getStatus()).isEqualTo("POSTED");

        StockMovement lateReceipt = new StockMovement();
        lateReceipt.setName("Late closed-period receipt");
        lateReceipt.setMaterialId(material.getId());
        lateReceipt.setWarehouseId(warehouse.getId());
        lateReceipt.setMovementType(MovementType.RECEIPT);
        lateReceipt.setQuantity(BigDecimal.ONE);
        lateReceipt.setUnitCost(BigDecimal.TEN);
        lateReceipt.setMovementDate(periodStart);
        assertThatThrownBy(() -> stockMovementService.createAndPost(lateReceipt))
            .hasMessageContaining("Inventory period is closed");
    }

    private Material material(String prefix) {
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        com.enterprise.mm.entity.UnitOfMeasure unit = new com.enterprise.mm.entity.UnitOfMeasure();
        unit.setCode("UOM-" + UUID.randomUUID().toString().substring(0, 8));
        unit.setName("Finance stock unit");
        unit.setStatus("ACTIVE");
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.WEIGHT);
        unit.setStockQuantityScale(7);
        unit = unitOfMeasureRepository.save(unit);
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setUnitOfMeasureId(unit.getId());
        material.setUnitOfMeasure(unit);
        return materialRepository.save(material);
    }

    private Warehouse warehouse(String prefix) {
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private InventoryPeriodCloseResponse approvedClose(UUID id) {
        return com.enterprise.mm.service.approval.ApprovedOperationContext.call(
            com.enterprise.mm.service.approval.InventoryPeriodCloseOperationHandler.MODULE_KEY,
            id,
            () -> periodCloseService.close(id)
        );
    }

    private Supplier supplier(String prefix) {
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setCategory("A");
        supplier.setRating(BigDecimal.valueOf(90));
        return supplierRepository.save(supplier);
    }
}

