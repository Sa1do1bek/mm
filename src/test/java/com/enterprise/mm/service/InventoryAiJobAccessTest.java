package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

class InventoryAiJobAccessTest {
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void companyScopedGenericJobReadStillRequiresPrivateDraftOwnership() {
        UUID company = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("other-user", "n/a"));
        AiAnalysisJob job = new AiAnalysisJob();
        job.setId(jobId);
        job.setAnalysisType(AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION);
        job.setEntityType(InventoryAiDraftService.ENTITY_TYPE);
        job.setEntityId(draftId);
        AiAnalysisJobRepository repository = mock(AiAnalysisJobRepository.class);
        InventoryAiDraftService drafts = mock(InventoryAiDraftService.class);
        when(repository.findScoped(jobId, "tenant-a", company)).thenReturn(Optional.of(job));
        when(drafts.requireOwner(draftId, "other-user")).thenThrow(new NotFoundException("Inventory AI draft not found"));
        AiAnalysisJobService service = new AiAnalysisJobService(repository, new ObjectMapper(),
            mock(ApplicationEventPublisher.class), new UnitOfMeasureDisplayService(mock(UnitOfMeasureRepository.class)),
            drafts, mock(InventoryAiResultService.class), mock(com.enterprise.mm.service.inventory.InventoryAccessService.class), mock(MaterialStockAccountRecommendationService.class));

        assertThatThrownBy(() -> service.getJob(jobId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void departmentHeadCannotReadOldUnscopedResultEvenAsDraftOwner() {
        UUID company = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("head", "n/a"));
        AiAnalysisJob job = new AiAnalysisJob();
        job.setId(jobId);
        job.setAnalysisType(AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION);
        job.setEntityType(InventoryAiDraftService.ENTITY_TYPE);
        job.setEntityId(draftId);
        job.setRequestPayload("{\"orderAnalysisVersion\":1}");
        job.setResultPayload("{}");
        AiAnalysisJobRepository repository = mock(AiAnalysisJobRepository.class);
        InventoryAiDraftService drafts = mock(InventoryAiDraftService.class);
        var access = mock(com.enterprise.mm.service.inventory.InventoryAccessService.class);
        when(access.hasRole("DEPARTMENT_HEAD")).thenReturn(true);
        when(repository.findScoped(jobId, "tenant-a", company)).thenReturn(Optional.of(job));
        AiAnalysisJobService service = new AiAnalysisJobService(repository, new ObjectMapper(),
            mock(ApplicationEventPublisher.class), new UnitOfMeasureDisplayService(mock(UnitOfMeasureRepository.class)),
            drafts, mock(InventoryAiResultService.class), access, mock(MaterialStockAccountRecommendationService.class));

        assertThatThrownBy(() -> service.getJob(jobId)).isInstanceOf(AccessDeniedException.class);
    }
}
