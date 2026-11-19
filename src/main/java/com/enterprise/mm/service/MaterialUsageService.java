package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.ConsolidatedDemand;
import com.enterprise.mm.entity.ConsignmentStockTransaction;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.CrossDockingTask;
import com.enterprise.mm.entity.DemandForecast;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.InventoryFxDifference;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.InventoryOptimizationRecommendation;
import com.enterprise.mm.entity.LaboratoryTest;
import com.enterprise.mm.entity.LandedCostAllocationLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialAnalog;
import com.enterprise.mm.entity.MaterialConsumptionNorm;
import com.enterprise.mm.entity.MaterialEquipmentCompatibility;
import com.enterprise.mm.entity.MaterialReservation;
import com.enterprise.mm.entity.MobileWarehouseOperation;
import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.PutawayRule;
import com.enterprise.mm.entity.QualityClaim;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.QualityNonconformance;
import com.enterprise.mm.entity.QualityQuarantineLot;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockValuation;
import com.enterprise.mm.entity.StockValuationDocument;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.entity.VmiStockAgreement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MaterialUsageService {
    private static final List<UsageReference> USAGE_REFERENCES = List.of(
        reference(AdvanceShippingNoticeItem.class, "materialId"),
        reference(ConsolidatedDemand.class, "materialId"),
        reference(ConsignmentStockTransaction.class, "materialId"),
        reference(ContractSpecification.class, "materialId"),
        reference(CrossDockingTask.class, "materialId"),
        reference(DemandForecast.class, "materialId"),
        reference(DemandPlanItem.class, "materialId"),
        reference(InventoryFxDifference.class, "materialId"),
        reference(InventoryItem.class, "materialId"),
        reference(InventoryOptimizationRecommendation.class, "materialId"),
        reference(LaboratoryTest.class, "materialId"),
        reference(LandedCostAllocationLine.class, "materialId"),
        reference(Material.class, "replacementMaterialId"),
        reference(MaterialAnalog.class, "materialId"),
        reference(MaterialAnalog.class, "analogMaterialId"),
        reference(MaterialConsumptionNorm.class, "materialId"),
        reference(MaterialEquipmentCompatibility.class, "materialId"),
        reference(MaterialReservation.class, "materialId"),
        reference(MobileWarehouseOperation.class, "materialId"),
        reference(NonMovingStock.class, "materialId"),
        reference(PurchaseOrderItem.class, "materialId"),
        reference(PurchaseRequestItem.class, "materialId"),
        reference(PutawayRule.class, "materialId"),
        reference(QualityClaim.class, "materialId"),
        reference(QualityInspection.class, "materialId"),
        reference(QualityNonconformance.class, "materialId"),
        reference(QualityQuarantineLot.class, "materialId"),
        reference(RFQItem.class, "materialId"),
        reference(StockBalance.class, "materialId"),
        reference(StockBatch.class, "materialId"),
        reference(StockMovement.class, "materialId"),
        reference(StockValuation.class, "materialId"),
        reference(StockValuationDocument.class, "materialId"),
        reference(SupplierQuotationItem.class, "materialId"),
        reference(VmiStockAgreement.class, "materialId")
    );

    @PersistenceContext
    private EntityManager entityManager;

    public boolean isUsed(UUID materialId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return USAGE_REFERENCES.stream().anyMatch(reference -> exists(reference, materialId, tenantId, companyId));
    }

    private boolean exists(UsageReference reference, UUID materialId, String tenantId, UUID companyId) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Integer> query = builder.createQuery(Integer.class);
        Root<?> root = query.from(reference.entityType());
        Predicate matchesMaterial = builder.equal(root.get(reference.materialField()), materialId);
        Predicate currentTenant = builder.equal(root.get("tenantId"), tenantId);
        Predicate currentCompany = builder.equal(root.get("companyId"), companyId);
        Predicate notDeleted = builder.isFalse(root.get("isDeleted"));
        query.select(builder.literal(1)).where(builder.and(matchesMaterial, currentTenant, currentCompany, notDeleted));
        return !entityManager.createQuery(query).setMaxResults(1).getResultList().isEmpty();
    }

    private static UsageReference reference(Class<?> entityType, String materialField) {
        return new UsageReference(entityType, materialField);
    }

    private record UsageReference(Class<?> entityType, String materialField) {
    }
}
