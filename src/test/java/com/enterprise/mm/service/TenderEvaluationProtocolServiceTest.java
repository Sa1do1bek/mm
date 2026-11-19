package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TenderEvaluationProtocol;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.TenderEvaluationProtocolMapper;
import com.enterprise.mm.payload.TenderEvaluationProtocolCreateRequest;
import com.enterprise.mm.payload.TenderEvaluationProtocolUpdateRequest;
import com.enterprise.mm.repository.TenderEvaluationProtocolRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenderEvaluationProtocolServiceTest {

    private final TenderEvaluationProtocolRepository repository = mock(TenderEvaluationProtocolRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final TenderEvaluationProtocolService service = new TenderEvaluationProtocolService(
        repository,
        new TenderEvaluationProtocolMapper(),
        auditService
    );

    @Test
    void genericCreateRejectsApprovedWorkflowStatus() {
        TenderEvaluationProtocolCreateRequest request = new TenderEvaluationProtocolCreateRequest();
        request.setName("Approved protocol");
        request.setStatus("APPROVED");

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RFQ evaluation action");

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void genericUpdateRejectsExistingApprovedProtocol() {
        UUID id = UUID.randomUUID();
        TenderEvaluationProtocol existing = new TenderEvaluationProtocol();
        existing.setId(id);
        existing.setTenantId(TenantContext.DEFAULT_TENANT);
        existing.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        existing.setCode("TEP-1");
        existing.setName("Approved protocol");
        existing.setStatus("APPROVED");
        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(existing));

        TenderEvaluationProtocolUpdateRequest request = new TenderEvaluationProtocolUpdateRequest();
        request.setName("Edited protocol");
        request.setStatus("ACTIVE");

        assertThatThrownBy(() -> service.update(id, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("generic registry");

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
