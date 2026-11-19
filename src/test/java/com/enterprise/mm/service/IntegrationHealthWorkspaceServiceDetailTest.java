package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.BusinessDocumentReference;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UX-LIVE-03: {@link IntegrationHealthWorkspaceService#detail(UUID)} must surface sibling
 * integration events sharing the same business document (aggregateId) as related documents
 * with a derived recovery status, so an operator can jump straight to the related activity.
 */
@ExtendWith(MockitoExtension.class)
class IntegrationHealthWorkspaceServiceDetailTest {
    @Mock private IntegrationEventRepository eventRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private IntegrationBusinessDocumentResolver documentResolver;
    @Mock private IntegrationSettingService settingService;

    private IntegrationHealthWorkspaceService service;

    private final String tenantId = "integration-health-detail-test";
    private final UUID companyId = UUID.randomUUID();

    private IntegrationHealthWorkspaceService buildService() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new IntegrationHealthWorkspaceService(
            eventRepository,
            auditLogRepository,
            documentResolver,
            new IntegrationPayloadRedactor(objectMapper),
            settingService,
            objectMapper
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void detailIncludesSiblingEventsAsRelatedDocumentsWithRecoveryStatus() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        service = buildService();

        UUID aggregateId = UUID.randomUUID();
        IntegrationEvent current = event(aggregateId, "PUBLISHED");
        IntegrationEvent retryingSibling = event(aggregateId, "RETRY");
        IntegrationEvent deadSibling = event(aggregateId, "DEAD");

        when(eventRepository.findWorkspaceEvent(tenantId, companyId, current.getId()))
            .thenReturn(Optional.of(current));
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            tenantId, companyId, "IntegrationEvent", current.getId()
        )).thenReturn(List.of());
        when(eventRepository.findByTenantIdAndCompanyIdAndReplayOfEventIdAndIsDeletedFalseOrderByCreatedAtAsc(
            tenantId, companyId, current.getId()
        )).thenReturn(List.of());
        when(eventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, companyId, aggregateId
        )).thenReturn(List.of(current, retryingSibling, deadSibling));
        when(documentResolver.resolve(current)).thenReturn(new BusinessDocumentReference(
            "Purchase Order", aggregateId, "PO-1", "Bearing supply", "purchase-orders", "/app/purchase-orders/" + aggregateId
        ));
        when(documentResolver.resolve(retryingSibling)).thenReturn(new BusinessDocumentReference(
            "Purchase Order", aggregateId, "PO-1", "Bearing supply", "purchase-orders", "/app/purchase-orders/" + aggregateId
        ));
        when(documentResolver.resolve(deadSibling)).thenReturn(new BusinessDocumentReference(
            "Purchase Order", aggregateId, null, "Document identity unavailable", null, null, null, false
        ));

        var detail = service.detail(current.getId());

        assertThat(detail.relatedDocuments()).hasSize(2);
        assertThat(detail.relatedDocuments()).extracting("eventId")
            .containsExactlyInAnyOrder(retryingSibling.getId(), deadSibling.getId());
        assertThat(detail.relatedDocuments()).filteredOn(related -> related.eventId().equals(retryingSibling.getId()))
            .singleElement()
            .satisfies(related -> {
                assertThat(related.recoveryStatus()).isEqualTo("RETRY_SCHEDULED");
                assertThat(related.document().code()).isEqualTo("PO-1");
            });
        assertThat(detail.relatedDocuments()).filteredOn(related -> related.eventId().equals(deadSibling.getId()))
            .singleElement()
            .satisfies(related -> {
                assertThat(related.recoveryStatus()).isEqualTo("NEEDS_RECOVERY");
                assertThat(related.document().identityAvailable()).isFalse();
            });
    }

    @Test
    void detailOmitsRelatedDocumentsWhenAggregateIdIsMissing() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        service = buildService();

        IntegrationEvent current = event(null, "PUBLISHED");

        when(eventRepository.findWorkspaceEvent(tenantId, companyId, current.getId()))
            .thenReturn(Optional.of(current));
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            tenantId, companyId, "IntegrationEvent", current.getId()
        )).thenReturn(List.of());
        when(eventRepository.findByTenantIdAndCompanyIdAndReplayOfEventIdAndIsDeletedFalseOrderByCreatedAtAsc(
            tenantId, companyId, current.getId()
        )).thenReturn(List.of());

        var detail = service.detail(current.getId());

        assertThat(detail.relatedDocuments()).isEmpty();
    }

    private IntegrationEvent event(UUID aggregateId, String status) {
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setCompanyId(companyId);
        event.setCode("EVT-" + event.getId().toString().substring(0, 8).toUpperCase());
        event.setEventType("MM_PO_SENT");
        event.setAggregateType("PurchaseOrder");
        event.setAggregateId(aggregateId);
        event.setStatus(status);
        event.setRetryCount(0);
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }
}
