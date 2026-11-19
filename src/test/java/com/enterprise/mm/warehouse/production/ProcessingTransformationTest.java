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
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingEffect;
import com.enterprise.mm.warehouse.posting.PostingEffectType;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ConfirmProcessorConsumptionCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.DispatchToProcessorCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ExecuteTransformationCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationLineItem;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationLineType;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationOrderStatus;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationResult;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProcessingTransformationTest {

    private WarehouseTransformationOrderRepository orderRepository;
    private AccountAwareStockPostingService postingService;
    private ProcessingTransformationService service;

    private final String tenantId = "test-tenant";
    private final UUID companyId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID processorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        orderRepository = mock(WarehouseTransformationOrderRepository.class);
        postingService = mock(AccountAwareStockPostingService.class);

        when(orderRepository.save(any(WarehouseTransformationOrder.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var effect = new PostingEffect(
            PostingEffectType.STOCK, UUID.randomUUID(), warehouseId, "1010", "PCS",
            new BigDecimal("1"), BigDecimal.ZERO, "UZS"
        );
        PostingReceipt receipt = new PostingReceipt(
            UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.now(), List.of(effect)
        );
        when(postingService.postIssue(any())).thenReturn(receipt);
        when(postingService.postReceipt(any())).thenReturn(receipt);

        service = new ProcessingTransformationService(orderRepository, postingService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P6 AT02: Cutting transformation with scrap yield and exact value conservation")
    void p6_at02_cuttingTransformation() {
        UUID rawSheetId = UUID.randomUUID();
        UUID cutPartsId = UUID.randomUUID();
        UUID scrapId = UUID.randomUUID();

        when(orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(any(), any(), any()))
            .thenReturn(Optional.empty());

        // 1. Input: 100 kg raw sheet @ 10,000,000 UZS
        var input = new TransformationLineItem(
            TransformationLineType.INPUT, rawSheetId, "1010",
            new BigDecimal("100.0000000"), new BigDecimal("100000.0000"), new BigDecimal("10000000.0000")
        );

        // 2. Output: 80 kg parts (9,500,000) + 20 kg scrap (500,000)
        var outputParts = new TransformationLineItem(
            TransformationLineType.OUTPUT, cutPartsId, "1010",
            new BigDecimal("80.0000000"), new BigDecimal("118750.0000"), new BigDecimal("9500000.0000")
        );
        var outputScrap = new TransformationLineItem(
            TransformationLineType.SCRAP, scrapId, "1006",
            new BigDecimal("20.0000000"), new BigDecimal("25000.0000"), new BigDecimal("500000.0000")
        );

        var cmd = new ExecuteTransformationCommand(
            "ORD-CUT-01", TransformationType.CUTTING, warehouseId, null,
            List.of(input), List.of(outputParts, outputScrap),
            LocalDate.of(2026, 10, 25), "RASKROY-101"
        );

        TransformationResult result = service.executeTransformation(cmd);

        assertThat(result.orderNumber()).isEqualTo("ORD-CUT-01");
        assertThat(result.transformationType()).isEqualTo(TransformationType.CUTTING);
        assertThat(result.status()).isEqualTo(TransformationOrderStatus.EXECUTED);
        assertThat(result.totalInputCost()).isEqualByComparingTo("10000000.0000");
        assertThat(result.totalOutputCost()).isEqualByComparingTo("10000000.0000");

        // Verify postings: 1 issue, 2 receipts
        verify(postingService, times(1)).postIssue(any());
        verify(postingService, times(2)).postReceipt(any());

        // Value discrepancy rejection
        var invalidScrap = new TransformationLineItem(
            TransformationLineType.SCRAP, scrapId, "1006",
            new BigDecimal("20.0000000"), new BigDecimal("10000.0000"), new BigDecimal("200000.0000")
        );
        var invalidCmd = new ExecuteTransformationCommand(
            "ORD-CUT-FAIL", TransformationType.CUTTING, warehouseId, null,
            List.of(input), List.of(outputParts, invalidScrap),
            LocalDate.of(2026, 10, 25), "RASKROY-FAIL"
        );
        assertThatThrownBy(() -> service.executeTransformation(invalidCmd))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Transformation value discrepancy");
    }

    @Test
    @DisplayName("P6 AT03: Assembly transformation of multiple components with exact cost rollup")
    void p6_at03_assemblyTransformation() {
        UUID compAId = UUID.randomUUID();
        UUID compBId = UUID.randomUUID();
        UUID finishedUnitId = UUID.randomUUID();

        when(orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(any(), any(), any()))
            .thenReturn(Optional.empty());

        // 2 units A @ 1,000,000 = 2,000,000; 1 unit B @ 3,000,000 = 3,000,000 -> Total 5,000,000
        var inA = new TransformationLineItem(
            TransformationLineType.INPUT, compAId, "1010",
            new BigDecimal("2.0000000"), new BigDecimal("1000000.0000"), new BigDecimal("2000000.0000")
        );
        var inB = new TransformationLineItem(
            TransformationLineType.INPUT, compBId, "1010",
            new BigDecimal("1.0000000"), new BigDecimal("3000000.0000"), new BigDecimal("3000000.0000")
        );

        // 1 finished unit = 5,000,000 UZS on Account 28
        var out = new TransformationLineItem(
            TransformationLineType.OUTPUT, finishedUnitId, "28",
            new BigDecimal("1.0000000"), new BigDecimal("5000000.0000"), new BigDecimal("5000000.0000")
        );

        var cmd = new ExecuteTransformationCommand(
            "ORD-ASM-01", TransformationType.ASSEMBLY, warehouseId, null,
            List.of(inA, inB), List.of(out),
            LocalDate.of(2026, 10, 26), "ASSEMBLY-ORDER-50"
        );

        TransformationResult result = service.executeTransformation(cmd);
        assertThat(result.transformationType()).isEqualTo(TransformationType.ASSEMBLY);
        assertThat(result.totalInputCost()).isEqualByComparingTo("5000000.0000");
        assertThat(result.totalOutputCost()).isEqualByComparingTo("5000000.0000");

        verify(postingService, times(2)).postIssue(any());
        verify(postingService, times(1)).postReceipt(any());
    }

    @Test
    @DisplayName("P6 AT04: Disassembly transformation with approved component cost allocation")
    void p6_at04_disassemblyTransformation() {
        UUID parentUnitId = UUID.randomUUID();
        UUID motorId = UUID.randomUUID();
        UUID impellerId = UUID.randomUUID();
        UUID scrapId = UUID.randomUUID();

        when(orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(any(), any(), any()))
            .thenReturn(Optional.empty());

        // 1 decommissioned pump unit = 6,000,000 UZS
        var input = new TransformationLineItem(
            TransformationLineType.INPUT, parentUnitId, "1010",
            new BigDecimal("1.0000000"), new BigDecimal("6000000.0000"), new BigDecimal("6000000.0000")
        );

        // Motor (4M) + Impeller (1.5M) + Scrap (0.5M) = 6M
        var outMotor = new TransformationLineItem(
            TransformationLineType.OUTPUT, motorId, "1010",
            new BigDecimal("1.0000000"), new BigDecimal("4000000.0000"), new BigDecimal("4000000.0000")
        );
        var outImpeller = new TransformationLineItem(
            TransformationLineType.OUTPUT, impellerId, "1010",
            new BigDecimal("1.0000000"), new BigDecimal("1500000.0000"), new BigDecimal("1500000.0000")
        );
        var outScrap = new TransformationLineItem(
            TransformationLineType.SCRAP, scrapId, "1006",
            new BigDecimal("10.0000000"), new BigDecimal("50000.0000"), new BigDecimal("500000.0000")
        );

        var cmd = new ExecuteTransformationCommand(
            "ORD-DISASM-01", TransformationType.DISASSEMBLY, warehouseId, null,
            List.of(input), List.of(outMotor, outImpeller, outScrap),
            LocalDate.of(2026, 10, 27), "DISASSEMBLY-55"
        );

        TransformationResult result = service.executeTransformation(cmd);
        assertThat(result.transformationType()).isEqualTo(TransformationType.DISASSEMBLY);
        assertThat(result.totalInputCost()).isEqualByComparingTo("6000000.0000");
        assertThat(result.totalOutputCost()).isEqualByComparingTo("6000000.0000");

        verify(postingService, times(1)).postIssue(any());
        verify(postingService, times(3)).postReceipt(any());
    }

    @Test
    @DisplayName("P6 AT05: Processor dispatch retains transit disposition; consumption posted on confirmation")
    void p6_at05_processorDispatchAndConsumption() {
        UUID rawMaterialId = UUID.randomUUID();
        UUID processedGoodId = UUID.randomUUID();

        when(orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(any(), any(), any()))
            .thenReturn(Optional.empty());

        // 1. Dispatch 500 units to processor
        var dispatchCmd = new DispatchToProcessorCommand(
            "ORD-TOLL-01", warehouseId, processorId, rawMaterialId, "20",
            new BigDecimal("500.0000000"), new BigDecimal("10000.0000"),
            LocalDate.of(2026, 10, 28), "CONTRACT-TOLL-99"
        );

        TransformationResult dispResult = service.dispatchToProcessor(dispatchCmd);
        assertThat(dispResult.status()).isEqualTo(TransformationOrderStatus.DISPATCHED_TO_PROCESSOR);

        // Verify issue was posted to PROCESSOR_TRANSIT disposition (not final expense!)
        var issueCaptor = ArgumentCaptor.forClass(AccountAwareStockPostingService.IssueRequest.class);
        verify(postingService).postIssue(issueCaptor.capture());
        AccountAwareStockPostingService.IssueRequest issue = issueCaptor.getValue();
        assertThat(issue.disposition()).isEqualTo("PROCESSOR_TRANSIT");
        assertThat(issue.accountCode()).isEqualTo("20");

        // 2. Confirm processing and receive processed goods
        WarehouseTransformationOrder existingOrder = new WarehouseTransformationOrder();
        existingOrder.setId(dispResult.orderId());
        existingOrder.setOrderNumber("ORD-TOLL-01");
        existingOrder.setWarehouseId(warehouseId);
        existingOrder.setProcessorId(processorId);
        existingOrder.setStatus(TransformationOrderStatus.DISPATCHED_TO_PROCESSOR);
        existingOrder.setTotalInputCost(new BigDecimal("5000000.0000"));

        when(orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(
            tenantId, companyId.toString(), "ORD-TOLL-01"
        )).thenReturn(Optional.of(existingOrder));

        var outProcessed = new TransformationLineItem(
            TransformationLineType.OUTPUT, processedGoodId, "28",
            new BigDecimal("480.0000000"), new BigDecimal("10416.6667"), new BigDecimal("5000000.0000")
        );

        var confirmCmd = new ConfirmProcessorConsumptionCommand(
            "ORD-TOLL-01", processorId, new BigDecimal("500.0000000"),
            List.of(outProcessed), LocalDate.of(2026, 10, 29), "PROCESSING-ACT-12"
        );

        TransformationResult confirmedResult = service.confirmProcessorConsumption(confirmCmd);
        assertThat(confirmedResult.status()).isEqualTo(TransformationOrderStatus.EXECUTED);
        assertThat(confirmedResult.totalOutputCost()).isEqualByComparingTo("5000000.0000");

        verify(postingService).postReceipt(any());
    }
}
