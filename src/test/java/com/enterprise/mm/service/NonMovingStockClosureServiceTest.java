package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.NonMovingStockExecution;
import com.enterprise.mm.entity.NonMovingStockExecutionOutput;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionOutputRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NonMovingStockClosureServiceTest {
    @Mock AttachmentRepository attachmentRepository;
    @Mock IntegrationEventRepository integrationEventRepository;
    @Mock NonMovingStockDecisionRepository decisionRepository;
    @Mock NonMovingStockDecisionLineRepository decisionLineRepository;
    @Mock NonMovingStockExecutionRepository executionRepository;
    @Mock NonMovingStockExecutionOutputRepository outputRepository;

    @Test
    void missingEvidenceAndPendingOutputBlockClosure() {
        UUID caseId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setId(caseId);
        stockCase.setTenantId("default");
        stockCase.setCompanyId(companyId);
        stockCase.setStatus("EXECUTED");
        NonMovingStockDecision decision = new NonMovingStockDecision();
        decision.setId(UUID.randomUUID());
        decision.setDecisionType("WRITE_OFF");
        NonMovingStockExecution execution = new NonMovingStockExecution();
        execution.setId(UUID.randomUUID());
        NonMovingStockExecutionOutput output = new NonMovingStockExecutionOutput();
        output.setOutputStatus("PENDING");
        when(decisionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc("default", caseId))
            .thenReturn(List.of(decision));
        when(executionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc("default", caseId))
            .thenReturn(List.of(execution));
        when(outputRepository.findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc("default", execution.getId()))
            .thenReturn(List.of(output));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", companyId, "NON_MOVING_STOCK_CASE", caseId
        )).thenReturn(List.of());
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", companyId, "NonMovingStockCase", caseId
        )).thenReturn(List.of());

        NonMovingStockEvidencePolicy policy = new NonMovingStockEvidencePolicy(
            attachmentRepository, integrationEventRepository, decisionRepository,
            decisionLineRepository, executionRepository, outputRepository
        );

        NonMovingStockEvidencePolicy.ClosureReadiness readiness = policy.closureReadiness(stockCase);

        assertThat(readiness.closureReady()).isFalse();
        assertThat(readiness.blockers())
            .extracting(NonMovingStockEvidencePolicy.Blocker::code)
            .contains("MISSING_EVIDENCE", "UNFINISHED_OUTPUT");
        assertThat(readiness.evidenceRequirements()).singleElement().satisfies(requirement -> {
            assertThat(requirement.code()).isEqualTo("WRITE_OFF_EVIDENCE");
            assertThat(requirement.acceptedTypes())
                .containsExactlyInAnyOrder("DISPOSAL_ACT", "WRITE_OFF_ACT", "COMMISSION_PROTOCOL");
            assertThat(requirement.satisfied()).isFalse();
        });
    }

    @Test
    void latestExecutionAndItsDecisionAreAuthoritativeForClosure() {
        NonMovingStockCase stockCase = executedCase();
        NonMovingStockDecision rejectedWriteOff = decision("WRITE_OFF");
        rejectedWriteOff.setStatus("REJECTED");
        NonMovingStockDecision executedTransfer = decision("TRANSFER");
        executedTransfer.setStatus("DECISION_APPROVED");
        UUID historicalEventId = UUID.randomUUID();
        NonMovingStockExecution failedAttempt = execution(rejectedWriteOff, "FAILED", historicalEventId);
        NonMovingStockExecution successfulAttempt = execution(executedTransfer, "EXECUTED", UUID.randomUUID());
        NonMovingStockExecutionOutput postedOutput = output("STOCK_MOVEMENT_DOCUMENT", "POSTED");
        Attachment transferConfirmation = attachment("TRANSFER_CONFIRMATION");
        IntegrationEvent published = integrationEvent(successfulAttempt.getIntegrationEventId(), "PUBLISHED", null);

        when(decisionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", stockCase.getId()
        )).thenReturn(List.of(rejectedWriteOff, executedTransfer));
        when(executionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", stockCase.getId()
        )).thenReturn(List.of(failedAttempt, successfulAttempt));
        when(outputRepository.findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            eq("default"), any(UUID.class)
        )).thenAnswer(invocation -> successfulAttempt.getId().equals(invocation.getArgument(1))
            ? List.of(postedOutput)
            : List.of());
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", stockCase.getCompanyId(), "NON_MOVING_STOCK_CASE", stockCase.getId()
        )).thenReturn(List.of(transferConfirmation));
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", stockCase.getCompanyId(), "NonMovingStockCase", stockCase.getId()
        )).thenReturn(List.of(
            published,
            integrationEvent(historicalEventId, "FAILED", null)
        ));

        NonMovingStockEvidencePolicy.ClosureReadiness readiness = policy().closureReadiness(stockCase);

        assertThat(readiness.closureReady()).isTrue();
        assertThat(readiness.blockers()).isEmpty();
        assertThat(readiness.evidenceRequirements())
            .extracting(NonMovingStockEvidencePolicy.EvidenceRequirement::code)
            .containsExactly("TRANSFER_EVIDENCE");
    }

    @Test
    void authoritativeExecutionMustHaveAtLeastOneOutput() {
        NonMovingStockCase stockCase = executedCase();
        NonMovingStockDecision transfer = decision("TRANSFER");
        NonMovingStockExecution execution = execution(transfer, "EXECUTED", null);

        when(decisionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", stockCase.getId()
        )).thenReturn(List.of(transfer));
        when(executionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", stockCase.getId()
        )).thenReturn(List.of(execution));
        when(outputRepository.findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", execution.getId()
        )).thenReturn(List.of());
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", stockCase.getCompanyId(), "NON_MOVING_STOCK_CASE", stockCase.getId()
        )).thenReturn(List.of(attachment("TRANSFER_CONFIRMATION")));
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", stockCase.getCompanyId(), "NonMovingStockCase", stockCase.getId()
        )).thenReturn(List.of());

        assertThat(policy().closureBlockers(stockCase))
            .extracting(NonMovingStockEvidencePolicy.Blocker::code)
            .contains("EXECUTION_OUTPUT_REQUIRED");
    }

    @Test
    void deadAuthoritativeIntegrationWithoutSuccessfulReplayBlocksClosure() {
        NonMovingStockCase stockCase = executedCase();
        NonMovingStockDecision transfer = decision("TRANSFER");
        UUID integrationEventId = UUID.randomUUID();
        NonMovingStockExecution execution = execution(transfer, "EXECUTED", integrationEventId);

        arrangeReadyTransfer(stockCase, transfer, execution, List.of(
            integrationEvent(UUID.randomUUID(), "PUBLISHED", null),
            integrationEvent(integrationEventId, "DEAD", null)
        ));

        assertThat(policy().closureBlockers(stockCase))
            .extracting(NonMovingStockEvidencePolicy.Blocker::code)
            .contains("FAILED_INTEGRATION");
    }

    @Test
    void publishedReplayCompletesFailedAuthoritativeIntegrationChain() {
        NonMovingStockCase stockCase = executedCase();
        NonMovingStockDecision transfer = decision("TRANSFER");
        UUID integrationEventId = UUID.randomUUID();
        NonMovingStockExecution execution = execution(transfer, "EXECUTED", integrationEventId);
        IntegrationEvent failedOriginal = integrationEvent(integrationEventId, "FAILED", null);
        IntegrationEvent publishedReplay = integrationEvent(UUID.randomUUID(), "PUBLISHED", integrationEventId);

        arrangeReadyTransfer(stockCase, transfer, execution, List.of(publishedReplay, failedOriginal));

        assertThat(policy().closureReadiness(stockCase).closureReady()).isTrue();
    }

    private void arrangeReadyTransfer(
        NonMovingStockCase stockCase,
        NonMovingStockDecision transfer,
        NonMovingStockExecution execution,
        List<IntegrationEvent> integrationEvents
    ) {
        when(decisionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", stockCase.getId()
        )).thenReturn(List.of(transfer));
        when(executionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", stockCase.getId()
        )).thenReturn(List.of(execution));
        when(outputRepository.findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", execution.getId()
        )).thenReturn(List.of(output("STOCK_MOVEMENT_DOCUMENT", "POSTED")));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", stockCase.getCompanyId(), "NON_MOVING_STOCK_CASE", stockCase.getId()
        )).thenReturn(List.of(attachment("TRANSFER_CONFIRMATION")));
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", stockCase.getCompanyId(), "NonMovingStockCase", stockCase.getId()
        )).thenReturn(integrationEvents);
    }

    private NonMovingStockEvidencePolicy policy() {
        return new NonMovingStockEvidencePolicy(
            attachmentRepository, integrationEventRepository, decisionRepository,
            decisionLineRepository, executionRepository, outputRepository
        );
    }

    private NonMovingStockCase executedCase() {
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setId(UUID.randomUUID());
        stockCase.setTenantId("default");
        stockCase.setCompanyId(UUID.randomUUID());
        stockCase.setStatus("EXECUTED");
        return stockCase;
    }

    private NonMovingStockDecision decision(String type) {
        NonMovingStockDecision decision = new NonMovingStockDecision();
        decision.setId(UUID.randomUUID());
        decision.setDecisionType(type);
        return decision;
    }

    private NonMovingStockExecution execution(
        NonMovingStockDecision decision,
        String status,
        UUID integrationEventId
    ) {
        NonMovingStockExecution execution = new NonMovingStockExecution();
        execution.setId(UUID.randomUUID());
        execution.setDecisionId(decision.getId());
        execution.setExecutionStatus(status);
        execution.setIntegrationEventId(integrationEventId);
        return execution;
    }

    private NonMovingStockExecutionOutput output(String type, String status) {
        NonMovingStockExecutionOutput output = new NonMovingStockExecutionOutput();
        output.setOutputType(type);
        output.setOutputStatus(status);
        return output;
    }

    private Attachment attachment(String type) {
        Attachment attachment = new Attachment();
        attachment.setAttachmentType(type);
        return attachment;
    }

    private IntegrationEvent integrationEvent(UUID id, String status, UUID replayOfEventId) {
        IntegrationEvent event = new IntegrationEvent();
        event.setId(id);
        event.setStatus(status);
        event.setReplayOfEventId(replayOfEventId);
        return event;
    }
}
