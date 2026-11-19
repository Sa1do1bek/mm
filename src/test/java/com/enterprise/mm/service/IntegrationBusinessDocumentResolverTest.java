package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.BusinessDocumentReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationBusinessDocumentResolverTest {
    @Mock private EntityManager entityManager;
    private IntegrationBusinessDocumentResolver resolver;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("integration-doc-resolver-test");
        CompanyContext.setCompany(companyId);
        resolver = new IntegrationBusinessDocumentResolver(entityManager, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void resolvesPurchaseOrderIdentityWhenEntityExists() {
        UUID poId = UUID.randomUUID();
        PurchaseOrder po = new PurchaseOrder();
        po.setId(poId);
        po.setTenantId(TenantContext.currentTenant());
        po.setCompanyId(companyId);
        po.setCode("PO-LIVE-03");
        po.setName("Live PO");
        po.setDeleted(false);
        when(entityManager.find(eq(PurchaseOrder.class), eq(poId))).thenReturn(po);

        IntegrationEvent event = new IntegrationEvent();
        event.setAggregateType("PurchaseOrder");
        event.setAggregateId(poId);
        event.setEventType("PO_SENT");

        BusinessDocumentReference ref = resolver.resolve(event);

        assertThat(ref.identityAvailable()).isTrue();
        assertThat(ref.code()).isEqualTo("PO-LIVE-03");
        assertThat(ref.documentNumber()).isEqualTo("PO-LIVE-03");
        assertThat(ref.id()).isEqualTo(poId);
        assertThat(ref.route()).contains(poId.toString());
    }

    @Test
    void marksIdentityUnavailableWhenDocumentCannotBeResolved() {
        IntegrationEvent event = new IntegrationEvent();
        event.setAggregateType("UnknownAggregate");
        event.setAggregateId(UUID.randomUUID());
        event.setEventType("CUSTOM_EVENT");
        event.setPayload("{}");

        BusinessDocumentReference ref = resolver.resolve(event);

        assertThat(ref.identityAvailable()).isFalse();
        assertThat(ref.name()).isEqualTo("Document identity unavailable");
        assertThat(ref.code()).isNull();
    }
}
