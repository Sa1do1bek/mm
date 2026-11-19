package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.StockValuationCostJobPhase;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.service.StockValuationCostJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "finance-user", roles = "ADMIN")
class StockValuationCostJobAsyncIntegrationTest {
    @Autowired
    private StockValuationCostJobService service;
    @Autowired
    private StockValuationCostJobRepository repository;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createdJobIsDispatchedAfterCommitAndCompletesMinimalLifecycle() throws InterruptedException {
        var created = service.startJob(null);

        StockValuationCostJobStatus status = null;
        StockValuationCostJobPhase phase = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            var job = repository.findById(created.id()).orElseThrow();
            status = job.getStatus();
            phase = job.getPhase();
            if (status == StockValuationCostJobStatus.COMPLETED) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(status).isIn(
            StockValuationCostJobStatus.COMPLETED,
            StockValuationCostJobStatus.COMPLETED_WITH_ERRORS);
        assertThat(phase).isEqualTo(StockValuationCostJobPhase.DONE);
    }
}
