package com.enterprise.mm.service.approval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class TransferExceptionApprovalAdapterTest {
    @Test void anOldTaskCannotApproveANewerApprovalCycle() {
        var state=org.mockito.Mockito.mock(com.enterprise.mm.service.transfer.TransferExceptionStateService.class);
        var c=new com.enterprise.mm.entity.TransferException();c.setId(java.util.UUID.randomUUID());c.setStatus("PENDING_APPROVAL");c.setRevision(7);c.setApprovalRevision(7L);c.setQuantity(java.math.BigDecimal.ONE);
        var current=java.util.UUID.randomUUID();c.setApprovalInstanceId(current);
        org.mockito.Mockito.when(state.lock(c.getId())).thenReturn(c);
        var adapter=new TransferExceptionApprovalAdapter(state,org.mockito.Mockito.mock(com.enterprise.mm.service.FinancialAuthorizationService.class),org.mockito.Mockito.mock(com.enterprise.mm.repository.ApprovalInstanceRepository.class));
        assertThatThrownBy(()->adapter.lockApprovalRevision(c.getId(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID())).hasMessageContaining("stale");
        assertThatCode(()->adapter.lockApprovalRevision(c.getId(),current,java.util.UUID.randomUUID())).doesNotThrowAnyException();
    }
    @Test void finalApprovalRequiresIndependentFinanceActor() {
        assertThatThrownBy(() -> TransferExceptionApprovalAdapter.validateFinalActor("reporter", "reporter", true)).hasMessageContaining("independent");
        assertThatThrownBy(() -> TransferExceptionApprovalAdapter.validateFinalActor("manager", "reporter", false)).hasMessageContaining("Finance");
        assertThatCode(() -> TransferExceptionApprovalAdapter.validateFinalActor("finance", "reporter", true)).doesNotThrowAnyException();
    }
}
