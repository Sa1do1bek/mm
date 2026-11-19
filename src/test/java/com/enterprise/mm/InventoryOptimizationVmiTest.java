package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.DemandForecastResponse;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationResponse;
import com.enterprise.mm.payload.InventoryPlanningDtos.ConsignmentConsumptionRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.DemandForecastRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.EoqCalculationRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.ForecastBatchRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.VmiReviewResult;
import com.enterprise.mm.payload.VmiStockAgreementResponse;
import com.enterprise.mm.payload.VmiStockAgreementCreateRequest;
import com.enterprise.mm.service.DemandForecastService;
import com.enterprise.mm.service.InventoryOptimizationRecommendationService;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.service.VmiStockAgreementService;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.DemandForecastMethod;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.VmiAgreementStatus;
import com.enterprise.mm.enums.VmiStockType;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.ConsignmentStockTransactionRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "inventory-manager", roles = "MM_MANAGER")
class InventoryOptimizationVmiTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired ConsignmentStockTransactionRepository consignmentTransactionRepository;
    @Autowired StockMovementService stockMovementService;
    @Autowired DemandForecastService demandForecastService;
    @Autowired InventoryOptimizationRecommendationService optimizationService;
    @Autowired VmiStockAgreementService vmiStockAgreementService;

    @Test
    void demandForecastEoqAndVmiConsignmentFlowWorks() {
        Material material = material("INV-MAT");
        Warehouse warehouse = warehouse("INV-WH");
        Supplier supplier = supplier("INV-SUP");
        UUID stockBatchId = seedReceipt(material, warehouse, BigDecimal.valueOf(500));
        seedIssue(material, warehouse, stockBatchId, BigDecimal.valueOf(10), LocalDateTime.now().minusMonths(5));
        seedIssue(material, warehouse, stockBatchId, BigDecimal.valueOf(20), LocalDateTime.now().minusMonths(4));
        seedIssue(material, warehouse, stockBatchId, BigDecimal.valueOf(30), LocalDateTime.now().minusMonths(3));
        seedIssue(material, warehouse, stockBatchId, BigDecimal.valueOf(40), LocalDateTime.now().minusMonths(2));
        seedIssue(material, warehouse, stockBatchId, BigDecimal.valueOf(50), LocalDateTime.now().minusMonths(1));

        DemandForecastResponse forecast = demandForecastService.generate(new DemandForecastRequest(
            material.getId(),
            warehouse.getId(),
            DemandForecastMethod.WEIGHTED_MOVING_AVERAGE.name(),
            6,
            3,
            15,
            BigDecimal.valueOf(0.95)
        ));
        assertThat(forecast.getForecastQuantity()).isGreaterThan(BigDecimal.ZERO);
        assertThat(forecast.getAnnualizedForecastQuantity()).isGreaterThan(BigDecimal.ZERO);
        assertThat(forecast.getModelQuality()).isIn("LOW", "MEDIUM", "HIGH");
        assertThat(forecast.getLeadTimeDemand()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(forecast.getRecommendedReorderPoint()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        InventoryOptimizationRecommendationResponse eoq = optimizationService.calculateEoq(new EoqCalculationRequest(
            material.getId(),
            warehouse.getId(),
            forecast.getId(),
            null,
            BigDecimal.valueOf(50),
            BigDecimal.valueOf(2),
            BigDecimal.valueOf(10),
            null,
            15,
            BigDecimal.valueOf(0.95)
        ));
        assertThat(eoq.getEoqQuantity()).isGreaterThan(BigDecimal.ZERO);
        assertThat(eoq.getOrderFrequencyDays()).isGreaterThan(BigDecimal.ZERO);
        assertThat(eoq.getAnnualOrderingCost()).isGreaterThan(BigDecimal.ZERO);
        assertThat(eoq.getAnnualHoldingCost()).isGreaterThan(BigDecimal.ZERO);
        assertThat(eoq.getAnnualTotalCost()).isGreaterThan(BigDecimal.ZERO);
        assertThat(eoq.getRecommendedMaxStock()).isGreaterThan(eoq.getRecommendedReorderPoint());
        optimizationService.applyToMaterial(eoq.getId());
        Material optimized = materialRepository.findAnyById(material.getId()).orElseThrow();
        assertThat(optimized.getMaxStock()).isEqualByComparingTo(eoq.getRecommendedMaxStock());
        assertThat(optimized.getReorderPoint()).isEqualByComparingTo(eoq.getRecommendedReorderPoint());

        setBalance(material, warehouse, BigDecimal.valueOf(5));
        VmiStockAgreementCreateRequest agreementRequest = new VmiStockAgreementCreateRequest();
        agreementRequest.setName("Supplier managed consignment stock");
        agreementRequest.setSupplierId(supplier.getId());
        agreementRequest.setMaterialId(material.getId());
        agreementRequest.setWarehouseId(warehouse.getId());
        agreementRequest.setStockType(VmiStockType.CONSIGNMENT.name());
        agreementRequest.setReorderPoint(BigDecimal.TEN);
        agreementRequest.setTargetStock(BigDecimal.valueOf(30));
        agreementRequest.setMinStock(BigDecimal.valueOf(5));
        agreementRequest.setMaxStock(BigDecimal.valueOf(40));
        VmiStockAgreementResponse agreement = vmiStockAgreementService.create(agreementRequest);
        assertThat(agreement.getStatus()).isEqualTo(VmiAgreementStatus.ACTIVE.name());
        UUID agreementId = agreement.getId();

        VmiReviewResult review = vmiStockAgreementService.review(agreementId);
        assertThat(review.replenishmentRequired()).isTrue();
        assertThat(review.recommendedReplenishmentQuantity()).isEqualByComparingTo("25");
        assertThat(review.integrationEventId()).isNotNull();
        assertThat(integrationEventRepository.findAnyById(review.integrationEventId())).isPresent();

        vmiStockAgreementService.consumeConsignment(agreementId, new ConsignmentConsumptionRequest(
            null,
            BigDecimal.valueOf(3),
            BigDecimal.valueOf(10),
            "CONS-SETTLE-1",
            "Consumed by maintenance"
        ));
        assertThat(consignmentTransactionRepository.findAll()).anyMatch(transaction ->
            agreementId.equals(transaction.getVmiAgreementId())
                && "CONSUMPTION".equals(transaction.getTransactionType())
                && transaction.getAmount().compareTo(BigDecimal.valueOf(30)) == 0
        );
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "CONSIGNMENT_CONSUMED".equals(event.getEventType()));
    }

    @Test
    void batchForecastGeneratesEoqRecommendationsAndCanApplyMaterialSettings() {
        Material material = material("INV-BATCH-MAT");
        Warehouse warehouse = warehouse("INV-BATCH-WH");
        UUID stockBatchId = seedReceipt(material, warehouse, BigDecimal.valueOf(100));
        seedIssue(material, warehouse, stockBatchId, BigDecimal.valueOf(12), LocalDateTime.now().minusMonths(2));
        seedIssue(material, warehouse, stockBatchId, BigDecimal.valueOf(18), LocalDateTime.now().minusMonths(1));

        var result = demandForecastService.generateBatch(new ForecastBatchRequest(
            warehouse.getId(),
            DemandForecastMethod.MOVING_AVERAGE.name(),
            3,
            2,
            20,
            BigDecimal.valueOf(0.95),
            BigDecimal.valueOf(50),
            BigDecimal.valueOf(0.20),
            true,
            true
        ));

        assertThat(result.materialCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.forecastCount()).isEqualTo(result.materialCount());
        assertThat(result.eoqRecommendationCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.appliedRecommendationCount()).isGreaterThanOrEqualTo(1);
        assertThat(materialRepository.findAnyById(material.getId()).orElseThrow().getMaxStock()).isNotNull();
    }

    private UUID seedReceipt(Material material, Warehouse warehouse, BigDecimal quantity) {
        StockMovement movement = new StockMovement();
        movement.setCode("RCPT-" + suffix());
        movement.setName("Receipt");
        movement.setMaterialId(material.getId());
        movement.setWarehouseId(warehouse.getId());
        movement.setMovementType(MovementType.RECEIPT);
        movement.setQuantity(quantity);
        movement.setUnitCost(BigDecimal.TEN);
        return stockMovementService.createAndPost(movement).getStockBatchId();
    }

    private void seedIssue(Material material, Warehouse warehouse, UUID stockBatchId, BigDecimal quantity, LocalDateTime postedAt) {
        StockMovement movement = new StockMovement();
        movement.setCode("ISS-" + suffix());
        movement.setName("Issue");
        movement.setMaterialId(material.getId());
        movement.setStockBatchId(stockBatchId);
        movement.setWarehouseId(warehouse.getId());
        movement.setMovementType(MovementType.ISSUE);
        movement.setQuantity(quantity);
        movement.setUnitCost(BigDecimal.TEN);
        var posted = stockMovementService.createAndPost(movement);
        StockMovement saved = stockMovementRepository.findAnyById(posted.getId()).orElseThrow();
        saved.setPostedAt(postedAt);
        stockMovementRepository.save(saved);
    }

    private void setBalance(Material material, Warehouse warehouse, BigDecimal quantity) {
        StockBalance balance = stockBalanceRepository.findBalance(TenantContext.currentTenant(), material.getId(), warehouse.getId(), true, null, true, null)
            .orElseGet(StockBalance::new);
        balance.setCode("BAL-" + suffix());
        balance.setName("VMI balance");
        balance.setStatus("ACTIVE");
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setQuantity(quantity);
        balance.setAvailableQuantity(quantity);
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(quantity.multiply(BigDecimal.TEN));
        stockBalanceRepository.save(balance);
    }

    @Autowired com.enterprise.mm.repository.UnitOfMeasureRepository stockUnits;

    private Material material(String prefix) {
        String code = prefix + "-" + suffix();
        Material material = new Material();
        var unit = new com.enterprise.mm.entity.UnitOfMeasure();
        unit.setCode("EA-" + suffix()); unit.setName("Each"); unit.setMeasurementNameShort("EA");
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);
        unit = stockUnits.saveAndFlush(unit);
        material.setUnitOfMeasureId(unit.getId()); material.setUnitOfMeasure(unit);
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Warehouse warehouse(String prefix) {
        String code = prefix + "-" + suffix();
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private Supplier supplier(String prefix) {
        String code = prefix + "-" + suffix();
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setRating(BigDecimal.valueOf(90));
        supplier.setCategory("A");
        return supplierRepository.save(supplier);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

