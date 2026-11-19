package com.enterprise.mm.service;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.BadRequestException;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class MaterialBusinessTypePolicy {
    public static final String MXIK_TYPE_GOODS = "GOODS";
    public static final String MXIK_TYPE_SERVICE = "SERVICE";

    public MaterialType normalizeMaterialType(MaterialType requested, MXIK mxik) {
        MaterialType normalized = requested == null ? deriveFromMxik(mxik) : requested;
        validateMxikCompatibility(normalized, mxik);
        return normalized;
    }

    public MaterialSubtype normalizeMaterialSubtype(MaterialType materialType, MaterialSubtype subtype) {
        if (materialType != MaterialType.TMB) {
            if (subtype != null) {
                throw new BadRequestException(
                    "Inventory subtype is only available for TMB materials", "material.subtype.tmb_only");
            }
            return null;
        }
        return subtype == null ? MaterialSubtype.MATERIAL : subtype;
    }

    public void validateMxikCompatibility(MaterialType materialType, MXIK mxik) {
        if (materialType == null || mxik == null || mxik.getType() == null || mxik.getType().isBlank()) {
            return;
        }
        String mxikType = mxik.getType().trim().toUpperCase(Locale.ROOT);
        String expected = expectedMxikType(materialType);
        if (!expected.equals(mxikType)) {
            throw new BusinessException(
                "MATERIAL_TYPE_MXIK_MISMATCH: " + materialType + " requires MXIK type " + expected,
                "material.type.mxik_mismatch",
                materialType,
                expected,
                mxikType
            );
        }
    }

    public MaterialType snapshotForMaterial(
        Material material,
        ProcurementLineType lineType,
        RequestType requestType,
        String context
    ) {
        MaterialType materialType = material == null
            ? MaterialType.SERVICES
            : normalizeMaterialType(material.getMaterialType(), material.getMxik());
        validateLineCompatibility(materialType, lineType, requestType, context);
        return materialType;
    }

    public void validateLineCompatibility(
        MaterialType materialType,
        ProcurementLineType lineType,
        RequestType requestType,
        String context
    ) {
        ProcurementLineType normalizedLineType = lineType == null ? ProcurementLineType.MATERIAL : lineType;
        RequestType normalizedRequestType = requestType == null ? RequestType.GOODS : requestType;
        if (materialType == MaterialType.SERVICES) {
            if (!normalizedRequestType.isServiceStyle()
                || (normalizedLineType != ProcurementLineType.SERVICE && normalizedLineType != ProcurementLineType.WORK)) {
                throw mismatch(context, materialType, normalizedLineType, normalizedRequestType);
            }
            return;
        }
        if (normalizedRequestType != RequestType.GOODS || normalizedLineType != ProcurementLineType.MATERIAL) {
            throw mismatch(context, materialType, normalizedLineType, normalizedRequestType);
        }
    }

    public String expectedMxikType(MaterialType materialType) {
        return materialType == MaterialType.SERVICES ? MXIK_TYPE_SERVICE : MXIK_TYPE_GOODS;
    }

    private MaterialType deriveFromMxik(MXIK mxik) {
        if (mxik != null && mxik.getType() != null
            && MXIK_TYPE_SERVICE.equals(mxik.getType().trim().toUpperCase(Locale.ROOT))) {
            return MaterialType.SERVICES;
        }
        return MaterialType.TMB;
    }

    private BusinessException mismatch(
        String context,
        MaterialType materialType,
        ProcurementLineType lineType,
        RequestType requestType
    ) {
        return new BusinessException(
            "PROCUREMENT_MATERIAL_TYPE_REQUIRED: " + safeContext(context) + " " + materialType
                + " requires compatible request and line type, not " + requestType + "/" + lineType,
            "material.type.line_mismatch",
            safeContext(context),
            materialType,
            requestType,
            lineType
        );
    }

    private String safeContext(String context) {
        return context == null || context.isBlank() ? "line" : context;
    }
}
