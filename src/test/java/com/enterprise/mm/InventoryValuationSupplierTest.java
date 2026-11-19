package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.BusinessActionDtos.SupplierEvaluationRequest;
import com.enterprise.mm.payload.MaterialMarketPriceCreateRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileSignatureRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileWarehouseRequest;
import com.enterprise.mm.payload.NonMovingStockDecisionCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.payload.StockMovementPostRequest;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.StockMovementCreateRequest;
import com.enterprise.mm.payload.StockMovementStatusChangeRequest;
import com.enterprise.mm.payload.StockMovementUpdateRequest;
import com.enterprise.mm.payload.StockValuationDocumentCreateRequest;
import com.enterprise.mm.payload.SupplierCreateRequest;
import com.enterprise.mm.payload.SupplierResponse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.MobileWarehouseService;
import com.enterprise.mm.service.NonMovingStockCaseService;
import com.enterprise.mm.service.NonMovingStockService;
import com.enterprise.mm.service.InventoryProcessingService;
import com.enterprise.mm.service.MaterialMarketPriceService;
import com.enterprise.mm.service.StockMovementDocumentService;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.service.StockValuationDocumentService;
import com.enterprise.mm.service.StockValuationService;
import com.enterprise.mm.service.SupplierEligibilityService;
import com.enterprise.mm.service.SupplierEvaluationService;
import com.enterprise.mm.service.SupplierService;
import com.enterprise.mm.service.WarehouseIdentificationService;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.enums.InventoryLineStatus;
import com.enterprise.mm.enums.MaterialMarketPriceSourceType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.enums.NonMovingStockDecisionType;
import com.enterprise.mm.enums.NonMovingStockExecutionStatus;
import com.enterprise.mm.enums.NonMovingStockStatus;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.InventoryDocumentRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.InventoryItemRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StockValuationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.NonMovingStockDecisionApprovalAdapter;
import com.enterprise.mm.service.approval.StockMovementApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "tester", roles = "ADMIN")
class InventoryValuationSupplierTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired com.enterprise.mm.repository.UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired CommissionRepository commissionRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired StockValuationRepository stockValuationRepository;
    @Autowired WarehouseIdentificationService warehouseIdentificationService;
    @Autowired MobileWarehouseService mobileWarehouseService;
    @Autowired StockMovementService stockMovementService;
    @Autowired StockMovementDocumentService stockMovementDocumentService;
    @Autowired InventoryProcessingService inventoryProcessingService;
    @Autowired com.enterprise.mm.repository.AttachmentRepository attachments;
    @Autowired StockValuationService stockValuationService;
    @Autowired StockValuationDocumentService stockValuationDocumentService;
    @Autowired SupplierEligibilityService supplierEligibilityService;
    @Autowired SupplierEvaluationService supplierEvaluationService;
    @Autowired SupplierService supplierService;
    @Autowired NonMovingStockService nonMovingStockService;
    @Autowired NonMovingStockCaseService nonMovingStockCaseService;
    @Autowired NonMovingStockExecutionRepository nonMovingStockExecutionRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired InventoryDocumentRepository inventoryDocumentRepository;
    @Autowired InventoryAccountingSettingsRepository inventoryAccountingSettingsRepository;
    @Autowired InventoryItemRepository inventoryItemRepository;
    @Autowired MaterialMarketPriceService materialMarketPriceService;
    @Autowired ApprovalEngineService approvalEngineService;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void ensureStockMovementApprovalFlow() {
        ensureApprovalFlow(
            StockMovementApprovalAdapter.MODULE_KEY,
            "Test stock movement approval flow",
            "TEST-SM-STEP",
            "ADMIN"
        );
    }

    @Test
    void supplierBranchesUseBaseInnWithGeneratedSuffixes() {
        String baseInn = "309777" + UUID.randomUUID().toString().substring(0, 3).replaceAll("[^0-9]", "1");
        SupplierCreateRequest headRequest = new SupplierCreateRequest();
        headRequest.setCode("SUP-HEAD-" + suffix());
        headRequest.setName("Head supplier");
        headRequest.setTaxId(baseInn);
        SupplierResponse head = supplierService.create(headRequest);

        SupplierCreateRequest firstBranch = new SupplierCreateRequest();
        firstBranch.setCode("SUP-BR-1-" + suffix());
        firstBranch.setName("Branch one");
        firstBranch.setCreateAsBranch(true);
        firstBranch.setParentSupplierId(head.getId());
        firstBranch.setBranchName("Navoiy branch");
        SupplierResponse branchOne = supplierService.create(firstBranch);

        SupplierCreateRequest secondBranch = new SupplierCreateRequest();
        secondBranch.setCode("SUP-BR-2-" + suffix());
        secondBranch.setName("Branch two");
        secondBranch.setCreateAsBranch(true);
        secondBranch.setBaseTaxId(baseInn);
        SupplierResponse branchTwo = supplierService.create(secondBranch);

        assertThat(head.getTaxId()).isEqualTo(baseInn);
        assertThat(branchOne.getBaseTaxId()).isEqualTo(baseInn);
        assertThat(branchOne.getTaxId()).isEqualTo(baseInn + "_1");
        assertThat(branchOne.getSupplierType()).isEqualTo("BRANCH");
        assertThat(branchOne.getParentSupplierId()).isEqualTo(head.getId());
        assertThat(branchTwo.getTaxId()).isEqualTo(baseInn + "_2");
        assertThat(branchTwo.getTaxIdSuffix()).isEqualTo(2);
    }

    @Test
    void stockMovementPostedAtIsSystemManaged() {
        Material material = material("MAT-POSTED-SYSTEM");
        Warehouse warehouse = warehouse("WH-POSTED-SYSTEM");

        StockMovementCreateRequest create = new StockMovementCreateRequest();
        create.setName("Manual postedAt attempt");
        create.setStatus(StockMovementStatus.POSTED);
        create.setMaterialId(material.getId());
        create.setWarehouseId(warehouse.getId());
        create.setMovementType(MovementType.RECEIPT);
        create.setQuantity(BigDecimal.TEN);
        create.setUnitCost(BigDecimal.valueOf(3));
        create.setPostedAt(LocalDateTime.now().minusDays(1));

        StockMovementResponse created = stockMovementService.create(create);

        assertThat(created.getStatus()).isEqualTo(StockMovementStatus.DRAFT.name());
        assertThat(created.getPostedAt()).isNull();
        assertThat(created.getTotalCost()).isEqualByComparingTo("30.0000");

        StockMovementUpdateRequest update = new StockMovementUpdateRequest();
        update.setName("Manual postedAt update attempt");
        update.setStatus(StockMovementStatus.POSTED);
        update.setMaterialId(material.getId());
        update.setWarehouseId(warehouse.getId());
        update.setMovementType(MovementType.RECEIPT);
        update.setQuantity(BigDecimal.valueOf(5));
        update.setUnitCost(BigDecimal.valueOf(4));
        update.setPostedAt(LocalDateTime.now().minusDays(2));

        StockMovementResponse updated = stockMovementService.update(created.getId(), update);

        assertThat(updated.getStatus()).isEqualTo(StockMovementStatus.DRAFT.name());
        assertThat(updated.getPostedAt()).isNull();
        assertThat(updated.getTotalCost()).isEqualByComparingTo("20.0000");

        StockMovementResponse posted = stockMovementService.post(created.getId());

        assertThat(posted.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
        assertThat(posted.getPostedAt()).isNotNull();
        assertThatThrownBy(() -> stockMovementService.update(created.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only DRAFT stock movement can be edited");
    }

    @Test
    void postedStockMovementCannotBeSoftDeletedDirectly() {
        Material material = material("MAT-SOFT-DELETE-GUARD");
        Warehouse warehouse = warehouse("WH-SOFT-DELETE-GUARD");

        StockMovementCreateRequest draftRequest = new StockMovementCreateRequest();
        draftRequest.setName("Draft movement");
        draftRequest.setMaterialId(material.getId());
        draftRequest.setWarehouseId(warehouse.getId());
        draftRequest.setMovementType(MovementType.RECEIPT);
        draftRequest.setQuantity(BigDecimal.ONE);
        draftRequest.setUnitCost(BigDecimal.TEN);
        StockMovementResponse draft = stockMovementService.create(draftRequest);

        stockMovementService.softDelete(draft.getId());

        assertThat(stockMovementRepository.findAnyById(draft.getId()).orElseThrow().isDeleted()).isTrue();

        StockMovement postedRequest = new StockMovement();
        postedRequest.setName("Posted movement");
        postedRequest.setMaterialId(material.getId());
        postedRequest.setWarehouseId(warehouse.getId());
        postedRequest.setMovementType(MovementType.RECEIPT);
        postedRequest.setQuantity(BigDecimal.TEN);
        postedRequest.setUnitCost(BigDecimal.ONE);
        StockMovementResponse posted = stockMovementService.createAndPost(postedRequest);

        assertThatThrownBy(() -> stockMovementService.softDelete(posted.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");

        StockMovement stored = stockMovementRepository.findAnyById(posted.getId()).orElseThrow();
        assertThat(stored.isDeleted()).isFalse();
        assertThat(stored.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
    }

    @Test
    void stockPostingWriteOffAndValuationWork() {
        Material material = material("MAT-STOCK-1");
        Warehouse warehouse = warehouse("WH-STOCK-1");

        postReceipt(material, warehouse, BigDecimal.valueOf(10), BigDecimal.valueOf(5));
        postReceipt(material, warehouse, BigDecimal.valueOf(5), BigDecimal.valueOf(8));

        assertThat(stockValuationService.calculateFifoCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(12)))
            .isEqualByComparingTo("66.0000");
        assertThat(stockValuationService.calculateWeightedAverageCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(3)))
            .isEqualByComparingTo("18.0000");

        var batchBalance = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), material.getId(), warehouse.getId()).getFirst();
        StockMovement issue = new StockMovement();
        issue.setCode("ISS-1");
        issue.setName("Issue");
        issue.setMaterialId(material.getId());
        issue.setWarehouseId(warehouse.getId());
        issue.setStockBatchId(batchBalance.getStockBatchId());
        issue.setMovementType(MovementType.WRITE_OFF);
        issue.setQuantity(BigDecimal.valueOf(3));
        issue.setUnitCost(BigDecimal.valueOf(5));
        stockMovementService.createAndPost(issue);

        BigDecimal totalQuantity = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), material.getId(), warehouse.getId()).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalQuantity).isEqualByComparingTo("12.0000");

        StockMovement tooLarge = new StockMovement();
        tooLarge.setCode("ISS-NEG");
        tooLarge.setName("Negative guard");
        tooLarge.setMaterialId(material.getId());
        tooLarge.setWarehouseId(warehouse.getId());
        tooLarge.setStockBatchId(batchBalance.getStockBatchId());
        tooLarge.setMovementType(MovementType.WRITE_OFF);
        tooLarge.setQuantity(BigDecimal.valueOf(1000));
        tooLarge.setUnitCost(BigDecimal.ONE);
        assertThatThrownBy(() -> stockMovementService.createAndPost(tooLarge)).isInstanceOf(BusinessException.class);
    }

    @Test
    @Transactional
    void lifoPolicyCalculatesOutboundCostAndUpdatesBatchQuantities() {
        enableValuationOverrides();
        Material material = material("MAT-LIFO-1");
        material.setValuationMethod(ValuationMethod.LIFO);
        materialRepository.save(material);
        Warehouse warehouse = warehouse("WH-LIFO-1");

        postReceipt(material, warehouse, BigDecimal.valueOf(10), BigDecimal.valueOf(5));
        postReceipt(material, warehouse, BigDecimal.valueOf(5), BigDecimal.valueOf(8));

        assertThat(stockValuationService.calculateLifoCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(12)))
            .isEqualByComparingTo("75.0000");

        StockMovement issue = new StockMovement();
        issue.setCode("ISS-LIFO");
        issue.setName("LIFO issue");
        issue.setMaterialId(material.getId());
        issue.setWarehouseId(warehouse.getId());
        issue.setMovementType(MovementType.ISSUE);
        issue.setQuantity(BigDecimal.valueOf(12));
        issue.setUnitCost(BigDecimal.ONE);

        StockMovementResponse posted = stockMovementService.createAndPost(issue);

        assertThat(posted.getUnitCost()).isEqualByComparingTo("6.2500");
        assertThat(posted.getTotalCost()).isEqualByComparingTo("75.0000");
        BigDecimal remainingValue = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getTotalValue())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(remainingValue).isEqualByComparingTo("15.0000");
        assertThat(stockValuationService.calculateLifoCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(3)))
            .isEqualByComparingTo("15.0000");
    }

    @Test
    void outboundMovementCannotConsumeQuantityReservedForOtherDemand() {
        Material material = material("MAT-RESERVED-GUARD");
        Warehouse warehouse = warehouse("WH-RESERVED-GUARD");
        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.ONE);

        var balance = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), material.getId(), warehouse.getId()
        ).getFirst();
        balance.setReservedQuantity(BigDecimal.valueOf(8));
        balance.setAvailableQuantity(BigDecimal.valueOf(2));
        stockBalanceRepository.save(balance);

        StockMovement issue = new StockMovement();
        issue.setCode("ISS-RESERVED-GUARD");
        issue.setName("Reserved stock guard");
        issue.setMaterialId(material.getId());
        issue.setWarehouseId(warehouse.getId());
        issue.setMovementType(MovementType.ISSUE);
        issue.setQuantity(BigDecimal.valueOf(3));
        issue.setUnitCost(BigDecimal.ONE);

        assertThatThrownBy(() -> stockMovementService.createAndPost(issue))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Insufficient available stock");
    }

    @Test
    void concurrentIssuePostingAllowsOnlyOneSuccessfulConsumption() throws Exception {
        Material material = material("MAT-CONCURRENT-ISSUE");
        Warehouse warehouse = warehouse("WH-CONCURRENT-ISSUE");
        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.ONE);

        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        List<ConcurrentResult<UUID>> results = runConcurrently(tenantId, companyId, List.of(
            () -> postIssueMovement(material, warehouse, BigDecimal.valueOf(7), "ISS-CON-1"),
            () -> postIssueMovement(material, warehouse, BigDecimal.valueOf(7), "ISS-CON-2")
        ));

        long successCount = results.stream().filter(ConcurrentResult::successful).count();
        long failureCount = results.size() - successCount;

        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);
        assertThat(results).filteredOn(result -> !result.successful())
            .singleElement()
            .satisfies(result -> assertThat(result.error()).isInstanceOf(BusinessException.class));

        BigDecimal remainingQuantity = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                tenantId,
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainingAvailable = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                tenantId,
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getAvailableQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(remainingQuantity).isEqualByComparingTo("3.0000");
        assertThat(remainingAvailable).isEqualByComparingTo("3.0000");
    }

    @Test
    void concurrentReceiptsIntoSameBatchDoNotCreateDuplicateBalances() throws Exception {
        Material material = material("MAT-CONCURRENT-BAL");
        Warehouse warehouse = warehouse("WH-CONCURRENT-BAL");
        StockBatch batch = stockBatch(material, warehouse, "BATCH-CONCURRENT-BAL");

        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        List<ConcurrentResult<UUID>> results = runConcurrently(tenantId, companyId, List.of(
            () -> postReceiptIntoExistingBatch(material, warehouse, batch.getId(), BigDecimal.ONE, BigDecimal.valueOf(2), "REC-CON-1"),
            () -> postReceiptIntoExistingBatch(material, warehouse, batch.getId(), BigDecimal.ONE, BigDecimal.valueOf(2), "REC-CON-2")
        ));

        assertThat(results).allMatch(ConcurrentResult::successful);

        var balances = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            tenantId,
            material.getId(),
            warehouse.getId()
        ).stream().filter(balance -> batch.getId().equals(balance.getStockBatchId())).toList();

        assertThat(balances).hasSize(1);
        assertThat(balances.getFirst().getQuantity()).isEqualByComparingTo("2.0000");
        assertThat(balances.getFirst().getAvailableQuantity()).isEqualByComparingTo("2.0000");
    }

    @Test
    void actualCostUsesBookCostWithoutBatchAndSelectedBatchCostWhenBatchIsKnown() {
        Material material = material("MAT-ACTUAL-COST");
        material.setValuationMethod(ValuationMethod.ACTUAL_COST);
        materialRepository.save(material);
        Warehouse warehouse = warehouse("WH-ACTUAL-COST");

        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(5));
        postReceipt(material, warehouse, BigDecimal.valueOf(5), BigDecimal.valueOf(8));

        assertThat(stockValuationService.calculateActualCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(3)))
            .isEqualByComparingTo("18.0000");

        UUID higherCostBatchId = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getStockBatchId())
            .filter(java.util.Objects::nonNull)
            .filter(batchId -> stockBatchRepository.findByIdAndIsDeletedFalse(batchId)
                .map(batch -> batch.getUnitCost())
                .orElse(BigDecimal.ZERO)
                .compareTo(BigDecimal.valueOf(8)) == 0)
            .findFirst()
            .orElseThrow();

        assertThat(stockValuationService.calculateActualCost(material.getId(), java.util.List.of(warehouse.getId()), higherCostBatchId, BigDecimal.valueOf(3)))
            .isEqualByComparingTo("24.0000");

        StockMovement issue = new StockMovement();
        issue.setCode("ISS-ACTUAL");
        issue.setName("Actual cost issue");
        issue.setMaterialId(material.getId());
        issue.setWarehouseId(warehouse.getId());
        issue.setMovementType(MovementType.ISSUE);
        issue.setQuantity(BigDecimal.valueOf(3));
        issue.setUnitCost(BigDecimal.ONE);

        StockMovementResponse posted = stockMovementService.createAndPost(issue);

        assertThat(posted.getUnitCost()).isEqualByComparingTo("6.0000");
        assertThat(posted.getTotalCost()).isEqualByComparingTo("18.0000");
    }

    @Test
    void specificBatchCostCalculatesCorrectlyWhenBatchIsKnown() {
        Material material = material("MAT-SPECIFIC-KNOWN");
        material.setValuationMethod(ValuationMethod.SPECIFIC_BATCH);
        materialRepository.save(material);
        Warehouse warehouse = warehouse("WH-SPECIFIC-KNOWN");

        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(5));
        postReceipt(material, warehouse, BigDecimal.valueOf(5), BigDecimal.valueOf(8));

        UUID higherCostBatchId = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getStockBatchId())
            .filter(java.util.Objects::nonNull)
            .filter(batchId -> stockBatchRepository.findByIdAndIsDeletedFalse(batchId)
                .map(batch -> batch.getUnitCost())
                .orElse(BigDecimal.ZERO)
                .compareTo(BigDecimal.valueOf(8)) == 0)
            .findFirst()
            .orElseThrow();

        assertThat(stockValuationService.calculateSpecificBatchCost(material.getId(), java.util.List.of(warehouse.getId()), higherCostBatchId, BigDecimal.valueOf(3)))
            .isEqualByComparingTo("24.0000");
    }

    @Test
    void specificBatchCostFallbackToActualCostWhenBatchIsNull() {
        Material material = material("MAT-SPECIFIC-FALLBACK");
        material.setValuationMethod(ValuationMethod.SPECIFIC_BATCH);
        materialRepository.save(material);
        Warehouse warehouse = warehouse("WH-SPECIFIC-FALLBACK");

        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(5)); // 50
        postReceipt(material, warehouse, BigDecimal.valueOf(5), BigDecimal.valueOf(8)); // 40

        BigDecimal totalQuantity = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // When batch is null and quantity matches total on hand, it should sum all balances
        assertThat(stockValuationService.calculateSpecificBatchCost(material.getId(), java.util.List.of(warehouse.getId()), null, totalQuantity))
            .isEqualByComparingTo("90.0000");
            
        // If batch is null and quantity is partial, it should throw BusinessException
        assertThatThrownBy(() -> stockValuationService.calculateSpecificBatchCost(material.getId(), java.util.List.of(warehouse.getId()), null, BigDecimal.valueOf(10)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Specific batch costing requires stockBatchId for partial issues");
    }

    @Test
    void fifoAndLifoValueReservedAndQuarantinedOnHandStock() {
        enableValuationOverrides();
        Material material = material("MAT-HELD-COST");
        Warehouse warehouse = warehouse("WH-HELD-COST");
        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(5));
        var balances = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), material.getId(), warehouse.getId());
        for (var balance : balances) {
            balance.setAvailableQuantity(BigDecimal.ZERO);
            balance.setReservedQuantity(balance.getQuantity().divide(BigDecimal.valueOf(2)));
            balance.setQuarantineQuantity(balance.getQuantity().divide(BigDecimal.valueOf(2)));
        }
        stockBalanceRepository.saveAllAndFlush(balances);
        assertThat(stockValuationService.calculateFifoCost(material.getId(), List.of(warehouse.getId()), BigDecimal.TEN))
            .isEqualByComparingTo("50");
        assertThat(stockValuationService.calculateLifoCost(material.getId(), List.of(warehouse.getId()), BigDecimal.TEN))
            .isEqualByComparingTo("50");
    }
    @Test
    void fifoAndLifoCalculationsReflectCurrentStockBalances() {
        enableValuationOverrides();
        Material material = material("MAT-SNAPSHOTS");
        material.setValuationMethod(ValuationMethod.FIFO);
        materialRepository.save(material);
        Warehouse warehouse = warehouse("WH-SNAPSHOTS");

        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(5)); // batch 1
        postReceipt(material, warehouse, BigDecimal.valueOf(5), BigDecimal.valueOf(8)); // batch 2
        
        // Let's create an issue of 3 items (FIFO will take from batch 1)
        StockMovement issue = new StockMovement();
        issue.setCode("ISS-SNAP-FIFO");
        issue.setName("Snapshot issue");
        issue.setMaterialId(material.getId());
        issue.setWarehouseId(warehouse.getId());
        issue.setMovementType(MovementType.ISSUE);
        issue.setQuantity(BigDecimal.valueOf(3));
        issue.setUnitCost(BigDecimal.ONE);
        stockMovementService.createAndPost(issue);
        
        // Batch 1 should have 7 left, Batch 2 should have 5 left.
        
        // FIFO for 8 items: should take 7 from Batch 1 (7 * 5 = 35) and 1 from Batch 2 (1 * 8 = 8), total = 43.
        assertThat(stockValuationService.calculateFifoCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(8)))
            .isEqualByComparingTo("43.0000");

        // LIFO for 8 items: should take 5 from Batch 2 (5 * 8 = 40) and 3 from Batch 1 (3 * 5 = 15), total = 55.
        assertThat(stockValuationService.calculateLifoCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(8)))
            .isEqualByComparingTo("55.0000");
    }

    @Test
    @Transactional
    void lowerOfCostOrMarketUsesApprovedMarketPriceForValuationAndOutboundCost() {
        enableValuationOverrides();
        Material material = material("MAT-MARKET-PRICE");
        material.setValuationMethod(ValuationMethod.LOWER_OF_COST_OR_MARKET);
        materialRepository.save(material);
        Warehouse warehouse = warehouse("WH-MARKET-PRICE");

        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.TEN);

        MaterialMarketPriceCreateRequest request = new MaterialMarketPriceCreateRequest();
        request.setCode("MKT-" + suffix());
        request.setName("Approved market price");
        request.setMaterialId(material.getId());
        request.setWarehouseId(warehouse.getId());
        request.setMarketUnitPrice(BigDecimal.valueOf(8));
        request.setNetRealizableUnitPrice(BigDecimal.valueOf(7));
        request.setCurrencyCode("UZS");
        request.setEffectiveDate(LocalDate.now());
        request.setSourceType(MaterialMarketPriceSourceType.SUPPLIER_QUOTE.name());
        var price = materialMarketPriceService.create(request);
        materialMarketPriceService.approve(price.getId());

        var valuation = stockValuationService.recalculate(material.getId(), warehouse.getId(), ValuationMethod.LOWER_OF_COST_OR_MARKET);
        assertThat(valuation.getUnitCost()).isEqualByComparingTo("8.0000");
        assertThat(valuation.getTotalValue()).isEqualByComparingTo("80.0000");
        assertThat(valuation.getValuationBasis()).isEqualTo("LOWER_OF_COST_OR_MARKET_MARKET");

        StockMovement issue = new StockMovement();
        issue.setCode("ISS-MARKET");
        issue.setName("Market price issue");
        issue.setMaterialId(material.getId());
        issue.setWarehouseId(warehouse.getId());
        issue.setMovementType(MovementType.ISSUE);
        issue.setQuantity(BigDecimal.valueOf(2));
        issue.setUnitCost(BigDecimal.ONE);

        StockMovementResponse posted = stockMovementService.createAndPost(issue);

        assertThat(posted.getUnitCost()).isEqualByComparingTo("8.0000");
        assertThat(posted.getTotalCost()).isEqualByComparingTo("16.0000");
    }

    @Test
    void inventoryCountAdjustmentsUseValuationPolicyAndCannotPostTwice() {
        Material material = material("MAT-COUNT-POLICY");
        Warehouse warehouse = warehouse("WH-COUNT-POLICY");
        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(5));

        InventoryDocument document = new InventoryDocument();
        document.setCode("INV-COUNT-POLICY");
        document.setName("Inventory count policy");
        document.setWarehouseId(warehouse.getId());
        document.setPlannedStartDate(LocalDate.now());
        document.setPlannedEndDate(LocalDate.now());
        document.setStatus("APPROVED");document.setSnapshotAt(LocalDateTime.now());document.setCountRevision(1);document.setSubmittedCountRevision(1L);document.setApprovedCountRevision(1L);document.setCommissionApprovedRevision(1L);document.setCommissionApprovedBy("independent-reviewer");
        InventoryDocument savedDocument = inventoryDocumentRepository.save(document);

        InventoryItem surplus = new InventoryItem();
        surplus.setCode("INV-COUNT-SURPLUS");
        surplus.setName("Surplus");
        surplus.setInventoryDocumentId(savedDocument.getId());
        surplus.setMaterialId(material.getId());surplus.setWarehouseId(warehouse.getId());surplus.setCountRevision(1);surplus.setDecisionCountRevision(1L);surplus.setDifferenceQuantity(new BigDecimal("2"));surplus.setUnitCost(new BigDecimal("5"));surplus.setDecisionType(com.enterprise.mm.enums.InventoryDiscrepancyDecisionType.POST_SURPLUS);surplus.setDecisionComment("Verified surplus");
        var proof=new com.enterprise.mm.entity.Attachment();proof.setName("Count evidence");proof.setEntityType("INVENTORY_COUNT_EVIDENCE");proof.setEntityId(savedDocument.getId());proof.setOriginalFileName("count.txt");proof.setContentType("text/plain");proof.setSizeBytes(1L);proof.setChecksumSha256("0".repeat(64));proof.setStorageKey("fixture");proof.setUploadedAt(LocalDateTime.now());surplus.setDecisionEvidenceIds(List.of(attachments.saveAndFlush(proof).getId()));
        surplus.setSystemQuantity(BigDecimal.TEN);
        surplus.setCountedQuantity(BigDecimal.valueOf(12));
        surplus.setLineStatus(InventoryLineStatus.RESOLVED);
        inventoryItemRepository.save(surplus);

        Long postingVersion = savedDocument.getVersion();
        var firstPosting = inventoryProcessingService.postAdjustments(savedDocument.getId(), new com.enterprise.mm.payload.InventoryAdjustmentPostRequest(postingVersion, 1L, LocalDate.now()), "count-policy-post");
        InventoryDocument postedDocument = inventoryDocumentRepository.findById(savedDocument.getId()).orElseThrow();
        assertThat(postedDocument.getStatus()).isEqualTo("ADJUSTMENT_POSTED");

        BigDecimal totalValue = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getTotalValue())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalValue).isEqualByComparingTo("60.0000");
        var replay = inventoryProcessingService.postAdjustments(savedDocument.getId(), new com.enterprise.mm.payload.InventoryAdjustmentPostRequest(postingVersion, 1L, LocalDate.now()), "count-policy-post");
        assertThat(replay).isEqualTo(firstPosting);
    }

    @Test
    void stockMovementDocumentAppliesBalanceOnlyOnExplicitReplaySafePosting() {
        Material material = material("MAT-DOC-APPROVAL");
        Warehouse warehouse = warehouse("WH-DOC-APPROVAL");

        StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.valueOf(7));
        item.setUnitCost(BigDecimal.valueOf(4));

        StockMovementDocumentCreateRequest request = new StockMovementDocumentCreateRequest();
        request.setName("Approval-driven receipt");
        request.setWarehouseId(warehouse.getId());
        request.setMovementType(MovementType.RECEIPT);
        request.setItems(java.util.List.of(item));

        var created = stockMovementDocumentService.create(request);
        var pending = stockMovementDocumentService.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.PENDING));
        assertThat(pending.getStatus()).isEqualTo(StockMovementStatus.PENDING.name());

        var approved = stockMovementDocumentService.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.APPROVED));
        assertThat(approved.getStatus()).isEqualTo(StockMovementStatus.APPROVED.name());
        assertThat(approved.getPostedAt()).isNull();

        BigDecimal approvedQuantity = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(approvedQuantity).isZero();

        String postingKey = "inventory-valuation-receipt-" + created.getId();
        var posted = stockMovementDocumentService.post(
            created.getId(),
            new StockMovementPostRequest(approved.getVersion()),
            postingKey
        );
        assertThat(posted.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
        assertThat(stockMovementDocumentService.post(
            created.getId(),
            new StockMovementPostRequest(approved.getVersion()),
            postingKey
        )).usingRecursiveComparison().isEqualTo(posted);

        BigDecimal postedQuantity = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(postedQuantity).isEqualByComparingTo("7.0000");
    }

    @Test
    void stockMovementDocumentIssueCanApproveWithoutExplicitBatch() {
        Material material = material("MAT-DOC-ISSUE");
        Warehouse warehouse = warehouse("WH-DOC-ISSUE");

        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(3));

        StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.valueOf(4));
        item.setUnitCost(BigDecimal.valueOf(3));

        StockMovementDocumentCreateRequest request = new StockMovementDocumentCreateRequest();
        request.setName("Approval-driven issue");
        request.setWarehouseId(warehouse.getId());
        request.setMovementType(MovementType.ISSUE);
        request.setItems(java.util.List.of(item));

        var created = stockMovementDocumentService.create(request);
        stockMovementDocumentService.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.PENDING));
        var approved = stockMovementDocumentService.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.APPROVED));

        assertThat(approved.getStatus()).isEqualTo(StockMovementStatus.APPROVED.name());

        BigDecimal approvedRemaining = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(approvedRemaining).isEqualByComparingTo("10.0000");

        stockMovementDocumentService.post(
            created.getId(),
            new StockMovementPostRequest(approved.getVersion()),
            "inventory-valuation-issue-" + created.getId()
        );

        BigDecimal postedRemaining = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(postedRemaining).isEqualByComparingTo("6.0000");
    }

    @Test
    void approvedInventoryDoesNotBlockStockMovementApproval() {
        Material material = material("MAT-INV-APPROVED-UNFREEZE");
        Warehouse warehouse = warehouse("WH-INV-APPROVED-UNFREEZE");

        InventoryDocument inventory = new InventoryDocument();
        inventory.setCode("INV-APPROVED-UNFREEZE");
        inventory.setName("Approved inventory should not block");
        inventory.setWarehouseId(warehouse.getId());
        inventory.setPlannedStartDate(LocalDate.now());
        inventory.setPlannedEndDate(LocalDate.now());
        inventory.setStatus(InventoryStatus.APPROVED.name());
        inventory.setFreezeOperations(true);
        inventoryDocumentRepository.save(inventory);

        StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.valueOf(3));
        item.setUnitCost(BigDecimal.valueOf(5));

        StockMovementDocumentCreateRequest request = new StockMovementDocumentCreateRequest();
        request.setName("Approved inventory receipt");
        request.setWarehouseId(warehouse.getId());
        request.setMovementType(MovementType.RECEIPT);
        request.setItems(List.of(item));

        var created = stockMovementDocumentService.create(request);
        stockMovementDocumentService.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.PENDING));
        var approved = stockMovementDocumentService.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.APPROVED));

        assertThat(approved.getStatus()).isEqualTo(StockMovementStatus.APPROVED.name());

        BigDecimal approvedQuantity = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(approvedQuantity).isZero();

        stockMovementDocumentService.post(
            created.getId(),
            new StockMovementPostRequest(approved.getVersion()),
            "approved-inventory-receipt-" + created.getId()
        );

        BigDecimal postedQuantity = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(postedQuantity).isEqualByComparingTo("3.0000");
    }

    @Test
    void weightedAverageMovingAverageRevaluationAndImpairmentWork() {
        Material material = material("MAT-VALUATION-DOC");
        Warehouse warehouse = warehouse("WH-VALUATION-DOC");

        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(5));
        postReceipt(material, warehouse, BigDecimal.TEN, BigDecimal.valueOf(15));

        assertThat(stockValuationService.calculateWeightedAverageCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(2)))
            .isEqualByComparingTo("20.0000");
        assertThat(stockValuationService.calculateMovingAverageCost(material.getId(), java.util.List.of(warehouse.getId()), BigDecimal.valueOf(2)))
            .isEqualByComparingTo("20.0000");

        var valuation = stockValuationService.recalculate(material.getId(), warehouse.getId(), ValuationMethod.WEIGHTED_AVERAGE);
        assertThat(valuation.getUnitCost()).isEqualByComparingTo("10.0000");
        assertThat(valuation.getTotalValue()).isEqualByComparingTo("200.0000");

        StockValuationDocumentCreateRequest revaluation = new StockValuationDocumentCreateRequest();
        revaluation.setName("Revaluation");
        revaluation.setMaterialId(material.getId());
        revaluation.setWarehouseId(warehouse.getId());
        revaluation.setDocumentType("REVALUATION");
        revaluation.setValuationMethod(ValuationMethod.MOVING_AVERAGE);
        revaluation.setNewUnitCost(BigDecimal.valueOf(12));
        revaluation.setReason("Approved revaluation test");
        var revaluationId = stockValuationDocumentService.create(revaluation).getId();
        stockValuationDocumentService.approve(revaluationId);
        var postedRevaluation = stockValuationDocumentService.post(revaluationId);
        assertThat(postedRevaluation.getStatus()).isEqualTo("POSTED");
        assertThat(postedRevaluation.getAmount()).isEqualByComparingTo("40.0000");

        StockValuationDocumentCreateRequest impairment = new StockValuationDocumentCreateRequest();
        impairment.setName("Impairment");
        impairment.setMaterialId(material.getId());
        impairment.setWarehouseId(warehouse.getId());
        impairment.setDocumentType("IMPAIRMENT_RESERVE");
        impairment.setValuationMethod(ValuationMethod.MOVING_AVERAGE);
        impairment.setReserveAmount(BigDecimal.valueOf(30));
        impairment.setReason("Approved impairment test");
        var impairmentId = stockValuationDocumentService.create(impairment).getId();
        stockValuationDocumentService.approve(impairmentId);
        stockValuationDocumentService.post(impairmentId);

        var movingAverageValuation = stockValuationRepository.findByTenantIdAndMaterialIdAndWarehouseIdAndValuationMethodAndIsDeletedFalse(
            TenantContext.currentTenant(), material.getId(), warehouse.getId(), ValuationMethod.MOVING_AVERAGE
        ).orElseThrow();
        assertThat(movingAverageValuation.getImpairmentReserve()).isEqualByComparingTo("30.0000");
        assertThat(movingAverageValuation.getCarryingValue()).isEqualByComparingTo("170.0000");
    }

    @Test
    void warehouseIdentificationAndMobileReceiveShipWork() {
        Material material = material("MAT-MOBILE-1");
        Warehouse warehouse = warehouse("WH-MOBILE-1");

        var generated = warehouseIdentificationService.generate("MATERIAL", material.getId(), "QR");
        assertThat(generated.identifierValue()).startsWith("QR-MATERIAL-");
        assertThat(warehouseIdentificationService.lookup(generated.identifierValue()).entityId()).isEqualTo(material.getId());

        var received = mobileWarehouseService.receive(new MobileWarehouseRequest(
            null,
            null,
            warehouse.getId(),
            null,
            null,
            null,
            BigDecimal.valueOf(5),
            BigDecimal.valueOf(2),
            generated.identifierValue()
        ));
        assertThat(received.status()).isEqualTo("COMPLETED");
        assertThat(received.stockMovementId()).isNotNull();

        var signed = mobileWarehouseService.sign(received.id(), new MobileSignatureRequest("e-imzo-test-signature", null, null, null));
        assertThat(signed.signed()).isTrue();

        UUID batchId = stockMovementRepository.findAnyById(received.stockMovementId()).orElseThrow().getStockBatchId();
        mobileWarehouseService.ship(new MobileWarehouseRequest(
            material.getId(),
            batchId,
            warehouse.getId(),
            null,
            null,
            null,
            BigDecimal.valueOf(2),
            BigDecimal.valueOf(2),
            null
        ));

        BigDecimal totalQuantity = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), material.getId(), warehouse.getId()).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalQuantity).isEqualByComparingTo("3.0000");
    }

    @Test
    void supplierEvaluationRestrictsLowRatedSupplierNewBusinessAndNonMovingStockIsDetected() {
        Supplier supplier = supplier("SUP-LOW-1");
        supplierEvaluationService.evaluate(new SupplierEvaluationRequest(
            supplier.getId(),
            null,
            BigDecimal.valueOf(40),
            BigDecimal.valueOf(45),
            BigDecimal.valueOf(45),
            BigDecimal.valueOf(50),
            BigDecimal.valueOf(30)
        ));
        Supplier updated = supplierRepository.findAnyById(supplier.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
        assertThat(updated.getCategory()).isEqualTo("D");
        var eligibility = supplierEligibilityService.evaluateNewBusiness(updated.getId(), null, "DIRECT_AWARD");
        assertThat(eligibility.newBusinessAllowed()).isFalse();
        assertThat(eligibility.hardBlocked()).isFalse();
        assertThat(eligibility.reasons())
            .extracting(reason -> reason.code())
            .contains("SUPPLIER_PERFORMANCE_RESTRICTS_NEW_BUSINESS");

        Material material = material("MAT-NMS-1");
        Warehouse warehouse = warehouse("WH-NMS-1");
        postReceipt(material, warehouse, BigDecimal.ONE, BigDecimal.ONE);
        stockMovementRepository.findAll().stream()
            .filter(movement -> material.getId().equals(movement.getMaterialId()) && warehouse.getId().equals(movement.getWarehouseId()))
            .forEach(movement -> {
                movement.setPostedAt(LocalDateTime.now().minusMonths(13));
                stockMovementRepository.save(movement);
            });

        var detected = nonMovingStockService.analyze();
        assertThat(detected).anyMatch(item -> item.getMaterialId().equals(material.getId()) && item.getStatus().equals(NonMovingStockStatus.DETECTED.name()));
    }

    @Test
    void scheduledNonMovingAnalysisProcessesAllTenantsWithPositiveStock() {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        try {
            TenantContext.setTenant("tenant-nms-scheduler-a");
            CompanyContext.setCompany(createAutoDetectCompany(12));
            Material materialA = material("MAT-NMS-SCHED-A");
            Warehouse warehouseA = warehouse("WH-NMS-SCHED-A");
            System.out.println("MAT-A COMPANY CONTEXT: " + CompanyContext.currentCompany());
            System.out.println("MAT-A SAVED COMPANY: " + materialA.getCompanyId());
            postReceipt(materialA, warehouseA, BigDecimal.ONE, BigDecimal.ONE);
            backdateMovements(materialA, warehouseA);

            TenantContext.setTenant("tenant-nms-scheduler-b");
            CompanyContext.setCompany(createAutoDetectCompany(12));
            Material materialB = material("MAT-NMS-SCHED-B");
            Warehouse warehouseB = warehouse("WH-NMS-SCHED-B");
            System.out.println("MAT-B COMPANY CONTEXT: " + CompanyContext.currentCompany());
            System.out.println("MAT-B SAVED COMPANY: " + materialB.getCompanyId());
            postReceipt(materialB, warehouseB, BigDecimal.ONE, BigDecimal.ONE);
            backdateMovements(materialB, warehouseB);

            var balances = stockBalanceRepository.findAll();
            System.out.println("ALL BALANCES: " + balances.stream().map(b -> b.getTenantId() + " qty: " + b.getQuantity()).toList());
            var detected = nonMovingStockService.analyzeAllTenants();
            
            System.out.println("DETECTED IN TEST: " + detected.stream().map(d -> d.getTenantId() + " " + d.getMaterialId()).toList());

            assertThat(detected).anyMatch(item ->
                "tenant-nms-scheduler-a".equals(item.getTenantId()) && materialA.getId().equals(item.getMaterialId()));
            assertThat(detected).anyMatch(item ->
                "tenant-nms-scheduler-b".equals(item.getTenantId()) && materialB.getId().equals(item.getMaterialId()));
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    @Test
    void nonMovingStockAnalysisAggregatesMultipleBalancesInOneWarehouse() {
        Material material = material("MAT-NMS-MULTI-BAL");
        Warehouse warehouse = warehouse("WH-NMS-MULTI-BAL");
        postReceipt(material, warehouse, BigDecimal.ONE, BigDecimal.valueOf(10));
        postReceipt(material, warehouse, BigDecimal.valueOf(2), BigDecimal.valueOf(10));
        backdateMovements(material, warehouse);

        var detection = nonMovingStockService.analyze().stream()
            .filter(item -> material.getId().equals(item.getMaterialId()))
            .findFirst()
            .orElseThrow();

        assertThat(detection.getQuantity()).isEqualByComparingTo("3.0000");
        assertThat(detection.getMonthsWithoutMovement()).isGreaterThanOrEqualTo(13);
    }

    @Test
    void nonMovingStockDetectedRecordsBecomeExecutableWriteOffCase() {
        ensureApprovalFlow(
            NonMovingStockDecisionApprovalAdapter.MODULE_KEY,
            "Test NMS decision approval flow",
            "TEST-NMS-STEP",
            "ADMIN"
        );
        Material material = material("MAT-NMS-CASE-WO");
        Warehouse warehouse = warehouse("WH-NMS-CASE-WO");
        postReceipt(material, warehouse, BigDecimal.valueOf(5), BigDecimal.valueOf(10));
        backdateMovements(material, warehouse);

        var detected = nonMovingStockService.analyze();
        var detectedIds = detected.stream()
            .filter(item -> material.getId().equals(item.getMaterialId()))
            .map(item -> item.getId())
            .toList();
        Commission commission = nonMovingCommission("NMS-COMM-WO");

        var caseResponse = nonMovingStockCaseService.createCaseFromDetected(detectedIds);

        assertThat(caseResponse.getStatus()).isEqualTo(NonMovingStockCaseStatus.CASE_CREATED.name());
        assertThat(caseResponse.getLines()).hasSize(1);
        assertThat(caseResponse.getLines().get(0).getBookValue()).isEqualByComparingTo("50.0000");
        assertThat(caseResponse.getLines().get(0).getAgingBucketMonths()).isEqualTo(12);

        NonMovingStockDecisionCreateRequest decisionRequest = new NonMovingStockDecisionCreateRequest();
        decisionRequest.setCommissionId(commission.getId());
        decisionRequest.setCommissionProtocolNo("NMS-2026-001");
        decisionRequest.setDecisionType(NonMovingStockDecisionType.WRITE_OFF.name());
        decisionRequest.setApprovedQuantity(BigDecimal.valueOf(5));
        decisionRequest.setWriteOffReason("Commission approved obsolete stock write-off");
        decisionRequest.setComment("Protocol NMS-2026-001");

        var submittedDecision = nonMovingStockCaseService.approveDecision(caseResponse.getId(), decisionRequest);
        var pendingCase = nonMovingStockCaseService.get(caseResponse.getId());
        assertThat(pendingCase.getStatus()).isEqualTo(NonMovingStockCaseStatus.APPROVAL_PENDING.name());
        assertThat(pendingCase.getCommissionId()).isEqualTo(commission.getId());
        assertThat(pendingCase.getCommissionProtocolNo()).isEqualTo("NMS-2026-001");
        approveAll(NonMovingStockDecisionApprovalAdapter.MODULE_KEY, submittedDecision.getId());
        var execution = nonMovingStockCaseService.executeLatestDecision(caseResponse.getId());

        assertThat(execution.getExecutionStatus()).isEqualTo(NonMovingStockExecutionStatus.EXECUTED.name());
        assertThat(execution.getStockMovementId()).isNotNull();
        StockMovement movement = stockMovementRepository.findAnyById(execution.getStockMovementId()).orElseThrow();
        assertThat(movement.getMovementType()).isEqualTo(MovementType.WRITE_OFF);
        assertThat(movement.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
        assertThat(movement.getReferenceType()).isEqualTo("NON_MOVING_STOCK_CASE");
        assertThat(movement.getReferenceId()).isEqualTo(caseResponse.getId());

        var updatedCase = nonMovingStockCaseService.get(caseResponse.getId());
        assertThat(updatedCase.getStatus()).isEqualTo(NonMovingStockCaseStatus.EXECUTED.name());
        assertThat(nonMovingStockExecutionRepository.findByTenantIdAndCaseIdAndIsDeletedFalse(TenantContext.currentTenant(), caseResponse.getId()))
            .anyMatch(item -> execution.getId().equals(item.getId()));
        assertThat(integrationEventRepository.findAll().stream()
            .filter(event -> TenantContext.currentTenant().equals(event.getTenantId()))
            .filter(event -> "NON_MOVING_STOCK_WRITE_OFF".equals(event.getEventType()))
            .map(IntegrationEvent::getAggregateId)
            .toList()).contains(caseResponse.getId());

        BigDecimal remaining = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                warehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(remaining).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void nonMovingStockCaseCanBeCreatedFromUnderReviewStatus() {
        Material material = material("MAT-NMS-CASE-UR");
        Warehouse warehouse = warehouse("WH-NMS-CASE-UR");
        postReceipt(material, warehouse, BigDecimal.valueOf(2), BigDecimal.valueOf(5));
        backdateMovements(material, warehouse);

        var detection = nonMovingStockService.analyze().stream()
            .filter(item -> material.getId().equals(item.getMaterialId()))
            .findFirst()
            .orElseThrow();

        nonMovingStockService.applyAction(detection.getId(), NonMovingStockStatus.UNDER_REVIEW.name(), "Moved to review");

        var caseResponse = nonMovingStockCaseService.createCaseFromDetected(List.of(detection.getId()));

        assertThat(caseResponse.getStatus()).isEqualTo(NonMovingStockCaseStatus.CASE_CREATED.name());
        assertThat(caseResponse.getLines()).isNotEmpty();
    }

    @Test
    void nonMovingStockSupportsDirectTransferExecution() {
        Material material = material("MAT-NMS-DIRECT-TR");
        Warehouse sourceWarehouse = warehouse("WH-NMS-DIRECT-SRC");
        Warehouse targetWarehouse = warehouse("WH-NMS-DIRECT-TGT");
        Commission commission = nonMovingCommission("NMS-COMM-TR");
        postReceipt(material, sourceWarehouse, BigDecimal.valueOf(4), BigDecimal.valueOf(8));
        backdateMovements(material, sourceWarehouse);

        var detected = nonMovingStockService.analyze();
        var detection = detected.stream()
            .filter(item -> material.getId().equals(item.getMaterialId()))
            .findFirst()
            .orElseThrow();

        NonMovingStockDecisionCreateRequest request = new NonMovingStockDecisionCreateRequest();
        request.setCommissionId(commission.getId());
        request.setDecisionType(NonMovingStockDecisionType.TRANSFER.name());
        request.setApprovedQuantity(BigDecimal.valueOf(4));
        request.setTargetWarehouseId(targetWarehouse.getId());
        request.setComment("Transfer to alternate warehouse");

        var execution = nonMovingStockCaseService.executeDirectDecision(detection.getId(), request);

        assertThat(execution.getExecutionStatus()).isEqualTo(NonMovingStockExecutionStatus.PENDING.name());
        assertThat(execution.getStockMovementId()).isNull();
        assertThat(execution.getStockMovementDocumentId()).isNotNull();
        var transferDocument = stockMovementDocumentService.get(execution.getStockMovementDocumentId());
        assertThat(transferDocument.getMovementType()).isEqualTo(MovementType.TRANSFER);
        assertThat(transferDocument.getWarehouseId()).isEqualTo(sourceWarehouse.getId());
        assertThat(transferDocument.getWarehouseName()).isEqualTo(sourceWarehouse.getName());
        assertThat(transferDocument.getTargetWarehouseId()).isEqualTo(targetWarehouse.getId());
        assertThat(transferDocument.getTargetWarehouseName()).isEqualTo(targetWarehouse.getName());
        assertThat(transferDocument.getStatus()).isEqualTo(StockMovementStatus.PENDING.name());
        assertThat(transferDocument.getItems()).isNotEmpty();
        assertThat(transferDocument.getItems())
            .allSatisfy(item -> {
                assertThat(item.getStatus()).isEqualTo(StockMovementStatus.PENDING.name());
                assertThat(item.getWarehouseName()).isEqualTo(sourceWarehouse.getName());
                assertThat(item.getTargetWarehouseName()).isEqualTo(targetWarehouse.getName());
            });

        BigDecimal sourceRemaining = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                sourceWarehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal targetReceived = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                targetWarehouse.getId()
            ).stream()
            .map(balance -> balance.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        var targetBalances = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                material.getId(),
                targetWarehouse.getId()
            );

        assertThat(sourceRemaining).isEqualByComparingTo("4.0000");
        assertThat(targetReceived).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(targetBalances).isEmpty();
    }

    @Test
    void supplierEvaluationCannotUseSupplierFromAnotherTenant() {
        String previousTenant = TenantContext.currentTenant();
        try {
            TenantContext.setTenant("tenant-supplier-eval-a");
            Supplier supplier = supplier("SUP-TENANT-EVAL-A");

            TenantContext.setTenant("tenant-supplier-eval-b");
            assertThatThrownBy(() -> supplierEvaluationService.evaluate(new SupplierEvaluationRequest(
                supplier.getId(),
                null,
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(90)
            ))).isInstanceOf(NotFoundException.class);
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }

    private void postReceipt(Material material, Warehouse warehouse, BigDecimal quantity, BigDecimal cost) {
        StockMovement receipt = new StockMovement();
        receipt.setCode("REC-" + material.getCode() + "-" + quantity);
        receipt.setName("Receipt");
        receipt.setMaterialId(material.getId());
        receipt.setWarehouseId(warehouse.getId());
        receipt.setMovementType(MovementType.RECEIPT);
        receipt.setQuantity(quantity);
        receipt.setUnitCost(cost);
        stockMovementService.createAndPost(receipt);
    }

    private UUID postIssueMovement(Material material, Warehouse warehouse, BigDecimal quantity, String code) {
        StockMovement issue = new StockMovement();
        issue.setCode(code + "-" + suffix());
        issue.setName("Concurrent issue");
        issue.setMaterialId(material.getId());
        issue.setWarehouseId(warehouse.getId());
        issue.setMovementType(MovementType.ISSUE);
        issue.setQuantity(quantity);
        issue.setUnitCost(BigDecimal.ONE);
        return stockMovementService.createAndPost(issue).getId();
    }

    private UUID postReceiptIntoExistingBatch(
        Material material,
        Warehouse warehouse,
        UUID stockBatchId,
        BigDecimal quantity,
        BigDecimal cost,
        String code
    ) {
        StockMovement receipt = new StockMovement();
        receipt.setCode(code + "-" + suffix());
        receipt.setName("Concurrent receipt to existing batch");
        receipt.setMaterialId(material.getId());
        receipt.setWarehouseId(warehouse.getId());
        receipt.setStockBatchId(stockBatchId);
        receipt.setMovementType(MovementType.RECEIPT);
        receipt.setQuantity(quantity);
        receipt.setUnitCost(cost);
        return stockMovementService.createAndPost(receipt).getId();
    }

    private void backdateMovements(Material material, Warehouse warehouse) {
        stockMovementRepository.findAll().stream()
            .filter(movement -> TenantContext.currentTenant().equals(movement.getTenantId()))
            .filter(movement -> material.getId().equals(movement.getMaterialId()) && warehouse.getId().equals(movement.getWarehouseId()))
            .forEach(movement -> {
                movement.setPostedAt(LocalDateTime.now().minusMonths(13));
                stockMovementRepository.save(movement);
            });
    }

    private UUID createAutoDetectCompany(int thresholdMonths) {
        CompanyContext.clear();
        Company company = new Company();
        company.setCode("COMP-" + suffix());
        company.setName("Scheduler test company");
        company.setStatus("ACTIVE");
        company.setNonMovingStockAutoDetectEnabled(true);
        company.setNonMovingStockThresholdMonths(thresholdMonths);
        return companyRepository.save(company).getId();
    }

    private void enableValuationOverrides() {
        InventoryAccountingSettings settings = inventoryAccountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseGet(() -> {
                InventoryAccountingSettings created = new InventoryAccountingSettings();
                created.setCode("INV-ACCOUNTING-SETTINGS");
                created.setName("Inventory accounting settings");
                created.setStatus("ACTIVE");
                return created;
            });
        settings.setAllowWarehouseValuationOverride(true);
        inventoryAccountingSettingsRepository.save(settings);
    }

    private void approveAll(String moduleKey, UUID entityId) {
        var instance = approvalEngineService.currentInstance(moduleKey, entityId);
        while (instance.approvalStatus() != ApprovalInstanceStatus.APPROVED) {
            var task = instance.tasks().stream()
                .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
                .findFirst()
                .orElseThrow();
            instance = approvalEngineService.approveTask(task.id(), "ok");
        }
    }

    private void ensureApprovalFlow(String moduleKey, String name, String stepCodePrefix, String approverRole) {
        ApprovalFlow saved = approvalFlowRepository
            .findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                moduleKey
            ).orElseGet(() -> {
                ApprovalFlow flow = new ApprovalFlow();
                flow.setCode("TEST-FLOW-" + UUID.randomUUID().toString().substring(0, 8));
                flow.setName(name);
                flow.setStatus("ACTIVE");
                flow.setModuleKey(moduleKey);
                flow.setActive(true);
                return approvalFlowRepository.save(flow);
            });

        List<ApprovalFlowStep> steps = approvalFlowStepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(),
            saved.getId()
        );
        if (steps.isEmpty()) {
            ApprovalFlowStep created = new ApprovalFlowStep();
            created.setApprovalFlowId(saved.getId());
            created.setCompanyId(saved.getCompanyId());
            created.setCode(stepCodePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
            created.setName(name + " step");
            created.setStepOrder(1);
            steps = List.of(created);
        }
        steps.forEach(step -> {
            step.setCompanyId(saved.getCompanyId());
            step.setStatus("ACTIVE");
            step.setAssigneeType(ApprovalAssigneeType.ROLE);
            step.setAssigneeRole(approverRole);
            step.setAssigneeUserId(null);
            step.setAssigneeUsername(null);
            step.setCanReturn(true);
            approvalFlowStepRepository.save(step);
        });
    }

    private Material material(String code) {
        com.enterprise.mm.entity.UnitOfMeasure unit = new com.enterprise.mm.entity.UnitOfMeasure();
        unit.setCode("UOM-" + UUID.randomUUID().toString().substring(0, 8));
        unit.setName("Valuation stock unit");
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

    private StockBatch stockBatch(Material material, Warehouse warehouse, String batchNumber) {
        StockBatch batch = new StockBatch();
        batch.setCode("BATCH-" + suffix());
        batch.setName(batch.getCode());
        batch.setStatus("ACTIVE");
        batch.setMaterialId(material.getId());
        batch.setWarehouseId(warehouse.getId());
        batch.setBatchNumber(batchNumber);
        batch.setReceivedDate(LocalDate.now());
        batch.setQuantity(BigDecimal.ZERO);
        batch.setUnitCost(BigDecimal.ZERO);
        batch.setPurchaseUnitCost(BigDecimal.ZERO);
        batch.setFinalUnitCost(BigDecimal.ZERO);
        return stockBatchRepository.save(batch);
    }

    private Supplier supplier(String code) {
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setRating(BigDecimal.valueOf(90));
        supplier.setCategory("A");
        return supplierRepository.save(supplier);
    }

    private Commission nonMovingCommission(String code) {
        Commission commission = new Commission();
        commission.setCode(code + "-" + suffix());
        commission.setName("Non-moving commission " + code);
        commission.setStatus("ACTIVE");
        commission.setType(CommissionType.NON_MOVING_STOCK);
        commission.setActive(true);
        return commissionRepository.save(commission);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private <T> List<ConcurrentResult<T>> runConcurrently(
        String tenantId,
        UUID companyId,
        List<Callable<T>> operations
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(operations.size());
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ConcurrentResult<T>>> futures = new ArrayList<>();
            for (Callable<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    TenantContext.setTenant(tenantId);
                    CompanyContext.setCompany(companyId);
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent operation");
                    }
                    try {
                        T value = new TransactionTemplate(transactionManager).execute(status -> {
                            try {
                                return operation.call();
                            } catch (RuntimeException ex) {
                                throw ex;
                            } catch (Exception ex) {
                                throw new IllegalStateException(ex);
                            }
                        });
                        return ConcurrentResult.success(value);
                    } catch (Throwable error) {
                        return ConcurrentResult.failure(error);
                    } finally {
                        CompanyContext.clear();
                        TenantContext.clear();
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ConcurrentResult<T>> results = new ArrayList<>();
            for (Future<ConcurrentResult<T>> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private record ConcurrentResult<T>(T value, Throwable error) {
        static <T> ConcurrentResult<T> success(T value) {
            return new ConcurrentResult<>(value, null);
        }

        static <T> ConcurrentResult<T> failure(Throwable error) {
            return new ConcurrentResult<>(null, error);
        }

        boolean successful() {
            return error == null;
        }
    }
}
