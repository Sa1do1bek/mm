package com.enterprise.mm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockValuation;
import com.enterprise.mm.entity.StockValuationDocument;
import com.enterprise.mm.enums.NonMovingStockExecutionOutputStatus;
import com.enterprise.mm.enums.RevaluationPostingMode;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.mapper.StockValuationDocumentMapper;
import com.enterprise.mm.repository.StockValuationDocumentRepository;
import java.math.BigDecimal;
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
class StockValuationDocumentNonMovingStockCallbackTest {
    @Mock StockValuationDocumentRepository repository;
    @Mock StockValuationDocumentMapper mapper;
    @Mock MaterialCatalogGuard materialCatalogGuard;
    @Mock StockValuationService valuationService;
    @Mock InventoryCostingService inventoryCostingService;
    @Mock InventoryValuationPolicyService valuationPolicyService;
    @Mock FinancialAuthorizationService financialAuthorizationService;
    @Mock AuditService auditService;
    @Mock RoleDataScopeService roleDataScopeService;
    @Mock NonMovingStockExecutionService nonMovingStockExecutionService;

    @InjectMocks StockValuationDocumentService service;

    private UUID companyId;

    @BeforeEach
    void setUpScope() {
        TenantContext.setTenant("default");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void postingValuationDocumentCompletesLinkedNonMovingStockOutput() {
        StockValuationDocument document = impairmentDocument("APPROVED");
        StockValuation valuation = new StockValuation();
        valuation.setQuantity(new BigDecimal("4"));
        valuation.setImpairmentReserve(new BigDecimal("30"));
        when(repository.findByIdAndIsDeletedFalse(document.getId())).thenReturn(Optional.of(document));
        when(valuationService.applyImpairmentReserve(
            document.getMaterialId(), document.getWarehouseId(), ValuationMethod.NET_REALIZABLE_VALUE,
            document.getReserveAmount(), document.getReason()
        )).thenReturn(valuation);
        when(valuationPolicyService.impairmentPostingMode()).thenReturn(RevaluationPostingMode.VALUATION_RECORD_ONLY);
        when(repository.save(any(StockValuationDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.post(document.getId());

        verify(nonMovingStockExecutionService).reconcileDocumentOutput(
            "STOCK_VALUATION_DOCUMENT",
            document.getId(),
            NonMovingStockExecutionOutputStatus.POSTED,
            null,
            null
        );
    }

    @Test
    void rejectingValuationDocumentCancelsLinkedNonMovingStockOutput() {
        StockValuationDocument document = impairmentDocument("UNDER_REVIEW");
        when(repository.findByIdAndIsDeletedFalse(document.getId())).thenReturn(Optional.of(document));
        when(repository.save(any(StockValuationDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.reject(document.getId());

        verify(nonMovingStockExecutionService).reconcileDocumentOutput(
            "STOCK_VALUATION_DOCUMENT",
            document.getId(),
            NonMovingStockExecutionOutputStatus.CANCELLED,
            "STOCK_VALUATION_DOCUMENT_REJECTED",
            "Stock valuation document was rejected"
        );
    }

    private StockValuationDocument impairmentDocument(String status) {
        StockValuationDocument document = new StockValuationDocument();
        document.setId(UUID.randomUUID());
        document.setTenantId("default");
        document.setCompanyId(companyId);
        document.setCode("VAL-NMS");
        document.setName("Non-moving stock impairment");
        document.setStatus(status);
        document.setMaterialId(UUID.randomUUID());
        document.setWarehouseId(UUID.randomUUID());
        document.setDocumentType("IMPAIRMENT_RESERVE");
        document.setValuationMethod(ValuationMethod.NET_REALIZABLE_VALUE);
        document.setQuantity(new BigDecimal("4"));
        document.setReserveAmount(new BigDecimal("30"));
        document.setReason("Approved non-moving stock impairment");
        return document;
    }
}
