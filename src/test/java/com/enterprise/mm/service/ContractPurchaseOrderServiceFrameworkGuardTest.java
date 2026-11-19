package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ProcurementFramework;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.ProcurementFrameworkRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
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
class ContractPurchaseOrderServiceFrameworkGuardTest {
    @Mock private ContractRepository contractRepository;
    @Mock private ContractSpecificationRepository specificationRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderService purchaseOrderService;
    @Mock private AuditService auditService;
    @Mock private PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    @Mock private ProcurementFrameworkRepository procurementFrameworkRepository;

    @InjectMocks
    private ContractPurchaseOrderService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void rejectsCreateFromFrameworkContractWithExplicitCallOffMessage() {
        UUID contractId = UUID.randomUUID();
        UUID companyId = CompanyContext.DEFAULT_COMPANY_ID;
        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setTenantId("default");
        contract.setCompanyId(companyId);
        contract.setStatus("ACTIVE");
        contract.setSupplierId(UUID.randomUUID());

        when(contractRepository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        when(procurementFrameworkRepository.findByTenantIdAndCompanyIdAndContractIdAndIsDeletedFalse(
            "default", companyId, contractId))
            .thenReturn(Optional.of(new ProcurementFramework()));

        assertThatThrownBy(() -> service.createFromContract(contractId))
            .isInstanceOf(BusinessException.class)
            .hasMessage(ContractPurchaseOrderService.FRAMEWORK_CALLOFF_REQUIRED);

        verify(purchaseOrderDemandCyclePolicy, never()).requireApprovedCycleForContract(any());
        verify(purchaseOrderService, never()).create(any());
    }
}
