package com.enterprise.mm.config;

import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.service.StockValuationCostJobTransactionService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Fails active jobs left behind by a previous application instance. */
@Component
@Slf4j
public class StockValuationCostJobRecovery {
    static final String RECOVERY_MESSAGE =
        "Application restarted while valuation job was queued or running";

    private final StockValuationCostJobRepository jobRepository;
    private final StockValuationCostJobTransactionService transactionService;
    private final LocalDateTime applicationStartedAt;

    @Autowired
    public StockValuationCostJobRecovery(
        StockValuationCostJobRepository jobRepository,
        StockValuationCostJobTransactionService transactionService
    ) {
        this(jobRepository, transactionService, LocalDateTime.now());
    }

    public StockValuationCostJobRecovery(
        StockValuationCostJobRepository jobRepository,
        StockValuationCostJobTransactionService transactionService,
        LocalDateTime applicationStartedAt
    ) {
        this.jobRepository = jobRepository;
        this.transactionService = transactionService;
        this.applicationStartedAt = applicationStartedAt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            List<StockValuationCostJob> abandoned = jobRepository.findAbandoned(
                List.of(StockValuationCostJobStatus.QUEUED, StockValuationCostJobStatus.RUNNING),
                applicationStartedAt);
            for (StockValuationCostJob job : abandoned) {
                try {
                    transactionService.markFailed(job.getId(), RECOVERY_MESSAGE);
                    log.warn("Recovered abandoned valuation job: jobId={} status={}",
                        job.getId(), job.getStatus());
                } catch (RuntimeException ex) {
                    log.error("Failed to recover abandoned valuation job: jobId={}", job.getId(), ex);
                }
            }
            if (!abandoned.isEmpty()) {
                log.info("Recovered {} abandoned valuation jobs", abandoned.size());
            }
        } catch (RuntimeException ex) {
            log.error("Failed to recover abandoned valuation jobs", ex);
        }
    }
}
