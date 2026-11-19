package com.enterprise.mm;

import static com.enterprise.mm.enums.ManagementOpportunityConfidence.HIGH;
import static com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE;
import static com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.DATA_INCOMPLETE;
import static com.enterprise.mm.enums.ManagementOpportunityStatus.APPROVED;
import static com.enterprise.mm.enums.ManagementOpportunityStatus.DRAFT;
import static com.enterprise.mm.enums.ManagementOpportunityStatus.READY_FOR_REVIEW;
import static com.enterprise.mm.enums.ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.entity.ManagementOpportunityCalculationRun;
import com.enterprise.mm.entity.ManagementOpportunityEvidence;
import com.enterprise.mm.enums.ManagementOpportunityEvidenceSourceEntityType;
import com.enterprise.mm.mapper.ManagementOpportunityCalculationRunMapper;
import com.enterprise.mm.payload.ManagementOpportunityCalculationRunResponse;
import com.enterprise.mm.payload.ManagementOpportunityCalculationStartRequest;
import com.enterprise.mm.repository.ManagementOpportunityCalculationRunRepository;
import com.enterprise.mm.repository.ManagementOpportunityEvidenceRepository;
import com.enterprise.mm.repository.ManagementOpportunityRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ManagementOpportunityCalculatedCandidate;
import com.enterprise.mm.service.ManagementOpportunityCalculationService;
import com.enterprise.mm.service.ManagementOpportunityEvidenceDescriptor;
import com.enterprise.mm.service.ManagementOpportunityRunService;
import com.enterprise.mm.service.approval.ManagementOpportunityApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class ManagementOpportunityWorkflowTest {
    @Mock ManagementOpportunityCalculationService calculationService;
    @Mock ManagementOpportunityCalculationRunRepository runRepository;
    @Mock ManagementOpportunityRepository opportunityRepository;
    @Mock ManagementOpportunityEvidenceRepository evidenceRepository;
    @Mock ManagementOpportunityCalculationRunMapper runMapper;
    @Mock AuditService auditService;
    @Mock PlatformTransactionManager transactionManager;

    private final UUID companyId = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("tenant-mo-workflow");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void runPersistsCompleteCandidateAndEvidence() {
        ManagementOpportunityRunService service = new ManagementOpportunityRunService(
            calculationService,
            runRepository,
            opportunityRepository,
            evidenceRepository,
            runMapper,
            auditService,
            transactionManager
        );
        LocalDate asOfDate = LocalDate.of(2026, 7, 12);
        ManagementOpportunityCalculationStartRequest request = new ManagementOpportunityCalculationStartRequest();
        request.setAsOfDate(asOfDate);
        request.setTypes(List.of(PROCUREMENT_PRICE_VARIANCE));
        request.setTargetCurrencyCode("UZS");

        var candidate = candidate(asOfDate);
        when(calculationService.calculate(asOfDate, request.getTypes(), "UZS")).thenReturn(List.of(candidate));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            ManagementOpportunityCalculationRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID());
            }
            return run;
        });
        when(opportunityRepository.save(any())).thenAnswer(invocation -> {
            ManagementOpportunity opportunity = invocation.getArgument(0);
            if (opportunity.getId() == null) {
                opportunity.setId(UUID.randomUUID());
            }
            return opportunity;
        });
        when(opportunityRepository.findActiveDuplicates(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(List.of());
        when(runMapper.toResponse(any())).thenReturn(new ManagementOpportunityCalculationRunResponse());

        service.start(request);

        ArgumentCaptor<ManagementOpportunity> opportunityCaptor = ArgumentCaptor.forClass(ManagementOpportunity.class);
        verify(opportunityRepository).save(opportunityCaptor.capture());
        assertThat(opportunityCaptor.getValue().getStatus()).isEqualTo(DRAFT.name());
        assertThat(opportunityCaptor.getValue().getNormalizedAmount()).isEqualByComparingTo("180.0000");

        ArgumentCaptor<ManagementOpportunityEvidence> evidenceCaptor = ArgumentCaptor.forClass(ManagementOpportunityEvidence.class);
        verify(evidenceRepository).save(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue().getEvidenceType()).isEqualTo("PO_ITEM");
    }

    @Test
    void runPersistsIncompleteCandidateForTheDataQualityWorklist() {
        ManagementOpportunityRunService service = new ManagementOpportunityRunService(
            calculationService,
            runRepository,
            opportunityRepository,
            evidenceRepository,
            runMapper,
            auditService,
            transactionManager
        );
        LocalDate asOfDate = LocalDate.of(2026, 7, 12);
        ManagementOpportunityCalculationStartRequest request = new ManagementOpportunityCalculationStartRequest();
        request.setAsOfDate(asOfDate);
        request.setTypes(List.of(PROCUREMENT_PRICE_VARIANCE));
        request.setTargetCurrencyCode("UZS");

        ManagementOpportunityCalculatedCandidate incomplete = new ManagementOpportunityCalculatedCandidate(
            PROCUREMENT_PRICE_VARIANCE,
            null,
            "PurchaseOrder",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            asOfDate,
            "USD",
            null,
            new BigDecimal("125.0000"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "DATA_INCOMPLETE",
            "PROCUREMENT_PRICE_VARIANCE_V1",
            "{\"skipReason\":\"Missing exchange rate\"}",
            HIGH,
            DATA_INCOMPLETE,
            "Missing exchange rate",
            List.of()
        );
        when(calculationService.calculate(asOfDate, request.getTypes(), "UZS")).thenReturn(List.of(incomplete));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            ManagementOpportunityCalculationRun run = invocation.getArgument(0);
            if (run.getId() == null) run.setId(UUID.randomUUID());
            return run;
        });
        when(opportunityRepository.save(any())).thenAnswer(invocation -> {
            ManagementOpportunity opportunity = invocation.getArgument(0);
            if (opportunity.getId() == null) opportunity.setId(UUID.randomUUID());
            return opportunity;
        });
        when(opportunityRepository.findActiveDuplicates(
            any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(List.of());
        when(runMapper.toResponse(any())).thenReturn(new ManagementOpportunityCalculationRunResponse());

        service.start(request);

        ArgumentCaptor<ManagementOpportunity> opportunityCaptor = ArgumentCaptor.forClass(ManagementOpportunity.class);
        verify(opportunityRepository).save(opportunityCaptor.capture());
        assertThat(opportunityCaptor.getValue().getStatus()).isEqualTo(DRAFT.name());
        assertThat(opportunityCaptor.getValue().getDataCompleteness()).isEqualTo(DATA_INCOMPLETE);
        assertThat(opportunityCaptor.getValue().getNormalizedAmount()).isNull();
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void approvalAdapterFinalizesManagementOpportunity() {
        UUID id = UUID.randomUUID();
        ManagementOpportunity opportunity = new ManagementOpportunity();
        opportunity.setId(id);
        opportunity.setTenantId(TenantContext.currentTenant());
        opportunity.setCompanyId(companyId);
        opportunity.setCode("MO-001");
        opportunity.setName("Management estimate");
        opportunity.setStatus(READY_FOR_REVIEW.name());
        opportunity.setOpportunityType(PROCUREMENT_PRICE_VARIANCE);

        when(opportunityRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(opportunity));

        ManagementOpportunityApprovalAdapter adapter = new ManagementOpportunityApprovalAdapter(opportunityRepository, auditService);
        adapter.markApproved(id, "approved");

        assertThat(opportunity.getStatus()).isEqualTo(APPROVED.name());
        assertThat(opportunity.getReviewComment()).isEqualTo("approved");
        verify(opportunityRepository).save(opportunity);
    }

    private ManagementOpportunityCalculatedCandidate candidate(LocalDate asOfDate) {
        return new ManagementOpportunityCalculatedCandidate(
            PROCUREMENT_PRICE_VARIANCE,
            UUID.randomUUID(),
            "PurchaseOrder",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            asOfDate,
            "UZS",
            "UZS",
            new BigDecimal("180.0000"),
            new BigDecimal("180.0000"),
            new BigDecimal("100.0000"),
            new BigDecimal("120.0000"),
            new BigDecimal("9.0000"),
            BigDecimal.ONE,
            null,
            null,
            "SAME_CURRENCY",
            "COMPLETE",
            "PROCUREMENT_PRICE_VARIANCE_V1",
            "{\"formula\":\"test\"}",
            HIGH,
            COMPLETE,
            null,
            List.of(new ManagementOpportunityEvidenceDescriptor(
                "PO_ITEM",
                ManagementOpportunityEvidenceSourceEntityType.PURCHASE_ORDER_ITEM,
                UUID.randomUUID(),
                "{\"acceptedQuantity\":9}"
            ))
        );
    }
}
