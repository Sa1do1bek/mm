package com.enterprise.mm.service;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.ProcurementCategoryStrategy;
import com.enterprise.mm.entity.ProcurementPlan;
import com.enterprise.mm.entity.PutawayRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MaterialGroupUsageService {
    private static final List<UsageReference> USAGE_REFERENCES = List.of(
        reference(Material.class, "materialGroupId"),
        reference(ProcurementCampaign.class, "materialGroupId"),
        reference(ProcurementCategoryStrategy.class, "materialGroupId"),
        reference(ProcurementPlan.class, "materialGroupId"),
        reference(PutawayRule.class, "materialGroupId")
    );

    @PersistenceContext
    private EntityManager entityManager;

    public boolean isUsed(MaterialGroup materialGroup) {
        return USAGE_REFERENCES.stream().anyMatch(reference -> exists(
            reference,
            materialGroup.getId(),
            materialGroup.getTenantId(),
            materialGroup.getCompanyId()
        ));
    }

    private boolean exists(UsageReference reference, UUID materialGroupId, String tenantId, UUID companyId) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Integer> query = builder.createQuery(Integer.class);
        Root<?> root = query.from(reference.entityType());
        Predicate matchesMaterialGroup = builder.equal(root.get(reference.materialGroupField()), materialGroupId);
        Predicate currentTenant = builder.equal(root.get("tenantId"), tenantId);
        Predicate currentCompany = builder.equal(root.get("companyId"), companyId);
        Predicate notDeleted = builder.isFalse(root.get("isDeleted"));
        query.select(builder.literal(1)).where(builder.and(matchesMaterialGroup, currentTenant, currentCompany, notDeleted));
        return !entityManager.createQuery(query).setMaxResults(1).getResultList().isEmpty();
    }

    private static UsageReference reference(Class<?> entityType, String materialGroupField) {
        return new UsageReference(entityType, materialGroupField);
    }

    private record UsageReference(Class<?> entityType, String materialGroupField) {
    }
}
