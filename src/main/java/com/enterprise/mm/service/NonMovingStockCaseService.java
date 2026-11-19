package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.NonMovingStockDecisionLine;
import com.enterprise.mm.entity.NonMovingStockExecution;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.NonMovingStockCaseMapper;
import com.enterprise.mm.payload.NonMovingStockCaseResponse;
import com.enterprise.mm.payload.NonMovingStockDecisionCreateRequest;
import com.enterprise.mm.payload.NonMovingStockDecisionResponse;
import com.enterprise.mm.payload.NonMovingStockExecutionResponse;
import com.enterprise.mm.payload.NonMovingStockCaseSearchRequest;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.payload.StockMovementStatusChangeRequest;
import com.enterprise.mm.payload.StockValuationDocumentCreateRequest;
import com.enterprise.mm.payload.StockValuationDocumentResponse;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockCaseRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.NonMovingStockDecisionApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
@RequiredArgsConstructor
public class NonMovingStockCaseService {
    private static final String ENTITY_NAME = "NonMovingStockCase";
    private static final String CASE_REFERENCE_TYPE = "NON_MOVING_STOCK_CASE";

    private final NonMovingStockCaseRepository caseRepository;
    private final NonMovingStockCaseLineRepository lineRepository;
    private final NonMovingStockDecisionRepository decisionRepository;
    private final NonMovingStockExecutionRepository executionRepository;
    private final NonMovingStockRepository nonMovingStockRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final MaterialRepository materialRepository;
    private final InventoryAccountingSettingsRepository inventoryAccountingSettingsRepository;
    private final StockMovementService stockMovementService;
    private final StockMovementDocumentService stockMovementDocumentService;
    private final StockValuationDocumentService stockValuationDocumentService;
    private final IntegrationEventService integrationEventService;
    private final ApprovalEngineService approvalEngineService;
    private final NonMovingStockCommissionService nonMovingStockCommissionService;
    private final NonMovingStockCaseMapper mapper;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final NonMovingStockCaseLifecycle lifecycle;
    private final NonMovingStockDecisionService decisionService;
    private final NonMovingStockExecutionService executionService;
    private final NonMovingStockReconciliationService reconciliationService;
    private final NonMovingStockEvidencePolicy evidencePolicy;
    private final NonMovingStockNotificationService notificationService;
    private final NonMovingStockActionPolicy nonMovingStockActionPolicy;



    @Transactional
    public NonMovingStockCaseResponse assign(
        UUID caseId,
        com.enterprise.mm.payload.NonMovingStockCaseCommandDtos.AssignmentRequest request
    ) {
        if (request == null) {
            throw new BusinessException("Assignment request is required");
        }
        NonMovingStockCase stockCase = findCase(caseId);
        requireVersion(stockCase, request.expectedVersion());
        com.enterprise.mm.entity.User user = userRepository.findByIdAndIsDeletedFalse(request.responsibleUserId())
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(stockCase.getCompanyId(), candidate.getCompanyId()))
            .filter(com.enterprise.mm.entity.User::isEnabled)
            .orElseThrow(() -> new BusinessException("Responsible user is not active in the case company"));
        stockCase.setResponsibleUserId(user.getId());
        stockCase.setResponsibleUsername(user.getUsername());
        stockCase.setResponsibleUser(user.getUsername());
        stockCase.setReviewDueDate(request.reviewDueDate());
        NonMovingStockCase saved = caseRepository.save(stockCase);
        auditService.log(ENTITY_NAME, saved.getId(), "CASE_ASSIGNED", user.getUsername(), null, null);
        notificationService.notifyOwner(saved, "CASE_ASSIGNED", "You are responsible for this case; review is due " + saved.getReviewDueDate());
        return response(saved);
    }

    @Transactional
    public NonMovingStockCaseResponse changeReviewDueDate(
        UUID caseId,
        com.enterprise.mm.payload.NonMovingStockCaseCommandDtos.DueDateRequest request
    ) {
        if (request == null || request.reviewDueDate() == null || !request.reviewDueDate().isAfter(LocalDate.now())) {
            throw new BusinessException("Review due date must be in the future");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException("Due-date change reason is required");
        }
        NonMovingStockCase stockCase = findCase(caseId);
        requireVersion(stockCase, request.expectedVersion());
        stockCase.setReviewDueDate(request.reviewDueDate());
        NonMovingStockCase saved = caseRepository.save(stockCase);
        auditService.log(ENTITY_NAME, saved.getId(), "REVIEW_DUE_DATE_CHANGED", request.reason().trim(), null, null);
        return response(saved);
    }

    @Transactional
    public NonMovingStockCaseResponse startReview(UUID caseId, long expectedVersion, String comment) {
        NonMovingStockCase stockCase = findCase(caseId);
        // UX-LIVE-04: enforce START_REVIEW action policy
        nonMovingStockActionPolicy.requireActionAllowed(stockCase, "START_REVIEW");
        return transition(caseId, expectedVersion, NonMovingStockCaseStatus.UNDER_REVIEW, comment, null, "CASE_REVIEW_STARTED");
    }

    @Transactional
    public NonMovingStockCaseResponse sendToCommission(UUID caseId, long expectedVersion, String comment) {
        NonMovingStockCase stockCase = findCase(caseId);
        // UX-LIVE-04: enforce SEND_TO_COMMISSION action policy
        nonMovingStockActionPolicy.requireActionAllowed(stockCase, "SEND_TO_COMMISSION");
        return transition(caseId, expectedVersion, NonMovingStockCaseStatus.COMMISSION_REVIEW, comment, null, "CASE_SENT_TO_COMMISSION");
    }

    @Transactional
    public NonMovingStockCaseResponse deferReview(
        UUID caseId,
        com.enterprise.mm.payload.NonMovingStockCaseCommandDtos.DeferredReviewRequest request
    ) {
        if (request == null) {
            throw new BusinessException("Deferred review request is required");
        }
        NonMovingStockCase stockCase = findCase(caseId);
        // UX-LIVE-04: enforce DEFER_REVIEW action policy
        nonMovingStockActionPolicy.requireActionAllowed(stockCase, "DEFER_REVIEW");
        return transition(
            caseId,
            request.expectedVersion(),
            NonMovingStockCaseStatus.REVIEW_DEFERRED,
            request.reason(),
            request.nextReviewDate(),
            "CASE_REVIEW_DEFERRED"
        );
    }

    @Transactional
    public NonMovingStockCaseResponse close(UUID caseId, long expectedVersion, String comment) {
        NonMovingStockCase stockCase = findCase(caseId);
        // UX-LIVE-04: enforce CLOSE action policy
        nonMovingStockActionPolicy.requireActionAllowed(stockCase, "CLOSE");
        List<NonMovingStockEvidencePolicy.Blocker> blockers = evidencePolicy.closureBlockers(stockCase);
        if (!blockers.isEmpty()) {
            throw new BusinessException("Case cannot be closed: " + blockers.stream()
                .map(blocker -> blocker.code() + " - " + blocker.message())
                .collect(java.util.stream.Collectors.joining("; ")));
        }
        NonMovingStockCaseResponse result = transition(
            caseId, expectedVersion, NonMovingStockCaseStatus.CLOSED, comment, null, "CASE_CLOSED"
        );
        setActiveMembership(caseId, false);
        return result;
    }

    @Transactional
    public NonMovingStockCaseResponse cancel(UUID caseId, long expectedVersion, String reason) {
        NonMovingStockCase stockCase = findCase(caseId);
        // UX-LIVE-04: enforce CANCEL action policy
        nonMovingStockActionPolicy.requireActionAllowed(stockCase, "CANCEL");
        NonMovingStockCaseResponse result = transition(
            caseId, expectedVersion, NonMovingStockCaseStatus.CANCELLED, reason, null, "CASE_CANCELLED"
        );
        setActiveMembership(caseId, false);
        return result;
    }

    @Transactional
    public NonMovingStockCaseResponse reopen(UUID caseId, long expectedVersion, String reason) {
        NonMovingStockCase stockCase = findCase(caseId);
        // UX-LIVE-04: enforce REOPEN action policy
        nonMovingStockActionPolicy.requireActionAllowed(stockCase, "REOPEN");
        NonMovingStockCaseResponse result = transition(
            caseId, expectedVersion, NonMovingStockCaseStatus.UNDER_REVIEW, reason, null, "CASE_REOPENED"
        );
        setActiveMembership(caseId, true);
        return result;
    }

    @Transactional
    public NonMovingStockCaseResponse createCaseFromDetected(com.enterprise.mm.payload.NonMovingStockCaseCreateRequest request) {
        if (request == null) {
            throw new BusinessException("Non-moving stock case request is required");
        }
        return response(createCaseForDetections(
            eligibleDetections(request.getIds()),
            request.getCommissionId(),
            request.getCommissionProtocolNo()
        ));
    }

    @Transactional
    public NonMovingStockCaseResponse createCaseFromDetected(List<UUID> ids) {
        return response(createCaseForDetections(eligibleDetections(ids)));
    }

    @Transactional
    public NonMovingStockExecutionResponse executeDirectDecision(UUID nonMovingStockId, NonMovingStockDecisionCreateRequest request) {
        NonMovingStock detection = findDetection(nonMovingStockId);
        NonMovingStockCase stockCase = resolveCaseForDirectExecution(detection);
        approveDecisionDirectly(stockCase, request);
        return executeLatestDecision(stockCase.getId());
    }

    @Transactional
    public NonMovingStockExecutionResponse executeDirectDecision(
        UUID nonMovingStockId,
        NonMovingStockDecisionCreateRequest request,
        boolean emergencyOverride,
        String overrideReason
    ) {
        if (!emergencyOverride || overrideReason == null || overrideReason.isBlank()) {
            throw new BusinessException(
                "Direct execution is restricted to an audited emergency override; use the non-moving stock case workflow"
            );
        }
        NonMovingStockExecutionResponse response = executeDirectDecision(nonMovingStockId, request);
        auditService.log(
            ENTITY_NAME, response.getCaseId(), "EMERGENCY_DIRECT_EXECUTION", overrideReason.trim()
        , null, null);
        return response;
    }

    @Transactional
    public NonMovingStockDecisionResponse approveDecision(UUID caseId, NonMovingStockDecisionCreateRequest request) {
        NonMovingStockCase stockCase = findCase(caseId);
        if (request.getExpectedVersion() != null) {
            requireVersion(stockCase, request.getExpectedVersion());
        }
        if (!List.of(
            NonMovingStockCaseStatus.CASE_CREATED.name(),
            NonMovingStockCaseStatus.UNDER_REVIEW.name(),
            NonMovingStockCaseStatus.COMMISSION_REVIEW.name()
        ).contains(stockCase.getStatus())) {
            throw new BusinessException("Decision can only be submitted from CASE_CREATED, UNDER_REVIEW or COMMISSION_REVIEW status");
        }
        NonMovingStockDecisionType type = decisionType(request);
        bindAndValidateCommission(stockCase, request);
        validateDecision(stockCase, type, request);
        NonMovingStockDecision saved = persistDecision(stockCase, request, type, NonMovingStockCaseStatus.APPROVAL_PENDING.name());
        advanceToApprovalPending(stockCase);
        caseRepository.save(stockCase);
        approvalEngineService.start(NonMovingStockDecisionApprovalAdapter.MODULE_KEY, saved.getId());
        auditService.log(ENTITY_NAME, stockCase.getId(), "DECISION_SUBMITTED_FOR_APPROVAL", type.name(), null, null);
        return decisionResponse(saved);
    }

    @Transactional
    public NonMovingStockExecutionResponse executeLatestDecision(UUID caseId) {
        NonMovingStockCase stockCase = findCase(caseId);
        NonMovingStockDecision decision = decisionRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId())
            .stream()
            .max(Comparator.comparing(NonMovingStockDecision::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .orElseThrow(() -> new BusinessException("Approved decision is required before execution"));
        if (!NonMovingStockCaseStatus.DECISION_APPROVED.name().equals(decision.getStatus())) {
            throw new BusinessException("Latest decision must be approved before execution");
        }
        return executeDecision(caseId, decision.getId());
    }

    @Transactional
    public NonMovingStockExecutionResponse executeDecision(UUID caseId, UUID decisionId) {
        return executeDecision(caseId, decisionId, "decision-" + decisionId, -1L);
    }

    @Transactional
    public NonMovingStockExecutionResponse executeDecision(
        UUID caseId,
        UUID decisionId,
        String idempotencyKey,
        long expectedVersion
    ) {
        NonMovingStockCase stockCase = findCase(caseId);
        // UX-LIVE-04: enforce EXECUTE_DECISION action policy
        nonMovingStockActionPolicy.requireActionAllowed(stockCase, "EXECUTE_DECISION");
        Optional<NonMovingStockExecutionResponse> replay = replayExecution(
            stockCase, decisionId, idempotencyKey
        );
        if (replay.isPresent()) {
            return replay.get();
        }

        stockCase = findCaseForExecution(caseId);
        replay = replayExecution(stockCase, decisionId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (expectedVersion >= 0) {
            requireVersion(stockCase, expectedVersion);
        }
        NonMovingStockDecision decision = decisionRepository.findByIdAndIsDeletedFalse(decisionId)
            .orElseThrow(() -> new NotFoundException("NonMovingStockDecision not found: " + decisionId));
        if (!stockCase.getId().equals(decision.getCaseId())) {
            throw new NotFoundException("NonMovingStockDecision not found: " + decisionId);
        }
        if (!NonMovingStockCaseStatus.DECISION_APPROVED.name().equals(decision.getStatus())) {
            throw new BusinessException("Decision must be approved before execution");
        }
        requireNoActiveExecution(stockCase, decisionId);
        List<NonMovingStockDecisionLine> persistedDecisionLines = decisionService.lines(decision.getId());
        reconcileApprovedLines(stockCase, persistedDecisionLines);
        NonMovingStockDecisionType type = NonMovingStockDecisionType.valueOf(decision.getDecisionType());
        NonMovingStockExecution execution = executionService.begin(stockCase, decision, idempotencyKey);

        if (type == NonMovingStockDecisionType.KEEP_WITH_REVIEW_DATE) {
            lifecycle.apply(
                stockCase,
                NonMovingStockCaseStatus.REVIEW_DEFERRED,
                currentVersion(stockCase),
                nvlText(decision.getComment(), "Approved deferred review"),
                decision.getNextReviewDate(),
                currentUsername()
            );
            caseRepository.save(stockCase);
            execution.setStatus(NonMovingStockExecutionStatus.EXECUTED.name());
            execution.setExecutionStatus(NonMovingStockExecutionStatus.EXECUTED.name());
            execution.setCompletedAt(LocalDateTime.now());
            NonMovingStockExecution saved = executionRepository.save(execution);
            return mapper.toExecutionResponse(saved, executionService.outputs(saved.getId()));
        }

        if (!persistedDecisionLines.isEmpty()) {
            return executeLineDecision(stockCase, decision, execution, persistedDecisionLines);
        }
        if (type == NonMovingStockDecisionType.MIXED) {
            throw new BusinessException("Mixed decision execution requires persisted decision lines");
        }

        lifecycle.apply(
            stockCase,
            NonMovingStockCaseStatus.IN_EXECUTION,
            currentVersion(stockCase),
            "Approved decision execution started",
            null,
            currentUsername()
        );
        caseRepository.save(stockCase);

        if (type == NonMovingStockDecisionType.IMPAIRMENT_RESERVE) {
            List<ImpairmentExecutionOutput> valuationOutputs = executeImpairmentDecision(stockCase, decision);
            UUID firstValuationDocumentId = valuationOutputs.get(0).documentId();
            IntegrationEvent event = integrationEventService.emit(
                integrationEventType(type),
                ENTITY_NAME,
                stockCase.getId(),
                Map.of(
                    "caseId", stockCase.getId().toString(),
                    "decisionId", decision.getId().toString(),
                    "decisionType", type.name(),
                    "valuationDocumentId", firstValuationDocumentId == null ? "" : firstValuationDocumentId.toString()
                )
            );
            execution.setValuationDocumentId(firstValuationDocumentId);
            execution.setIntegrationEventId(event.getId());
            valuationOutputs.forEach(output -> executionService.recordOutput(
                execution, null, output.caseLineId(), NonMovingStockExecutionOutputType.VALUATION_DOCUMENT.name(),
                "STOCK_VALUATION_DOCUMENT", output.documentId(), output.documentNumber(),
                NonMovingStockExecutionOutputStatus.PENDING, output.quantity(), output.reserveAmount(),
                decision.getExpectedRecoveryCurrencyCode()
            ));
            executionService.recordOutput(
                execution, null, null, NonMovingStockExecutionOutputType.INTEGRATION_EVENT.name(),
                "INTEGRATION_EVENT", event.getId(), null, NonMovingStockExecutionOutputStatus.POSTED,
                null, null, null
            );
            NonMovingStockExecution savedExecution = executionService.synchronizeStatus(execution);
            auditService.log(ENTITY_NAME, stockCase.getId(), "EXECUTE_" + type.name(), String.valueOf(firstValuationDocumentId), null, null);
            return mapper.toExecutionResponse(savedExecution, executionService.outputs(savedExecution.getId()));
        }

        if (type == NonMovingStockDecisionType.TRANSFER) {
            UUID stockMovementDocumentId = createPendingTransferDocument(stockCase, decision);
            execution.setStockMovementId(stockMovementDocumentId);
            executionService.recordOutput(
                execution, null, null, NonMovingStockExecutionOutputType.STOCK_MOVEMENT_DOCUMENT.name(),
                "STOCK_MOVEMENT_DOCUMENT", stockMovementDocumentId, null,
                NonMovingStockExecutionOutputStatus.PENDING, decision.getApprovedQuantity(), null, null
            );
            execution.setStatus(NonMovingStockExecutionStatus.PENDING.name());
            execution.setExecutionStatus(NonMovingStockExecutionStatus.PENDING.name());
            NonMovingStockExecution savedExecution = executionRepository.save(execution);
            stockCase.setStatus(NonMovingStockCaseStatus.IN_EXECUTION.name());
            caseRepository.save(stockCase);
            auditService.log(ENTITY_NAME, stockCase.getId(), "EXECUTE_TRANSFER_PENDING", String.valueOf(stockMovementDocumentId), null, null);
            return mapper.toExecutionResponse(savedExecution, executionService.outputs(savedExecution.getId()));
        }

        if (type != NonMovingStockDecisionType.WRITE_OFF && type != NonMovingStockDecisionType.SCRAP) {
            throw new BusinessException("Execution is not implemented for decision type: " + type.name());
        }

        StockExecutionResult stockExecution = executeStockMovements(stockCase, decision, type);
        IntegrationEvent event = integrationEventService.emit(
            integrationEventType(type),
            ENTITY_NAME,
            stockCase.getId(),
            Map.of(
                "caseId", stockCase.getId().toString(),
                "decisionId", decision.getId().toString(),
                "decisionType", type.name(),
                "stockMovementId", stockExecution.firstMovementId() == null ? "" : stockExecution.firstMovementId().toString()
            )
        );
        execution.setStockMovementId(stockExecution.firstMovementId());
        execution.setIntegrationEventId(event.getId());
        executionService.recordOutput(
            execution, null, null, NonMovingStockExecutionOutputType.STOCK_MOVEMENT.name(),
            "STOCK_MOVEMENT", stockExecution.firstMovementId(), null,
            NonMovingStockExecutionOutputStatus.POSTED, decision.getApprovedQuantity(), null, null
        );
        executionService.recordOutput(
            execution, null, null, NonMovingStockExecutionOutputType.INTEGRATION_EVENT.name(),
            "INTEGRATION_EVENT", event.getId(), null, NonMovingStockExecutionOutputStatus.POSTED,
            null, null, null
        );
        execution.setStatus(NonMovingStockExecutionStatus.EXECUTED.name());
        execution.setExecutionStatus(NonMovingStockExecutionStatus.EXECUTED.name());
        NonMovingStockExecution savedExecution = executionRepository.save(execution);

        lifecycle.apply(
            stockCase,
            NonMovingStockCaseStatus.EXECUTED,
            currentVersion(stockCase),
            "Operational outputs completed",
            null,
            currentUsername()
        );
        caseRepository.save(stockCase);
        reconcileDetectionQuantities(stockExecution.executedQuantityByDetection(), terminalDetectionStatus(type));
        auditService.log(ENTITY_NAME, stockCase.getId(), "EXECUTE_" + type.name(), String.valueOf(stockExecution.firstMovementId()), null, null);
        return mapper.toExecutionResponse(savedExecution, executionService.outputs(savedExecution.getId()));
    }

    private NonMovingStockExecutionResponse executeLineDecision(
        NonMovingStockCase stockCase,
        NonMovingStockDecision decision,
        NonMovingStockExecution execution,
        List<NonMovingStockDecisionLine> decisionLines
    ) {
        NonMovingStockDecisionType packageType = NonMovingStockDecisionType.valueOf(decision.getDecisionType());
        String executionLabel = packageType == NonMovingStockDecisionType.MIXED
            ? "mixed"
            : packageType.name().toLowerCase(Locale.ROOT);
        lifecycle.apply(
            stockCase, NonMovingStockCaseStatus.IN_EXECUTION, currentVersion(stockCase),
            "Approved " + executionLabel + " decision execution started", null, currentUsername()
        );
        caseRepository.save(stockCase);
        Map<UUID, NonMovingStockCaseLine> caseLineById = lineRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), stockCase.getId()
            ).stream().collect(java.util.stream.Collectors.toMap(NonMovingStockCaseLine::getId, line -> line));
        Map<TransferGroupKey, List<NonMovingStockDecisionLine>> transfers = decisionLines.stream()
            .filter(line -> NonMovingStockDecisionType.TRANSFER.name().equals(line.getDecisionType()))
            .collect(java.util.stream.Collectors.groupingBy(line -> {
                NonMovingStockCaseLine caseLine = requiredCaseLine(caseLineById, line);
                return new TransferGroupKey(caseLine.getWarehouseId(), line.getTargetWarehouseId());
            }, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<NonMovingStockDecisionType, Map<UUID, BigDecimal>> executedByType =
            new java.util.EnumMap<>(NonMovingStockDecisionType.class);

        for (NonMovingStockDecisionLine decisionLine : decisionLines) {
            NonMovingStockDecisionType lineType = NonMovingStockDecisionType.valueOf(decisionLine.getDecisionType());
            if (lineType == NonMovingStockDecisionType.TRANSFER) {
                continue;
            }
            NonMovingStockCaseLine caseLine = requiredCaseLine(caseLineById, decisionLine);
            if (lineType == NonMovingStockDecisionType.WRITE_OFF || lineType == NonMovingStockDecisionType.SCRAP) {
                var movement = stockMovementService.createAndPost(stockMovement(
                    stockCase, caseLine, decisionLine.getApprovedQuantity(), lineType
                ));
                executionService.recordOutput(
                    execution, decisionLine.getId(), caseLine.getId(),
                    NonMovingStockExecutionOutputType.STOCK_MOVEMENT.name(), "STOCK_MOVEMENT",
                    movement.getId(), movement.getCode(), NonMovingStockExecutionOutputStatus.POSTED,
                    decisionLine.getApprovedQuantity(), null, decisionLine.getCurrencyCode()
                );
                if (packageType != NonMovingStockDecisionType.MIXED
                    && execution.getStockMovementId() == null) {
                    execution.setStockMovementId(movement.getId());
                }
                executedByType.computeIfAbsent(lineType, ignored -> new HashMap<>()).merge(
                    caseLine.getNonMovingStockId(), decisionLine.getApprovedQuantity(), BigDecimal::add);
            } else if (lineType == NonMovingStockDecisionType.IMPAIRMENT_RESERVE) {
                StockValuationDocumentResponse valuation = stockValuationDocumentService.create(
                    impairmentRequest(stockCase, caseLine, decisionLine)
                );
                executionService.recordOutput(
                    execution, decisionLine.getId(), caseLine.getId(),
                    NonMovingStockExecutionOutputType.VALUATION_DOCUMENT.name(), "STOCK_VALUATION_DOCUMENT",
                    valuation.getId(), valuation.getCode(), NonMovingStockExecutionOutputStatus.PENDING,
                    decisionLine.getApprovedQuantity(), decisionLine.getFinancialAmount(), decisionLine.getCurrencyCode()
                );
                if (execution.getValuationDocumentId() == null) {
                    execution.setValuationDocumentId(valuation.getId());
                }
                executedByType.computeIfAbsent(lineType, ignored -> new HashMap<>()).merge(
                    caseLine.getNonMovingStockId(), decisionLine.getApprovedQuantity(), BigDecimal::add);
            } else {
                throw new BusinessException("Line execution is not implemented for decision type: " + lineType.name());
            }
        }
        transfers.forEach((group, lines) -> createTransferOutput(
            stockCase,
            execution,
            group,
            lines,
            caseLineById,
            packageType == NonMovingStockDecisionType.TRANSFER
        ));
        IntegrationEvent event = integrationEventService.emit(
            packageType == NonMovingStockDecisionType.MIXED
                ? "NON_MOVING_STOCK_MIXED"
                : integrationEventType(packageType),
            ENTITY_NAME,
            stockCase.getId(),
            Map.of("caseId", stockCase.getId().toString(), "decisionId", decision.getId().toString())
        );
        execution.setIntegrationEventId(event.getId());
        executionService.recordOutput(
            execution, null, null, NonMovingStockExecutionOutputType.INTEGRATION_EVENT.name(),
            "INTEGRATION_EVENT", event.getId(), null, NonMovingStockExecutionOutputStatus.POSTED,
            null, null, null
        );
        NonMovingStockExecution saved = executionService.synchronizeStatus(execution);
        if (NonMovingStockExecutionStatus.EXECUTED.name().equals(saved.getExecutionStatus())) {
            lifecycle.apply(
                stockCase, NonMovingStockCaseStatus.EXECUTED, currentVersion(stockCase),
                "All " + executionLabel + " decision outputs completed", null, currentUsername()
            );
            caseRepository.save(stockCase);
            executedByType.forEach((lineType, quantities) ->
                reconcileDetectionQuantities(quantities, terminalDetectionStatus(lineType))
            );
        }
        auditService.log(
            ENTITY_NAME,
            stockCase.getId(),
            "EXECUTE_" + packageType.name(),
            saved.getExecutionStatus()
        , null, null);
        return mapper.toExecutionResponse(saved, executionService.outputs(saved.getId()));
    }

    private void createTransferOutput(
        NonMovingStockCase stockCase,
        NonMovingStockExecution execution,
        TransferGroupKey group,
        List<NonMovingStockDecisionLine> decisionLines,
        Map<UUID, NonMovingStockCaseLine> caseLineById,
        boolean exposeAsPrimaryDocument
    ) {
        StockMovementDocumentCreateRequest request = new StockMovementDocumentCreateRequest();
        request.setCode("NMS-TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        request.setName("Non-moving mixed transfer " + stockCase.getCaseNo());
        request.setWarehouseId(group.sourceWarehouseId());
        request.setTargetWarehouseId(group.targetWarehouseId());
        request.setMovementType(MovementType.TRANSFER);
        request.setMovementDate(LocalDate.now());
        request.setReferenceType(CASE_REFERENCE_TYPE);
        request.setReferenceId(stockCase.getId());
        request.setItems(decisionLines.stream().map(line -> {
            NonMovingStockCaseLine caseLine = requiredCaseLine(caseLineById, line);
            StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
            item.setMaterialId(caseLine.getMaterialId());
            item.setStorageLocationId(caseLine.getStorageLocationId());
            item.setStockBatchId(caseLine.getBatchId());
            item.setQuantity(line.getApprovedQuantity());
            item.setUnitCost(unitCost(caseLine));
            return item;
        }).toList());
        StockMovementDocumentResponse created = stockMovementDocumentService.create(request);
        stockMovementDocumentService.changeStatus(
            created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.PENDING)
        );
        BigDecimal quantity = decisionLines.stream().map(NonMovingStockDecisionLine::getApprovedQuantity)
            .map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (exposeAsPrimaryDocument && execution.getStockMovementId() == null) {
            execution.setStockMovementId(created.getId());
        }
        executionService.recordOutput(
            execution, null, null, NonMovingStockExecutionOutputType.STOCK_MOVEMENT_DOCUMENT.name(),
            "STOCK_MOVEMENT_DOCUMENT", created.getId(), created.getCode(),
            NonMovingStockExecutionOutputStatus.PENDING, quantity, null, null
        );
    }

    private StockMovement stockMovement(
        NonMovingStockCase stockCase,
        NonMovingStockCaseLine caseLine,
        BigDecimal quantity,
        NonMovingStockDecisionType type
    ) {
        StockMovement movement = new StockMovement();
        movement.setCompanyId(stockCase.getCompanyId());
        movement.setCode("NMS-MOV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        movement.setName("Non-moving stock " + type.name());
        movement.setMaterialId(caseLine.getMaterialId());
        movement.setWarehouseId(caseLine.getWarehouseId());
        movement.setStorageLocationId(caseLine.getStorageLocationId());
        movement.setStockBatchId(caseLine.getBatchId());
        movement.setMovementType(MovementType.WRITE_OFF);
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost(caseLine));
        movement.setMovementDate(LocalDate.now());
        movement.setReferenceType(CASE_REFERENCE_TYPE);
        movement.setReferenceId(stockCase.getId());
        return movement;
    }

    private StockValuationDocumentCreateRequest impairmentRequest(
        NonMovingStockCase stockCase,
        NonMovingStockCaseLine caseLine,
        NonMovingStockDecisionLine decisionLine
    ) {
        if (nvl(decisionLine.getFinancialAmount()).signum() <= 0) {
            throw new BusinessException("Positive impairment amount is required for every mixed impairment line");
        }
        StockValuationDocumentCreateRequest request = new StockValuationDocumentCreateRequest();
        request.setCode("NMS-IMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        request.setName("Non-moving impairment " + stockCase.getCaseNo());
        request.setMaterialId(caseLine.getMaterialId());
        request.setWarehouseId(caseLine.getWarehouseId());
        request.setDocumentType("IMPAIRMENT_RESERVE");
        request.setValuationMethod(ValuationMethod.NET_REALIZABLE_VALUE);
        request.setDocumentDate(LocalDate.now());
        request.setQuantity(decisionLine.getApprovedQuantity());
        request.setReserveAmount(decisionLine.getFinancialAmount());
        request.setReason("Non-moving stock case " + stockCase.getCaseNo() + ": " + decisionLine.getComment());
        return request;
    }

    private NonMovingStockCaseLine requiredCaseLine(
        Map<UUID, NonMovingStockCaseLine> caseLineById,
        NonMovingStockDecisionLine decisionLine
    ) {
        NonMovingStockCaseLine caseLine = caseLineById.get(decisionLine.getCaseLineId());
        if (caseLine == null) {
            throw new BusinessException("Decision references missing case line: " + decisionLine.getCaseLineId());
        }
        return caseLine;
    }

    private record TransferGroupKey(UUID sourceWarehouseId, UUID targetWarehouseId) {
    }

    private void reconcileApprovedLines(
        NonMovingStockCase stockCase,
        List<NonMovingStockDecisionLine> decisionLines
    ) {
        Map<UUID, NonMovingStockCaseLine> caseLineById = lineRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), stockCase.getId()
            ).stream()
            .collect(java.util.stream.Collectors.toMap(NonMovingStockCaseLine::getId, line -> line));
        for (NonMovingStockDecisionLine decisionLine : decisionLines) {
            NonMovingStockCaseLine caseLine = caseLineById.get(decisionLine.getCaseLineId());
            if (caseLine == null) {
                throw new BusinessException("Decision references a case line that no longer exists");
            }
            var result = reconciliationService.reconcile(
                caseLine, decisionLine.getApprovedQuantity(), decisionLine.getId()
            );
            if (!result.ready()) {
                stockCase.setBlockerType(result.status().name());
                stockCase.setBlockerDetails(result.message());
                caseRepository.save(stockCase);
                throw new BusinessException("Execution blocked by reconciliation: " + result.status().name());
            }
        }
        stockCase.setBlockerType(null);
        stockCase.setBlockerDetails(null);
    }

    @Transactional(readOnly = true)
    public Page<NonMovingStockCaseResponse> search(NonMovingStockCaseSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(true);
        String search = filter.normalizedSearch();
        return caseRepository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            isBlank(search),
            searchPattern(search),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            null,
            List.of(new UUID(0L, 0L)),
            true,
            null,
            false,
            List.of("__NO_FILTER__"),
            true,
            List.of("__NO_FILTER__"),
            true,
            null,
            null,
            null,
            List.of("__NO_FILTER__"),
            true,
            List.of("__NO_FILTER__"),
            true,
            List.of("__NO_FILTER__"),
            true,
            false,
            pageable
        ).map(this::response);
    }

    @Transactional(readOnly = true)
    public NonMovingStockCaseResponse get(UUID id) {
        return response(findCase(id));
    }

    private String materialUomCode(UUID materialId) {
        if (materialId == null) {
            return null;
        }
        return materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
            .map(Material::getUnitOfMeasure)
            .map(com.enterprise.mm.entity.UnitOfMeasure::getCode)
            .orElse(null);
    }

    private List<NonMovingStockCaseLine> buildLines(NonMovingStockCase stockCase, NonMovingStock detection) {
        List<StockBalance> balances = stockBalanceRepository
            .findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                detection.getMaterialId(),
                detection.getWarehouseId()
            ).stream()
            .filter(balance -> Objects.equals(balance.getCompanyId(), detection.getCompanyId()))
            .filter(balance -> nvl(balance.getQuantity()).signum() > 0)
            .toList();
        if (balances.isEmpty()) {
            throw new BusinessException("Positive stock balance is required to create non-moving stock case");
        }
        Material material = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), detection.getMaterialId())
            .orElse(null);
        LocalDateTime lastMovementAt = lastMovementAt(detection);
        AtomicBoolean activeMembershipAvailable = new AtomicBoolean(true);
        return balances.stream()
            .map(balance -> {
                NonMovingStockCaseLine line = new NonMovingStockCaseLine();
                line.setCompanyId(detection.getCompanyId());
                line.setCaseId(stockCase.getId());
                line.setNonMovingStockId(detection.getId());
                line.setActiveMembership(activeMembershipAvailable.getAndSet(false));
                line.setMaterialId(detection.getMaterialId());
                line.setWarehouseId(detection.getWarehouseId());
                line.setStorageLocationId(balance.getStorageLocationId());
                line.setBatchId(balance.getStockBatchId());
                line.setQuantity(nvl(balance.getQuantity()));
                line.setAvailableQuantity(nvl(balance.getAvailableQuantity()));
                line.setReservedQuantity(nvl(balance.getReservedQuantity()));
                line.setBlockedQuantity(nvl(balance.getQuarantineQuantity()));
                line.setBookValue(nvl(balance.getTotalValue()));
                line.setUnitCost(nvl(balance.getQuantity()).signum() == 0
                    ? BigDecimal.ZERO
                    : nvl(balance.getTotalValue()).divide(nvl(balance.getQuantity()), 4, RoundingMode.HALF_UP));
                line.setCurrencyCode(stockCase.getAccountingCurrencyCode());
                line.setValuationHealth(stockCase.getValuationHealth());
                line.setValuationHealthMessage(stockCase.getValuationHealthMessage());
                line.setValuationDate(balance.getBalanceAt() == null
                    ? LocalDate.now()
                    : balance.getBalanceAt().toLocalDate());
                line.setAnalysisAsOfDate(detection.getDetectedAt() == null
                    ? LocalDate.now()
                    : detection.getDetectedAt().toLocalDate());
                line.setDetectionRule("MONTHS_WITHOUT_MOVEMENT");
                line.setDetectionRuleVersion("1");
                line.setDetectionThreshold(detection.getMonthsWithoutMovement());
                line.setExclusionResult("INCLUDED");
                line.setMonthsWithoutMovement(detection.getMonthsWithoutMovement());
                line.setAgingBucketMonths(bucket(detection.getMonthsWithoutMovement()));
                line.setLastMovementAt(lastMovementAt);
                line.setReplacementMaterialId(material == null ? null : material.getReplacementMaterialId());
                line.setMaterialStatus(material == null ? null : material.getStatus());
                line.setCode("NMS-LINE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
                line.setName("Non-moving line");
                line.setStatus(NonMovingStockCaseStatus.CASE_CREATED.name());
                return line;
            })
            .toList();
    }

    private StockExecutionResult executeStockMovements(NonMovingStockCase stockCase, NonMovingStockDecision decision, NonMovingStockDecisionType type) {
        BigDecimal remaining = nvl(decision.getApprovedQuantity());
        UUID firstMovementId = null;
        Map<UUID, BigDecimal> executedQuantityByDetection = new HashMap<>();
        List<NonMovingStockCaseLine> lines = lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId());
        for (NonMovingStockCaseLine line : lines) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal quantity = nvl(line.getQuantity()).min(remaining);
            if (quantity.signum() <= 0) {
                continue;
            }
            StockMovement movement = new StockMovement();
            movement.setCompanyId(stockCase.getCompanyId());
            movement.setCode("NMS-MOV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            movement.setName("Non-moving stock " + type.name());
            movement.setMaterialId(line.getMaterialId());
            movement.setWarehouseId(line.getWarehouseId());
            movement.setTargetWarehouseId(type == NonMovingStockDecisionType.TRANSFER ? decision.getTargetWarehouseId() : null);
            movement.setStorageLocationId(line.getStorageLocationId());
            movement.setStockBatchId(line.getBatchId());
            movement.setMovementType(type == NonMovingStockDecisionType.TRANSFER ? MovementType.TRANSFER : MovementType.WRITE_OFF);
            movement.setQuantity(quantity);
            movement.setUnitCost(unitCost(line));
            movement.setMovementDate(LocalDate.now());
            movement.setReferenceType(CASE_REFERENCE_TYPE);
            movement.setReferenceId(stockCase.getId());
            var posted = stockMovementService.createAndPost(movement);
            if (firstMovementId == null) {
                firstMovementId = posted.getId();
            }
            executedQuantityByDetection.merge(line.getNonMovingStockId(), quantity, BigDecimal::add);
            remaining = remaining.subtract(quantity);
        }
        if (remaining.signum() > 0) {
            throw new BusinessException("Approved quantity exceeds available case quantity");
        }
        return new StockExecutionResult(firstMovementId, executedQuantityByDetection);
    }

    private List<ImpairmentExecutionOutput> executeImpairmentDecision(
        NonMovingStockCase stockCase,
        NonMovingStockDecision decision
    ) {
        BigDecimal reserveAmount = nvl(decision.getImpairmentReserveAmount());
        if (reserveAmount.signum() <= 0) {
            throw new BusinessException("Impairment reserve amount is required");
        }
        List<NonMovingStockCaseLine> lines = lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId());
        BigDecimal totalBookValue = lines.stream()
            .map(NonMovingStockCaseLine::getBookValue)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalBookValue.signum() <= 0) {
            throw new BusinessException("Positive book value is required for impairment decision");
        }
        BigDecimal cappedReserve = reserveAmount.min(totalBookValue);
        BigDecimal remainder = cappedReserve;
        List<ImpairmentExecutionOutput> outputs = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            NonMovingStockCaseLine line = lines.get(i);
            BigDecimal lineBookValue = nvl(line.getBookValue());
            if (lineBookValue.signum() <= 0) {
                continue;
            }
            BigDecimal lineReserve = i == lines.size() - 1
                ? remainder
                : cappedReserve.multiply(lineBookValue).divide(totalBookValue, 4, RoundingMode.HALF_UP);
            if (lineReserve.signum() <= 0) {
                continue;
            }
            StockValuationDocumentCreateRequest request = new StockValuationDocumentCreateRequest();
            request.setCode("NMS-IMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            request.setName("Non-moving impairment " + stockCase.getCaseNo());
            request.setMaterialId(line.getMaterialId());
            request.setWarehouseId(line.getWarehouseId());
            request.setDocumentType("IMPAIRMENT_RESERVE");
            request.setValuationMethod(ValuationMethod.NET_REALIZABLE_VALUE);
            request.setDocumentDate(LocalDate.now());
            request.setQuantity(line.getQuantity());
            request.setReserveAmount(lineReserve);
            request.setReason("Non-moving stock case " + stockCase.getCaseNo() + ": " + nvlText(decision.getComment(), decision.getWriteOffReason()));
            StockValuationDocumentResponse created = stockValuationDocumentService.create(request);
            outputs.add(new ImpairmentExecutionOutput(
                line.getId(), created.getId(), created.getCode(), line.getQuantity(), lineReserve
            ));
            remainder = remainder.subtract(lineReserve);
        }
        return List.copyOf(outputs);
    }

    private record ImpairmentExecutionOutput(
        UUID caseLineId,
        UUID documentId,
        String documentNumber,
        BigDecimal quantity,
        BigDecimal reserveAmount
    ) {
    }

    private UUID createPendingTransferDocument(
        NonMovingStockCase stockCase,
        NonMovingStockDecision decision
    ) {
        BigDecimal remaining = nvl(decision.getApprovedQuantity());
        List<NonMovingStockCaseLine> lines = lineRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                stockCase.getId()
            );
        java.util.ArrayList<StockMovementDocumentItemRequest> items = new java.util.ArrayList<>();
        for (NonMovingStockCaseLine line : lines) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal quantity = nvl(line.getQuantity()).min(remaining);
            if (quantity.signum() <= 0) {
                continue;
            }
            StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
            item.setMaterialId(line.getMaterialId());
            item.setStorageLocationId(line.getStorageLocationId());
            item.setStockBatchId(line.getBatchId());
            item.setQuantity(quantity);
            item.setUnitCost(unitCost(line));
            items.add(item);
            remaining = remaining.subtract(quantity);
        }
        if (items.isEmpty() || remaining.signum() > 0) {
            throw new BusinessException("Approved quantity exceeds available case quantity");
        }

        StockMovementDocumentCreateRequest request = new StockMovementDocumentCreateRequest();
        request.setCode("NMS-TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        request.setName("Non-moving stock transfer " + stockCase.getCaseNo());
        request.setDescription(decision.getComment());
        request.setWarehouseId(stockCasePrimaryWarehouseId(stockCase, lines));
        request.setTargetWarehouseId(decision.getTargetWarehouseId());
        request.setMovementType(MovementType.TRANSFER);
        request.setMovementDate(LocalDate.now());
        request.setReferenceType(CASE_REFERENCE_TYPE);
        request.setReferenceId(stockCase.getId());
        request.setItems(items);

        StockMovementDocumentResponse created = stockMovementDocumentService.create(request);
        stockMovementDocumentService.changeStatus(
            created.getId(),
            new StockMovementStatusChangeRequest(StockMovementStatus.PENDING)
        );
        return created.getId();
    }

    private void markDetections(UUID caseId, NonMovingStockStatus status) {
        lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), caseId).stream()
            .map(NonMovingStockCaseLine::getNonMovingStockId)
            .distinct()
            .forEach(id -> nonMovingStockRepository.findByIdAndIsDeletedFalse(id).ifPresent(detection -> {
                detection.setStatus(status.name());
                nonMovingStockRepository.save(detection);
            }));
    }

    private void reconcileDetectionQuantities(
        Map<UUID, BigDecimal> executedQuantityByDetection,
        NonMovingStockStatus terminalStatus
    ) {
        executedQuantityByDetection.forEach((detectionId, executedQuantity) ->
            nonMovingStockRepository.findByIdAndIsDeletedFalse(detectionId).ifPresent(detection -> {
                BigDecimal originalQuantity = nvl(detection.getQuantity());
                BigDecimal normalizedExecutedQuantity = nvl(executedQuantity);
                if (normalizedExecutedQuantity.signum() <= 0) {
                    return;
                }
                if (originalQuantity.compareTo(normalizedExecutedQuantity) <= 0) {
                    detection.setStatus(terminalStatus.name());
                    detection.setPlannedAction(terminalStatus.name());
                    nonMovingStockRepository.save(detection);
                    return;
                }

                NonMovingStock executedPart = new NonMovingStock();
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
            })
        );
    }

    private NonMovingStock findDetection(UUID id) {
        NonMovingStock detection = nonMovingStockRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("NonMovingStock not found: " + id));
        if (!TenantContext.currentTenant().equals(detection.getTenantId())) {
            throw new NotFoundException("NonMovingStock not found: " + id);
        }
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(detection.getCompanyId())) {
            throw new NotFoundException("NonMovingStock not found: " + id);
        }
        return detection;
    }

    private NonMovingStockCase createCaseForDetections(List<NonMovingStock> detections) {
        return createCaseForDetections(detections, null, null);
    }

    private NonMovingStockCase createCaseForDetections(
        List<NonMovingStock> detections,
        UUID commissionId,
        String commissionProtocolNo
    ) {
        UUID companyId = detections.stream()
            .map(NonMovingStock::getCompanyId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new BusinessException("Company is required to create non-moving stock case"));
        if (detections.stream().map(NonMovingStock::getCompanyId).anyMatch(id -> !Objects.equals(id, companyId))) {
            throw new BusinessException("Non-moving stock case must belong to one company");
        }
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setCompanyId(companyId);
        stockCase.setCaseNo("NMS-CASE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        stockCase.setCode(stockCase.getCaseNo());
        stockCase.setName("Non-moving stock case " + stockCase.getCaseNo());
        stockCase.setStatus(NonMovingStockCaseStatus.CASE_CREATED.name());
        stockCase.setDetectedAt(LocalDateTime.now());
        stockCase.setReviewDueDate(LocalDate.now().plusDays(14));
        ValuationSnapshot valuation = accountingValuationSnapshot(companyId);
        stockCase.setAccountingCurrencyCode(valuation.currencyCode());
        stockCase.setValuationHealth(valuation.health());
        stockCase.setValuationHealthMessage(valuation.message());
        stockCase.setCommissionProtocolNo(trim(commissionProtocolNo));
        if (commissionId != null) {
            stockCase.setCommissionId(requireCaseCommission(companyId, commissionId).getId());
        }
        NonMovingStockCase savedCase = caseRepository.save(stockCase);

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalBookValue = BigDecimal.ZERO;
        // UX-LIVE-09: totalQuantity aggregates across detections that may reference different
        // materials; guard against silently summing incompatible units of measure.
        String referenceUomCode = null;
        for (NonMovingStock detection : detections) {
            String detectionUomCode = materialUomCode(detection.getMaterialId());
            if (detectionUomCode != null) {
                if (referenceUomCode == null) {
                    referenceUomCode = detectionUomCode;
                } else {
                    com.enterprise.mm.common.DataQualityGuards.requireSameUom(
                        referenceUomCode, detectionUomCode, "non-moving stock case total quantity");
                }
            }
            List<NonMovingStockCaseLine> lines = buildLines(savedCase, detection);
            for (NonMovingStockCaseLine line : lines) {
                NonMovingStockCaseLine savedLine = lineRepository.save(line);
                totalQuantity = totalQuantity.add(nvl(savedLine.getQuantity()));
                totalBookValue = totalBookValue.add(nvl(savedLine.getBookValue()));
            }
            detection.setStatus(NonMovingStockStatus.CASE_CREATED.name());
            nonMovingStockRepository.save(detection);
        }
        savedCase.setTotalQuantity(totalQuantity);
        savedCase.setTotalBookValue(totalBookValue);
        caseRepository.save(savedCase);
        auditService.log(ENTITY_NAME, savedCase.getId(), "CREATE_FROM_DETECTED", savedCase.getCaseNo(), null, null);
        return savedCase;
    }

    private ValuationSnapshot accountingValuationSnapshot(UUID companyId) {
        Optional<String> configuredCurrency = inventoryAccountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId)
            .map(com.enterprise.mm.entity.InventoryAccountingSettings::getBaseCurrencyCode)
            .filter(value -> value != null && value.trim().matches("(?i)[A-Z]{3}"))
            .map(value -> value.trim().toUpperCase(Locale.ROOT));
        return configuredCurrency
            .map(currency -> new ValuationSnapshot(currency, "READY", null))
            .orElseGet(() -> new ValuationSnapshot(
                "UZS",
                "PARTIAL",
                "Inventory accounting settings or a valid base currency were unavailable when the case was created; "
                    + "the persisted stock valuation uses the documented UZS fallback."
            ));
    }

    private record ValuationSnapshot(String currencyCode, String health, String message) {
    }

    private NonMovingStockCase resolveCaseForDirectExecution(NonMovingStock detection) {
        return lineRepository.findByTenantIdAndNonMovingStockIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                detection.getId()
            ).stream()
            .map(NonMovingStockCaseLine::getCaseId)
            .distinct()
            .map(this::findCase)
            .filter(stockCase -> !List.of(
                NonMovingStockCaseStatus.EXECUTED.name(),
                NonMovingStockCaseStatus.CLOSED.name(),
                NonMovingStockCaseStatus.CANCELLED.name()
            ).contains(stockCase.getStatus()))
            .findFirst()
            .orElseGet(() -> createCaseForDetections(List.of(detection)));
    }

    private NonMovingStockCase findCase(UUID id) {
        return requireAccessibleCase(caseRepository.findByIdAndIsDeletedFalse(id), id);
    }

    private NonMovingStockCase findCaseForExecution(UUID id) {
        return requireAccessibleCase(caseRepository.findByIdAndIsDeletedFalseForUpdate(id), id);
    }

    private NonMovingStockCase requireAccessibleCase(
        Optional<NonMovingStockCase> candidate,
        UUID id
    ) {
        NonMovingStockCase stockCase = candidate
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        if (!TenantContext.currentTenant().equals(stockCase.getTenantId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(stockCase.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        return stockCase;
    }

    private Optional<NonMovingStockExecutionResponse> replayExecution(
        NonMovingStockCase stockCase,
        UUID decisionId,
        String idempotencyKey
    ) {
        return executionService.idempotent(stockCase.getCompanyId(), idempotencyKey)
            .map(existing -> {
                if (!stockCase.getId().equals(existing.getCaseId())
                    || !decisionId.equals(existing.getDecisionId())) {
                    throw new BusinessException("Idempotency key is already used for another execution");
                }
                return mapper.toExecutionResponse(existing, executionService.outputs(existing.getId()));
            });
    }

    private void requireNoActiveExecution(NonMovingStockCase stockCase, UUID decisionId) {
        executionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), stockCase.getId()
            ).stream()
            .filter(execution -> decisionId.equals(execution.getDecisionId()))
            .filter(this::isActiveExecution)
            .findFirst()
            .ifPresent(execution -> {
                throw new BusinessException(
                    "Decision already has an active execution: " + execution.getId()
                );
            });
    }

    private boolean isActiveExecution(NonMovingStockExecution execution) {
        return List.of(
            NonMovingStockExecutionStatus.IN_EXECUTION.name(),
            NonMovingStockExecutionStatus.PENDING.name()
        ).contains(execution.getExecutionStatus());
    }

    private NonMovingStockCaseResponse response(NonMovingStockCase stockCase) {
        List<NonMovingStockDecision> decisions = decisionRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId());
        NonMovingStockCaseResponse result = mapper.toResponse(
            stockCase,
            lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId()),
            decisions,
            executionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId())
        );
        result.setDecisions(decisions.stream().map(this::decisionResponse).toList());
        return result;
    }

    private NonMovingStockDecisionResponse decisionResponse(NonMovingStockDecision decision) {
        return mapper.toDecisionResponse(decision, decisionService.lines(decision.getId()));
    }

    private void validateDecision(NonMovingStockCase stockCase, NonMovingStockDecisionType type, NonMovingStockDecisionCreateRequest request) {
        if (request == null) {
            throw new BusinessException("Decision request is required");
        }
        if (!supportedDecisionTypes().contains(type)) {
            throw new BusinessException("Decision type is not supported yet: " + type.name());
        }
        boolean hasDecisionLines = request.getLines() != null && !request.getLines().isEmpty();
        if (!hasDecisionLines
            && type == NonMovingStockDecisionType.TRANSFER
            && request.getTargetWarehouseId() == null) {
            throw new BusinessException("Target warehouse is required for transfer decision");
        }
        if (!hasDecisionLines
            && type == NonMovingStockDecisionType.TRANSFER
            && request.getTargetWarehouseId() != null
            && lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId()).stream()
                .map(NonMovingStockCaseLine::getWarehouseId)
                .filter(Objects::nonNull)
                .anyMatch(warehouseId -> warehouseId.equals(request.getTargetWarehouseId()))) {
            throw new BusinessException("Target warehouse must be different from source warehouse");
        }
        if (!hasDecisionLines
            && type == NonMovingStockDecisionType.IMPAIRMENT_RESERVE
            && nvl(request.getImpairmentReserveAmount()).signum() <= 0) {
            throw new BusinessException("Impairment reserve amount is required");
        }
        if (!hasDecisionLines
            && (type == NonMovingStockDecisionType.WRITE_OFF || type == NonMovingStockDecisionType.SCRAP)
            && (request.getWriteOffReason() == null || request.getWriteOffReason().isBlank())) {
            throw new BusinessException("Write-off reason is required");
        }
        if (type == NonMovingStockDecisionType.KEEP_WITH_REVIEW_DATE && request.getNextReviewDate() == null) {
            throw new BusinessException("Next review date is required");
        }
        if (normalizedApprovedQuantity(stockCase, request).compareTo(nvl(stockCase.getTotalQuantity())) > 0) {
            throw new BusinessException("Approved quantity exceeds case quantity");
        }
        validateRecoveryAssumptions(request);
    }

    private BigDecimal normalizedApprovedQuantity(NonMovingStockCase stockCase, BigDecimal value) {
        BigDecimal quantity = nvl(value);
        return quantity.signum() > 0 ? quantity : nvl(stockCase.getTotalQuantity());
    }

    private BigDecimal normalizedApprovedQuantity(
        NonMovingStockCase stockCase,
        NonMovingStockDecisionCreateRequest request
    ) {
        if (request != null && request.getLines() != null && !request.getLines().isEmpty()) {
            return request.getLines().stream()
                .map(com.enterprise.mm.payload.NonMovingStockDecisionLineRequest::getApprovedQuantity)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return normalizedApprovedQuantity(stockCase, request == null ? null : request.getApprovedQuantity());
    }

    private NonMovingStockDecisionType decisionType(NonMovingStockDecisionCreateRequest request) {
        if (request != null && request.getLines() != null && !request.getLines().isEmpty()) {
            List<NonMovingStockDecisionType> lineTypes = request.getLines().stream()
                .map(com.enterprise.mm.payload.NonMovingStockDecisionLineRequest::getDecisionType)
                .map(this::decisionType)
                .distinct()
                .toList();
            return lineTypes.size() == 1 ? lineTypes.get(0) : NonMovingStockDecisionType.MIXED;
        }
        return decisionType(request == null ? null : request.getDecisionType());
    }

    private NonMovingStockDecisionType decisionType(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Decision type is required");
        }
        return NonMovingStockDecisionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private LocalDateTime lastMovementAt(NonMovingStock detection) {
        return stockMovementRepository
            .findFirstByTenantIdAndCompanyIdAndMaterial_IdAndWarehouse_IdAndStatusInAndIsDeletedFalseOrderByPostedAtDesc(
                TenantContext.currentTenant(),
                detection.getCompanyId(),
                detection.getMaterialId(),
                detection.getWarehouseId(),
                StockMovementStatus.effectiveStatusNames()
            )
            .map(movement -> movement.getPostedAt() == null ? movement.getUpdatedAt() : movement.getPostedAt())
            .orElse(detection.getDetectedAt());
    }

    private int bucket(Integer months) {
        int value = months == null ? 0 : months;
        if (value >= 24) {
            return 24;
        }
        if (value >= 12) {
            return 12;
        }
        if (value >= 6) {
            return 6;
        }
        if (value >= 3) {
            return 3;
        }
        return 0;
    }

    private BigDecimal unitCost(NonMovingStockCaseLine line) {
        BigDecimal quantity = nvl(line.getQuantity());
        if (quantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return nvl(line.getBookValue()).divide(quantity, 4, RoundingMode.HALF_UP);
    }

    private NonMovingStockStatus terminalDetectionStatus(NonMovingStockDecisionType type) {
        return switch (type) {
            case TRANSFER -> NonMovingStockStatus.TRANSFER;
            case SCRAP -> NonMovingStockStatus.SCRAP;
            case IMPAIRMENT_RESERVE -> NonMovingStockStatus.IMPAIRMENT_RESERVE;
            default -> NonMovingStockStatus.WRITE_OFF;
        };
    }

    private String integrationEventType(NonMovingStockDecisionType type) {
        return switch (type) {
            case TRANSFER -> "NON_MOVING_STOCK_TRANSFER";
            case SCRAP -> "NON_MOVING_STOCK_SCRAP";
            case IMPAIRMENT_RESERVE -> "NON_MOVING_STOCK_IMPAIRMENT_RESERVE";
            default -> "NON_MOVING_STOCK_WRITE_OFF";
        };
    }

    private boolean isEligibleForCaseCreation(String status) {
        return NonMovingStockStatus.DETECTED.name().equals(status)
            || NonMovingStockStatus.UNDER_REVIEW.name().equals(status);
    }

    private List<NonMovingStockDecisionType> supportedDecisionTypes() {
        return List.of(
            NonMovingStockDecisionType.MIXED,
            NonMovingStockDecisionType.TRANSFER,
            NonMovingStockDecisionType.WRITE_OFF,
            NonMovingStockDecisionType.SCRAP,
            NonMovingStockDecisionType.IMPAIRMENT_RESERVE,
            NonMovingStockDecisionType.KEEP_WITH_REVIEW_DATE
        );
    }

    private List<NonMovingStock> eligibleDetections(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("Detected non-moving stock ids are required");
        }
        LinkedHashSet<UUID> distinctIds = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id == null) {
                throw new BusinessException("Detected non-moving stock ids must not contain null");
            }
            if (!distinctIds.add(id)) {
                throw new BusinessException(
                    "Detected non-moving stock ids must be distinct; duplicate: " + id
                );
            }
        }
        List<NonMovingStock> detections = distinctIds.stream().map(this::findDetectionForCaseCreation).toList();
        List<NonMovingStock> ineligible = detections.stream()
            .filter(detection -> !isEligibleForCaseCreation(detection.getStatus()))
            .toList();
        if (!ineligible.isEmpty()) {
            String details = ineligible.stream()
                .map(detection -> detection.getId() + "=" + detection.getStatus())
                .collect(java.util.stream.Collectors.joining(", "));
            throw new BusinessException(
                "Selected non-moving stock is not eligible for case creation; "
                    + "required status DETECTED or UNDER_REVIEW: " + details
            );
        }
        detections.forEach(this::requireNoActiveCaseMembership);
        return detections;
    }

    private NonMovingStock findDetectionForCaseCreation(UUID id) {
        NonMovingStock detection = nonMovingStockRepository.findByIdAndIsDeletedFalseForUpdate(id)
            .orElseThrow(() -> new NotFoundException("NonMovingStock not found: " + id));
        if (!TenantContext.currentTenant().equals(detection.getTenantId())) {
            throw new NotFoundException("NonMovingStock not found: " + id);
        }
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(detection.getCompanyId())) {
            throw new NotFoundException("NonMovingStock not found: " + id);
        }
        return detection;
    }

    private void requireNoActiveCaseMembership(NonMovingStock detection) {
        lineRepository.findByTenantIdAndNonMovingStockIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), detection.getId()
            ).stream()
            .filter(NonMovingStockCaseLine::isActiveMembership)
            .findFirst()
            .ifPresent(line -> {
                throw new BusinessException(
                    "Detected non-moving stock " + detection.getId()
                        + " already belongs to active case " + line.getCaseId()
                );
            });
    }

    private NonMovingStockDecisionResponse approveDecisionDirectly(
        NonMovingStockCase stockCase,
        NonMovingStockDecisionCreateRequest request
    ) {
        NonMovingStockDecisionType type = decisionType(request);
        bindAndValidateCommission(stockCase, request);
        validateDecision(stockCase, type, request);
        NonMovingStockDecision saved = persistDecision(stockCase, request, type, NonMovingStockCaseStatus.DECISION_APPROVED.name());
        stockCase.setStatus(NonMovingStockCaseStatus.DECISION_APPROVED.name());
        caseRepository.save(stockCase);
        auditService.log(ENTITY_NAME, stockCase.getId(), "DECISION_APPROVED_DIRECT", type.name(), null, null);
        return decisionResponse(saved);
    }

    private NonMovingStockDecision persistDecision(
        NonMovingStockCase stockCase,
        NonMovingStockDecisionCreateRequest request,
        NonMovingStockDecisionType type,
        String status
    ) {
        List<NonMovingStockDecision> existing = decisionRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId());
        NonMovingStockDecision decision = new NonMovingStockDecision();
        decision.setCompanyId(stockCase.getCompanyId());
        decision.setCaseId(stockCase.getId());
        decision.setDecisionType(type.name());
        decision.setApprovedQuantity(normalizedApprovedQuantity(stockCase, request));
        decision.setTargetWarehouseId(request.getTargetWarehouseId());
        decision.setSalePrice(request.getSalePrice());
        decision.setImpairmentReserveAmount(nvl(request.getImpairmentReserveAmount()));
        decision.setWriteOffReason(trim(request.getWriteOffReason()));
        decision.setComment(trim(request.getComment()));
        decision.setNextReviewDate(request.getNextReviewDate());
        decision.setExpectedRecoveryAmount(request.getExpectedRecoveryAmount());
        decision.setExpectedRecoveryCurrencyCode(normalizeCurrency(request.getExpectedRecoveryCurrencyCode()));
        decision.setRecoveryBasisReference(trim(request.getRecoveryBasisReference()));
        decision.setRecoveryAllocationBasis(normalizeAllocationBasis(request.getRecoveryAllocationBasis()));
        decision.setCode("NMS-DEC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        decision.setName(BusinessNameGenerator.workflowDocumentName("NonMovingStockDecision", stockCase.getName()));
        decision.setStatus(status);
        decision.setDecisionVersion(existing.stream()
            .map(NonMovingStockDecision::getDecisionVersion)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0) + 1);
        existing.stream()
            .max(Comparator.comparing(NonMovingStockDecision::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(NonMovingStockDecision::getId)
            .ifPresent(decision::setSupersedesDecisionId);
        NonMovingStockDecision saved = decisionRepository.save(decision);
        List<NonMovingStockCaseLine> caseLines = lineRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), stockCase.getId());
        decisionService.persistLines(saved, caseLines, request);
        return decisionService.markSubmitted(saved);
    }

    private void validateRecoveryAssumptions(NonMovingStockDecisionCreateRequest request) {
        boolean hasRecoveryMetadata = request.getExpectedRecoveryAmount() != null
            || !isBlank(request.getExpectedRecoveryCurrencyCode())
            || !isBlank(request.getRecoveryBasisReference())
            || !isBlank(request.getRecoveryAllocationBasis());
        if (!hasRecoveryMetadata) {
            return;
        }
        if (request.getExpectedRecoveryAmount() == null || request.getExpectedRecoveryAmount().signum() < 0) {
            throw new BusinessException("Expected recovery amount must be zero or greater when recovery metadata is provided");
        }
        if (isBlank(request.getExpectedRecoveryCurrencyCode()) || request.getExpectedRecoveryCurrencyCode().trim().length() != 3) {
            throw new BusinessException("Expected recovery currency code must be a 3-letter ISO code");
        }
        if (isBlank(request.getRecoveryBasisReference())) {
            throw new BusinessException("Recovery basis reference is required when recovery metadata is provided");
        }
    }

    private NonMovingStockCaseResponse transition(
        UUID caseId,
        long expectedVersion,
        NonMovingStockCaseStatus target,
        String reason,
        LocalDate nextReviewDate,
        String auditAction
    ) {
        NonMovingStockCase stockCase = findCase(caseId);
        String previousStatus = stockCase.getStatus();
        lifecycle.apply(stockCase, target, expectedVersion, reason, nextReviewDate, currentUsername());
        NonMovingStockCase saved = caseRepository.save(stockCase);
        auditService.log(
            ENTITY_NAME,
            saved.getId(),
            auditAction,
            previousStatus + " -> " + target.name() + "; reason=" + (reason == null ? "" : reason.trim())
        , null, null);
        notificationService.notifyOwner(saved, auditAction, reason);
        return response(saved);
    }

    private void advanceToApprovalPending(NonMovingStockCase stockCase) {
        NonMovingStockCaseStatus status = lifecycle.status(stockCase.getStatus());
        long version = currentVersion(stockCase);
        if (status == NonMovingStockCaseStatus.CASE_CREATED) {
            lifecycle.apply(stockCase, NonMovingStockCaseStatus.UNDER_REVIEW, version, "Compatibility review start", null, currentUsername());
            status = NonMovingStockCaseStatus.UNDER_REVIEW;
        }
        if (status == NonMovingStockCaseStatus.UNDER_REVIEW) {
            lifecycle.apply(stockCase, NonMovingStockCaseStatus.COMMISSION_REVIEW, version, "Compatibility commission submission", null, currentUsername());
            status = NonMovingStockCaseStatus.COMMISSION_REVIEW;
        }
        if (status != NonMovingStockCaseStatus.COMMISSION_REVIEW) {
            throw new BusinessException("Decision can only be submitted from commission review");
        }
        lifecycle.apply(stockCase, NonMovingStockCaseStatus.APPROVAL_PENDING, version, "Decision submitted", null, currentUsername());
    }

    private void setActiveMembership(UUID caseId, boolean active) {
        List<NonMovingStockCaseLine> lines = lineRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), caseId);
        lines.forEach(line -> line.setActiveMembership(active));
        lineRepository.saveAll(lines);
    }

    private void requireVersion(NonMovingStockCase stockCase, long expectedVersion) {
        long actualVersion = currentVersion(stockCase);
        if (actualVersion != expectedVersion) {
            throw new BusinessException("Non-moving stock case version conflict: expected " + expectedVersion + " but was " + actualVersion);
        }
    }

    private long currentVersion(NonMovingStockCase stockCase) {
        return stockCase.getVersion() == null ? 0L : stockCase.getVersion();
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    private void bindAndValidateCommission(NonMovingStockCase stockCase, NonMovingStockDecisionCreateRequest request) {
        UUID requestedCommissionId = request == null ? null : request.getCommissionId();
        String requestedProtocolNo = request == null ? null : request.getCommissionProtocolNo();
        if (stockCase.getCommissionId() == null) {
            if (requestedCommissionId == null) {
                throw new BusinessException("commissionId is required before decision approval");
            }
            stockCase.setCommissionId(requireCaseCommission(stockCase.getCompanyId(), requestedCommissionId).getId());
        } else {
            requireCaseCommission(stockCase.getCompanyId(), stockCase.getCommissionId());
            if (requestedCommissionId != null && !stockCase.getCommissionId().equals(requestedCommissionId)) {
                throw new BusinessException("Decision commissionId does not match case commissionId");
            }
        }
        if (!isBlank(requestedProtocolNo)) {
            stockCase.setCommissionProtocolNo(trim(requestedProtocolNo));
        }
    }

    private com.enterprise.mm.entity.Commission requireCaseCommission(UUID companyId, UUID commissionId) {
        com.enterprise.mm.entity.Commission commission = nonMovingStockCommissionService.requireNonMovingStockCommission(commissionId);
        if (!Objects.equals(companyId, commission.getCompanyId())) {
            throw new BusinessException("Commission belongs to a different company: " + commissionId);
        }
        return commission;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeCurrency(String value) {
        return isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String searchPattern(String value) {
        return isBlank(value) ? "" : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private String normalizeAllocationBasis(String value) {
        return isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nvlText(String primary, String fallback) {
        String value = primary == null || primary.isBlank() ? fallback : primary;
        return value == null || value.isBlank() ? "impairment reserve approved" : value.trim();
    }

    private UUID stockCasePrimaryWarehouseId(
        NonMovingStockCase stockCase,
        List<NonMovingStockCaseLine> lines
    ) {
        return lines.stream()
            .map(NonMovingStockCaseLine::getWarehouseId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new BusinessException("Case warehouse is required for transfer: " + stockCase.getCaseNo()));
    }

    private String splitDetectionCode(String baseCode, NonMovingStockStatus status) {
        String safeBaseCode = (baseCode == null || baseCode.isBlank()) ? "NMS" : baseCode.trim();
        String suffix = status.name() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String value = safeBaseCode + "-" + suffix;
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private String splitDetectionName(String baseName, NonMovingStockStatus status) {
        String safeBaseName = (baseName == null || baseName.isBlank()) ? "Non-moving stock" : baseName.trim();
        String value = safeBaseName + " (" + status.name() + ")";
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    private record StockExecutionResult(
        UUID firstMovementId,
        Map<UUID, BigDecimal> executedQuantityByDetection
    ) {}
}
