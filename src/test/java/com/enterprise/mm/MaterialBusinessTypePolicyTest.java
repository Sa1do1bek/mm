package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.MaterialBusinessTypePolicy;
import com.enterprise.mm.service.ProcurementDocumentCategoryValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialBusinessTypePolicyTest {
    private final MaterialBusinessTypePolicy policy = new MaterialBusinessTypePolicy();

    @Test
    void explicitMaterialTypeSurvivesAbsentMxikAndDefaultsLegacyGoodsToTmb() {
        assertThat(policy.normalizeMaterialType(MaterialType.FIXED_ASSET, null)).isEqualTo(MaterialType.FIXED_ASSET);
        assertThat(policy.normalizeMaterialType(null, null)).isEqualTo(MaterialType.TMB);
    }

    @Test
    void validatesMxikCompatibilityWithoutChangingExternalMxikSemantics() {
        MXIK goods = mxik("GOODS");
        MXIK service = mxik("SERVICE");

        assertThat(policy.normalizeMaterialType(MaterialType.FIXED_ASSET, goods)).isEqualTo(MaterialType.FIXED_ASSET);
        assertThat(policy.normalizeMaterialType(MaterialType.TMB, goods)).isEqualTo(MaterialType.TMB);
        assertThat(policy.normalizeMaterialType(MaterialType.SERVICES, service)).isEqualTo(MaterialType.SERVICES);
        assertThat(policy.normalizeMaterialType(null, service)).isEqualTo(MaterialType.SERVICES);

        assertThatThrownBy(() -> policy.normalizeMaterialType(MaterialType.SERVICES, goods))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("MATERIAL_TYPE_MXIK_MISMATCH");
        assertThatThrownBy(() -> policy.normalizeMaterialType(MaterialType.FIXED_ASSET, service))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("MATERIAL_TYPE_MXIK_MISMATCH");
    }

    @Test
    void validatesRequestAndLineCompatibilityFromMaterialType() {
        Material fixedAsset = material(MaterialType.FIXED_ASSET);
        Material service = material(MaterialType.SERVICES);

        assertThat(policy.snapshotForMaterial(fixedAsset, ProcurementLineType.MATERIAL, RequestType.GOODS, "line"))
            .isEqualTo(MaterialType.FIXED_ASSET);
        assertThat(policy.snapshotForMaterial(service, ProcurementLineType.SERVICE, RequestType.SERVICE, "line"))
            .isEqualTo(MaterialType.SERVICES);

        assertThatThrownBy(() -> policy.snapshotForMaterial(fixedAsset, ProcurementLineType.SERVICE, RequestType.SERVICE, "line"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PROCUREMENT_MATERIAL_TYPE_REQUIRED");
        assertThatThrownBy(() -> policy.snapshotForMaterial(service, ProcurementLineType.MATERIAL, RequestType.GOODS, "line"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PROCUREMENT_MATERIAL_TYPE_REQUIRED");
    }

    @Test
    void rejectsMixedTmbAndFixedAssetDocuments() {
        ProcurementDocumentCategoryValidator validator = new ProcurementDocumentCategoryValidator();

        validator.validateNoMixedGoodsBusinessTypes("purchaseRequest", List.of(MaterialType.TMB, MaterialType.TMB));
        validator.validateNoMixedGoodsBusinessTypes("purchaseOrder", List.of(MaterialType.FIXED_ASSET, MaterialType.FIXED_ASSET));

        assertThatThrownBy(() -> validator.validateNoMixedGoodsBusinessTypes(
            "purchaseRequest",
            List.of(MaterialType.TMB, MaterialType.FIXED_ASSET)
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PROCUREMENT_MATERIAL_TYPE_MIXED");
    }

    @Test
    void inventorySubtypesShareTmbProcurementAndRejectOtherBusinessTypes() {
        assertThat(policy.normalizeMaterialSubtype(MaterialType.TMB, null)).isEqualTo(MaterialSubtype.MATERIAL);
        for (MaterialSubtype subtype : MaterialSubtype.values()) {
            assertThat(policy.normalizeMaterialSubtype(MaterialType.TMB, subtype)).isEqualTo(subtype);
            Material item = material(MaterialType.TMB);
            item.setMaterialSubtype(subtype);
            assertThat(policy.snapshotForMaterial(item, ProcurementLineType.MATERIAL, RequestType.GOODS, "line"))
                .isEqualTo(MaterialType.TMB);
            assertThatThrownBy(() -> policy.normalizeMaterialSubtype(MaterialType.SERVICES, subtype))
                .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> policy.normalizeMaterialSubtype(MaterialType.FIXED_ASSET, subtype))
                .isInstanceOf(BadRequestException.class);
        }
        assertThat(policy.normalizeMaterialSubtype(MaterialType.SERVICES, null)).isNull();
    }

    @Test
    void omittedSubtypeDoesNotChangeLegacyRequestFingerprints() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var request = new com.enterprise.mm.payload.MaterialCreateRequest();
        request.setMaterialType(MaterialType.TMB);
        assertThat(mapper.readTree(mapper.writeValueAsString(request)).has("materialSubtype")).isFalse();
        request.setMaterialSubtype(MaterialSubtype.EQUIPMENT);
        assertThat(mapper.readTree(mapper.writeValueAsString(request)).get("materialSubtype").asText())
            .isEqualTo("EQUIPMENT");
    }

    private Material material(MaterialType type) {
        Material material = new Material();
        material.setMaterialType(type);
        return material;
    }

    private MXIK mxik(String type) {
        MXIK mxik = new MXIK();
        mxik.setType(type);
        return mxik;
    }
}
