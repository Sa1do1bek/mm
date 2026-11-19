package com.enterprise.mm.warehouse.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.service.StockAccountResolver;
import com.enterprise.mm.warehouse.custody.CustodyAgreementService;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReturnablePackagingServiceTest {
    @Mock ReturnablePackagingLotRepository lots;
    @Mock ReturnablePackagingReturnRepository returns;
    @Mock ReturnablePackagingEventRepository events;
    @Mock GoodsReceiptLineRepository receiptLines;
    @Mock MaterialRepository materials;
    @Mock SupplierRepository suppliers;
    @Mock StockMovementRepository stockMovements;
    @Mock WarehouseRepository warehouses;
    @Mock StockMovementService movements;
    @Mock AccountAwareStockPostingService accountPosting;
    @Mock CustodyAgreementService custodyAgreements;
    @Mock RoleDataScopeService dataScope;
    @Mock StockAccountResolver stockAccountResolver;

    private ReturnablePackagingService service;
    private final UUID company = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();
    private final UUID packagingId = UUID.randomUUID();
    private final UUID contentId = UUID.randomUUID();
    private final UUID receiptId = UUID.randomUUID();
    private final UUID receiptLineId = UUID.randomUUID();
    private final UUID poItemId = UUID.randomUUID();

    @BeforeEach void setup() {
        TenantContext.setTenant("packaging-test");
        CompanyContext.setCompany(company);
        service = new ReturnablePackagingService(lots, returns, events, receiptLines, materials, suppliers,
            stockMovements, warehouses, movements, accountPosting, custodyAgreements, dataScope,
            new ObjectMapper(), stockAccountResolver);
        org.mockito.Mockito.lenient().when(stockAccountResolver.resolveForMaterial(packagingId))
            .thenReturn(new StockAccountResolver.Resolution(UUID.randomUUID(), "1010", "Stock", null, "COMPANY_DEFAULT"));
    }

    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void lotViewIncludesWarehouseAndReceiptDate() {
        ReturnablePackagingLot lot = lot();
        LocalDateTime receivedAt = LocalDateTime.of(2026, 10, 28, 12, 0);
        lot.setCreatedAt(receivedAt);
        lot.setOwnership("SUPPLIER");
        lot.setUnitCost(new BigDecimal("75.50"));
        when(lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        viewLookups();
        Warehouse warehouseEntity = new Warehouse();
        warehouseEntity.setName("Main warehouse");
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, warehouse))
            .thenReturn(Optional.of(warehouseEntity));

        var view = service.get(lot.getId());

        assertThat(view.warehouseId()).isEqualTo(warehouse);
        assertThat(view.warehouseName()).isEqualTo("Main warehouse");
        assertThat(view.createdAt()).isEqualTo(receivedAt);
        assertThat(view.unitCost()).isEqualByComparingTo("75.50");
        assertThat(view.amount()).isEqualByComparingTo("377.5000");
    }

    @Test void companyPackagingReceiptPostsQuantityAndValueToSelectedAccount() {
        receiptFixture();
        PostingReceipt posted = ownedPostingReceipt();
        when(accountPosting.postReceipt(any())).thenReturn(posted);
        when(lots.save(any())).thenAnswer(call -> call.getArgument(0));

        service.recordReceipt(receiptId, supplierId, List.of(new PackagingReceiptRequest(
            poItemId, packagingId, new BigDecimal("5"), "COMPANY", "1010", new BigDecimal("100"))));

        ArgumentCaptor<AccountAwareStockPostingService.ReceiptRequest> posting =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(accountPosting).postReceipt(posting.capture());
        assertThat(posting.getValue().ownerId()).isNull();
        assertThat(posting.getValue().stockCategory()).isEqualTo("OWNED");
        assertThat(posting.getValue().accountCode()).isEqualTo("1010");
        assertThat(posting.getValue().unitCost()).isEqualByComparingTo("100");
        assertThat(posting.getValue().command().contextKey()).isEqualTo("OWNED");
        ArgumentCaptor<ReturnablePackagingLot> lot = ArgumentCaptor.forClass(ReturnablePackagingLot.class);
        verify(lots).save(lot.capture());
        assertThat(lot.getValue().getFilledQuantity()).isEqualByComparingTo("5");
        assertThat(lot.getValue().getContentMaterialId()).isEqualTo(contentId);
        assertThat(lot.getValue().getReceiptPostingBatchId()).isEqualTo(posted.batchId());
        assertThat(lot.getValue().getReceiptStockMovementId()).isNull();
        verify(movements, never()).postReturnablePackagingMovement(any());
    }

    @Test void supplierPackagingReceiptUsesEnteredCostInCustody() {
        receiptFixture();
        UUID batch = UUID.randomUUID();
        when(accountPosting.postReceipt(any())).thenReturn(new PostingReceipt(batch, UUID.randomUUID(), 1, LocalDate.now(),
            List.of(new com.enterprise.mm.warehouse.posting.PostingEffect(
                com.enterprise.mm.warehouse.posting.PostingEffectType.STOCK, packagingId, warehouse,
                "002", "box", new BigDecimal("5"), BigDecimal.ZERO, null, null, null, null,
                supplierId, "CUSTODY", "AVAILABLE", "test"))));
        when(lots.save(any())).thenAnswer(call -> call.getArgument(0));

        service.recordReceipt(receiptId, supplierId, List.of(new PackagingReceiptRequest(
            poItemId, packagingId, new BigDecimal("5"), "SUPPLIER", "002", new BigDecimal("75.50"))));

        ArgumentCaptor<AccountAwareStockPostingService.ReceiptRequest> posting =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(accountPosting).postReceipt(posting.capture());
        assertThat(posting.getValue().ownerId()).isEqualTo(supplierId);
        assertThat(posting.getValue().stockCategory()).isEqualTo("CUSTODY");
        assertThat(posting.getValue().unitCost()).isEqualByComparingTo("75.50");
        ArgumentCaptor<ReturnablePackagingLot> saved = ArgumentCaptor.forClass(ReturnablePackagingLot.class);
        verify(lots).save(saved.capture());
        assertThat(saved.getValue().getUnitCost()).isEqualByComparingTo("75.50");
        verify(movements, never()).postReturnablePackagingMovement(any());
    }

    @Test void supplierPackagingReceiptRequiresUnitCost() {
        receiptFixture();

        assertThatThrownBy(() -> service.recordReceipt(receiptId, supplierId, List.of(new PackagingReceiptRequest(
            poItemId, packagingId, new BigDecimal("5"), "SUPPLIER", "002", null))))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("unit cost is required");
        verify(accountPosting, never()).postReceipt(any());
    }

    @Test void supplierPackagingManualReceiptUsesEnteredCostInCustody() {
        Supplier supplier = new Supplier(); supplier.setId(supplierId); supplier.setCompanyId(company); supplier.setName("Supplier");
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", supplierId)).thenReturn(Optional.of(supplier));
        Material packaging = new Material(); packaging.setId(packagingId); packaging.setCompanyId(company);
        packaging.setMaterialSubtype(MaterialSubtype.RETURNABLE_PACKAGING);
        UnitOfMeasure unit = new UnitOfMeasure(); unit.setCode("EA"); packaging.setUnitOfMeasure(unit);
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", packagingId))
            .thenReturn(Optional.of(packaging));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        StockMovement content = new StockMovement(); content.setId(UUID.randomUUID()); content.setMaterialId(contentId);
        content.setWarehouseId(warehouse); content.setMovementType(MovementType.RECEIPT);
        content.setQuantity(new BigDecimal("1000"));
        when(accountPosting.postReceipt(any())).thenReturn(custodyPostingReceipt());
        when(lots.save(any())).thenAnswer(call -> call.getArgument(0));

        service.recordManualReceipt(UUID.randomUUID(), supplierId, List.of(new ManualPackagingReceiptRequest(
            0, packagingId, new BigDecimal("5"), "SUPPLIER", "002", new BigDecimal("75.50"),
            LocalDate.now().plusMonths(3))), List.of(content));

        ArgumentCaptor<AccountAwareStockPostingService.ReceiptRequest> posting =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(accountPosting).postReceipt(posting.capture());
        assertThat(posting.getValue().stockCategory()).isEqualTo("CUSTODY");
        assertThat(posting.getValue().unitCost()).isEqualByComparingTo("75.50");
        ArgumentCaptor<ReturnablePackagingLot> saved = ArgumentCaptor.forClass(ReturnablePackagingLot.class);
        verify(lots).save(saved.capture());
        assertThat(saved.getValue().getUnitCost()).isEqualByComparingTo("75.50");
        verify(movements, never()).postReturnablePackagingMovement(any());
    }

    @Test void approvedManualReceiptCreatesPackagingLinkedToPostedContent() {
        Supplier supplier = new Supplier(); supplier.setId(supplierId); supplier.setCompanyId(company); supplier.setName("Supplier");
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", supplierId)).thenReturn(Optional.of(supplier));
        Material packaging = new Material(); packaging.setId(packagingId); packaging.setCompanyId(company);
        packaging.setMaterialSubtype(MaterialSubtype.RETURNABLE_PACKAGING);
        UnitOfMeasure unit = new UnitOfMeasure(); unit.setCode("EA"); packaging.setUnitOfMeasure(unit);
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", packagingId))
            .thenReturn(Optional.of(packaging));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        StockMovement content = new StockMovement(); content.setId(UUID.randomUUID()); content.setMaterialId(contentId);
        content.setWarehouseId(warehouse); content.setMovementType(MovementType.RECEIPT);
        content.setQuantity(new BigDecimal("1000"));
        when(accountPosting.postReceipt(any())).thenReturn(ownedPostingReceipt());
        when(lots.save(any())).thenAnswer(call -> call.getArgument(0));
        UUID documentId = UUID.randomUUID();

        service.recordManualReceipt(documentId, supplierId, List.of(new ManualPackagingReceiptRequest(
            0, packagingId, new BigDecimal("5"), "COMPANY", "1010", new BigDecimal("100"), LocalDate.now().plusMonths(3))),
            List.of(content));

        ArgumentCaptor<ReturnablePackagingLot> saved = ArgumentCaptor.forClass(ReturnablePackagingLot.class);
        verify(lots).save(saved.capture());
        assertThat(saved.getValue().getSourceType()).isEqualTo("MANUAL_WAREHOUSE");
        assertThat(saved.getValue().getManualDocumentId()).isEqualTo(documentId);
        assertThat(saved.getValue().getManualContentMovementId()).isEqualTo(content.getId());
        assertThat(saved.getValue().getReceivedQuantity()).isEqualByComparingTo("5");
        ArgumentCaptor<AccountAwareStockPostingService.ReceiptRequest> posting =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(accountPosting).postReceipt(posting.capture());
        assertThat(posting.getValue().stockCategory()).isEqualTo("OWNED");
        assertThat(posting.getValue().accountCode()).isEqualTo("1010");
        assertThat(posting.getValue().unitCost()).isEqualByComparingTo("100");
        verify(movements, never()).postReturnablePackagingMovement(any());
    }

    @Test void onlyEmptyUnreservedPackagingCanBeReturned() {
        ReturnablePackagingLot lot = lot();
        lot.setFilledQuantity(new BigDecimal("3"));
        lot.setEmptyQuantity(new BigDecimal("2"));
        lot.setReservedQuantity(BigDecimal.ONE);
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);

        assertThatThrownBy(() -> service.createReturn(new ReturnablePackagingDtos.CreateReturnRequest(
            lot.getId(), new BigDecimal("2"), "Empty drums"))).isInstanceOf(ConflictException.class);
        verify(returns, never()).save(any());
    }

    @Test void emptyingCanLinkPostedContentIssueAsEvidence() {
        ReturnablePackagingLot lot = lot();
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        when(lots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        viewLookups();
        UUID movementId = UUID.randomUUID();
        StockMovement issue = new StockMovement(); issue.setId(movementId); issue.setTenantId("packaging-test");
        issue.setCompanyId(company); issue.setMaterialId(contentId); issue.setWarehouseId(warehouse);
        issue.setMovementType(MovementType.ISSUE); issue.setStatus("POSTED");
        when(stockMovements.findById(movementId)).thenReturn(Optional.of(issue));

        service.changeState(lot.getId(), new ReturnablePackagingDtos.ChangeStateRequest(
            BigDecimal.ONE, "FILLED", "EMPTY", 0L, "Oil consumed", movementId));

        ArgumentCaptor<ReturnablePackagingEvent> event = ArgumentCaptor.forClass(ReturnablePackagingEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().getReason()).isEqualTo("Oil consumed");
        assertThat(event.getValue().getContentMovementId()).isEqualTo(movementId);
    }

    @Test void emptyingDoesNotPostStockButApprovedReturnReservesOnlyEmptyDrums() {
        ReturnablePackagingLot lot = lot();
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        when(lots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        viewLookups();

        service.changeState(lot.getId(), new ReturnablePackagingDtos.ChangeStateRequest(
            new BigDecimal("2"), "FILLED", "EMPTY", 0L));
        assertThat(lot.getFilledQuantity()).isEqualByComparingTo("3");
        assertThat(lot.getEmptyQuantity()).isEqualByComparingTo("2");
        verify(movements, never()).postReturnablePackagingMovement(any());

        ReturnablePackagingReturn document = returnDocument(lot);
        document.setQuantity(new BigDecimal("2"));
        when(returns.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, document.getId()))
            .thenReturn(Optional.of(document));
        when(lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(returns.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service.approve(document.getId(), new ReturnablePackagingDtos.VersionRequest(0L));
        assertThat(lot.getReservedQuantity()).isEqualByComparingTo("2");
        assertThat(document.getStatus()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> service.changeState(lot.getId(), new ReturnablePackagingDtos.ChangeStateRequest(
            BigDecimal.ONE, "EMPTY", "DAMAGED", 0L, "Broken", null))).isInstanceOf(ConflictException.class);
    }

    @Test void dispatchSubtractsPackagingWhileSupplierConfirmationOnlyClosesLiability() {
        ReturnablePackagingLot lot = lot();
        lot.setOwnership("COMPANY"); lot.setEmptyQuantity(new BigDecimal("2"));
        lot.setFilledQuantity(new BigDecimal("3")); lot.setReservedQuantity(new BigDecimal("2"));
        lot.setStockBatchId(UUID.randomUUID()); lot.setUnitCost(new BigDecimal("100"));
        ReturnablePackagingReturn document = returnDocument(lot);
        document.setStatus("APPROVED"); document.setQuantity(new BigDecimal("2"));
        when(returns.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, document.getId()))
            .thenReturn(Optional.of(document));
        when(lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        when(returns.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        StockMovementResponse posted = new StockMovementResponse(); posted.setId(UUID.randomUUID());
        when(movements.postReturnablePackagingMovement(any())).thenReturn(posted);

        service.dispatch(document.getId(), new ReturnablePackagingDtos.DispatchRequest(0L, "WAYBILL-7"));
        assertThat(lot.getEmptyQuantity()).isEqualByComparingTo("0");
        assertThat(lot.getDispatchedQuantity()).isEqualByComparingTo("2");
        assertThat(lot.getReservedQuantity()).isEqualByComparingTo("0");
        assertThat(document.getDispatchStockMovementId()).isEqualTo(posted.getId());
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).postReturnablePackagingMovement(movement.capture());
        assertThat(movement.getValue().getMovementType()).isEqualTo(MovementType.ISSUE);
        assertThat(movement.getValue().getMaterialId()).isEqualTo(packagingId);

        service.confirm(document.getId(), new ReturnablePackagingDtos.ConfirmRequest(0L, BigDecimal.ONE));
        assertThat(document.getStatus()).isEqualTo("PARTIALLY_CONFIRMED");
        assertThat(lot.getConfirmedQuantity()).isEqualByComparingTo("1");
        assertThat(lot.getDispatchedQuantity()).isEqualByComparingTo("2");
        verify(movements).postReturnablePackagingMovement(any());
    }

    @Test void failedDispatchCanReturnToStockBeforeSupplierConfirmation() {
        ReturnablePackagingLot lot = lot();
        lot.setOwnership("COMPANY"); lot.setFilledQuantity(new BigDecimal("3"));
        lot.setDispatchedQuantity(new BigDecimal("2")); lot.setUnitCost(new BigDecimal("100"));
        ReturnablePackagingReturn document = returnDocument(lot);
        document.setStatus("DISPATCHED"); document.setQuantity(new BigDecimal("2"));
        when(returns.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, document.getId()))
            .thenReturn(Optional.of(document));
        when(lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        when(returns.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        StockMovementResponse posted = new StockMovementResponse();
        posted.setId(UUID.randomUUID()); posted.setStockBatchId(UUID.randomUUID());
        when(movements.postReturnablePackagingMovement(any())).thenReturn(posted);

        service.recall(document.getId(), new ReturnablePackagingDtos.RecallRequest(0L, "Carrier returned all drums"));

        assertThat(lot.getDispatchedQuantity()).isEqualByComparingTo("0");
        assertThat(lot.getEmptyQuantity()).isEqualByComparingTo("2");
        assertThat(lot.getStockBatchId()).isEqualTo(posted.getStockBatchId());
        assertThat(document.getStatus()).isEqualTo("RECALLED");
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).postReturnablePackagingMovement(movement.capture());
        assertThat(movement.getValue().getMovementType()).isEqualTo(MovementType.RECEIPT);
        assertThat(movement.getValue().getReferenceType()).isEqualTo("RETURNABLE_PACKAGING_RECALL");
    }

    @Test void supplierPackagingDispatchAndRecallPreserveEnteredCost() {
        ReturnablePackagingLot lot = lot();
        lot.setOwnership("SUPPLIER"); lot.setAccountCode("002");
        lot.setEmptyQuantity(new BigDecimal("2")); lot.setFilledQuantity(new BigDecimal("3"));
        lot.setReservedQuantity(new BigDecimal("2")); lot.setUnitCost(new BigDecimal("75.50"));
        ReturnablePackagingReturn document = returnDocument(lot);
        document.setStatus("APPROVED"); document.setQuantity(new BigDecimal("2"));
        when(returns.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, document.getId()))
            .thenReturn(Optional.of(document));
        when(lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        when(returns.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Material packaging = new Material(); packaging.setId(packagingId); packaging.setCompanyId(company);
        UnitOfMeasure unit = new UnitOfMeasure(); unit.setCode("EA"); packaging.setUnitOfMeasure(unit);
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", packagingId))
            .thenReturn(Optional.of(packaging));
        when(accountPosting.postIssue(any())).thenReturn(custodyPostingReceipt());
        when(accountPosting.postReceipt(any())).thenReturn(custodyPostingReceipt());

        service.dispatch(document.getId(), new ReturnablePackagingDtos.DispatchRequest(0L, "WAYBILL-8"));

        ArgumentCaptor<AccountAwareStockPostingService.IssueRequest> issue =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.IssueRequest.class);
        verify(accountPosting).postIssue(issue.capture());
        assertThat(issue.getValue().unitCost()).isEqualByComparingTo("75.50");

        service.recall(document.getId(), new ReturnablePackagingDtos.RecallRequest(0L, "Supplier rejected drums"));

        ArgumentCaptor<AccountAwareStockPostingService.ReceiptRequest> receipt =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(accountPosting).postReceipt(receipt.capture());
        assertThat(receipt.getValue().unitCost()).isEqualByComparingTo("75.50");
    }

    @Test void accountPostedCompanyPackagingDispatchAndRecallStayOnSelectedAccount() {
        ReturnablePackagingLot lot = lot();
        lot.setOwnership("COMPANY"); lot.setAccountCode("1010");
        lot.setReceiptPostingBatchId(UUID.randomUUID());
        lot.setEmptyQuantity(new BigDecimal("2")); lot.setFilledQuantity(new BigDecimal("3"));
        lot.setReservedQuantity(new BigDecimal("2")); lot.setUnitCost(new BigDecimal("100"));
        ReturnablePackagingReturn document = returnDocument(lot);
        document.setStatus("APPROVED"); document.setQuantity(new BigDecimal("2"));
        when(returns.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, document.getId()))
            .thenReturn(Optional.of(document));
        when(lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        when(returns.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Material packaging = new Material(); packaging.setId(packagingId); packaging.setCompanyId(company);
        UnitOfMeasure unit = new UnitOfMeasure(); unit.setCode("EA"); packaging.setUnitOfMeasure(unit);
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", packagingId))
            .thenReturn(Optional.of(packaging));
        when(accountPosting.postIssue(any())).thenReturn(ownedPostingReceipt());
        when(accountPosting.postReceipt(any())).thenReturn(ownedPostingReceipt());

        service.dispatch(document.getId(), new ReturnablePackagingDtos.DispatchRequest(0L, "WAYBILL-9"));

        ArgumentCaptor<AccountAwareStockPostingService.IssueRequest> issue =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.IssueRequest.class);
        verify(accountPosting).postIssue(issue.capture());
        assertThat(issue.getValue().ownerId()).isNull();
        assertThat(issue.getValue().stockCategory()).isEqualTo("OWNED");
        assertThat(issue.getValue().accountCode()).isEqualTo("1010");
        assertThat(issue.getValue().unitCost()).isEqualByComparingTo("100");
        assertThat(issue.getValue().command().contextKey()).isEqualTo("OWNED");

        service.recall(document.getId(), new ReturnablePackagingDtos.RecallRequest(0L, "Supplier rejected drums"));

        ArgumentCaptor<AccountAwareStockPostingService.ReceiptRequest> receipt =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(accountPosting).postReceipt(receipt.capture());
        assertThat(receipt.getValue().ownerId()).isNull();
        assertThat(receipt.getValue().stockCategory()).isEqualTo("OWNED");
        assertThat(receipt.getValue().accountCode()).isEqualTo("1010");
        assertThat(receipt.getValue().unitCost()).isEqualByComparingTo("100");
        assertThat(receipt.getValue().command().contextKey()).isEqualTo("OWNED");
        verify(movements, never()).postReturnablePackagingMovement(any());
    }

    @Test void recallAfterPartialConfirmationReturnsOnlyOutstandingDrum() {
        ReturnablePackagingLot lot = lot(); lot.setOwnership("COMPANY");
        lot.setFilledQuantity(new BigDecimal("3")); lot.setDispatchedQuantity(new BigDecimal("2"));
        lot.setConfirmedQuantity(BigDecimal.ONE); lot.setUnitCost(new BigDecimal("100"));
        lot.setStockBatchId(UUID.randomUUID());
        ReturnablePackagingReturn doc = returnDocument(lot); doc.setStatus("PARTIALLY_CONFIRMED");
        doc.setQuantity(new BigDecimal("2")); doc.setConfirmedQuantity(BigDecimal.ONE);
        when(returns.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, doc.getId()))
            .thenReturn(Optional.of(doc));
        when(lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        when(returns.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        StockMovementResponse posted = new StockMovementResponse(); posted.setId(UUID.randomUUID());
        posted.setStockBatchId(lot.getStockBatchId());
        when(movements.postReturnablePackagingMovement(any())).thenReturn(posted);

        service.recall(doc.getId(), new ReturnablePackagingDtos.RecallRequest(0L, "One drum came back", BigDecimal.ONE));

        assertThat(doc.getStatus()).isEqualTo("PARTIALLY_RECALLED");
        assertThat(doc.getRecalledQuantity()).isEqualByComparingTo("1");
        assertThat(lot.getDispatchedQuantity()).isEqualByComparingTo("1");
        assertThat(lot.getEmptyQuantity()).isEqualByComparingTo("1");
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).postReturnablePackagingMovement(movement.capture());
        assertThat(movement.getValue().getStockBatchId()).isEqualTo(lot.getStockBatchId());
        assertThat(movement.getValue().getQuantity()).isEqualByComparingTo("1");
    }

    private void receiptFixture() {
        Supplier supplier = new Supplier();
        supplier.setId(supplierId); supplier.setCompanyId(company); supplier.setName("Supplier");
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", supplierId)).thenReturn(Optional.of(supplier));
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.setId(receiptLineId); line.setPurchaseOrderItemId(poItemId); line.setMaterialId(contentId);
        line.setWarehouseId(warehouse); line.setAcceptedQuantity(new BigDecimal("1000"));
        when(receiptLines.findByTenantIdAndCompanyIdAndGoodsReceiptIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "packaging-test", company, receiptId)).thenReturn(List.of(line));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        Material packaging = new Material();
        packaging.setId(packagingId); packaging.setCompanyId(company); packaging.setMaterialType(MaterialType.TMB);
        packaging.setMaterialSubtype(MaterialSubtype.RETURNABLE_PACKAGING);
        UnitOfMeasure unit = new UnitOfMeasure(); unit.setCode("box"); packaging.setUnitOfMeasure(unit);
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", packagingId)).thenReturn(Optional.of(packaging));
    }

    @Test void openingLotHasUnknownConditionWithoutInventedSupplierOrContents() {
        ReturnablePackagingLot lot = lot();
        lot.setSourceType("LEGACY_OPENING"); lot.setSupplierId(null); lot.setContentMaterialId(null);
        lot.setFilledQuantity(BigDecimal.ZERO); lot.setUnknownQuantity(new BigDecimal("5"));
        lot.setLegacyStockBalanceId(UUID.randomUUID());
        when(lots.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        Material material = new Material(); material.setId(packagingId); material.setCompanyId(company); material.setName("Drum");
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", packagingId)).thenReturn(Optional.of(material));

        var view = service.get(lot.getId());

        assertThat(view.supplierId()).isNull(); assertThat(view.supplierName()).isNull();
        assertThat(view.contentMaterialId()).isNull(); assertThat(view.contentMaterialName()).isNull();
        assertThat(view.unknownQuantity()).isEqualByComparingTo("5");
        assertThat(view.onHandQuantity()).isEqualByComparingTo("5");
        assertThat(view.legacyStockBalanceId()).isEqualTo(lot.getLegacyStockBalanceId());
        verify(suppliers, never()).findByTenantIdAndIdAndIsDeletedFalse(any(), any());
    }

    @Test void unknownOpeningConditionRequiresWarehouseEvidenceAndDoesNotPostStock() {
        ReturnablePackagingLot lot = lot(); lot.setFilledQuantity(BigDecimal.ZERO);
        lot.setUnknownQuantity(new BigDecimal("5"));
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        assertThatThrownBy(() -> service.changeState(lot.getId(), new ReturnablePackagingDtos.ChangeStateRequest(
            new BigDecimal("2"), "UNKNOWN", "EMPTY", 0L, "", null)))
            .hasMessageContaining("reason for classifying");
        viewLookups();
        when(lots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var view = service.changeState(lot.getId(), new ReturnablePackagingDtos.ChangeStateRequest(
            new BigDecimal("2"), "UNKNOWN", "EMPTY", 0L, "Physically checked by warehouse", null));
        assertThat(view.unknownQuantity()).isEqualByComparingTo("3");
        assertThat(view.emptyQuantity()).isEqualByComparingTo("2");
        assertThat(view.onHandQuantity()).isEqualByComparingTo("5");
        verify(movements, never()).postReturnablePackagingMovement(any());
        verify(accountPosting, never()).postReceipt(any());
    }

    @Test void openingReturnNeedsAnExplicitReceivingSupplierInTheSameCompany() {
        ReturnablePackagingLot lot = lot(); lot.setSourceType("LEGACY_OPENING"); lot.setSupplierId(null);
        lot.setFilledQuantity(BigDecimal.ZERO); lot.setEmptyQuantity(new BigDecimal("5"));
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        assertThatThrownBy(() -> service.createReturn(new ReturnablePackagingDtos.CreateReturnRequest(lot.getId(), BigDecimal.ONE, null)))
            .hasMessageContaining("Select the receiving supplier");
        Supplier foreign = new Supplier(); foreign.setCompanyId(UUID.randomUUID());
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", supplierId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.createReturn(new ReturnablePackagingDtos.CreateReturnRequest(lot.getId(), BigDecimal.ONE, null, supplierId)))
            .hasMessageContaining("unavailable");
        verify(returns, never()).save(any());
        assertThat(lot.getSupplierId()).isNull();
    }

    @Test void receivingSupplierBelongsToTheReturnAndDoesNotInventTheOpeningSupplier() {
        ReturnablePackagingLot lot = lot(); lot.setSourceType("LEGACY_OPENING"); lot.setSupplierId(null);
        lot.setFilledQuantity(BigDecimal.ZERO); lot.setEmptyQuantity(new BigDecimal("5"));
        when(lots.findWithLockByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("packaging-test", company, lot.getId()))
            .thenReturn(Optional.of(lot));
        when(dataScope.canAccessWarehouse(warehouse)).thenReturn(true);
        Supplier supplier = new Supplier(); supplier.setId(supplierId); supplier.setCompanyId(company); supplier.setName("Receiving supplier");
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", supplierId)).thenReturn(Optional.of(supplier));
        when(returns.save(any())).thenAnswer(call -> {
            ReturnablePackagingReturn saved = call.getArgument(0); saved.setId(UUID.randomUUID()); return saved;
        });
        var document = service.createReturn(new ReturnablePackagingDtos.CreateReturnRequest(lot.getId(), BigDecimal.ONE, "Return agreed", supplierId));
        assertThat(document.supplierId()).isEqualTo(supplierId);
        assertThat(document.supplierName()).isEqualTo("Receiving supplier");
        assertThat(lot.getSupplierId()).isNull();
        verify(lots, never()).save(any());
        verify(movements, never()).postReturnablePackagingMovement(any());
    }

    private ReturnablePackagingLot lot() {
        ReturnablePackagingLot lot = new ReturnablePackagingLot();
        lot.setId(UUID.randomUUID()); lot.setVersion(0L); lot.setWarehouseId(warehouse);
        lot.setSupplierId(supplierId); lot.setMaterialId(packagingId); lot.setContentMaterialId(contentId);
        lot.setReceivedQuantity(new BigDecimal("5")); lot.setFilledQuantity(new BigDecimal("5"));
        return lot;
    }

    private ReturnablePackagingReturn returnDocument(ReturnablePackagingLot lot) {
        ReturnablePackagingReturn document = new ReturnablePackagingReturn();
        document.setId(UUID.randomUUID()); document.setVersion(0L); document.setLotId(lot.getId());
        document.setSupplierId(supplierId); document.setStatus("DRAFT");
        document.setQuantity(BigDecimal.ONE); return document;
    }

    private PostingReceipt custodyPostingReceipt() {
        return new PostingReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.now(),
            List.of(new com.enterprise.mm.warehouse.posting.PostingEffect(
                com.enterprise.mm.warehouse.posting.PostingEffectType.STOCK, packagingId, warehouse,
                "002", "EA", BigDecimal.ONE, BigDecimal.ZERO, null, null, null, null,
                supplierId, "CUSTODY", "AVAILABLE", "test")));
    }

    private PostingReceipt ownedPostingReceipt() {
        return new PostingReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.now(),
            List.of(new com.enterprise.mm.warehouse.posting.PostingEffect(
                com.enterprise.mm.warehouse.posting.PostingEffectType.ACCOUNT, packagingId, warehouse,
                "1010", "EA", new BigDecimal("5"), new BigDecimal("500"), "UZS", null, null, null,
                null, "OWNED", "AVAILABLE", "test")));
    }

    private void viewLookups() {
        Supplier supplier = new Supplier(); supplier.setId(supplierId); supplier.setCompanyId(company); supplier.setName("Supplier");
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", supplierId)).thenReturn(Optional.of(supplier));
        Material packaging = new Material(); packaging.setId(packagingId); packaging.setCompanyId(company); packaging.setName("Drum");
        Material content = new Material(); content.setId(contentId); content.setCompanyId(company); content.setName("Oil");
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", packagingId)).thenReturn(Optional.of(packaging));
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("packaging-test", contentId)).thenReturn(Optional.of(content));
    }
}
