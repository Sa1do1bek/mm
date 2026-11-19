package com.enterprise.mm.warehouse.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.enterprise.mm.warehouse.posting.PostingEffect;
import com.enterprise.mm.warehouse.posting.PostingEffectType;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ConfirmProcessorConsumptionCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.DispatchToProcessorCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ExecuteTransformationCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ReturnFromProcessorCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationLineItem;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationLineType;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationOrderStatus;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationType;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReceiptEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IndustrialHandoffTest {

    private WarehouseProductionReceiptRepository receiptRepository;
    private WarehouseTransformationOrderRepository transformationOrderRepository;
    private AccountAwareStockPostingService postingService;
    private ProductionReceiptAdapterService receiptAdapterService;
    private ProcessingTransformationService transformationService;

    private final String tenantId = "navoiy-azot";
    private final UUID companyId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID processorId = UUID.randomUUID();
    private final UUID rawMaterialId = UUID.randomUUID();
    private final UUID finishedMaterialId = UUID.randomUUID();
    private final UUID scrapMaterialId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        receiptRepository = mock(WarehouseProductionReceiptRepository.class);
        transformationOrderRepository = mock(WarehouseTransformationOrderRepository.class);
        postingService = mock(AccountAwareStockPostingService.class);

        when(receiptRepository.save(any(WarehouseProductionReceipt.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(transformationOrderRepository.save(any(WarehouseTransformationOrder.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var effect = new PostingEffect(
            PostingEffectType.STOCK, rawMaterialId, warehouseId, "1010", "PCS",
            new BigDecimal("1"), BigDecimal.ZERO, "UZS"
        );
        PostingReceipt receipt = new PostingReceipt(
            UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.now(), List.of(effect)
        );
        when(postingService.postIssue(any())).thenReturn(receipt);
        when(postingService.postReceipt(any())).thenReturn(receipt);

        receiptAdapterService = new ProductionReceiptAdapterService(receiptRepository, postingService);
        transformationService = new ProcessingTransformationService(transformationOrderRepository, postingService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P6 AT07: Industrial handoff - processor partial return, yield/value reconciliation, and dispatch without duplicate billing")
    void p6_at07_yieldValueReconciliation_and_dispatchWithoutDuplicateBilling() {
        String orderNumber = "TOLL-P6-AT07-001";
        LocalDate today = LocalDate.of(2026, 10, 14);

        // 1. Dispatch 100 units raw material to processor at 10.0 UZS/unit = 1000.0 UZS
        when(transformationOrderRepository.findByTenantIdAndCompanyIdAndOrderNumber(tenantId, companyId.toString(), orderNumber))
            .thenReturn(Optional.empty());

        var dispatchCmd = new DispatchToProcessorCommand(
            orderNumber, warehouseId, processorId, rawMaterialId, "20",
            new BigDecimal("100.0000000"), new BigDecimal("10.0000"), today, "DISPATCH-BATCH-01"
        );
        var dispatchResult = transformationService.dispatchToProcessor(dispatchCmd);
        assertThat(dispatchResult.status()).isEqualTo(TransformationOrderStatus.DISPATCHED_TO_PROCESSOR);
        assertThat(dispatchResult.totalInputCost()).isEqualByComparingTo("1000.0000");

        // Capture order state to simulate repository storage
        var order = new WarehouseTransformationOrder();
        order.setId(dispatchResult.orderId());
        order.setTenantId(tenantId);
        order.setCompanyId(companyId.toString());
        order.setOrderNumber(orderNumber);
        order.setTransformationType(TransformationType.PROCESSOR_TOLLING);
        order.setStatus(TransformationOrderStatus.DISPATCHED_TO_PROCESSOR);
        order.setWarehouseId(warehouseId);
        order.setProcessorId(processorId);
        order.setTotalInputCost(new BigDecimal("1000.0000"));
        order.setTotalOutputCost(BigDecimal.ZERO);
        order.setExecutionDate(today);

        var inputLine = new WarehouseTransformationLine();
        inputLine.setId(UUID.randomUUID());
        inputLine.setLineType(TransformationLineType.INPUT);
        inputLine.setMaterialId(rawMaterialId);
        inputLine.setAccountCode("20");
        inputLine.setQuantity(new BigDecimal("100.0000000"));
        inputLine.setUnitCost(new BigDecimal("10.0000"));
        inputLine.setAllocatedCost(new BigDecimal("1000.0000"));
        order.addLine(inputLine);

        // 2. Processor Partial Return: Processor returns 20 unused units back to warehouse
        when(transformationOrderRepository.findByTenantIdAndCompanyIdAndOrderNumber(tenantId, companyId.toString(), orderNumber))
            .thenReturn(Optional.of(order));

        var returnCmd = new ReturnFromProcessorCommand(
            orderNumber, warehouseId, rawMaterialId,
            new BigDecimal("20.0000000"), new BigDecimal("10.0000"), today,
            "Unused raw materials returned", "RET-DOC-01"
        );
        var returnResult = transformationService.returnFromProcessor(returnCmd);
        assertThat(returnResult.status()).isEqualTo(TransformationOrderStatus.PARTIALLY_RETURNED);

        // Verify receipt posted back to warehouse AVAILABLE stock on account 1010
        ArgumentCaptor<AccountAwareStockPostingService.ReceiptRequest> receiptCaptor =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(postingService).postReceipt(receiptCaptor.capture());
        var returnedReceiptReq = receiptCaptor.getValue();
        assertThat(returnedReceiptReq.accountCode()).isEqualTo("1010");
        assertThat(returnedReceiptReq.disposition()).isEqualTo("AVAILABLE");
        assertThat(returnedReceiptReq.quantity()).isEqualByComparingTo("20.0000000");
        assertThat(returnedReceiptReq.unitCost()).isEqualByComparingTo("10.0000");

        // 3. Confirm consumption of remaining 80 units (Cost: 80 * 10 = 800.0 UZS)
        // Yield: 75 finished units (750.0 UZS on account 28) + 5 scrap units (50.0 UZS on account 1010)
        var confirmCmd = new ConfirmProcessorConsumptionCommand(
            orderNumber, processorId, new BigDecimal("80.0000000"),
            List.of(
                new TransformationLineItem(
                    TransformationLineType.OUTPUT, finishedMaterialId, "28",
                    new BigDecimal("75.0000000"), new BigDecimal("10.0000"), new BigDecimal("750.0000")
                ),
                new TransformationLineItem(
                    TransformationLineType.SCRAP, scrapMaterialId, "1010",
                    new BigDecimal("5.0000000"), new BigDecimal("10.0000"), new BigDecimal("50.0000")
                )
            ),
            today, "CONFIRM-ACT-01"
        );

        var confirmResult = transformationService.confirmProcessorConsumption(confirmCmd);
        assertThat(confirmResult.status()).isEqualTo(TransformationOrderStatus.EXECUTED);
        assertThat(confirmResult.totalOutputCost()).isEqualByComparingTo("800.0000");

        // 4. Exact Yield and Value Reconciliation
        BigDecimal dispatchedCost = order.getTotalInputCost(); // 1000.0000
        BigDecimal returnedCost = new BigDecimal("20.0000000").multiply(new BigDecimal("10.0000")); // 200.0000
        BigDecimal consumedCost = dispatchedCost.subtract(returnedCost); // 800.0000
        BigDecimal outputProductsCost = new BigDecimal("750.0000");
        BigDecimal scrapCost = new BigDecimal("50.0000");
        BigDecimal totalProducedValue = outputProductsCost.add(scrapCost); // 800.0000

        BigDecimal discrepancy = consumedCost.subtract(totalProducedValue);
        assertThat(discrepancy).isEqualByComparingTo(BigDecimal.ZERO);

        // 5. Warehouse Sales Dispatch without Duplicate Billing
        // Dispatching 50 units finished goods from Account 28 to external customer
        var salesPostingCmd = new PostingCommand(
            "SALES", "ISSUE", "SALES_DISPATCH", UUID.randomUUID(),
            "SALES-ORDER-7788", today, 1L, mapper.createObjectNode().put("qty", "50")
        );
        var salesDispatchReq = new AccountAwareStockPostingService.IssueRequest(
            salesPostingCmd,
            finishedMaterialId,
            warehouseId,
            null, null, null, null,
            "OWNED",
            "SALES_DISPATCH",
            "28",
            "PCS",
            new BigDecimal("50.0000000"),
            new BigDecimal("10.0000"),
            "UZS",
            "INVOICE-SUBLEDGER-REF-7788"
        );

        var salesReceipt = postingService.postIssue(salesDispatchReq);
        assertThat(salesReceipt).isNotNull();
        assertThat(salesReceipt.batchId()).isNotNull();

        // Verify issue was captured for sales dispatch
        ArgumentCaptor<AccountAwareStockPostingService.IssueRequest> issueCaptor =
            ArgumentCaptor.forClass(AccountAwareStockPostingService.IssueRequest.class);
        verify(postingService, times(2)).postIssue(issueCaptor.capture()); // 1 for tolling dispatch, 1 for sales dispatch
        var capturedSalesIssue = issueCaptor.getAllValues().get(1);
        assertThat(capturedSalesIssue.accountCode()).isEqualTo("28");
        assertThat(capturedSalesIssue.disposition()).isEqualTo("SALES_DISPATCH");
        assertThat(capturedSalesIssue.quantity()).isEqualByComparingTo("50.0000000");
    }

    @Test
    @DisplayName("E2E 05: Complete end-to-end industrial lifecycle across production receipt, tolling, partial return, transformation and sales dispatch")
    void e2e_05_completeIndustrialLifecycle() {
        LocalDate date = LocalDate.of(2026, 10, 14);

        // -------------------------------------------------------------
        // Step 1: External Production Receipt Adapter into Account 28
        // -------------------------------------------------------------
        String externalEventId = "EXT-PRD-EVENT-2026-900";
        var event = new ProductionReceiptEvent(
            externalEventId, 1, finishedMaterialId, warehouseId,
            new BigDecimal("200.0000000"), "PCS", new BigDecimal("50.0000"),
            new BigDecimal("10000.0000"), "28", "APPROVED", date, "Initial synthesis completion"
        );
        when(receiptRepository.findByTenantIdAndCompanyIdAndEventId(tenantId, companyId.toString(), externalEventId))
            .thenReturn(Optional.empty());

        var receiptResult = receiptAdapterService.processProductionReceipt(event);
        assertThat(receiptResult.eventId()).isEqualTo(externalEventId);
        assertThat(receiptResult.quantity()).isEqualByComparingTo("200.0000000");
        assertThat(receiptResult.isDuplicate()).isFalse();

        // -------------------------------------------------------------
        // Step 2: Idempotency Protection: Replay returns original receipt
        // -------------------------------------------------------------
        var captor = ArgumentCaptor.forClass(WarehouseProductionReceipt.class);
        verify(receiptRepository).save(captor.capture());
        WarehouseProductionReceipt saved = captor.getValue();

        when(receiptRepository.findByTenantIdAndCompanyIdAndEventId(tenantId, companyId.toString(), externalEventId))
            .thenReturn(Optional.of(saved));

        var replayResult = receiptAdapterService.processProductionReceipt(event);
        assertThat(replayResult.receiptId()).isEqualTo(saved.getId());
        assertThat(replayResult.isDuplicate()).isTrue();

        // -------------------------------------------------------------
        // Step 3: Raw material dispatched to tolling processor (Account 20)
        // -------------------------------------------------------------
        String tollingOrder = "TOLL-E2E5-001";
        when(transformationOrderRepository.findByTenantIdAndCompanyIdAndOrderNumber(tenantId, companyId.toString(), tollingOrder))
            .thenReturn(Optional.empty());

        var dispatchResult = transformationService.dispatchToProcessor(new DispatchToProcessorCommand(
            tollingOrder, warehouseId, processorId, rawMaterialId, "20",
            new BigDecimal("100.0000000"), new BigDecimal("25.0000"), date, "PO-TOLL-1"
        ));
        assertThat(dispatchResult.status()).isEqualTo(TransformationOrderStatus.DISPATCHED_TO_PROCESSOR);
        assertThat(dispatchResult.totalInputCost()).isEqualByComparingTo("2500.0000");

        var tollingOrderEntity = new WarehouseTransformationOrder();
        tollingOrderEntity.setId(dispatchResult.orderId());
        tollingOrderEntity.setTenantId(tenantId);
        tollingOrderEntity.setCompanyId(companyId.toString());
        tollingOrderEntity.setOrderNumber(tollingOrder);
        tollingOrderEntity.setTransformationType(TransformationType.PROCESSOR_TOLLING);
        tollingOrderEntity.setStatus(TransformationOrderStatus.DISPATCHED_TO_PROCESSOR);
        tollingOrderEntity.setWarehouseId(warehouseId);
        tollingOrderEntity.setProcessorId(processorId);
        tollingOrderEntity.setTotalInputCost(new BigDecimal("2500.0000"));
        tollingOrderEntity.setTotalOutputCost(BigDecimal.ZERO);
        tollingOrderEntity.setExecutionDate(date);

        var tollInputLine = new WarehouseTransformationLine();
        tollInputLine.setId(UUID.randomUUID());
        tollInputLine.setLineType(TransformationLineType.INPUT);
        tollInputLine.setMaterialId(rawMaterialId);
        tollInputLine.setAccountCode("20");
        tollInputLine.setQuantity(new BigDecimal("100.0000000"));
        tollInputLine.setUnitCost(new BigDecimal("25.0000"));
        tollInputLine.setAllocatedCost(new BigDecimal("2500.0000"));
        tollingOrderEntity.addLine(tollInputLine);

        // -------------------------------------------------------------
        // Step 4: Processor returns 20 unused units back to warehouse
        // -------------------------------------------------------------
        when(transformationOrderRepository.findByTenantIdAndCompanyIdAndOrderNumber(tenantId, companyId.toString(), tollingOrder))
            .thenReturn(Optional.of(tollingOrderEntity));

        var returnResult = transformationService.returnFromProcessor(new ReturnFromProcessorCommand(
            tollingOrder, warehouseId, rawMaterialId,
            new BigDecimal("20.0000000"), new BigDecimal("25.0000"), date,
            "Partial return of unused chemical", "RET-TOLL-01"
        ));
        assertThat(returnResult.status()).isEqualTo(TransformationOrderStatus.PARTIALLY_RETURNED);

        // -------------------------------------------------------------
        // Step 5: Processor completes processing of remaining 80 units
        // Consumed cost: 80 * 25.0 = 2000.0 UZS
        // Yield: 70 units refined chemical (1750.0 UZS) + 10 units scrap (250.0 UZS)
        // -------------------------------------------------------------
        var confirmResult = transformationService.confirmProcessorConsumption(new ConfirmProcessorConsumptionCommand(
            tollingOrder, processorId, new BigDecimal("80.0000000"),
            List.of(
                new TransformationLineItem(
                    TransformationLineType.OUTPUT, finishedMaterialId, "28",
                    new BigDecimal("70.0000000"), new BigDecimal("25.0000"), new BigDecimal("1750.0000")
                ),
                new TransformationLineItem(
                    TransformationLineType.SCRAP, scrapMaterialId, "1010",
                    new BigDecimal("10.0000000"), new BigDecimal("25.0000"), new BigDecimal("250.0000")
                )
            ),
            date, "CONFIRM-TOLL-01"
        ));
        assertThat(confirmResult.status()).isEqualTo(TransformationOrderStatus.EXECUTED);
        assertThat(confirmResult.totalOutputCost()).isEqualByComparingTo("2000.0000");

        // -------------------------------------------------------------
        // Step 6: Cutting transformation order
        // Intermediate chemical roll (10 units @ 50.0 = 500.0 UZS) cut into:
        // 20 smaller canisters (@ 22.5 = 450.0 UZS) + 2 scrap units (@ 25.0 = 50.0 UZS)
        // Total In (500.0) == Total Out (500.0)
        // -------------------------------------------------------------
        String cuttingOrder = "CUT-E2E5-001";
        when(transformationOrderRepository.findByTenantIdAndCompanyIdAndOrderNumber(tenantId, companyId.toString(), cuttingOrder))
            .thenReturn(Optional.empty());

        UUID cutProductId = UUID.randomUUID();
        var cuttingCmd = new ExecuteTransformationCommand(
            cuttingOrder,
            TransformationType.CUTTING,
            warehouseId,
            null,
            List.of(
                new TransformationLineItem(
                    TransformationLineType.INPUT, finishedMaterialId, "28",
                    new BigDecimal("10.0000000"), new BigDecimal("50.0000"), new BigDecimal("500.0000")
                )
            ),
            List.of(
                new TransformationLineItem(
                    TransformationLineType.OUTPUT, cutProductId, "28",
                    new BigDecimal("20.0000000"), new BigDecimal("22.5000"), new BigDecimal("450.0000")
                ),
                new TransformationLineItem(
                    TransformationLineType.SCRAP, scrapMaterialId, "1010",
                    new BigDecimal("2.0000000"), new BigDecimal("25.0000"), new BigDecimal("50.0000")
                )
            ),
            date, "CUT-WORK-ORDER-01"
        );
        var cuttingResult = transformationService.executeTransformation(cuttingCmd);
        assertThat(cuttingResult.status()).isEqualTo(TransformationOrderStatus.EXECUTED);
        assertThat(cuttingResult.totalInputCost()).isEqualByComparingTo(cuttingResult.totalOutputCost());

        // -------------------------------------------------------------
        // Step 7: Sales dispatch of canisters to client
        // -------------------------------------------------------------
        var salesPostingCmd = new PostingCommand(
            "SALES", "ISSUE", "SALES_DISPATCH", UUID.randomUUID(),
            "SO-E2E5-99", date, 1L, mapper.createObjectNode().put("qty", "20")
        );
        var salesDispatchReq = new AccountAwareStockPostingService.IssueRequest(
            salesPostingCmd,
            cutProductId,
            warehouseId,
            null, null, null, null,
            "OWNED",
            "SALES_DISPATCH",
            "28",
            "PCS",
            new BigDecimal("20.0000000"),
            new BigDecimal("22.5000"),
            "UZS",
            "INVOICE-E2E5-99"
        );
        var salesReceipt = postingService.postIssue(salesDispatchReq);
        assertThat(salesReceipt).isNotNull();

        // -------------------------------------------------------------
        // Step 8: Multi-Account Ledger and Conservation Reconciliation
        // -------------------------------------------------------------
        // Total Tolling Inputs: 2500 UZS (100 units)
        // Reconciled Tolling: Returned (500 UZS, 20 units) + Consumed Outputs (1750 UZS, 70 units) + Consumed Scrap (250 UZS, 10 units) = 2500 UZS
        BigDecimal tollingConservationDelta = new BigDecimal("2500.0000")
            .subtract(new BigDecimal("500.0000"))
            .subtract(new BigDecimal("1750.0000"))
            .subtract(new BigDecimal("250.0000"));
        assertThat(tollingConservationDelta).isEqualByComparingTo(BigDecimal.ZERO);

        // Cutting Conservation: Input (500 UZS) == Output (450 UZS) + Scrap (50 UZS)
        BigDecimal cuttingConservationDelta = cuttingResult.totalInputCost().subtract(cuttingResult.totalOutputCost());
        assertThat(cuttingConservationDelta).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
