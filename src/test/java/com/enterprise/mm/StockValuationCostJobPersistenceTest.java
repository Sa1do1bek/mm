package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.entity.StockValuationCostJobResult;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse.StockCost;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.StockValuationCostJobResultRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StockValuationCostJobPersistenceTest {
    @Autowired
    private StockValuationCostJobRepository jobRepository;
    @Autowired
    private StockValuationCostJobResultRepository resultRepository;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void persistsAndReadsScopedJobWithOrderedResults() {
        TenantContext.setTenant("job-tenant");
        UUID companyId = CompanyContext.currentCompany();
        StockValuationCostJob job = jobRepository.saveAndFlush(
            StockValuationCostJob.queued("job-tenant", companyId, null, "finance-user"));
        resultRepository.save(StockValuationCostJobResult.from(job.getId(), 1,
            new StockCost(UUID.randomUUID(), "Copper", UUID.randomUUID(), "Main", BigDecimal.TEN,
                ValuationMethod.FIFO, new BigDecimal("50.0000"), true, null)));

        assertThat(jobRepository.findScoped(job.getId(), "job-tenant", companyId)).isPresent();
        assertThat(jobRepository.findForUpdate(job.getId())).isPresent();
        assertThat(resultRepository.findByJobIdOrderBySequenceNoAsc(job.getId(), PageRequest.of(0, 20))
            .getTotalElements()).isEqualTo(1);
    }
}
