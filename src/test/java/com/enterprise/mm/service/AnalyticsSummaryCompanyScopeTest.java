package com.enterprise.mm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.repository.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsSummaryCompanyScopeTest {
    @Test
    void dashboardSummaryUsesCompanyScopedQueries() {
        var materials = mock(MaterialRepository.class);
        var suppliers = mock(SupplierRepository.class);
        var requests = mock(PurchaseRequestRepository.class);
        var orders = mock(PurchaseOrderRepository.class);
        var balances = mock(StockBalanceRepository.class);
        var nonMoving = mock(NonMovingStockRepository.class);
        var service = new AnalyticsSummaryService(materials, suppliers, requests, orders, balances, nonMoving);
        UUID company = UUID.randomUUID();
        CompanyContext.setCompany(company);
        TenantContext.setTenant("integration-test");
        try {
            service.companySummary();
            verify(balances).findByTenantIdAndCompanyIdAndIsDeletedFalse("integration-test", company);
            verify(orders).countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(eq("integration-test"), eq(company), any());
            verify(requests).countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(eq("integration-test"), eq(company), any());
            verify(balances, never()).findByTenantIdAndIsDeletedFalse(any());
        } finally {
            CompanyContext.clear();
            TenantContext.clear();
        }
    }
}
