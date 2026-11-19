package com.enterprise.mm.config;

import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.service.AiAnalysisJobTransactionService;
import com.enterprise.mm.service.AiAnalysisJobWorker;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AiAnalysisJobRecovery {
    private static final String ABANDONED_CODE = "AI_JOB_ABANDONED_ON_RESTART";
    private static final String ABANDONED_MESSAGE = "Application restarted while AI analysis job was running";
    private static final String DISPATCH_REJECTED_CODE = "AI_JOB_DISPATCH_REJECTED";
    private static final String DISPATCH_REJECTED_MESSAGE =
        "AI analysis job could not be restarted because worker capacity is full";

    private final AiAnalysisJobRepository jobRepository;
    private final AiAnalysisJobWorker worker;
    private final AiAnalysisJobTransactionService transactionService;
    private final LocalDateTime applicationStartedAt;

    @Autowired
    public AiAnalysisJobRecovery(
        AiAnalysisJobRepository jobRepository,
        AiAnalysisJobWorker worker,
        AiAnalysisJobTransactionService transactionService
    ) {
        this(jobRepository, worker, transactionService, LocalDateTime.now());
    }

    public AiAnalysisJobRecovery(
        AiAnalysisJobRepository jobRepository,
        AiAnalysisJobWorker worker,
        AiAnalysisJobTransactionService transactionService,
        LocalDateTime applicationStartedAt
    ) {
        this.jobRepository = jobRepository;
        this.worker = worker;
        this.transactionService = transactionService;
        this.applicationStartedAt = applicationStartedAt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        List<AiAnalysisJob> abandoned = jobRepository.findAbandoned(
            List.of(AiAnalysisJobStatus.QUEUED, AiAnalysisJobStatus.RUNNING),
            applicationStartedAt);
        for (AiAnalysisJob job : abandoned) {
            if (job.getStatus() == AiAnalysisJobStatus.QUEUED) {
                redispatch(job);
                continue;
            }
            if (job.getStatus() == AiAnalysisJobStatus.RUNNING) {
                transactionService.markFailed(job.getId(), ABANDONED_CODE, ABANDONED_MESSAGE);
            }
        }
    }

    private void redispatch(AiAnalysisJob job) {
        try {
            if (isMxik(job.getAnalysisType())) {
                worker.runMxik(job.getId());
            } else {
                worker.run(job.getId());
            }
        } catch (TaskRejectedException ex) {
            transactionService.markFailed(job.getId(), DISPATCH_REJECTED_CODE, DISPATCH_REJECTED_MESSAGE);
        } catch (RuntimeException ex) {
            log.error("Failed to redispatch abandoned AI analysis job: jobId={}", job.getId(), ex);
            transactionService.markFailed(job.getId(), "AI_JOB_RECOVERY_FAILED", ex.getMessage());
        }
    }

    private boolean isMxik(AiAnalysisType analysisType) {
        return analysisType == AiAnalysisType.MATERIAL_MXIK_MATCH
            || analysisType == AiAnalysisType.MATERIAL_MXIK_MATCH_BATCH;
    }
}
