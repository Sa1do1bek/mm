package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierAccreditationApplicationResponse;
import com.enterprise.mm.payload.SupplierAccreditationCommandDtos.DecisionCommand;
import com.enterprise.mm.payload.SupplierAccreditationCommandDtos.VersionedCommand;
import com.enterprise.mm.payload.SupplierAccreditationDecisionActionRequest;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierAccreditationCommandServiceTest {
    @Mock SupplierAccreditationService accreditationService;
    @Mock IdempotencyService idempotencyService;
    @Mock ApprovalEngineService approvalEngineService;
    @Mock com.enterprise.mm.supplier.governance.readiness.SupplierReadinessService readinessService;

    private SupplierAccreditationCommandService service;
    private UUID applicationId;

    @BeforeEach
    void setUp() {
        applicationId = UUID.randomUUID();
        TenantContext.setTenant("tenant-supplier-governance");
        CompanyContext.setCompany(UUID.randomUUID());
        service = new SupplierAccreditationCommandService(
            accreditationService,
            idempotencyService,
            approvalEngineService,
            readinessService,
            org.mockito.Mockito.mock(com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy.class)
        );
        var application = new com.enterprise.mm.entity.SupplierAccreditationApplication();
        application.setId(applicationId);
        application.setSupplierId(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(accreditationService.validateApplicationForMutation(applicationId))
            .thenReturn(application);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void allEightGovernanceCommandsUseDistinctIdempotencyScopesAndExistingAuditedMutations() {
        SupplierAccreditationApplicationResponse current = response(12L, "UNDER_REVIEW");
        SupplierAccreditationApplicationResponse changed = response(13L, "APPROVED");
        VersionedCommand versioned = new VersionedCommand(12L, "Need current evidence");
        DecisionCommand decision = new DecisionCommand(
            12L,
            "APPROVE",
            List.of(UUID.randomUUID()),
            LocalDate.now(),
            LocalDate.now().plusYears(1),
            "Commission approved"
        );

        executeIdempotently();
        when(accreditationService.get(applicationId)).thenReturn(current);
        when(accreditationService.submit(applicationId)).thenReturn(changed);
        when(accreditationService.startReview(applicationId)).thenReturn(changed);
        when(accreditationService.runComplianceCheck(applicationId)).thenReturn(changed);
        when(accreditationService.requestClarification(applicationId, versioned.reviewComment())).thenReturn(changed);
        when(accreditationService.suspend(applicationId, versioned.reviewComment())).thenReturn(changed);
        when(accreditationService.renew(applicationId)).thenReturn(changed);
        when(accreditationService.approve(eq(applicationId), any())).thenReturn(changed);
        when(accreditationService.reject(eq(applicationId), any())).thenReturn(changed);

        service.submit(applicationId, versioned, "key-submit");
        service.startReview(applicationId, versioned, "key-start");
        service.runComplianceCheck(applicationId, versioned, "key-compliance");
        service.requestClarification(applicationId, versioned, "key-clarification");
        service.approve(applicationId, decision, "key-approve");
        service.reject(applicationId, decision, "key-reject");
        service.suspend(applicationId, versioned, "key-suspend");
        service.renew(applicationId, versioned, "key-renew");

        ArgumentCaptor<String> operations = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(8)).execute(
            operations.capture(),
            eq(applicationId),
            anyString(),
            any(),
            eq(SupplierAccreditationApplicationResponse.class),
            any()
        );
        assertThat(operations.getAllValues()).containsExactly(
            "SUPPLIER_ACCREDITATION_SUBMIT",
            "SUPPLIER_ACCREDITATION_START_REVIEW",
            "SUPPLIER_ACCREDITATION_RUN_COMPLIANCE_CHECK",
            "SUPPLIER_ACCREDITATION_REQUEST_CLARIFICATION",
            "SUPPLIER_ACCREDITATION_APPROVE",
            "SUPPLIER_ACCREDITATION_REJECT",
            "SUPPLIER_ACCREDITATION_SUSPEND",
            "SUPPLIER_ACCREDITATION_RENEW"
        );
        verify(accreditationService, times(8)).get(applicationId);
        verify(accreditationService).submit(applicationId);
        verify(accreditationService).startReview(applicationId);
        verify(accreditationService).runComplianceCheck(applicationId);
        verify(accreditationService).requestClarification(applicationId, "Need current evidence");
        verify(accreditationService).suspend(applicationId, "Need current evidence");
        verify(accreditationService).renew(applicationId);

        ArgumentCaptor<SupplierAccreditationDecisionActionRequest> decisions =
            ArgumentCaptor.forClass(SupplierAccreditationDecisionActionRequest.class);
        verify(accreditationService).approve(eq(applicationId), decisions.capture());
        verify(accreditationService).reject(eq(applicationId), decisions.capture());
        assertThat(decisions.getAllValues()).allSatisfy(mapped -> {
            assertThat(mapped.decision()).isEqualTo("APPROVE");
            assertThat(mapped.allowedMaterialGroupIds()).isEqualTo(decision.allowedMaterialGroupIds());
            assertThat(mapped.reviewComment()).isEqualTo("Commission approved");
        });
    }

    @Test
    void staleExpectedVersionConflictsBeforeDomainMutation() {
        VersionedCommand command = new VersionedCommand(4L, null);
        executeIdempotently();
        when(accreditationService.get(applicationId)).thenReturn(response(5L, "SUBMITTED"));

        assertThatThrownBy(() -> service.startReview(applicationId, command, "key-stale"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(accreditationService, never()).startReview(any());
    }

    @Test
    void commandRequiresExpectedVersionAndIdempotencyKeyBeforeIdempotencyStore() {
        assertThatThrownBy(() -> service.startReview(
            applicationId,
            new VersionedCommand(null, null),
            "key"
        ))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.startReview(
            applicationId,
            new VersionedCommand(1L, null),
            " "
        ))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.startReview(applicationId, null, "key"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("expectedVersion");

        verifyNoInteractions(idempotencyService);
    }

    @Test
    void replayReturnsStoredResponseAndInvokesAuditedDomainMutationOnlyOnce() {
        IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
        AtomicReference<IdempotencyRecord> stored = new AtomicReference<>();
        when(repository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            anyString(),
            any(),
            anyString(),
            any(),
            anyString()
        )).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            stored.set(record);
            return record;
        });
        SupplierAccreditationCommandService replayService = new SupplierAccreditationCommandService(
            accreditationService,
            new IdempotencyService(repository, new ObjectMapper().findAndRegisterModules()),
            approvalEngineService,
            readinessService,
            org.mockito.Mockito.mock(com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy.class)
        );
        VersionedCommand command = new VersionedCommand(7L, "Start review");
        SupplierAccreditationApplicationResponse changed = response(8L, "UNDER_REVIEW");
        when(accreditationService.get(applicationId)).thenReturn(response(7L, "SUBMITTED"));
        when(accreditationService.startReview(applicationId)).thenReturn(changed);

        SupplierAccreditationApplicationResponse first = replayService.startReview(applicationId, command, "same-key");
        SupplierAccreditationApplicationResponse replay = replayService.startReview(applicationId, command, "same-key");

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getVersion()).isEqualTo(8L);
        assertThat(replay.getStatus()).isEqualTo("UNDER_REVIEW");
        verify(accreditationService).get(applicationId);
        verify(accreditationService).startReview(applicationId);

        assertThatThrownBy(() -> replayService.startReview(
            applicationId,
            new VersionedCommand(7L, "Different payload"),
            "same-key"
        ))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("different request payload");
        verify(accreditationService).startReview(applicationId);
    }

    @SuppressWarnings("unchecked")
    private void executeIdempotently() {
        when(idempotencyService.execute(
            anyString(),
            eq(applicationId),
            anyString(),
            any(),
            eq(SupplierAccreditationApplicationResponse.class),
            any()
        )).thenAnswer(invocation ->
            ((Supplier<SupplierAccreditationApplicationResponse>) invocation.getArgument(5)).get()
        );
    }

    private SupplierAccreditationApplicationResponse response(long version, String status) {
        SupplierAccreditationApplicationResponse response = new SupplierAccreditationApplicationResponse();
        response.setId(applicationId);
        response.setCode("ACC-100");
        response.setName("Supplier accreditation");
        response.setVersion(version);
        response.setStatus(status);
        return response;
    }
}
