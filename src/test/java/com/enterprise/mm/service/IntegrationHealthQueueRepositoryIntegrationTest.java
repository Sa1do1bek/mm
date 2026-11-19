package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.CompanyScope;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IntegrationHealthQueueRepositoryIntegrationTest {
    @Autowired IntegrationEventRepository repository;
    @Autowired IntegrationHealthWorkspaceService service;

    @AfterEach
    void clearContext() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void queuePredicatesHaveCountListParityAndStayInsideCompanyScope() {
        UUID company = UUID.randomUUID();
        TenantContext.setTenant("tenant-integration-queues");
        CompanyContext.setCompany(company);
        LocalDateTime now = LocalDateTime.now();

        save(company, "FAILED-DUE", IntegrationEventStatus.RETRY, 2, now.minusMinutes(1), "Provider returned HTTP 503", null);
        save(company, "RETRY-LATER", IntegrationEventStatus.RETRY, 2, now.plusMinutes(15), "Provider returned HTTP 503", null);
        save(company, "NO-TRANSPORT", IntegrationEventStatus.RETRY, 1, now.plusMinutes(10), "No integration transport is configured", null);
        save(company, "RECONCILE", IntegrationEventStatus.PUBLISHED, 0, null, "Published to exchange", null);
        save(company, "DEAD", IntegrationEventStatus.DEAD, 5, null, "Provider unavailable", null);
        save(company, "CANCELLED", IntegrationEventStatus.CANCELLED, 1, null, "Manual decision", null);
        save(UUID.randomUUID(), "OTHER-COMPANY", IntegrationEventStatus.DEAD, 8, null, "Other company", null);

        assertQueue(company, IntegrationHealthWorkspaceService.QUEUE_FAILED, now, 1, "FAILED-DUE");
        assertQueue(company, IntegrationHealthWorkspaceService.QUEUE_RETRY, now, 1, "RETRY-LATER");
        assertQueue(company, IntegrationHealthWorkspaceService.QUEUE_NO_TRANSPORT, now, 1, "NO-TRANSPORT");
        assertQueue(company, IntegrationHealthWorkspaceService.QUEUE_RECONCILIATION, now, 1, "RECONCILE");
        assertQueue(company, IntegrationHealthWorkspaceService.QUEUE_DEAD, now, 2, "DEAD", "CANCELLED");
        assertQueue(company, IntegrationHealthWorkspaceService.QUEUE_ALL, now, 6,
            "FAILED-DUE", "RETRY-LATER", "NO-TRANSPORT", "RECONCILE", "DEAD", "CANCELLED");
    }

    @Test
    void globalAdminCurrentCompanyDrilldownKeepsIntegrationSummaryAndListScoped() {
        UUID company = UUID.randomUUID();
        TenantContext.setTenant("tenant-integration-admin-scope");
        CompanyContext.setCompany(company);
        save(company, "DEAD-CURRENT", IntegrationEventStatus.DEAD, 5, null, "Current", null);
        save(UUID.randomUUID(), "DEAD-OTHER", IntegrationEventStatus.DEAD, 5, null, "Other", null);
        repository.flush();
        authenticateGlobalAdmin();

        assertThat(service.events(
            IntegrationHealthWorkspaceService.QUEUE_DEAD,
            null,
            PageRequest.of(0, 20)
        ).getTotalElements()).isEqualTo(2);

        AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);
        var scoped = service.events(
            IntegrationHealthWorkspaceService.QUEUE_DEAD,
            null,
            PageRequest.of(0, 20)
        );

        assertThat(service.summary().deadManualDecision()).isEqualTo(scoped.getTotalElements()).isEqualTo(1);
        assertThat(scoped.getContent()).extracting(item -> item.code()).containsExactly("DEAD-CURRENT");
    }

    private void assertQueue(UUID company, String queue, LocalDateTime now, long count, String... codes) {
        var page = repository.searchWorkspace(
            TenantContext.currentTenant(), company, queue, null, now,
            IntegrationDispatchService.MAX_AUTOMATIC_RETRIES, PageRequest.of(0, 20)
        );
        assertThat(page.getTotalElements()).isEqualTo(count).isEqualTo(page.getNumberOfElements());
        assertThat(page.getContent()).extracting(IntegrationEvent::getCode).containsExactlyInAnyOrder(codes);
    }

    private void save(
        UUID company,
        String code,
        IntegrationEventStatus status,
        int retries,
        LocalDateTime nextRetry,
        String lastError,
        String externalId
    ) {
        IntegrationEvent event = new IntegrationEvent();
        event.setTenantId(TenantContext.currentTenant());
        event.setCompanyId(company);
        event.setCode(code);
        event.setName(code);
        event.setStatus(status.name());
        event.setEventType("FI.STOCK_RECEIPT_POSTED");
        event.setAggregateType("FI");
        event.setAggregateId(UUID.randomUUID());
        event.setPayload("{}");
        event.setRetryCount(retries);
        event.setNextRetryAt(nextRetry);
        event.setLastError(lastError);
        event.setExternalId(externalId);
        event.setIdempotencyKey("idempotency-" + UUID.randomUUID());
        repository.save(event);
    }

    private void authenticateGlobalAdmin() {
        var authentication = new UsernamePasswordAuthenticationToken(
            "global-admin",
            null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
