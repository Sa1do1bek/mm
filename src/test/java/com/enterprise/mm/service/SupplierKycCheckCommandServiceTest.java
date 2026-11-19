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
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierKycDecisionCommand;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierKycDecisionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.VersionedSupplierComplianceCommand;
import com.enterprise.mm.payload.SupplierKycCheckResponse;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class SupplierKycCheckCommandServiceTest {
    @Mock SupplierKycCheckService lifecycleService;
    @Mock IdempotencyService idempotencyService;

    private SupplierKycCheckCommandService service;
    private UUID checkId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-kyc-command");
        CompanyContext.setCompany(UUID.randomUUID());
        checkId = UUID.randomUUID();
        service = new SupplierKycCheckCommandService(lifecycleService, idempotencyService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void allKycLifecycleCommandsUseVersionedIdempotentWrappers() {
        VersionedSupplierComplianceCommand versioned =
            new VersionedSupplierComplianceCommand(4L, "Operator confirmed state");
        SupplierKycDecisionCommand decision =
            new SupplierKycDecisionCommand(4L, "HIGH", "Sanctions match confirmed", true);
        SupplierKycCheckResponse current = response(4L, "REQUESTED");
        SupplierKycCheckResponse changed = response(5L, "FAILED");

        executeIdempotently();
        when(lifecycleService.get(checkId)).thenReturn(current);
        when(lifecycleService.request(checkId)).thenReturn(changed);
        when(lifecycleService.pass(eq(checkId), any())).thenReturn(changed);
        when(lifecycleService.fail(eq(checkId), any())).thenReturn(changed);
        when(lifecycleService.waive(eq(checkId), any())).thenReturn(changed);
        when(lifecycleService.expire(checkId)).thenReturn(changed);

        service.request(checkId, versioned, "key-request");
        service.pass(checkId, decision, "key-pass");
        service.fail(checkId, decision, "key-fail");
        service.waive(checkId, decision, "key-waive");
        service.expire(checkId, versioned, "key-expire");

        ArgumentCaptor<String> operations = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(5)).execute(
            operations.capture(),
            eq(checkId),
            anyString(),
            any(),
            eq(SupplierKycCheckResponse.class),
            any()
        );
        assertThat(operations.getAllValues()).containsExactly(
            "SUPPLIER_KYC_REQUEST",
            "SUPPLIER_KYC_PASS",
            "SUPPLIER_KYC_FAIL",
            "SUPPLIER_KYC_WAIVE",
            "SUPPLIER_KYC_EXPIRE"
        );
        verify(lifecycleService, times(5)).get(checkId);
        verify(lifecycleService).request(checkId);
        verify(lifecycleService).expire(checkId);

        ArgumentCaptor<SupplierKycDecisionRequest> decisions =
            ArgumentCaptor.forClass(SupplierKycDecisionRequest.class);
        verify(lifecycleService).pass(eq(checkId), decisions.capture());
        verify(lifecycleService).fail(eq(checkId), decisions.capture());
        verify(lifecycleService).waive(eq(checkId), decisions.capture());
        assertThat(decisions.getAllValues()).allSatisfy(mapped -> {
            assertThat(mapped.riskLevel()).isEqualTo("HIGH");
            assertThat(mapped.findings()).isEqualTo("Sanctions match confirmed");
            assertThat(mapped.remediationRequired()).isTrue();
        });
    }

    @Test
    void staleVersionConflictsInsideIdempotencyActionBeforeMutation() {
        VersionedSupplierComplianceCommand command =
            new VersionedSupplierComplianceCommand(3L, null);
        executeIdempotently();
        when(lifecycleService.get(checkId)).thenReturn(response(4L, "DRAFT"));

        assertThatThrownBy(() -> service.request(checkId, command, "key-stale"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(lifecycleService, never()).request(any());
    }

    @Test
    void rejectsMissingVersionKeyAndMeaningfulFailOrWaiveFindings() {
        assertThatThrownBy(() -> service.request(
            checkId,
            new VersionedSupplierComplianceCommand(null, null),
            "key"
        )).isInstanceOf(BadRequestException.class).hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.request(
            checkId,
            new VersionedSupplierComplianceCommand(1L, null),
            " "
        )).isInstanceOf(BadRequestException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.fail(
            checkId,
            new SupplierKycDecisionCommand(1L, "HIGH", "  ", true),
            "key-fail"
        )).isInstanceOf(BadRequestException.class).hasMessageContaining("findings");
        assertThatThrownBy(() -> service.waive(
            checkId,
            new SupplierKycDecisionCommand(1L, "MEDIUM", "x", false),
            "key-waive"
        )).isInstanceOf(BadRequestException.class).hasMessageContaining("findings");

        verifyNoInteractions(idempotencyService);
    }

    @Test
    void sameKeyReplayReturnsStoredResponseWithoutRecheckingStaleVersion() {
        IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
        AtomicReference<IdempotencyRecord> stored = new AtomicReference<>();
        when(repository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            anyString(), any(), anyString(), any(), anyString()
        )).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            stored.set(record);
            return record;
        });
        SupplierKycCheckCommandService replayService = new SupplierKycCheckCommandService(
            lifecycleService,
            new IdempotencyService(repository, new ObjectMapper().findAndRegisterModules())
        );
        SupplierKycDecisionCommand command =
            new SupplierKycDecisionCommand(7L, "LOW", "Screening completed", false);
        when(lifecycleService.get(checkId)).thenReturn(response(7L, "REQUESTED"));
        when(lifecycleService.pass(eq(checkId), any())).thenReturn(response(8L, "PASSED"));

        SupplierKycCheckResponse first = replayService.pass(checkId, command, "same-key");
        SupplierKycCheckResponse replay = replayService.pass(checkId, command, "same-key");

        assertThat(replay.getVersion()).isEqualTo(first.getVersion()).isEqualTo(8L);
        verify(lifecycleService).get(checkId);
        verify(lifecycleService).pass(eq(checkId), any());
    }

    @SuppressWarnings("unchecked")
    private void executeIdempotently() {
        when(idempotencyService.execute(
            anyString(),
            eq(checkId),
            anyString(),
            any(),
            eq(SupplierKycCheckResponse.class),
            any()
        )).thenAnswer(invocation -> ((Supplier<SupplierKycCheckResponse>) invocation.getArgument(5)).get());
    }

    private SupplierKycCheckResponse response(long version, String status) {
        SupplierKycCheckResponse response = new SupplierKycCheckResponse();
        response.setId(checkId);
        response.setVersion(version);
        response.setStatus(status);
        return response;
    }
}
