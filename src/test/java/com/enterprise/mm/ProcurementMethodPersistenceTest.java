package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import java.math.BigDecimal;
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
class ProcurementMethodPersistenceTest {
    @Autowired ProcurementMethodDecisionRepository decisionRepository;
    @Autowired ProcurementProcedureRepository procedureRepository;

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
    void persistsDecisionAndItsSeparateExecutionProcedure() {
        UUID marketResearchAttachmentId = UUID.randomUUID();
        UUID technicalSpecificationAttachmentId = UUID.randomUUID();

        ProcurementMethodDecision decision = new ProcurementMethodDecision();
        decision.setCode("PMD-PERSIST-1");
        decision.setName("Procurement method decision persistence test");
        decision.setStatus("DRAFT");
        decision.setDecisionNumber("PMD-PERSIST-1");
        decision.setSelectedMethod("OPEN_TENDER");
        decision.setJustification("Competitive supplier market");
        decision.setEstimatedAmount(BigDecimal.valueOf(10_000));
        decision.setCurrencyCode("UZS");
        decision.setPotentialSupplierCount(3);
        decision.setMarketResearchAttachmentId(marketResearchAttachmentId);
        decision.setTechnicalSpecificationAttachmentId(technicalSpecificationAttachmentId);
        ProcurementMethodDecision savedDecision = decisionRepository.saveAndFlush(decision);

        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setCode("PROC-PERSIST-1");
        procedure.setName("Procurement procedure persistence test");
        procedure.setStatus("DRAFT");
        procedure.setProcedureNumber("PROC-PERSIST-1");
        procedure.setMethodDecisionId(savedDecision.getId());
        procedure.setMethod("OPEN_TENDER");
        procedure.setExecutionPath("TENDER_WITH_PRICE_DISCOVERY");
        ProcurementProcedure savedProcedure = procedureRepository.saveAndFlush(procedure);

        assertThat(decisionRepository.findByIdAndIsDeletedFalse(savedDecision.getId())).isPresent();
        assertThat(savedDecision.getStatus()).isEqualTo("DRAFT");
        assertThat(savedDecision.getSelectedMethod()).isEqualTo("OPEN_TENDER");
        assertThat(savedDecision.getMarketResearchAttachmentId()).isEqualTo(marketResearchAttachmentId);
        assertThat(savedDecision.getTechnicalSpecificationAttachmentId()).isEqualTo(technicalSpecificationAttachmentId);
        assertThat(savedDecision.getVersion()).isNotNull();
        assertThat(savedProcedure.getMethodDecisionId()).isEqualTo(savedDecision.getId());
        assertThat(savedProcedure.getMethod()).isEqualTo("OPEN_TENDER");
        assertThat(procedureRepository.findByMethodDecisionIdAndIsDeletedFalse(savedDecision.getId()))
            .contains(savedProcedure);
    }
}
