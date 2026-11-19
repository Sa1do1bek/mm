package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.LandedCostAllocation;
import com.enterprise.mm.entity.LandedCostAllocationLine;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.mapper.LandedCostAllocationMapper;
import com.enterprise.mm.payload.LandedCostAllocationResponse;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.LandedCostAllocationLineRepository;
import com.enterprise.mm.repository.LandedCostAllocationRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LandedCostAllocationServiceTest {
    @Mock private LandedCostAllocationRepository repository;
    @Mock private LandedCostAllocationMapper mapper;
    @Mock private LandedCostAllocationLineRepository lineRepository;
    @Mock private StockBalanceRepository stockBalanceRepository;
    @Mock private StockBatchRepository stockBatchRepository;
    @Mock private ImportDeliveryRepository importDeliveryRepository;
    @Mock private InventoryCostingService inventoryCostingService;
    @Mock private FinancialAuthorizationService financialAuthorizationService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private MaterialCatalogGuard materialCatalogGuard;

    @InjectMocks
    private LandedCostAllocationService service;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-lc");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void postSupportsNonBatchedMaterialsWithoutException() {
        UUID docId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        LandedCostAllocation doc = new LandedCostAllocation();
        doc.setId(docId);
        doc.setTenantId("tenant-lc");
        doc.setCompanyId(companyId);
        doc.setStatus("DRAFT");
        doc.setCode("LCA-001");
        doc.setTotalAmount(new BigDecimal("1000.00"));
        doc.setBaseTotalAmount(new BigDecimal("1000.00"));
        doc.setImportDeliveryId(UUID.randomUUID());
        doc.setCurrencyCode("UZS");
        doc.setBaseCurrencyCode("UZS");
        doc.setExchangeRate(BigDecimal.ONE);
        doc.setAllocationMethod("VALUE");
        doc.setDocumentDate(LocalDate.now());

        LandedCostAllocationLine line = new LandedCostAllocationLine();
        line.setId(UUID.randomUUID());
        line.setTenantId("tenant-lc");
        line.setCompanyId(companyId);
        line.setAllocationDocumentId(docId);
        line.setMaterialId(materialId);
        line.setWarehouseId(warehouseId);
        line.setStockBatchId(null); // non-batched
        line.setQuantity(new BigDecimal("10"));
        line.setAllocatedAmount(new BigDecimal("1000.00"));
        line.setAllocatedBaseAmount(new BigDecimal("1000.00"));

        when(repository.findByIdAndIsDeletedFalse(docId)).thenReturn(Optional.of(doc));
        when(lineRepository.findByTenantIdAndCompanyIdAndAllocationDocumentIdAndIsDeletedFalse(
            "tenant-lc", companyId, docId
        )).thenReturn(List.of(line));

        StockBalance balance = new StockBalance();
        balance.setQuantity(new BigDecimal("10"));
        balance.setTotalValue(new BigDecimal("500.00"));
        when(stockBalanceRepository.findCompanyBalanceForUpdate(
            eq("tenant-lc"), eq(companyId), eq(materialId), eq(warehouseId), eq(true), any(), eq(true), any()
        )).thenReturn(Optional.of(balance));

        // Non-batched adjustment returns null stockBatchId
        InventoryCostingService.LandedCostAdjustment adjustment = new InventoryCostingService.LandedCostAdjustment(
            UUID.randomUUID(), null, new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("1000.00")
        );
        when(inventoryCostingService.capitalizeLandedCost(
            materialId, warehouseId, null, new BigDecimal("1000.00"), "LANDED_COST_ALLOCATION", docId
        )).thenReturn(List.of(adjustment));

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any())).thenAnswer(inv -> {
            LandedCostAllocation entity = inv.getArgument(0);
            LandedCostAllocationResponse resp = new LandedCostAllocationResponse();
            resp.setId(entity.getId());
            resp.setStatus(entity.getStatus());
            resp.setTotalAmount(entity.getTotalAmount());
            return resp;
        });

        LandedCostAllocationResponse result = service.post(docId);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("POSTED");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("1500.00");
        verify(financialAuthorizationService).requireFinance("post landed cost");
        verify(lineRepository).save(line);
    }
}
