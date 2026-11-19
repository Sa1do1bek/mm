package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierCapaActionCommand;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierCapaActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.VersionedSupplierComplianceCommand;
import com.enterprise.mm.payload.SupplierCorrectiveActionResponse;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierCorrectiveActionCommandServiceTest {
    @Mock SupplierCorrectiveActionService lifecycleService;
    @Mock IdempotencyService idempotencyService;

    private SupplierCorrectiveActionCommandService service;
    private UUID actionId;

    @BeforeEach
    void setUp() {
        actionId = UUID.randomUUID();
        service = new SupplierCorrectiveActionCommandService(lifecycleService, idempotencyService);
    }

    @Test
    void allCapaLifecycleCommandsUseVersionedIdempotentWrappers() {
        VersionedSupplierComplianceCommand versioned =
            new VersionedSupplierComplianceCommand(11L, "Operator confirmed state");
        SupplierCapaActionCommand action =
            new SupplierCapaActionCommand(11L, "Evidence reviewed and effective");
        SupplierCorrectiveActionResponse current = response(11L, "SUBMITTED");
        SupplierCorrectiveActionResponse changed = response(12L, "VERIFIED");

        executeIdempotently();
        when(lifecycleService.get(actionId)).thenReturn(current);
        when(lifecycleService.open(actionId)).thenReturn(changed);
        when(lifecycleService.start(actionId)).thenReturn(changed);
        when(lifecycleService.submit(eq(actionId), any())).thenReturn(changed);
        when(lifecycleService.verify(eq(actionId), any())).thenReturn(changed);
        when(lifecycleService.close(actionId)).thenReturn(changed);
        when(lifecycleService.cancel(eq(actionId), any())).thenReturn(changed);

        service.open(actionId, versioned, "key-open");
        service.start(actionId, versioned, "key-start");
        service.submit(actionId, action, "key-submit");
        service.verify(actionId, action, "key-verify");
        service.close(actionId, versioned, "key-close");
        service.cancel(actionId, action, "key-cancel");

        ArgumentCaptor<String> operations = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(6)).execute(
            operations.capture(),
            eq(actionId),
            anyString(),
            any(),
            eq(SupplierCorrectiveActionResponse.class),
            any()
        );
        assertThat(operations.getAllValues()).containsExactly(
            "SUPPLIER_CAPA_OPEN",
            "SUPPLIER_CAPA_START",
            "SUPPLIER_CAPA_SUBMIT",
            "SUPPLIER_CAPA_VERIFY",
            "SUPPLIER_CAPA_CLOSE",
            "SUPPLIER_CAPA_CANCEL"
        );
        verify(lifecycleService, times(6)).get(actionId);
        verify(lifecycleService).open(actionId);
        verify(lifecycleService).start(actionId);
        verify(lifecycleService).close(actionId);

        ArgumentCaptor<SupplierCapaActionRequest> comments =
            ArgumentCaptor.forClass(SupplierCapaActionRequest.class);
        verify(lifecycleService).submit(eq(actionId), comments.capture());
        verify(lifecycleService).verify(eq(actionId), comments.capture());
        verify(lifecycleService).cancel(eq(actionId), comments.capture());
        assertThat(comments.getAllValues())
            .extracting(SupplierCapaActionRequest::comment)
            .containsOnly("Evidence reviewed and effective");
    }

    @Test
    void staleVersionConflictsInsideIdempotencyActionBeforeMutation() {
        VersionedSupplierComplianceCommand command =
            new VersionedSupplierComplianceCommand(2L, null);
        executeIdempotently();
        when(lifecycleService.get(actionId)).thenReturn(response(3L, "DRAFT"));

        assertThatThrownBy(() -> service.open(actionId, command, "key-stale"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(lifecycleService, never()).open(any());
    }

    @Test
    void rejectsMissingVersionKeyAndMeaningfulVerifyOrCancelComment() {
        assertThatThrownBy(() -> service.open(
            actionId,
            new VersionedSupplierComplianceCommand(null, null),
            "key"
        )).isInstanceOf(BadRequestException.class).hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.start(
            actionId,
            new VersionedSupplierComplianceCommand(1L, null),
            " "
        )).isInstanceOf(BadRequestException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.verify(
            actionId,
            new SupplierCapaActionCommand(1L, " "),
            "key-verify"
        )).isInstanceOf(BadRequestException.class).hasMessageContaining("comment");
        assertThatThrownBy(() -> service.cancel(
            actionId,
            new SupplierCapaActionCommand(1L, "x"),
            "key-cancel"
        )).isInstanceOf(BadRequestException.class).hasMessageContaining("comment");

        verifyNoInteractions(idempotencyService);
    }

    @SuppressWarnings("unchecked")
    private void executeIdempotently() {
        when(idempotencyService.execute(
            anyString(),
            eq(actionId),
            anyString(),
            any(),
            eq(SupplierCorrectiveActionResponse.class),
            any()
        )).thenAnswer(invocation -> ((Supplier<SupplierCorrectiveActionResponse>) invocation.getArgument(5)).get());
    }

    private SupplierCorrectiveActionResponse response(long version, String status) {
        SupplierCorrectiveActionResponse response = new SupplierCorrectiveActionResponse();
        response.setId(actionId);
        response.setVersion(version);
        response.setStatus(status);
        return response;
    }
}
