package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementFramework;
import com.enterprise.mm.entity.ProcurementFrameworkLedgerEntry;
import com.enterprise.mm.enums.ProcurementFrameworkLedgerType;
import com.enterprise.mm.enums.ProcurementFrameworkStatus;
import com.enterprise.mm.repository.ProcurementFrameworkLedgerRepository;
import com.enterprise.mm.repository.ProcurementFrameworkRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProcurementFrameworkPersistenceTest {
    @Autowired ProcurementFrameworkRepository frameworkRepository;
    @Autowired ProcurementFrameworkLedgerRepository ledgerRepository;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void persistsFrameworkHeaderAndSourceLinkedLedger() {
        ProcurementFramework framework = new ProcurementFramework();
        framework.setCode("FW-PERSIST-1");
        framework.setName("Framework persistence");
        framework.setFrameworkStatus(ProcurementFrameworkStatus.ACTIVE);
        framework.setProcedureId(UUID.randomUUID());
        framework.setMethodDecisionId(UUID.randomUUID());
        framework.setPurchaseRequestId(UUID.randomUUID());
        framework.setContractId(UUID.randomUUID());
        framework.setAwardReference("FW-AWARD-PERSIST");
        framework.setSupplierId(UUID.randomUUID());
        framework.setSupplierModel("SINGLE_SUPPLIER");
        framework.setValidFrom(LocalDate.now());
        framework.setValidTo(LocalDate.now().plusDays(30));
        framework.setCurrencyCode("UZS");
        framework.setCeilingAmount(new BigDecimal("500.00"));
        framework.setCommittedAmount(BigDecimal.ZERO);
        framework.setConsumedAmount(BigDecimal.ZERO);
        ProcurementFramework saved = frameworkRepository.saveAndFlush(framework);

        ProcurementFrameworkLedgerEntry entry = new ProcurementFrameworkLedgerEntry();
        entry.setCode("FWL-PERSIST-1");
        entry.setName("Framework commit");
        entry.setStatus("POSTED");
        entry.setFrameworkId(saved.getId());
        entry.setTransactionType(ProcurementFrameworkLedgerType.COMMIT);
        entry.setCallOffReference("CO-PERSIST-1");
        entry.setPurchaseOrderId(UUID.randomUUID());
        entry.setSourcePurchaseRequestId(saved.getPurchaseRequestId());
        entry.setSourceProcedureId(saved.getProcedureId());
        entry.setAmount(new BigDecimal("125.00"));
        entry.setCurrencyCode("UZS");
        entry.setIdempotencyKey("persist-key");
        entry.setOccurredAt(LocalDateTime.now());
        ProcurementFrameworkLedgerEntry savedEntry = ledgerRepository.saveAndFlush(entry);

        assertThat(frameworkRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, saved.getId())).contains(saved);
        assertThat(ledgerRepository.findByTenantIdAndCompanyIdAndFrameworkIdAndIsDeletedFalseOrderByOccurredAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, saved.getId())).containsExactly(savedEntry);
    }
}
