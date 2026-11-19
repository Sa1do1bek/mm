package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ServiceOrderAcceptanceLifecycleService;
import com.enterprise.mm.service.ServiceReviewHistoryService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServiceAcceptanceActApprovalAdapterLifecycleTest {
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void technicalRejectionTriggersOrderStatusRecomputation() {
        TenantContext.setTenant("default");
        UUID orderId = UUID.randomUUID();
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setServiceOrderId(orderId);
        act.setStatus("TECHNICAL_REVIEW");
        ServiceAcceptanceActRepository repository = mock(ServiceAcceptanceActRepository.class);
        ServiceOrderAcceptanceLifecycleService lifecycle = mock(ServiceOrderAcceptanceLifecycleService.class);
        when(repository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));
        when(repository.save(act)).thenReturn(act);
        ServiceAcceptanceActApprovalAdapter adapter = new ServiceAcceptanceActApprovalAdapter(
            repository,
            mock(ServiceRequestRepository.class),
            mock(AuditService.class),
            mock(ServiceReviewHistoryService.class),
            mock(AttachmentService.class),
            lifecycle
        );

        adapter.markRejected(act.getId(), "evidence mismatch");

        assertThat(act.getStatus()).isEqualTo("REJECTED");
        verify(lifecycle).recompute(orderId);
    }
}
