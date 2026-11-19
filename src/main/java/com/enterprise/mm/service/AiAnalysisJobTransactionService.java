package com.enterprise.mm.service;

import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiAnalysisJobTransactionService {
    private static final EnumSet<AiAnalysisJobStatus> FAILABLE_STATUSES = EnumSet.of(
        AiAnalysisJobStatus.QUEUED,
        AiAnalysisJobStatus.RUNNING
    );

    private final AiAnalysisJobRepository jobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID createMxikChild(AiAnalysisJob parent, UUID materialId) {
        AiAnalysisJob child = AiAnalysisJob.queued(parent.getTenantId(), parent.getCompanyId(),
            AiAnalysisType.MATERIAL_MXIK_MATCH, "MATERIAL", materialId,
            parent.getId() + ":" + materialId, parent.getRequestedLanguage(), parent.getRequestedBy(),
            "{\"materialId\":\"" + materialId + "\"}");
        return jobRepository.saveAndFlush(child).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRunning(UUID jobId) {
        Optional<AiAnalysisJob> current = jobRepository.findForUpdate(jobId);
        if (current.isEmpty()) {
            return false;
        }
        AiAnalysisJob job = current.get();
        if (job.getStatus() != AiAnalysisJobStatus.QUEUED) {
            return false;
        }
        job.setStatus(AiAnalysisJobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        jobRepository.save(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markCompleted(UUID jobId, String provider, String model, String modelVersion, String resultPayload) {
        Optional<AiAnalysisJob> current = jobRepository.findForUpdate(jobId);
        if (current.isEmpty()) {
            return false;
        }
        AiAnalysisJob job = current.get();
        if (job.getStatus() != AiAnalysisJobStatus.RUNNING) {
            return false;
        }
        job.setStatus(AiAnalysisJobStatus.COMPLETED);
        job.setProvider(provider);
        job.setModel(model);
        job.setModelVersion(modelVersion);
        job.setResultPayload(resultPayload);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(UUID jobId, String errorCode, String errorMessage) {
        Optional<AiAnalysisJob> current = jobRepository.findForUpdate(jobId);
        if (current.isEmpty()) {
            return false;
        }
        AiAnalysisJob job = current.get();
        if (!FAILABLE_STATUSES.contains(job.getStatus())) {
            return false;
        }
        job.setStatus(AiAnalysisJobStatus.FAILED);
        job.setErrorCode(errorCode);
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);
        return true;
    }
}
