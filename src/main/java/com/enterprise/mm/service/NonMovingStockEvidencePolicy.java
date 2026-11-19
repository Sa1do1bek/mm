package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.NonMovingStockDecisionLine;
import com.enterprise.mm.entity.NonMovingStockExecution;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.enums.NonMovingStockExecutionOutputStatus;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionOutputRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NonMovingStockEvidencePolicy {
    private static final String CASE_ATTACHMENT_TYPE = "NON_MOVING_STOCK_CASE";
    private static final String CASE_AGGREGATE_TYPE = "NonMovingStockCase";
    private static final Set<String> PENDING_INTEGRATION_STATUSES = Set.of(
        IntegrationEventStatus.NEW.name(), IntegrationEventStatus.RETRY.name()
    );
    private final AttachmentRepository attachmentRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final NonMovingStockDecisionRepository decisionRepository;
    private final NonMovingStockDecisionLineRepository decisionLineRepository;
    private final NonMovingStockExecutionRepository executionRepository;
    private final NonMovingStockExecutionOutputRepository outputRepository;



    @Transactional(readOnly = true)
    public List<Blocker> closureBlockers(NonMovingStockCase stockCase) {
        return closureReadiness(stockCase).blockers();
    }

    @Transactional(readOnly = true)
    public ClosureReadiness closureReadiness(NonMovingStockCase stockCase) {
        List<Blocker> blockers = new ArrayList<>();
        List<EvidenceRequirement> requirements = new ArrayList<>();
        if (stockCase == null || stockCase.getId() == null) {
            List<Blocker> missingCase = List.of(new Blocker("CASE_REQUIRED", "A persisted case is required for closure"));
            return new ClosureReadiness(false, missingCase, List.of());
        }
        if (!"EXECUTED".equals(stockCase.getStatus())) {
            blockers.add(new Blocker("CASE_NOT_EXECUTED", "Only an executed case can be closed"));
        }
        if (stockCase.getBlockerType() != null && !stockCase.getBlockerType().isBlank()) {
            blockers.add(new Blocker("UNRESOLVED_RECONCILIATION", stockCase.getBlockerType() + ": " + stockCase.getBlockerDetails()));
        }
        String tenant = stockCase.getTenantId() == null ? TenantContext.currentTenant() : stockCase.getTenantId();
        List<NonMovingStockExecution> executions = executionRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(tenant, stockCase.getId());
        NonMovingStockExecution authoritativeExecution = executions.isEmpty() ? null : executions.get(executions.size() - 1);
        if (authoritativeExecution == null) {
            blockers.add(new Blocker("EXECUTION_REQUIRED", "An authoritative execution is required before closure"));
        } else {
            var outputs = outputRepository
                .findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
                    tenant, authoritativeExecution.getId()
                );
            if (!"EXECUTED".equals(authoritativeExecution.getExecutionStatus())) {
                blockers.add(new Blocker("EXECUTION_NOT_COMPLETED", "The authoritative execution is not completed"));
            }
            if (outputs.isEmpty()) {
                blockers.add(new Blocker(
                    "EXECUTION_OUTPUT_REQUIRED",
                    "The authoritative execution must contain at least one output"
                ));
            } else if (outputs.stream().anyMatch(output ->
                !NonMovingStockExecutionOutputStatus.POSTED.name().equals(output.getOutputStatus()))) {
                blockers.add(new Blocker("UNFINISHED_OUTPUT", "All authoritative execution outputs must be posted"));
            }
        }
        List<IntegrationEvent> integrationEvents = integrationEventRepository
            .findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenant, stockCase.getCompanyId(), CASE_AGGREGATE_TYPE, stockCase.getId()
            );
        evaluateIntegrationReadiness(blockers, authoritativeExecution, integrationEvents);
        Set<String> decisionTypes = decisionTypes(tenant, stockCase.getId(), authoritativeExecution);
        List<Attachment> evidence = attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenant, stockCase.getCompanyId(), CASE_ATTACHMENT_TYPE, stockCase.getId()
            );
        Set<String> evidenceTypes = evidence.stream().map(Attachment::getAttachmentType)
            .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        evaluateEvidenceRequirement(
            blockers, requirements, decisionTypes, evidenceTypes, "WRITE_OFF_EVIDENCE",
            List.of("WRITE_OFF", "SCRAP"),
            List.of("DISPOSAL_ACT", "WRITE_OFF_ACT", "COMMISSION_PROTOCOL"),
            "Required write-off/disposal act is not attached"
        );
        evaluateEvidenceRequirement(
            blockers, requirements, decisionTypes, evidenceTypes, "TRANSFER_EVIDENCE",
            List.of("TRANSFER"), List.of("TRANSFER_CONFIRMATION", "STOCK_MOVEMENT_DOCUMENT"),
            "Required transfer confirmation is not attached"
        );
        evaluateEvidenceRequirement(
            blockers, requirements, decisionTypes, evidenceTypes, "IMPAIRMENT_EVIDENCE",
            List.of("IMPAIRMENT_RESERVE"), List.of("VALUATION_EVIDENCE", "ACCOUNTING_POSTING"),
            "Required valuation/accounting evidence is not attached"
        );
        List<Blocker> result = List.copyOf(blockers);
        return new ClosureReadiness(result.isEmpty(), result, List.copyOf(requirements));
    }

    private Set<String> decisionTypes(
        String tenant,
        java.util.UUID caseId,
        NonMovingStockExecution authoritativeExecution
    ) {
        List<NonMovingStockDecision> decisions = decisionRepository
            .findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(tenant, caseId);
        NonMovingStockDecision decision = authoritativeExecution == null
            || authoritativeExecution.getDecisionId() == null
            ? (decisions.isEmpty() ? null : decisions.get(decisions.size() - 1))
            : decisions.stream()
                .filter(candidate -> java.util.Objects.equals(
                    candidate.getId(), authoritativeExecution.getDecisionId()
                ))
                .findFirst()
                .orElse(null);
        Set<String> types = new HashSet<>();
        if (decision != null) {
            if (decision.getDecisionType() != null) {
                types.add(decision.getDecisionType());
            }
            decisionLineRepository.findByTenantIdAndDecisionIdAndIsDeletedFalseOrderByLineOrderAsc(
                tenant, decision.getId()
            ).stream().map(NonMovingStockDecisionLine::getDecisionType)
                .filter(java.util.Objects::nonNull).forEach(types::add);
        }
        return types;
    }

    private void evaluateIntegrationReadiness(
        List<Blocker> blockers,
        NonMovingStockExecution authoritativeExecution,
        List<IntegrationEvent> integrationEvents
    ) {
        if (authoritativeExecution == null || authoritativeExecution.getIntegrationEventId() == null) {
            return;
        }
        Set<java.util.UUID> chainIds = new HashSet<>();
        chainIds.add(authoritativeExecution.getIntegrationEventId());
        boolean expanded;
        do {
            expanded = false;
            for (IntegrationEvent event : integrationEvents) {
                if (event.getId() != null && event.getReplayOfEventId() != null
                    && chainIds.contains(event.getReplayOfEventId()) && chainIds.add(event.getId())) {
                    expanded = true;
                }
            }
        } while (expanded);
        List<IntegrationEvent> chain = integrationEvents.stream()
            .filter(event -> event.getId() != null && chainIds.contains(event.getId()))
            .toList();
        if (chain.stream().anyMatch(event ->
            IntegrationEventStatus.PUBLISHED.name().equals(event.getStatus()))) {
            return;
        }
        if (chain.isEmpty()) {
            blockers.add(new Blocker(
                "INTEGRATION_EVENT_REQUIRED",
                "The authoritative integration event is missing"
            ));
            return;
        }
        if (chain.stream().anyMatch(event -> PENDING_INTEGRATION_STATUSES.contains(event.getStatus()))) {
            blockers.add(new Blocker(
                "INTEGRATION_PENDING",
                "Required integration delivery is still pending"
            ));
            return;
        }
        blockers.add(new Blocker(
            "FAILED_INTEGRATION",
            "Required integration delivery failed and has no successful replay"
        ));
    }

    private void evaluateEvidenceRequirement(
        List<Blocker> blockers,
        List<EvidenceRequirement> requirements,
        Set<String> decisionTypes,
        Set<String> evidenceTypes,
        String code,
        List<String> controlledTypes,
        List<String> acceptedEvidence,
        String message
    ) {
        if (decisionTypes.stream().noneMatch(controlledTypes::contains)) {
            return;
        }
        boolean satisfied = evidenceTypes.stream().anyMatch(acceptedEvidence::contains);
        requirements.add(new EvidenceRequirement(
            code, List.copyOf(controlledTypes), List.copyOf(acceptedEvidence), satisfied, message
        ));
        if (!satisfied) {
            blockers.add(new Blocker("MISSING_EVIDENCE", message));
        }
    }

    public record ClosureReadiness(
        boolean closureReady,
        List<Blocker> blockers,
        List<EvidenceRequirement> evidenceRequirements
    ) {
    }

    public record EvidenceRequirement(
        String code,
        List<String> decisionTypes,
        List<String> acceptedTypes,
        boolean satisfied,
        String message
    ) {
    }

    public record Blocker(String code, String message) {
    }
}
