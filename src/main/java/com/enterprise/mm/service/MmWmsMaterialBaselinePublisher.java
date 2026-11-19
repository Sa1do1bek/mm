package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues a complete MM material-master baseline for WMS as ordinary canonical
 * material events.  WMS can therefore reuse its normal delta consumer and
 * preserves the same per-material idempotency and revision semantics.
 */
@Service
@RequiredArgsConstructor
public class MmWmsMaterialBaselinePublisher {

    private final MaterialRepository materials;
    private final MaterialWmsIntegrationPublisher materialPublisher;


    @Transactional
    public BaselineQueueResult queueForWms() {
        UUID baselineId = UUID.randomUUID();
        List<Material> snapshot = materials.findByTenantIdAndCompanyIdOrderByIdAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany());
        for (Material material : snapshot) {
            if (material.getId() == null) {
                throw new IllegalStateException("MM WMS baseline requires a canonical material ID");
            }
            materialPublisher.publishToWms(material, baselineId);
        }
        return new BaselineQueueResult(baselineId, snapshot.size());
    }

    public record BaselineQueueResult(UUID baselineId, int materialCount) { }
}
