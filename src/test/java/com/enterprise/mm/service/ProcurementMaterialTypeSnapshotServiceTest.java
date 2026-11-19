package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementMaterialTypeSnapshotServiceTest {
    @Mock
    private MaterialCatalogGuard materialCatalogGuard;

    private ProcurementMaterialTypeSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new ProcurementMaterialTypeSnapshotService(
            materialCatalogGuard,
            new MaterialBusinessTypePolicy()
        );
    }

    @Test
    void purchaseOrderItemFreezesBatchAndSerialPoliciesFromMaterial() {
        UUID materialId = UUID.randomUUID();
        Material material = new Material();
        material.setId(materialId);
        material.setMaterialType(MaterialType.TMB);
        material.setTrackingMode(TrackingMode.SERIAL);
        material.setBatchTrackingPolicy(BatchTrackingPolicy.REQUIRED);
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);

        when(materialCatalogGuard.requireActiveMaterial(materialId, "materialId")).thenReturn(material);

        service.applySnapshot(item, RequestType.GOODS);

        assertThat(item.getBatchTrackingSnapshot()).isEqualTo(BatchTrackingPolicy.REQUIRED);
        assertThat(item.getSerialTrackingSnapshot()).isEqualTo(SerialTrackingPolicy.REQUIRED);

        material.setBatchTrackingPolicy(BatchTrackingPolicy.NONE);
        material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);

        assertThat(item.getBatchTrackingSnapshot()).isEqualTo(BatchTrackingPolicy.REQUIRED);
        assertThat(item.getSerialTrackingSnapshot()).isEqualTo(SerialTrackingPolicy.REQUIRED);
    }
}
