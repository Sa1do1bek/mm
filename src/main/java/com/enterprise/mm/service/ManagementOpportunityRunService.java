package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.entity.ManagementOpportunityCalculationRun;
import com.enterprise.mm.entity.ManagementOpportunityEvidence;
import com.enterprise.mm.enums.ManagementOpportunityRunStatus;
import com.enterprise.mm.enums.ManagementOpportunityStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ManagementOpportunityCalculationRunMapper;
import com.enterprise.mm.payload.ManagementOpportunityCalculationRunResponse;
import com.enterprise.mm.payload.ManagementOpportunityCalculationStartRequest;
import com.enterprise.mm.payload.ManagementOpportunityRunSearchRequest;
import com.enterprise.mm.repository.ManagementOpportunityCalculationRunRepository;
import com.enterprise.mm.repository.ManagementOpportunityEvidenceRepository;
import com.enterprise.mm.repository.ManagementOpportunityRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ManagementOpportunityRunService {
    private final ManagementOpportunityCalculationService calculationService;
    private final ManagementOpportunityCalculationRunRepository runRepository;
    private final ManagementOpportunityRepository opportunityRepository;
    private final ManagementOpportunityEvidenceRepository evidenceRepository;
    private final ManagementOpportunityCalculationRunMapper runMapper;
    private final AuditService auditService;
    private final TransactionTemplate failedRunTransactionTemplate;

    public ManagementOpportunityRunService(
        ManagementOpportunityCalculationService calculationService,
        ManagementOpportunityCalculationRunRepository runRepository,
        ManagementOpportunityRepository opportunityRepository,
        ManagementOpportunityEvidenceRepository evidenceRepository,
        ManagementOpportunityCalculationRunMapper runMapper,
        AuditService auditService,
        PlatformTransactionManager transactionManager
    ) {
        this.calculationService = calculationService;
        this.runRepository = runRepository;
        this.opportunityRepository = opportunityRepository;
        this.evidenceRepository = evidenceRepository;
        this.runMapper = runMapper;
        this.auditService = auditService;
        this.failedRunTransactionTemplate = new TransactionTemplate(transactionManager);
        this.failedRunTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public Page<ManagementOpportunityCalculationRunResponse> search(
        ManagementOpportunityRunSearchRequest request,
        Pageable pageable
    ) {
        ManagementOpportunityRunSearchRequest filter = request == null
            ? new ManagementOpportunityRunSearchRequest(null, null, null, null, null, null, null, null, null, false)
            : request;
        return runRepository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            filter.includeDeleted(),
            blankToNull(filter.status()),
            requestedTypePattern(filter.requestedType()),
            currencyOrNull(filter.targetCurrencyCode()),
            filter.asOfDateFrom(),
            filter.asOfDateTo(),
            filter.startedAtFrom(),
            filter.startedAtTo(),
            filter.hasSkipped(),
            filter.hasFailure(),
            pageable
        ).map(runMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ManagementOpportunityCalculationRunResponse get(UUID id) {
        return runMapper.toResponse(findEntity(id));
    }

    @Transactional
    public ManagementOpportunityCalculationRunResponse start(ManagementOpportunityCalculationStartRequest request) {
        ManagementOpportunityCalculationRun run = new ManagementOpportunityCalculationRun();
        run.setCompanyId(CompanyContext.currentCompany());
        run.setCode("MO-RUN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        run.setName("Management opportunity run");
        run.setStatus(ManagementOpportunityRunStatus.RUNNING.name());
        run.setAsOfDate(request.getAsOfDate());
        run.setTargetCurrencyCode(currency(request.getTargetCurrencyCode()));
        run.setRequestedTypes(request.getTypes() == null || request.getTypes().isEmpty()
            ? "ALL"
            : request.getTypes().stream().map(Enum::name).collect(Collectors.joining(",")));
        run.setStartedAt(LocalDateTime.now());
        run = runRepository.save(run);

        try {
            List<ManagementOpportunityCalculatedCandidate> candidates = calculationService.calculate(
                request.getAsOfDate(),
                request.getTypes(),
                request.getTargetCurrencyCode()
            );
            int persisted = 0;
            int skipped = 0;
            StringBuilder skipSummary = new StringBuilder();
            for (ManagementOpportunityCalculatedCandidate candidate : candidates) {
                if (!candidate.persistsAsCase()) {
                    skipped++;
                    appendSkip(skipSummary, candidate);
                    continue;
                }
                archiveOlderUnapprovedDuplicate(candidate);
                ManagementOpportunity opportunity = persistOpportunity(run, candidate);
                persistEvidence(opportunity, candidate);
                persisted++;
            }
            run.setCandidateCount(persisted);
            run.setSkippedCount(skipped);
            run.setSkipSummary(skipSummary.toString());
            run.setStatus(ManagementOpportunityRunStatus.COMPLETED.name());
            run.setCompletedAt(LocalDateTime.now());
            ManagementOpportunityCalculationRun saved = runRepository.save(run);
            auditService.log("ManagementOpportunityCalculationRun", saved.getId(), "COMPLETE", saved.getCode(), null, null);
            return runMapper.toResponse(saved);
        } catch (RuntimeException exception) {
            persistFailedRun(run.getId(), exception);
            throw exception;
        }
    }

    private void persistFailedRun(UUID runId, RuntimeException exception) {
        failedRunTransactionTemplate.executeWithoutResult(status ->
            runRepository.findByIdAndIsDeletedFalse(runId).ifPresent(run -> {
                run.setStatus(ManagementOpportunityRunStatus.FAILED.name());
                run.setFailureSummary(safeFailureSummary(exception));
                run.setCompletedAt(LocalDateTime.now());
                runRepository.save(run);
            })
        );
    }

    private ManagementOpportunity persistOpportunity(
        ManagementOpportunityCalculationRun run,
        ManagementOpportunityCalculatedCandidate candidate
    ) {
        ManagementOpportunity entity = new ManagementOpportunity();
        entity.setCompanyId(CompanyContext.currentCompany());
        entity.setCode("MO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        entity.setName(candidate.type().name() + " management estimate");
        entity.setStatus(ManagementOpportunityStatus.DRAFT.name());
        entity.setOpportunityType(candidate.type());
        entity.setCalculationRunId(run.getId());
        entity.setPolicyId(candidate.policyId());
        entity.setSourceAggregateType(candidate.sourceAggregateType());
        entity.setSourceAggregateId(candidate.sourceAggregateId());
        entity.setSourceLineId(candidate.sourceLineId());
        entity.setMaterialId(candidate.materialId());
        entity.setSupplierId(candidate.supplierId());
        entity.setWarehouseId(candidate.warehouseId());
        entity.setAsOfDate(candidate.asOfDate());
        entity.setSourceCurrencyCode(candidate.sourceCurrencyCode());
        entity.setNormalizedCurrencyCode(candidate.normalizedCurrencyCode());
        entity.setSourceAmount(candidate.sourceAmount());
        entity.setNormalizedAmount(candidate.normalizedAmount());
        entity.setBaselineAmount(candidate.baselineAmount());
        entity.setActualAmount(candidate.actualAmount());
        entity.setQuantity(candidate.quantity());
        entity.setExchangeRate(candidate.exchangeRate());
        entity.setExchangeRateId(candidate.exchangeRateId());
        entity.setExchangeRateDate(candidate.exchangeRateDate());
        entity.setExchangeRateSource(candidate.exchangeRateSource());
        entity.setNormalizationQuality(candidate.normalizationQuality());
        entity.setFormulaCode(candidate.formulaCode());
        entity.setFormulaSnapshot(candidate.formulaSnapshot());
        entity.setConfidence(candidate.confidence());
        entity.setDataCompleteness(candidate.completeness());
        return opportunityRepository.save(entity);
    }

    private void persistEvidence(ManagementOpportunity opportunity, ManagementOpportunityCalculatedCandidate candidate) {
        for (ManagementOpportunityEvidenceDescriptor descriptor : candidate.evidence()) {
            ManagementOpportunityEvidence evidence = new ManagementOpportunityEvidence();
            evidence.setCompanyId(opportunity.getCompanyId());
            evidence.setCode("MO-EV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            evidence.setName(descriptor.type() + " evidence");
            evidence.setStatus("CAPTURED");
            evidence.setOpportunityId(opportunity.getId());
            evidence.setEvidenceType(descriptor.type());
            evidence.setSourceEntityType(descriptor.sourceType());
            evidence.setSourceEntityId(descriptor.sourceId());
            evidence.setCapturedSnapshot(descriptor.snapshotJson());
            evidenceRepository.save(evidence);
        }
    }

    private void archiveOlderUnapprovedDuplicate(ManagementOpportunityCalculatedCandidate candidate) {
        List<String> statuses = List.of(
            ManagementOpportunityStatus.DRAFT.name(),
            ManagementOpportunityStatus.READY_FOR_REVIEW.name(),
            ManagementOpportunityStatus.REJECTED.name()
        );
        opportunityRepository.findActiveDuplicates(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            candidate.type(),
            candidate.sourceAggregateType(),
            candidate.sourceAggregateId(),
            candidate.sourceLineId(),
            candidate.asOfDate(),
            statuses
        ).forEach(existing -> {
            existing.setStatus(ManagementOpportunityStatus.ARCHIVED.name());
            opportunityRepository.save(existing);
        });
    }

    private void appendSkip(StringBuilder builder, ManagementOpportunityCalculatedCandidate candidate) {
        if (candidate.skipReason() == null || candidate.skipReason().isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("; ");
        }
        builder.append(candidate.type()).append(": ").append(candidate.skipReason());
        if (builder.length() > 1000) {
            builder.setLength(1000);
        }
    }

    private String safeFailureSummary(RuntimeException exception) {
        String message = exception.getMessage() == null ? "Calculation failed" : exception.getMessage().replaceAll("[\\r\\n]+", " ");
        return message.substring(0, Math.min(message.length(), 500));
    }

    private String currency(String value) {
        return value == null || value.isBlank() ? "UZS" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String currencyOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String requestedTypePattern(String value) {
        return value == null || value.isBlank() ? null : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ManagementOpportunityCalculationRun findEntity(UUID id) {
        return runRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                id
            )
            .orElseThrow(() -> new NotFoundException("ManagementOpportunityCalculationRun not found: " + id));
    }
}
