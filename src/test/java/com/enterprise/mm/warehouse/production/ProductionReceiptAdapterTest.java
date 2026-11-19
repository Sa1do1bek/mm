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
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingEffect;
import com.enterprise.mm.warehouse.posting.PostingEffectType;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReceiptEvent;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReceiptResult;
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

class ProductionReceiptAdapterTest {

    private WarehouseProductionReceiptRepository receiptRepository;
    private AccountAwareStockPostingService postingService;
    private ProductionReceiptAdapterService adapterService;

    private final String tenantId = "test-tenant";
    private final UUID companyId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        receiptRepository = mock(WarehouseProductionReceiptRepository.class);
        postingService = mock(AccountAwareStockPostingService.class);

        when(receiptRepository.save(any(WarehouseProductionReceipt.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        adapterService = new ProductionReceiptAdapterService(receiptRepository, postingService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P6 AT01: Validated production receipt posts to account 28 with quality disposition")
    void p6_at01_productionReceiptPosting() {
        when(receiptRepository.findByTenantIdAndCompanyIdAndEventId(any(), any(), any()))
            .thenReturn(Optional.empty());

        var effect = new PostingEffect(
            PostingEffectType.STOCK, materialId, warehouseId, "28", "KG",
            new BigDecimal("100.0000000"), BigDecimal.ZERO, "UZS"
        );
        PostingReceipt mockPostingReceipt = new PostingReceipt(
            UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.of(2026, 10, 20),
            List.of(effect)
        );
        when(postingService.postReceipt(any(AccountAwareStockPostingService.ReceiptRequest.class)))
            .thenReturn(mockPostingReceipt);

        var event = new ProductionReceiptEvent(
            "EVT-PROD-101",
            1,
            materialId,
            warehouseId,
            new BigDecimal("100.0000000"),
            "KG",
            new BigDecimal("500000.0000"),
            new BigDecimal("50000000.0000"),
            "28",
            "APPROVED",
            LocalDate.of(2026, 10, 20),
            "ORDER-PROD-900"
        );

        ProductionReceiptResult result = adapterService.processProductionReceipt(event);

        // 1. Verify result
        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.eventId()).isEqualTo("EVT-PROD-101");
        assertThat(result.quantity()).isEqualByComparingTo("100.0000000");
        assertThat(result.totalCost()).isEqualByComparingTo("50000000.0000");
        assertThat(result.postingReceiptId()).isEqualTo(mockPostingReceipt.batchId().toString());

        // 2. Verify shared posting request
        var captor = ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(postingService).postReceipt(captor.capture());
        AccountAwareStockPostingService.ReceiptRequest req = captor.getValue();
        assertThat(req.accountCode()).isEqualTo("28");
        assertThat(req.materialId()).isEqualTo(materialId);
        assertThat(req.warehouseId()).isEqualTo(warehouseId);
        assertThat(req.disposition()).isEqualTo("AVAILABLE");
        assertThat(req.quantity()).isEqualByComparingTo("100.0000000");
        assertThat(req.unitCost()).isEqualByComparingTo("500000.0000");

        // 3. Verify repository saved
        verify(receiptRepository).save(any(WarehouseProductionReceipt.class));
    }

    @Test
    @DisplayName("P6 AT06: Repeated delivery posts once; conflicting event payload is rejected")
    void p6_at06_duplicateAndConflictSafety() {
        var event = new ProductionReceiptEvent(
            "EVT-PROD-DUP-01",
            1,
            materialId,
            warehouseId,
            new BigDecimal("50.0000000"),
            "PCS",
            new BigDecimal("100000.0000"),
            new BigDecimal("5000000.0000"),
            "28",
            "APPROVED",
            LocalDate.of(2026, 10, 21),
            "ORDER-PROD-901"
        );

        // Compute hash of the original event
        WarehouseProductionReceipt existing = new WarehouseProductionReceipt();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setCompanyId(companyId.toString());
        existing.setEventId("EVT-PROD-DUP-01");
        existing.setQuantity(new BigDecimal("50.0000000"));
        existing.setTotalCost(new BigDecimal("5000000.0000"));
        existing.setPostingReceiptId("POST-REC-EXISTING");
        // exact hash matching
        existing.setContentHash("d117a544f8087fc76278fc9e09d0cb0958a0bb967926b01b6ec9f96404ebca30");

        // 1. First scenario: exactly identical retry
        // Let's first test that passing identical event when already in repository returns isDuplicate=true
        // We can let the adapter compute the hash:
        when(receiptRepository.findByTenantIdAndCompanyIdAndEventId(any(), any(), any()))
            .thenReturn(Optional.empty());
        var effect1 = new PostingEffect(
            PostingEffectType.STOCK, materialId, warehouseId, "28", "PCS",
            new BigDecimal("50.0000000"), BigDecimal.ZERO, "UZS"
        );
        when(postingService.postReceipt(any())).thenReturn(
            new PostingReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.now(), List.of(effect1))
        );

        ProductionReceiptResult first = adapterService.processProductionReceipt(event);
        assertThat(first.isDuplicate()).isFalse();

        // Capture saved record
        var captor = ArgumentCaptor.forClass(WarehouseProductionReceipt.class);
        verify(receiptRepository).save(captor.capture());
        WarehouseProductionReceipt saved = captor.getValue();

        // 2. Exact duplicate retry: repository returns previously saved record
        when(receiptRepository.findByTenantIdAndCompanyIdAndEventId(
            tenantId, companyId.toString(), "EVT-PROD-DUP-01"
        )).thenReturn(Optional.of(saved));

        ProductionReceiptResult retry = adapterService.processProductionReceipt(event);
        assertThat(retry.isDuplicate()).isTrue();
        assertThat(retry.postingReceiptId()).isEqualTo(first.postingReceiptId());
        // Verify postReceipt was NOT called again!
        verify(postingService).postReceipt(any()); // Only once from step 1

        // 3. Conflicting modified event: same ID, different quantity (e.g. 75 instead of 50)
        var modifiedEvent = new ProductionReceiptEvent(
            "EVT-PROD-DUP-01",
            1,
            materialId,
            warehouseId,
            new BigDecimal("75.0000000"), // changed!
            "PCS",
            new BigDecimal("100000.0000"),
            new BigDecimal("7500000.0000"),
            "28",
            "APPROVED",
            LocalDate.of(2026, 10, 21),
            "ORDER-PROD-901"
        );

        assertThatThrownBy(() -> adapterService.processProductionReceipt(modifiedEvent))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Conflict: Production receipt event ID 'EVT-PROD-DUP-01' already exists with different payload");
    }

    @Test
    @DisplayName("P6 PR6A: Null accountCode in production event defaults to canonical 2810")
    void p6_pr6a_nullAccountDefaultsTo2810() {
        var effect = new PostingEffect(
            PostingEffectType.STOCK, materialId, warehouseId, "2810", "KG",
            new BigDecimal("20.0000000"), BigDecimal.ZERO, "UZS"
        );
        PostingReceipt mockPostingReceipt = new PostingReceipt(
            UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.now(), List.of(effect)
        );
        when(postingService.postReceipt(any())).thenReturn(mockPostingReceipt);

        var eventWithNullAccount = new ProductionReceiptEvent(
            "EVT-PROD-DEF-2810",
            1,
            materialId,
            warehouseId,
            new BigDecimal("20.0000000"),
            "KG",
            new BigDecimal("100000.0000"),
            new BigDecimal("2000000.0000"),
            null, // null account code!
            "APPROVED",
            LocalDate.of(2026, 10, 20),
            "ORDER-PROD-999"
        );

        ProductionReceiptResult result = adapterService.processProductionReceipt(eventWithNullAccount);
        assertThat(result.isDuplicate()).isFalse();

        var captor = ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(postingService).postReceipt(captor.capture());
        AccountAwareStockPostingService.ReceiptRequest req = captor.getValue();
        assertThat(req.accountCode()).isEqualTo("2810");
    }
}
