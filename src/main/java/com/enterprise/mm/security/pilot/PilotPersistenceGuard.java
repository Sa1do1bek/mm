package com.enterprise.mm.security.pilot;

import com.enterprise.mm.entity.*;
import java.math.BigDecimal;
import java.util.Set;
import org.hibernate.event.spi.*;

/** Backstop for imports, approval adapters and managed-entity dirty updates inside enabled orchestration. */
public class PilotPersistenceGuard implements PreInsertEventListener, PreUpdateEventListener, PreDeleteEventListener {
    private final PilotCapabilityPolicy policy;
    private static final Set<String> ASSETS = Set.of("FixedAsset", "AssetAcceptance", "AssetAcceptanceLine",
        "AssetAcceptanceUnit", "AssetHandover", "AssetHandoverLine", "AssetOpeningImport", "AssetOpeningImportLine",
        "AssetVerification", "AssetVerificationLine", "AssetAllocation", "AssetEvent", "AssetNumberRegistry", "AssetDepreciationEntry");
    private static final Set<String> SERVICES = Set.of("ServiceRequest", "ServiceOrder", "ServiceExecution", "ServiceAcceptanceAct",
        "ServiceRequestRemark", "ServiceRequestMilestone", "ServiceReviewHistory", "ServiceClaim", "WorkCompletionAct");
    private static final Set<String> SERIALS = Set.of("MaterialUnit", "MaterialUnitOperation", "MaterialUnitEvent", "GoodsReceiptUnit");
    private static final Set<String> FINANCE = Set.of("ContractInvoice", "ContractInvoiceEvidenceSnapshot", "ContractPayment");

    public PilotPersistenceGuard(PilotCapabilityPolicy policy) { this.policy = policy; }

    public void check(Object entity, String[] properties, Object[] previous) {
        String type = org.hibernate.Hibernate.getClass(entity).getSimpleName();
        if (ASSETS.contains(type)) policy.require(PilotCapability.FIXED_ASSET_EXECUTION);
        if (SERVICES.contains(type)) policy.require(PilotCapability.SERVICE_EXECUTION);
        if (SERIALS.contains(type)) policy.require(PilotCapability.SERIALIZED_EXECUTION);
        if (FINANCE.contains(type)) policy.require(PilotCapability.MM_FINANCE_EXECUTION);
        if (entity instanceof IntegrationEvent event) policy.requireIntegration(event);
        // Generic procurement/import commands must not create excluded service business state.
        if ((entity instanceof PurchaseOrder order && order.getRequestType() == com.enterprise.mm.enums.RequestType.SERVICE)
            || (entity instanceof PurchaseRequest request && request.getRequestType() == com.enterprise.mm.enums.RequestType.SERVICE))
            policy.require(PilotCapability.SERVICE_EXECUTION);
        if ((entity instanceof PurchaseOrderItem item && item.getMaterialTypeSnapshot() == com.enterprise.mm.enums.MaterialType.FIXED_ASSET)
            || (entity instanceof PurchaseRequestItem requestItem && requestItem.getMaterialTypeSnapshot() == com.enterprise.mm.enums.MaterialType.FIXED_ASSET))
            policy.require(PilotCapability.FIXED_ASSET_EXECUTION);
        if (entity instanceof BudgetTransaction transaction && transaction.getUsedDelta() != null
            && transaction.getUsedDelta().signum() != 0) policy.require(PilotCapability.MM_FINANCE_EXECUTION);
        if (entity instanceof BudgetItem budget) {
            Object old = null;
            if (previous != null && properties != null) {
                for (int i = 0; i < properties.length; i++) if ("usedAmount".equals(properties[i])) old = previous[i];
            }
            BigDecimal before = old instanceof BigDecimal value ? value : BigDecimal.ZERO;
            BigDecimal after = budget.getUsedAmount() == null ? BigDecimal.ZERO : budget.getUsedAmount();
            if (before.compareTo(after) != 0) policy.require(PilotCapability.MM_FINANCE_EXECUTION);
        }
    }

    private void checkStockMaterial(Object entity, org.hibernate.event.spi.EventSource session) {
        java.util.UUID materialId = entity instanceof StockBalance balance ? balance.getMaterialId()
            : entity instanceof StockBatch batch ? batch.getMaterialId()
            : entity instanceof StockMovement movement ? movement.getMaterialId() : null;
        if (materialId != null) {
            Material material = session.get(Material.class, materialId);
            if (material != null) policy.requireMaterialExecution(material);
        }
    }

    @Override public boolean onPreInsert(PreInsertEvent event) {
        checkStockMaterial(event.getEntity(), event.getSession());
        check(event.getEntity(), null, null); return false;
    }
    @Override public boolean onPreUpdate(PreUpdateEvent event) {
        checkStockMaterial(event.getEntity(), event.getSession());
        check(event.getEntity(), event.getPersister().getPropertyNames(), event.getOldState()); return false;
    }
    @Override public boolean onPreDelete(PreDeleteEvent event) {
        checkStockMaterial(event.getEntity(), event.getSession());
        check(event.getEntity(), null, null); return false;
    }
}
