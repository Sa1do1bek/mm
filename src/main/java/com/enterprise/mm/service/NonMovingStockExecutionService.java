package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.NonMovingStockDecisionLine;
import com.enterprise.mm.entity.NonMovingStockExecution;
import com.enterprise.mm.entity.NonMovingStockExecutionOutput;
import com.enterprise.mm.enums.NonMovingStockDecisionType;
import com.enterprise.mm.enums.NonMovingStockExecutionOutputStatus;
import com.enterprise.mm.enums.NonMovingStockExecutionStatus;
import com.enterprise.mm.enums.NonMovingStockReconciliationStatus;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.enums.NonMovingStockStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockCaseRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionOutputRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NonMovingStockExecutionService {
    private final NonMovingStockExecutionRepository executionRepository;
    private final NonMovingStockExecutionOutputRepository outputRepository;
    private final NonMovingStockCaseRepository caseRepository;
    private final NonMovingStockDecisionLineRepository decisionLineRepository;
    private final NonMovingStockDecisionRepository decisionRepository;
    private final NonMovingStockCaseLineRepository caseLineRepository;
    private final NonMovingStockRepository nonMovingStockRepository;



    @Transactional
    public NonMovingStockExecution begin(
        NonMovingStockCase stockCase,
        NonMovingStockDecision decision,
        String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency key is required for non-moving stock execution");
        }
        Optional<NonMovingStockExecution> existing = executionRepository
            .findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
                stockCase.getTenantId(),
                stockCase.getCompanyId(),
                idempotencyKey.trim()
            );
        if (existing.isPresent()) {
            return existing.get();
        }
        NonMovingStockExecution execution = new NonMovingStockExecution();
        execution.setCompanyId(stockCase.getCompanyId());
        execution.setCaseId(stockCase.getId());
        execution.setDecisionId(decision.getId());
        execution.setCode("NMS-EXE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        execution.setName("Non-moving stock execution");
        execution.setStatus(NonMovingStockExecutionStatus.IN_EXECUTION.name());
        execution.setExecutionStatus(NonMovingStockExecutionStatus.IN_EXECUTION.name());
        execution.setIdempotencyKey(idempotencyKey.trim());
        execution.setReconciliationStatus(NonMovingStockReconciliationStatus.READY.name());
        execution.setStartedAt(LocalDateTime.now());
        return executionRepository.save(execution);
    }

    @Transactional(readOnly = true)
    public Optional<NonMovingStockExecution> idempotent(UUID companyId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return executionRepository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            TenantContext.currentTenant(),
            companyId,
            idempotencyKey.trim()
        );
    }

    @Transactional
    public NonMovingStockExecutionOutput recordOutput(
        NonMovingStockExecution execution,
        UUID decisionLineId,
        UUID caseLineId,
        String outputType,
        String documentType,
        UUID documentId,
        String documentNumber,
        NonMovingStockExecutionOutputStatus status,
        BigDecimal quantity,
        BigDecimal amount,
        String currencyCode
    ) {
        NonMovingStockExecutionOutput output = new NonMovingStockExecutionOutput();
        output.setCompanyId(execution.getCompanyId());
        output.setExecutionId(execution.getId());
        output.setDecisionLineId(decisionLineId);
        output.setCaseLineId(caseLineId);
        output.setOutputType(outputType);
        output.setDocumentType(documentType);
        output.setDocumentId(documentId);
        output.setDocumentNumber(documentNumber);
        output.setOutputStatus(status.name());
        output.setQuantity(quantity);
        output.setAmount(amount);
        output.setCurrencyCode(currencyCode);
        output.setCode("NMS-OUT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        output.setName(outputType + " output");
        output.setStatus(status.name());
        if (status == NonMovingStockExecutionOutputStatus.POSTED) {
            output.setCompletedAt(LocalDateTime.now());
        }
        return outputRepository.save(output);
    }

    @Transactional(readOnly = true)
    public List<NonMovingStockExecutionOutput> outputs(UUID executionId) {
        return outputRepository.findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), executionId
        );
    }

    @Transactional
    public NonMovingStockExecution synchronizeStatus(NonMovingStockExecution execution) {
        List<NonMovingStockExecutionOutput> outputs = outputs(execution.getId());
        boolean failed = outputs.stream().anyMatch(output ->
            NonMovingStockExecutionOutputStatus.FAILED.name().equals(output.getOutputStatus())
                || NonMovingStockExecutionOutputStatus.CANCELLED.name().equals(output.getOutputStatus()));
        boolean complete = !outputs.isEmpty() && outputs.stream().allMatch(output ->
            NonMovingStockExecutionOutputStatus.POSTED.name().equals(output.getOutputStatus())
                || NonMovingStockExecutionOutputStatus.REVERSED.name().equals(output.getOutputStatus()));
        if (failed) {
            execution.setStatus(NonMovingStockExecutionStatus.FAILED.name());
            execution.setExecutionStatus(NonMovingStockExecutionStatus.FAILED.name());
        } else if (complete) {
            execution.setStatus(NonMovingStockExecutionStatus.EXECUTED.name());
            execution.setExecutionStatus(NonMovingStockExecutionStatus.EXECUTED.name());
            execution.setCompletedAt(LocalDateTime.now());
        } else {
            execution.setStatus(NonMovingStockExecutionStatus.PENDING.name());
            execution.setExecutionStatus(NonMovingStockExecutionStatus.PENDING.name());
        }
        return executionRepository.save(execution);
    }

    @Transactional
    public NonMovingStockExecution reconcileOutput(
        UUID outputId,
        String downstreamStatus,
        String errorCode,
        String errorMessage
    ) {
        NonMovingStockExecutionOutput output = outputRepository.findByIdAndIsDeletedFalse(outputId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .orElseThrow(() -> new BusinessException("Non-moving stock execution output not found: " + outputId));
        NonMovingStockExecutionOutputStatus status;
        try {
            status = NonMovingStockExecutionOutputStatus.valueOf(
                downstreamStatus == null ? "" : downstreamStatus.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Unsupported downstream output status: " + downstreamStatus);
        }
        OutputContext context = outputContext(output);
        if (isStatusReplay(output, status)) {
            return context.execution();
        }
        requireOpenCaseForCallback(context.stockCase(), status);
        return applyOutputStatus(context, status, errorCode, errorMessage);
    }

    @Transactional
    public void reconcileDocumentOutput(
        String documentType,
        UUID documentId,
        NonMovingStockExecutionOutputStatus status,
        String errorCode,
        String errorMessage
    ) {
        if (documentType == null || documentId == null || status == null) {
            return;
        }
        List<NonMovingStockExecutionOutput> outputs = outputRepository
            .findByTenantIdAndDocumentTypeAndDocumentIdAndIsDeletedFalse(
                TenantContext.currentTenant(), documentType, documentId
            );
        if (outputs.isEmpty()) {
            return;
        }
        List<OutputContext> pendingCallbacks = outputs.stream()
            .filter(output -> !isStatusReplay(output, status))
            .map(this::outputContext)
            .toList();
        pendingCallbacks.forEach(context -> requireOpenCaseForCallback(context.stockCase(), status));
        pendingCallbacks.forEach(context -> applyOutputStatus(context, status, errorCode, errorMessage));
    }

    private NonMovingStockExecution applyOutputStatus(
        OutputContext context,
        NonMovingStockExecutionOutputStatus status,
        String errorCode,
        String errorMessage
    ) {
        NonMovingStockExecution execution = context.execution();
        boolean wasExecuted = NonMovingStockExecutionStatus.EXECUTED.name()
            .equals(execution.getExecutionStatus());
        NonMovingStockExecutionOutput output = context.output();
        output.setOutputStatus(status.name());
        output.setStatus(status.name());
        output.setErrorCode(errorCode);
        output.setErrorMessage(errorMessage);
        if (status == NonMovingStockExecutionOutputStatus.POSTED
            || status == NonMovingStockExecutionOutputStatus.REVERSED
            || status == NonMovingStockExecutionOutputStatus.CANCELLED) {
            output.setCompletedAt(LocalDateTime.now());
        }
        outputRepository.save(output);

        NonMovingStockExecution synchronizedExecution = synchronizeStatus(execution);
        boolean becameExecuted = !wasExecuted
            && NonMovingStockExecutionStatus.EXECUTED.name()
                .equals(synchronizedExecution.getExecutionStatus());
        if (status == NonMovingStockExecutionOutputStatus.POSTED && becameExecuted) {
            reconcileAuthoritativeDecisionLines(synchronizedExecution);
        }
        synchronizeCaseStatus(
            synchronizedExecution, context.stockCase(), status, errorMessage
        );
        return synchronizedExecution;
    }

    private OutputContext outputContext(NonMovingStockExecutionOutput output) {
        NonMovingStockExecution execution = executionRepository
            .findByIdAndIsDeletedFalse(output.getExecutionId())
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .orElseThrow(() -> new BusinessException(
                "Non-moving stock execution not found: " + output.getExecutionId()
            ));
        NonMovingStockCase stockCase = caseRepository
            .findByIdAndIsDeletedFalse(execution.getCaseId())
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .orElseThrow(() -> new BusinessException(
                "Non-moving stock case not found: " + execution.getCaseId()
            ));
        return new OutputContext(output, execution, stockCase);
    }

    private boolean isStatusReplay(
        NonMovingStockExecutionOutput output,
        NonMovingStockExecutionOutputStatus status
    ) {
        return status.name().equals(output.getOutputStatus());
    }

    private void requireOpenCaseForCallback(
        NonMovingStockCase stockCase,
        NonMovingStockExecutionOutputStatus status
    ) {
        if (NonMovingStockCaseStatus.CLOSED.name().equals(stockCase.getStatus())) {
            throw new BusinessException(
                "Closed non-moving stock case must be explicitly reopened before applying a late "
                    + status.name() + " output"
            );
        }
    }

    private void synchronizeCaseStatus(
        NonMovingStockExecution execution,
        NonMovingStockCase stockCase,
        NonMovingStockExecutionOutputStatus outputStatus,
        String errorMessage
    ) {
        if (NonMovingStockCaseStatus.CLOSED.name().equals(stockCase.getStatus())) {
            return;
        }
        if (NonMovingStockExecutionStatus.EXECUTED.name().equals(execution.getExecutionStatus())) {
            stockCase.setStatus(NonMovingStockCaseStatus.EXECUTED.name());
            stockCase.setBlockerType(null);
            stockCase.setBlockerDetails(null);
            caseRepository.save(stockCase);
        } else if (outputStatus == NonMovingStockExecutionOutputStatus.CANCELLED
            || outputStatus == NonMovingStockExecutionOutputStatus.FAILED) {
            stockCase.setStatus(NonMovingStockCaseStatus.IN_EXECUTION.name());
            stockCase.setBlockerType(outputStatus.name());
            stockCase.setBlockerDetails(errorMessage == null || errorMessage.isBlank()
                ? "Downstream execution output is " + outputStatus.name()
                : errorMessage.trim());
            caseRepository.save(stockCase);
        }
    }

    private void reconcileAuthoritativeDecisionLines(NonMovingStockExecution execution) {
        List<NonMovingStockDecisionLine> decisionLines = decisionLineRepository
            .findByTenantIdAndDecisionIdAndIsDeletedFalseOrderByLineOrderAsc(
                TenantContext.currentTenant(), execution.getDecisionId()
            );
        if (decisionLines.isEmpty()) {
            reconcileLegacyExecutionOutputs(execution);
            return;
        }
        Map<UUID, NonMovingStockCaseLine> caseLines = caseLineRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), execution.getCaseId()
            ).stream()
            .collect(Collectors.toMap(NonMovingStockCaseLine::getId, Function.identity()));
        for (NonMovingStockDecisionLine decisionLine : decisionLines) {
            NonMovingStockCaseLine caseLine = caseLines.get(decisionLine.getCaseLineId());
            if (caseLine == null) {
                throw new BusinessException(
                    "Execution decision references missing case line: " + decisionLine.getCaseLineId()
                );
            }
            NonMovingStockStatus terminalStatus = terminalDetectionStatus(decisionLine);
            reconcileDetectionQuantity(
                execution,
                caseLine.getNonMovingStockId(),
                decisionLine.getApprovedQuantity(),
                terminalStatus
            );
        }
    }

    private NonMovingStockStatus terminalDetectionStatus(NonMovingStockDecisionLine decisionLine) {
        return terminalDetectionStatus(decisionLine.getDecisionType());
    }

    private NonMovingStockStatus terminalDetectionStatus(String decisionType) {
        NonMovingStockDecisionType type;
        try {
            type = NonMovingStockDecisionType.valueOf(decisionType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(
                "Unsupported non-moving stock decision type: " + decisionType
            );
        }
        return switch (type) {
            case TRANSFER -> NonMovingStockStatus.TRANSFER;
            case WRITE_OFF -> NonMovingStockStatus.WRITE_OFF;
            case SCRAP -> NonMovingStockStatus.SCRAP;
            case IMPAIRMENT_RESERVE -> NonMovingStockStatus.IMPAIRMENT_RESERVE;
            default -> throw new BusinessException(
                "Decision-line execution is not supported for type: " + type.name()
            );
        };
    }

    private void reconcileLegacyExecutionOutputs(NonMovingStockExecution execution) {
        NonMovingStockDecision decision = decisionRepository
            .findByIdAndIsDeletedFalse(execution.getDecisionId())
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(execution.getCompanyId(), candidate.getCompanyId()))
            .orElseThrow(() -> new BusinessException(
                "Non-moving stock decision not found for execution: " + execution.getDecisionId()
            ));
        NonMovingStockStatus terminalStatus = terminalDetectionStatus(decision.getDecisionType());
        Map<UUID, NonMovingStockCaseLine> caseLines = caseLineRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), execution.getCaseId()
            ).stream()
            .collect(Collectors.toMap(NonMovingStockCaseLine::getId, Function.identity()));
        outputs(execution.getId()).stream()
            .filter(output -> NonMovingStockExecutionOutputStatus.POSTED.name()
                .equals(output.getOutputStatus()))
            .filter(output -> output.getCaseLineId() != null)
            .filter(output -> nvl(output.getQuantity()).signum() > 0)
            .forEach(output -> {
                NonMovingStockCaseLine caseLine = caseLines.get(output.getCaseLineId());
                if (caseLine == null) {
                    throw new BusinessException(
                        "Execution output references missing case line: " + output.getCaseLineId()
                    );
                }
                reconcileDetectionQuantity(
                    execution,
                    caseLine.getNonMovingStockId(),
                    output.getQuantity(),
                    terminalStatus
                );
            });
    }

    private void reconcileDetectionQuantity(
        NonMovingStockExecution execution,
        UUID detectionId,
        BigDecimal executedQuantity,
        NonMovingStockStatus terminalStatus
    ) {
        BigDecimal normalizedExecutedQuantity = nvl(executedQuantity);
        if (normalizedExecutedQuantity.signum() <= 0) {
            throw new BusinessException("Executed decision-line quantity must be positive");
        }
        NonMovingStock detection = nonMovingStockRepository.findByIdAndIsDeletedFalse(detectionId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(execution.getCompanyId(), candidate.getCompanyId()))
            .orElseThrow(() -> new BusinessException(
                "Non-moving stock detection not found for execution: " + detectionId
            ));
        BigDecimal originalQuantity = nvl(detection.getQuantity());
        if (originalQuantity.compareTo(normalizedExecutedQuantity) < 0) {
            throw new BusinessException(
                "Executed decision-line quantity exceeds source detection quantity: " + detectionId
            );
        }
        if (originalQuantity.compareTo(normalizedExecutedQuantity) == 0) {
            detection.setStatus(terminalStatus.name());
            detection.setPlannedAction(terminalStatus.name());
            nonMovingStockRepository.save(detection);
            return;
        }

        NonMovingStock executedPart = new NonMovingStock();
        executedPart.setTenantId(detection.getTenantId());
        executedPart.setCompanyId(detection.getCompanyId());
        executedPart.setCode(splitDetectionCode(detection.getCode(), terminalStatus));
        executedPart.setName(splitDetectionName(detection.getName(), terminalStatus));
        executedPart.setStatus(terminalStatus.name());
        executedPart.setMaterialId(detection.getMaterialId());
        executedPart.setWarehouseId(detection.getWarehouseId());
        executedPart.setQuantity(normalizedExecutedQuantity);
        executedPart.setMonthsWithoutMovement(detection.getMonthsWithoutMovement());
        executedPart.setDetectedAt(detection.getDetectedAt());
        executedPart.setPlannedAction(terminalStatus.name());
        executedPart.setActionComment(detection.getActionComment());
        nonMovingStockRepository.save(executedPart);

        detection.setQuantity(originalQuantity.subtract(normalizedExecutedQuantity));
        detection.setStatus(NonMovingStockStatus.DETECTED.name());
        detection.setPlannedAction(null);
        detection.setActionComment(null);
        nonMovingStockRepository.save(detection);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String splitDetectionCode(String baseCode, NonMovingStockStatus status) {
        String safeBaseCode = (baseCode == null || baseCode.isBlank()) ? "NMS" : baseCode.trim();
        String suffix = status.name() + "-"
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String value = safeBaseCode + "-" + suffix;
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private String splitDetectionName(String baseName, NonMovingStockStatus status) {
        String safeBaseName = (baseName == null || baseName.isBlank())
            ? "Non-moving stock"
            : baseName.trim();
        String value = safeBaseName + " (" + status.name() + ")";
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    private record OutputContext(
        NonMovingStockExecutionOutput output,
        NonMovingStockExecution execution,
        NonMovingStockCase stockCase
    ) {
    }
}
