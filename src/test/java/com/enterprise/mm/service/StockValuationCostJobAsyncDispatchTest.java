package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.event.StockValuationCostJobCreatedEvent;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.StockValuationCostJobResultRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class StockValuationCostJobAsyncDispatchTest {
    @Mock
    private StockValuationCostJobRepository jobRepository;
    @Mock
    private StockValuationCostJobResultRepository resultRepository;
    @Mock
    private StockValuationCostJobCreationService creationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private WarehouseRepository warehouseRepository;

    private StockValuationCostJobService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-async");
        CompanyContext.setCompany(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("finance-user", null));
        service = new StockValuationCostJobService(jobRepository, resultRepository, creationService, eventPublisher,
            warehouseRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void newJobPublishesCreatedEvent() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob created = StockValuationCostJob.queued(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), null, "finance-user");
        created.setId(jobId);
        when(jobRepository.findActive(TenantContext.currentTenant(), CompanyContext.currentCompany(), null))
            .thenReturn(Optional.empty());
        when(creationService.create(TenantContext.currentTenant(), CompanyContext.currentCompany(), null, List.of(), null, "finance-user"))
            .thenReturn(created);

        service.startJob(null);

        verify(eventPublisher).publishEvent(new StockValuationCostJobCreatedEvent(jobId));
    }

    @Test
    void duplicateActiveJobDoesNotPublishCreatedEvent() {
        StockValuationCostJob existing = StockValuationCostJob.queued(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), null, "finance-user");
        when(jobRepository.findActive(TenantContext.currentTenant(), CompanyContext.currentCompany(), null))
            .thenReturn(Optional.of(existing));

        service.startJob(null);

        verify(eventPublisher, never()).publishEvent(any(StockValuationCostJobCreatedEvent.class));
        verify(creationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void eventIsNotPublishedWhenCreationLosesUniqueConstraintRace() {
        UUID warehouseId = UUID.randomUUID();
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId))
            .thenReturn(java.util.Optional.of(new Warehouse()));
        StockValuationCostJob winner = StockValuationCostJob.queued(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId, "other-user");
        when(jobRepository.findActive(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId))
            .thenReturn(Optional.empty(), Optional.of(winner));
        when(creationService.create(any(), any(), any(), any(), any(), any()))
            .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(service.startJob(warehouseId).id()).isEqualTo(winner.getId());
        verify(eventPublisher, never()).publishEvent(any(StockValuationCostJobCreatedEvent.class));
    }
}
