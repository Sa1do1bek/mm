package com.enterprise.mm.service;

import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BusinessException;
import java.util.Collection;
import org.springframework.stereotype.Service;

@Service
public class ProcurementDocumentCategoryValidator {
    public void validateNoMixedGoodsBusinessTypes(String documentName, Collection<MaterialType> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        boolean hasTmb = snapshots.stream().anyMatch(MaterialType.TMB::equals);
        boolean hasFixedAsset = snapshots.stream().anyMatch(MaterialType.FIXED_ASSET::equals);
        if (hasTmb && hasFixedAsset) {
            throw new BusinessException(
                "PROCUREMENT_MATERIAL_TYPE_MIXED: " + documentName + " cannot mix TMB and FIXED_ASSET lines",
                "material.type.mixed_document",
                documentName
            );
        }
    }
}
