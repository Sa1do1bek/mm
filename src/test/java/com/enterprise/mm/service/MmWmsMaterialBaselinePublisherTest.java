package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MmWmsMaterialBaselinePublisherTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000008889");

    @Mock private MaterialRepository materials;
    @Mock private UnitOfMeasureRepository units;
    @Mock private IntegrationEventService integrationEvents;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-mm-wms-baseline");
        CompanyContext.setCompany(COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void queuesEachMaterialAsAnIndividualCanonicalWmsEvent() {
        Material first = material("MAT-1", 4L);
        Material second = material("MAT-2", 7L);
        when(materials.findByTenantIdAndCompanyIdOrderByIdAsc("tenant-mm-wms-baseline", COMPANY_ID))
            .thenReturn(List.of(first, second));

        MaterialWmsIntegrationPublisher materialPublisher = new MaterialWmsIntegrationPublisher(integrationEvents, units);
        MmWmsMaterialBaselinePublisher service = new MmWmsMaterialBaselinePublisher(materials, materialPublisher);

        var result = service.queueForWms();

        assertThat(result.baselineId()).isNotNull();
        assertThat(result.materialCount()).isEqualTo(2);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(integrationEvents, times(2)).emitInCurrentTransaction(
            eq("mm.material.changed.v1"), eq("WMS"), any(UUID.class), payloadCaptor.capture());
        verify(integrationEvents, never()).emitInCurrentTransaction(
            eq("mm.material.changed.v1"), eq("ERP"), any(UUID.class), any(Map.class));

        @SuppressWarnings("unchecked")
        List<Map<?, ?>> envelopes = (List) payloadCaptor.getAllValues().stream()
            .map(payload -> payload.get("raw"))
            .toList();
        assertThat(envelopes).allSatisfy(envelope -> {
            assertThat(envelope.get("source")).isEqualTo("mm");
            assertThat(envelope.get("target")).isEqualTo("wms");
            assertThat(envelope.get("type")).isEqualTo("mm.material.changed.v1");
            assertThat(envelope.get("kind")).isEqualTo("EVENT");
            assertThat(envelope.get("aggregateType")).isEqualTo("material");
        });
        assertThat(envelopes.get(0).get("aggregateId")).isEqualTo(first.getId());
        assertThat(envelopes.get(1).get("aggregateId")).isEqualTo(second.getId());
        assertThat(((Map<?, ?>) envelopes.get(0).get("data")).get("sourceRevision")).isEqualTo(4L);
        assertThat(((Map<?, ?>) envelopes.get(1).get("data")).get("sourceRevision")).isEqualTo(7L);
        assertThat(envelopes.get(0).get("correlationId")).isEqualTo(result.baselineId().toString());
        assertThat(envelopes.get(1).get("correlationId")).isEqualTo(result.baselineId().toString());
    }

    private Material material(String code, long version) {
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode(code);
        material.setName(code + " name");
        material.setStatus("ACTIVE");
        material.setVersion(version);
        return material;
    }
}
