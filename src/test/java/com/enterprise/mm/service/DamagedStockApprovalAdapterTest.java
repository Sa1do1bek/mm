package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.entity.DamagedStockCase;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.approval.DamagedStockApprovalAdapter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DamagedStockApprovalAdapterTest {
    @Test void requiresEvidenceBeforeSubmittingAQuarantinedCase() {
        UUID id = UUID.randomUUID(), company = UUID.randomUUID();
        var stockCase = new DamagedStockCase(); stockCase.setId(id); stockCase.setTenantId("tenant"); stockCase.setCompanyId(company); stockCase.setStatus("QUARANTINED");
        var damage = mock(DamagedStockService.class); when(damage.lockCase(id)).thenReturn(stockCase);
        @SuppressWarnings("unchecked") ObjectProvider<DamagedStockService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(damage);
        var attachments = mock(AttachmentRepository.class);
        var adapter = new DamagedStockApprovalAdapter(provider, mock(ApprovalInstanceRepository.class), attachments, mock(AuditService.class));
        assertThatThrownBy(() -> adapter.validateCanSubmit(id)).isInstanceOf(BusinessException.class).hasMessageContaining("evidence");
        verify(damage).requireSubmittable(stockCase);
        when(attachments.existsByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndAttachmentTypeAndIsDeletedFalse("tenant", company, "DAMAGED_STOCK", id, "DOCUMENT")).thenReturn(true);
        assertThatCode(() -> adapter.validateCanSubmit(id)).doesNotThrowAnyException();
    }
}
