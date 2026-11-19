package com.enterprise.mm.service;

import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcurementMaterialTypeSnapshotService {
    private final MaterialCatalogGuard materialCatalogGuard;
    private final MaterialBusinessTypePolicy policy;

    public void applySnapshot(DemandPlanItem item, RequestType requestType) {
        Material material = materialCatalogGuard.requireUsableMaterial(item.getMaterialId(), "materialId");
        MaterialType materialType = policy.normalizeMaterialType(material.getMaterialType(), material.getMxik());
        item.setMaterialTypeSnapshot(resolveSnapshot(
            item.getMaterialTypeSnapshot(),
            material,
            materialType == MaterialType.SERVICES ? ProcurementLineType.SERVICE : ProcurementLineType.MATERIAL,
            requestType,
            "demandPlanItem"
        ));
    }

    public void applySnapshot(PurchaseRequestItem item, RequestType requestType) {
        item.setMaterialTypeSnapshot(resolveSnapshot(
            item.getMaterialTypeSnapshot(),
            item.getLineType() == ProcurementLineType.MATERIAL
                ? materialCatalogGuard.requireActiveMaterial(item.getMaterialId(), "materialId")
                : null,
            item.getLineType(),
            requestType,
            "purchaseRequestItem"
        ));
    }

    public void applySnapshot(RFQItem item, RequestType requestType) {
        item.setMaterialTypeSnapshot(resolveSnapshot(
            item.getMaterialTypeSnapshot(),
            item.getLineType() == ProcurementLineType.MATERIAL
                ? materialCatalogGuard.requireActiveMaterial(item.getMaterialId(), "materialId")
                : null,
            item.getLineType(),
            requestType,
            "rfqItem"
        ));
    }

    public void applySnapshot(SupplierQuotationItem item, RequestType requestType) {
        item.setMaterialTypeSnapshot(resolveSnapshot(
            item.getMaterialTypeSnapshot(),
            item.getLineType() == ProcurementLineType.MATERIAL
                ? materialCatalogGuard.requireActiveMaterial(item.getMaterialId(), "materialId")
                : null,
            item.getLineType(),
            requestType,
            "supplierQuotationItem"
        ));
    }

    public void applySnapshot(ContractSpecification item) {
        item.setMaterialTypeSnapshot(resolveSnapshot(
            item.getMaterialTypeSnapshot(),
            item.getLineType() == ProcurementLineType.MATERIAL
                ? materialCatalogGuard.requireActiveMaterial(item.getMaterialId(), "materialId")
                : null,
            item.getLineType(),
            item.getLineType() == ProcurementLineType.MATERIAL ? RequestType.GOODS : RequestType.SERVICE,
            "contractSpecification"
        ));
    }

    public void applySnapshot(PurchaseOrderItem item, RequestType requestType) {
        Material material = item.getLineType() == ProcurementLineType.MATERIAL
            ? materialCatalogGuard.requireActiveMaterial(item.getMaterialId(), "materialId")
            : null;
        item.setMaterialTypeSnapshot(resolveSnapshot(
            item.getMaterialTypeSnapshot(),
            material,
            item.getLineType(),
            requestType,
            "purchaseOrderItem"
        ));
        item.setTrackingModeSnapshot(resolveTrackingSnapshot(item.getTrackingModeSnapshot(), material, item.getLineType(), "purchaseOrderItem"));
        item.setBatchTrackingSnapshot(resolveBatchTrackingSnapshot(item.getBatchTrackingSnapshot(), material, item.getLineType(), "purchaseOrderItem"));
        item.setSerialTrackingSnapshot(resolveSerialTrackingSnapshot(item.getSerialTrackingSnapshot(), material, item.getLineType(), "purchaseOrderItem"));
    }

    private BatchTrackingPolicy resolveBatchTrackingSnapshot(
        BatchTrackingPolicy requestedSnapshot,
        Material material,
        ProcurementLineType lineType,
        String context
    ) {
        BatchTrackingPolicy actual = material == null || lineType != ProcurementLineType.MATERIAL
            ? BatchTrackingPolicy.NONE
            : material.getBatchTrackingPolicy() == null
                ? TrackingMode.batchTrackingPolicy(material.getTrackingMode())
                : material.getBatchTrackingPolicy();
        if (requestedSnapshot != null && requestedSnapshot != actual) {
            throw new BusinessException(
                "BATCH_TRACKING_SNAPSHOT_MISMATCH: " + context + " snapshot " + requestedSnapshot
                    + " does not match material batch tracking policy " + actual,
                "material.batch_tracking.snapshot_mismatch",
                context,
                requestedSnapshot,
                actual
            );
        }
        return actual;
    }

    private SerialTrackingPolicy resolveSerialTrackingSnapshot(
        SerialTrackingPolicy requestedSnapshot,
        Material material,
        ProcurementLineType lineType,
        String context
    ) {
        SerialTrackingPolicy actual = material == null || lineType != ProcurementLineType.MATERIAL
            ? SerialTrackingPolicy.NONE
            : material.getSerialTrackingPolicy() == null
                ? TrackingMode.serialTrackingPolicy(material.getTrackingMode())
                : material.getSerialTrackingPolicy();
        if (requestedSnapshot != null && requestedSnapshot != actual) {
            throw new BusinessException(
                "SERIAL_TRACKING_SNAPSHOT_MISMATCH: " + context + " snapshot " + requestedSnapshot
                    + " does not match material serial tracking policy " + actual,
                "material.serial_tracking.snapshot_mismatch",
                context,
                requestedSnapshot,
                actual
            );
        }
        return actual;
    }

    private TrackingMode resolveTrackingSnapshot(
        TrackingMode requestedSnapshot,
        Material material,
        ProcurementLineType lineType,
        String context
    ) {
        TrackingMode actual = material == null || lineType != ProcurementLineType.MATERIAL
            ? TrackingMode.QUANTITY
            : material.getTrackingMode() == null ? TrackingMode.QUANTITY : material.getTrackingMode();
        if (requestedSnapshot != null && requestedSnapshot != actual) {
            throw new BusinessException(
                "TRACKING_MODE_SNAPSHOT_MISMATCH: " + context + " snapshot " + requestedSnapshot
                    + " does not match material tracking mode " + actual,
                "material.tracking.snapshot_mismatch",
                context,
                requestedSnapshot,
                actual
            );
        }
        return actual;
    }

    private MaterialType resolveSnapshot(
        MaterialType requestedSnapshot,
        Material material,
        ProcurementLineType lineType,
        RequestType requestType,
        String context
    ) {
        MaterialType actual = policy.snapshotForMaterial(material, lineType, requestType, context);
        if (requestedSnapshot != null && requestedSnapshot != actual) {
            throw new BusinessException(
                "PROCUREMENT_MATERIAL_TYPE_SNAPSHOT_MISMATCH: " + context + " snapshot " + requestedSnapshot
                    + " does not match material type " + actual,
                "material.type.snapshot_mismatch",
                context,
                requestedSnapshot,
                actual
            );
        }
        return actual;
    }
}
