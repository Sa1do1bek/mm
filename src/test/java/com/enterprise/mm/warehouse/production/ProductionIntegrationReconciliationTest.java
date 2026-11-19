package com.enterprise.mm.warehouse.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingEffect;
import com.enterprise.mm.warehouse.posting.PostingEffectType;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionCorrectionCommand;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionCorrectionResult;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReconciliationReport;
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

class ProductionIntegrationReconciliationTest {

    private WarehouseProductionReceiptRepository receiptRepository;
    private WarehouseProductionCorrectionRepository correctionRepository;
    private AccountAwareStockPostingService postingService;
    private ProductionReconciliationService reconciliationService;

    private final String tenantId = "test-tenant";
    private final UUID companyId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        receiptRepository = mock(WarehouseProductionReceiptRepository.class);
        correctionRepository = mock(WarehouseProductionCorrectionRepository.class);
        postingService = mock(AccountAwareStockPostingService.class);

        when(correctionRepository.save(any(WarehouseProductionCorrection.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        reconciliationService = new ProductionReconciliationService(
            receiptRepository,
            correctionRepository,
            postingService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P6 PR6B: Correction linkage appends compensating effect without editing posted receipt")
    void testCorrectionLinkageWithoutEditingPostedReceipt() {
        // Setup parent production receipt
        WarehouseProductionReceipt parent = new WarehouseProductionReceipt();
        parent.setId(UUID.randomUUID());
        parent.setTenantId(tenantId);
        parent.setCompanyId(companyId.toString());
        parent.setEventId("EVT-PROD-PARENT-01");
        parent.setEventVersion(1);
        parent.setMaterialId(materialId);
        parent.setWarehouseId(warehouseId);
        parent.setAccountCode("28");
        parent.setQuantity(new BigDecimal("100.0000000"));
        parent.setTotalCost(new BigDecimal("50000000.0000"));
        parent.setPostingReceiptId("POST-ORIGINAL-01");

        when(receiptRepository.findByTenantIdAndCompanyIdAndEventId(
            tenantId, companyId.toString(), "EVT-PROD-PARENT-01"
        )).thenReturn(Optional.of(parent));

        when(correctionRepository.findByTenantIdAndCompanyIdAndCorrectionEventId(any(), any(), any()))
            .thenReturn(Optional.empty());

        var effect = new PostingEffect(
            PostingEffectType.STOCK, materialId, warehouseId, "28", "PCS",
            new BigDecimal("10.0000000"), BigDecimal.ZERO, "UZS"
        );
        PostingReceipt mockPostingReceipt = new PostingReceipt(
            UUID.randomUUID(), UUID.randomUUID(), 2, LocalDate.now(), List.of(effect)
        );
        when(postingService.postIssue(any())).thenReturn(mockPostingReceipt);

        // Negative correction: reduction of 10 units
        var correctionCmd = new ProductionCorrectionCommand(
            "EVT-PROD-PARENT-01",
            "EVT-CORR-01",
            2,
            new BigDecimal("-10.0000000"),
            new BigDecimal("-5000000.0000"),
            "DEFECTIVE_YIELD_ADJUSTMENT"
        );

        ProductionCorrectionResult result = reconciliationService.applyProductionCorrection(correctionCmd);

        // 1. Verify parent receipt was NOT changed in-place
        assertThat(parent.getQuantity()).isEqualByComparingTo("100.0000000");
        assertThat(parent.getEventVersion()).isEqualTo(1);

        // 2. Verify correction result
        assertThat(result.parentEventId()).isEqualTo("EVT-PROD-PARENT-01");
        assertThat(result.correctionEventId()).isEqualTo("EVT-CORR-01");
        assertThat(result.deltaQuantity()).isEqualByComparingTo("-10.0000000");

        // 3. Verify issue posting was called for the reduction
        var issueCaptor = ArgumentCaptor.forClass(AccountAwareStockPostingService.IssueRequest.class);
        verify(postingService).postIssue(issueCaptor.capture());
        AccountAwareStockPostingService.IssueRequest issue = issueCaptor.getValue();
        assertThat(issue.quantity()).isEqualByComparingTo("10.0000000");
        assertThat(issue.accountCode()).isEqualTo("28");
        assertThat(issue.sourceReference()).contains("CORRECTION_OF:EVT-PROD-PARENT-01");

        // 4. Verify correction saved
        verify(correctionRepository).save(any(WarehouseProductionCorrection.class));

        // 5. Reject equal or lower version
        var staleCmd = new ProductionCorrectionCommand(
            "EVT-PROD-PARENT-01", "EVT-CORR-STALE", 1, new BigDecimal("5"), BigDecimal.ZERO, "STALE"
        );
        assertThatThrownBy(() -> reconciliationService.applyProductionCorrection(staleCmd))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("must be strictly greater than parent version");
    }

    @Test
    @DisplayName("P6 PR6B: Independent receipt totals reconstruction across receipts and corrections")
    void testReconstructProductionReceiptTotals() {
        // Original receipts
        WarehouseProductionReceipt r1 = new WarehouseProductionReceipt();
        r1.setMaterialId(materialId);
        r1.setWarehouseId(warehouseId);
        r1.setQuantity(new BigDecimal("100.0000000"));
        r1.setTotalCost(new BigDecimal("50000000.0000"));

        WarehouseProductionReceipt r2 = new WarehouseProductionReceipt();
        r2.setMaterialId(materialId);
        r2.setWarehouseId(warehouseId);
        r2.setQuantity(new BigDecimal("200.0000000"));
        r2.setTotalCost(new BigDecimal("100000000.0000"));

        when(receiptRepository.findByTenantIdAndCompanyIdAndMaterialId(
            tenantId, companyId.toString(), materialId
        )).thenReturn(List.of(r1, r2));

        // Corrections
        WarehouseProductionCorrection c1 = new WarehouseProductionCorrection();
        c1.setMaterialId(materialId);
        c1.setWarehouseId(warehouseId);
        c1.setDeltaQuantity(new BigDecimal("-10.0000000"));
        c1.setDeltaCost(new BigDecimal("-5000000.0000"));

        WarehouseProductionCorrection c2 = new WarehouseProductionCorrection();
        c2.setMaterialId(materialId);
        c2.setWarehouseId(warehouseId);
        c2.setDeltaQuantity(new BigDecimal("20.0000000"));
        c2.setDeltaCost(new BigDecimal("10000000.0000"));

        when(correctionRepository.findByTenantIdAndCompanyIdAndMaterialId(
            tenantId, companyId.toString(), materialId
        )).thenReturn(List.of(c1, c2));

        ProductionReconciliationReport report = reconciliationService.reconstructProductionReceiptTotals(
            materialId, warehouseId
        );

        // Original sum = 300, 150,000,000
        assertThat(report.totalOriginalQuantity()).isEqualByComparingTo("300.0000000");
        assertThat(report.totalOriginalCost()).isEqualByComparingTo("150000000.0000");

        // Delta sum = +10, +5,000,000
        assertThat(report.totalDeltaQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(report.totalDeltaCost()).isEqualByComparingTo("5000000.0000");

        // Net reconstructed = 310, 155,000,000
        assertThat(report.netReconstructedQuantity()).isEqualByComparingTo("310.0000000");
        assertThat(report.netReconstructedCost()).isEqualByComparingTo("155000000.0000");
    }

    @Test
    @DisplayName("P6 PR6B: Duplicate correction event with identical payload replays existing result without double-posting")
    void testCorrectionIdempotentReplay() {
        WarehouseProductionCorrection existing = new WarehouseProductionCorrection();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setCompanyId(companyId.toString());
        existing.setParentEventId("EVT-PROD-PARENT-01");
        existing.setCorrectionEventId("EVT-CORR-REPLAY");
        existing.setDeltaQuantity(new BigDecimal("-5.0000000"));
        existing.setDeltaCost(new BigDecimal("-2500000.0000"));
        existing.setPostingReceiptId("POST-REPLAY-99");

        when(correctionRepository.findByTenantIdAndCompanyIdAndCorrectionEventId(
            tenantId, companyId.toString(), "EVT-CORR-REPLAY"
        )).thenReturn(Optional.of(existing));

        var replayCmd = new ProductionCorrectionCommand(
            "EVT-PROD-PARENT-01",
            "EVT-CORR-REPLAY",
            2,
            new BigDecimal("-5.0000000"),
            new BigDecimal("-2500000.0000"),
            "RETRY"
        );

        ProductionCorrectionResult result = reconciliationService.applyProductionCorrection(replayCmd);
        assertThat(result.correctionId()).isEqualTo(existing.getId());
        assertThat(result.postingReceiptId()).isEqualTo("POST-REPLAY-99");
        assertThat(result.deltaQuantity()).isEqualByComparingTo("-5.0000000");

        // Verify NO additional posting was executed
        verify(postingService, never()).postIssue(any());
        verify(postingService, never()).postReceipt(any());
    }
}
