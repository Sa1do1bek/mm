package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.event.AiAnalysisJobCreatedEvent;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.enterprise.mm.service.InventoryAiDraftService.FileSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class InventoryAiJobRetryTest {
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID DRAFT = UUID.randomUUID();
    private final AiAnalysisJobRepository jobs = mock(AiAnalysisJobRepository.class);
    private final InventoryAiDraftService drafts = mock(InventoryAiDraftService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AiAnalysisJobService service = new AiAnalysisJobService(jobs, new ObjectMapper(), events,
        new UnitOfMeasureDisplayService(mock(UnitOfMeasureRepository.class)), drafts,
        mock(InventoryAiResultService.class), mock(com.enterprise.mm.service.inventory.InventoryAccessService.class), mock(MaterialStockAccountRecommendationService.class));
    private final AnalysisRequest request = new AnalysisRequest(DRAFT,
        List.of(new FileSnapshot(UUID.randomUUID(), "order.pdf", "application/pdf", "a".repeat(64))));

    @BeforeEach
    void setup() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(COMPANY);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("planner", "n/a"));
        when(drafts.snapshot(DRAFT, "planner", false)).thenReturn(request);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void preservesUzbekScriptInNewJobAndDefaultsToUzbek() {
        when(jobs.findEquivalentInventoryJobs(eq("tenant-a"), eq(COMPANY),
            eq(AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION), eq(InventoryAiDraftService.ENTITY_TYPE),
            eq(DRAFT), any(), eq("uz-Cyrl"), eq(PageRequest.of(0, 1)))).thenReturn(List.of());
        when(jobs.save(any())).thenAnswer(call -> {
            AiAnalysisJob job = call.getArgument(0);
            job.setId(UUID.randomUUID());
            return job;
        });

        assertThat(service.startInventoryDraftJob(DRAFT, "uz-Cyrl,uz;q=0.9").requestedLanguage())
            .isEqualTo("uz-Cyrl");
        assertThat(service.startInventoryDraftJob(DRAFT, null).requestedLanguage()).isEqualTo("uz");
    }

    @Test
    void retriesFailedJobWithSameAnalysisIdAndClearsPreviousFailure() {
        AiAnalysisJob failed = AiAnalysisJob.queued("tenant-a", COMPANY,
            AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION, InventoryAiDraftService.ENTITY_TYPE,
            DRAFT, service.requestHash(request), "uz", "planner", service.serializeRequest(request));
        UUID analysisId = UUID.randomUUID();
        failed.setId(analysisId);
        failed.setStatus(AiAnalysisJobStatus.FAILED);
        failed.setStartedAt(LocalDateTime.now().minusMinutes(4));
        failed.setCompletedAt(LocalDateTime.now());
        failed.setErrorCode("INVENTORY_EXTRACTION_TIMEOUT");
        failed.setErrorMessage("Timed out");
        when(jobs.findEquivalentInventoryJobs("tenant-a", COMPANY,
            AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION, InventoryAiDraftService.ENTITY_TYPE,
            DRAFT, service.requestHash(request), "uz", PageRequest.of(0, 1)))
            .thenReturn(List.of(failed));
        when(jobs.save(failed)).thenReturn(failed);

        var response = service.startInventoryDraftJob(DRAFT, null);

        assertThat(response.id()).isEqualTo(analysisId);
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(failed.getStartedAt()).isNull();
        assertThat(failed.getCompletedAt()).isNull();
        assertThat(failed.getErrorCode()).isNull();
        assertThat(failed.getErrorMessage()).isNull();
        verify(events).publishEvent(any(AiAnalysisJobCreatedEvent.class));
    }

    @Test
    void commissionAnalysisVersionChangesRequestIdentity() {
        AnalysisRequest versionOne = new AnalysisRequest(DRAFT, request.documents(), 1);
        assertThat(service.requestHash(versionOne)).isNotEqualTo(service.requestHash(request));
    }
}
