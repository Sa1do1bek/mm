package com.enterprise.mm.warehouse.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementPosition;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementQuery;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementReport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P3 AT06 Acceptance Test:
 * Verifies independent holding statement calculation and balance reconciliation.
 * Aggregates all holding positions across material, department, employee, and account dimensions,
 * verifying that net movements match active quantities with zero unexplained delta.
 */
class HoldingStatementReconciliationTest {

    private static final String TENANT = "tenant-navoiy";
    private static final UUID COMPANY = UUID.randomUUID();

    private WarehouseHoldingRepository holdingRepository;
    private WarehouseHoldingHandoverRepository handoverRepository;
    private WarehouseHoldingTransferRepository transferRepository;
    private WarehouseHoldingService holdingService;

    private final Map<UUID, WarehouseHolding> holdingStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        holdingStore.clear();

        holdingRepository = mock(WarehouseHoldingRepository.class);
        handoverRepository = mock(WarehouseHoldingHandoverRepository.class);
        transferRepository = mock(WarehouseHoldingTransferRepository.class);

        when(holdingRepository.findAll()).thenAnswer(inv -> new ArrayList<>(holdingStore.values()));

        holdingService = new WarehouseHoldingService(
            holdingRepository,
            handoverRepository,
            transferRepository,
            null
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P3 AT06.1: Holding statement reconciles active holdings to 100% balanced with zero discrepancy")
    void statementCalculatesNetHoldingMovementsAndReconcilesToZeroDelta() {
        UUID mat1 = UUID.randomUUID();
        UUID dept1 = UUID.randomUUID();
        UUID emp1 = UUID.randomUUID();
        UUID dept2 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        // Holding 1: Dept 1 / Emp 1
        WarehouseHolding h1 = new WarehouseHolding();
        h1.setId(UUID.randomUUID());
        h1.setTenantId(TENANT);
        h1.setCompanyId(COMPANY);
        h1.setMaterialId(mat1);
        h1.setDepartmentId(dept1);
        h1.setEmployeeId(emp1);
        h1.setAccountCode("014");
        h1.setUom("PCS");
        h1.setActiveQuantity(new BigDecimal("10.0000000"));
        h1.setPendingQuantity(BigDecimal.ZERO);
        h1.setConsumedQuantity(new BigDecimal("2.0000000"));
        h1.setReturnedQuantity(new BigDecimal("3.0000000"));
        h1.setWrittenOffQuantity(new BigDecimal("1.0000000"));
        h1.setTransferredInQuantity(BigDecimal.ZERO);
        h1.setTransferredOutQuantity(new BigDecimal("4.0000000"));
        holdingStore.put(h1.getId(), h1);

        // Holding 2: Dept 2 / Emp 2
        WarehouseHolding h2 = new WarehouseHolding();
        h2.setId(UUID.randomUUID());
        h2.setTenantId(TENANT);
        h2.setCompanyId(COMPANY);
        h2.setMaterialId(mat1);
        h2.setDepartmentId(dept2);
        h2.setEmployeeId(emp2);
        h2.setAccountCode("014");
        h2.setUom("PCS");
        h2.setActiveQuantity(new BigDecimal("4.0000000"));
        h2.setPendingQuantity(BigDecimal.ZERO);
        h2.setConsumedQuantity(BigDecimal.ZERO);
        h2.setReturnedQuantity(BigDecimal.ZERO);
        h2.setWrittenOffQuantity(BigDecimal.ZERO);
        h2.setTransferredInQuantity(new BigDecimal("4.0000000"));
        h2.setTransferredOutQuantity(BigDecimal.ZERO);
        holdingStore.put(h2.getId(), h2);

        HoldingStatementQuery query = new HoldingStatementQuery(mat1, null, null, "014");
        HoldingStatementReport report = holdingService.generateHoldingStatement(query);

        assertThat(report).isNotNull();
        assertThat(report.totalHoldings()).isEqualTo(2);
        assertThat(report.balancedHoldings()).isEqualTo(2);
        assertThat(report.discrepancyHoldings()).isEqualTo(0);
        assertThat(report.isFullyBalanced()).isTrue();
        assertThat(report.totalActiveQuantity()).isEqualByComparingTo("14.0000000");

        for (HoldingStatementPosition pos : report.positions()) {
            assertThat(pos.isBalanced()).isTrue();
            assertThat(pos.quantityDelta()).isEqualByComparingTo("0.0000000");
        }
    }
}
