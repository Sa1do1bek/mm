package com.enterprise.mm.repository.specification;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetCategory;
import com.enterprise.mm.enums.DepreciationMethod;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.payload.FixedAssetSearchRequest;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class FixedAssetSpecification {

    public static Specification<FixedAsset> fromRequest(FixedAssetSearchRequest request) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            predicates.add(cb.isFalse(root.get("isDeleted")));

            if (request == null) {
                return cb.and(predicates.toArray(new Predicate[0]));
            }

            if (request.materialId() != null) {
                predicates.add(cb.equal(root.get("material").get("id"), request.materialId()));
            }
            if (request.equipmentId() != null) {
                predicates.add(cb.equal(root.get("equipment").get("id"), request.equipmentId()));
            }
            if (StringUtils.hasText(request.lifecycleStatus())) {
                try {
                    predicates.add(cb.equal(root.get("lifecycleStatus"), FixedAssetLifecycleStatus.valueOf(request.lifecycleStatus())));
                } catch (IllegalArgumentException ignored) {}
            }
            if (StringUtils.hasText(request.custodyKind())) {
                try {
                    predicates.add(cb.equal(root.get("custodyKind"), FixedAssetCustodyKind.valueOf(request.custodyKind())));
                } catch (IllegalArgumentException ignored) {}
            }
            if (StringUtils.hasText(request.category())) {
                try {
                    predicates.add(cb.equal(root.get("category"), AssetCategory.valueOf(request.category())));
                } catch (IllegalArgumentException ignored) {}
            }
            if (StringUtils.hasText(request.depreciationMethod())) {
                try {
                    predicates.add(cb.equal(root.get("depreciationMethod"), DepreciationMethod.valueOf(request.depreciationMethod())));
                } catch (IllegalArgumentException ignored) {}
            }
            if (request.warehouseId() != null) {
                predicates.add(cb.equal(root.get("warehouse").get("id"), request.warehouseId()));
            }
            if (request.departmentId() != null) {
                predicates.add(cb.equal(root.get("department").get("id"), request.departmentId()));
            }
            if (StringUtils.hasText(request.search())) {
                String term = "%" + request.search().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("name")), term),
                    cb.like(cb.lower(root.get("assetCode")), term),
                    cb.like(cb.lower(root.get("inventoryNumber")), term),
                    cb.like(cb.lower(root.get("serialNumber")), term),
                    cb.like(cb.lower(root.join("material", JoinType.LEFT).get("name")), term),
                    cb.like(cb.lower(root.join("material", JoinType.LEFT).get("code")), term)
                ));
            }
            if (StringUtils.hasText(request.inventoryNumber())) {
                predicates.add(cb.like(cb.lower(root.get("inventoryNumber")), "%" + request.inventoryNumber().toLowerCase() + "%"));
            }
            if (StringUtils.hasText(request.serialNumber())) {
                predicates.add(cb.like(cb.lower(root.get("serialNumber")), "%" + request.serialNumber().toLowerCase() + "%"));
            }
            if (StringUtils.hasText(request.query())) {
                String pattern = "%" + request.query().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("code")), pattern),
                    cb.like(cb.lower(root.get("assetCode")), pattern),
                    cb.like(cb.lower(root.get("inventoryNumber")), pattern),
                    cb.like(cb.lower(root.get("serialNumber")), pattern)
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
