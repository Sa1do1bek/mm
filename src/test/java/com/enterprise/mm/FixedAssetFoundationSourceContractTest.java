package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FixedAssetFoundationSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void frozenEnumsExistWithWaveZeroValues() throws Exception {
        assertEnum("AssetAcceptanceLineQualityDecision", "PENDING", "ACCEPTED", "REJECTED", "HOLD");
        assertEnum("AssetAcceptanceUnitQualityDecision", "PENDING", "ACCEPTED", "REJECTED", "HOLD");
        assertEnum("AssetAcceptanceApprovalStatus", "DRAFT", "SUBMITTED", "APPROVED", "REJECTED");
        assertEnum("AssetAcceptancePostingStatus", "NOT_POSTED", "POSTED", "CANCELLED");
        assertEnum("FixedAssetLifecycleStatus", "IN_WAREHOUSE", "ISSUED_PENDING_COMMISSIONING", "IN_USE",
            "IDLE", "UNDER_REPAIR", "RETURNED_TO_SUPPLIER", "DISPOSED", "CANCELLED");
        assertEnum("FixedAssetCustodyKind", "WAREHOUSE", "DEPARTMENT", "IN_TRANSIT", "EXTERNAL", "NONE");
        assertEnum("AssetHandoverStatus", "DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "DISPATCHED",
            "ACKNOWLEDGED", "RETURN_RESOLUTION", "EXCEPTION_REPORTED", "CLOSED", "CANCELLED");
        assertEnum("AssetAllocationStatus", "ACTIVE", "RELEASED", "CANCELLED");
        assertEnum("AssetNumberRegistryStatus", "RESERVED", "ASSIGNED", "CORRECTED", "RETIRED");
        assertEnum("AssetVerificationStatus", "DRAFT", "IN_PROGRESS", "COMPLETED", "CLOSED", "CANCELLED");
        assertEnum("AssetVerificationDiscrepancyType", "MISSING_SCAN", "LOCATION_MISMATCH",
            "CUSTODIAN_MISMATCH", "EXTRA_SCAN", "VERSION_CHANGED");
    }

    @Test
    void fixedAssetEntitiesModelFoundationWithoutOwningStockBehavior() throws Exception {
        assertThat(source("entity/AssetManagementSettings.java")).contains("centralWarehouseId", "inventoryNumberNextValue");
        assertThat(source("entity/AssetNumberRegistry.java")).contains("normalizedNumber", "fixedAssetId", "AssetNumberRegistryStatus");
        assertThat(source("entity/AssetAcceptance.java")).contains("approvalStatus", "postingStatus").doesNotContain("StockMovement");
        assertThat(source("entity/AssetAcceptanceLine.java")).contains("qualityDecision", "materialTypeSnapshot");
        assertThat(source("entity/AssetAcceptanceUnit.java")).contains(
            "qualityDecision",
            "postedFixedAssetId",
            "receivingUnitNumber",
            "manufacturerSerialNumber"
        );
        assertThat(source("entity/FixedAsset.java")).contains("equipmentId", "inventoryNumber", "FixedAssetLifecycleStatus", "FixedAssetCustodyKind");
        assertThat(source("entity/AssetEvent.java")).contains("beforeLifecycleStatus", "afterLifecycleStatus", "sourceDocumentType");
        assertThat(source("entity/AssetVerificationLine.java")).contains("expectedCustodyKind", "observedCustodyKind", "discrepancyType");

        String fixedAssetSource = source("entity/FixedAsset.java");
        assertThat(fixedAssetSource).doesNotContain("StockMovement", "StockBalance", "StockBatch");
    }

    @Test
    void exposedCommandServicesStayBehindTheFeatureFlagOrWaveOneBoundary() throws Exception {
        String acceptanceService = source("service/AssetAcceptanceService.java");
        assertThat(acceptanceService).contains(
            "app.features.fixed-assets.enabled",
            "ASSET_SCOPE_DENIED",
            "ASSET_INVALID_TRANSITION"
        ).doesNotContain("StockMovementService", "StockBalanceService", "StockBatchService");
        assertThat(source("service/FixedAssetService.java")).contains(
            "ASSET_INVALID_TRANSITION",
            "AssetEventResponse",
            "putSourceDocumentName(names, doc.getId(), doc.getName())",
            "fixedAssetSourceDocumentName"
        ).doesNotContain("StockMovementService", "StockBalanceService", "StockBatchService");
        assertThat(source("service/FixedAssetService.java"))
            .doesNotContain("names.put(doc.getId(), doc.getCode())", "names.put(doc.getId(), doc.getAssetCode())");
        assertThat(source("service/AssetVerificationService.java")).contains(
            "observational",
            "ASSET_VERIFICATION_OBSERVATIONAL_ONLY"
        ).doesNotContain("StockMovementService", "StockBalanceService", "StockBatchService");
        assertThat(source("payload/AssetHandoverSearchRequest.java")).contains("UUID fixedAssetId");
        assertThat(source("controller/AssetHandoverController.java")).contains(
            "@RequestParam(required = false) UUID fixedAssetId",
            "new AssetHandoverSearchRequest(status, handoverType, fixedAssetId, fromWarehouseId)"
        );
        assertThat(source("repository/AssetHandoverRepository.java")).contains(
            "line.fixedAssetId = :fixedAssetId",
            "Page<AssetHandover> search"
        );
        assertThat(source("service/AssetHandoverService.java")).contains(
            "parseOptionalStatus",
            "parseOptionalHandoverType",
            "request.fixedAssetId()"
        );
    }

    @Test
    void assetAcceptanceHardeningKeepsIdentityAsnAndQuantityRulesExplicit() throws Exception {
        String acceptanceService = source("service/AssetAcceptanceService.java");
        String fulfillment = source("fulfillment/FulfillmentQuantities.java");

        assertThat(acceptanceService).contains(
            "nextReceivingUnitNumber",
            "manufacturerSerialNumber",
            "validateAcceptanceAsn",
            "ASSET_ACCEPTANCE_ASN_OVER_RECEIPT",
            "fixedAssetOpenSupplierObligation",
            "assignGeneratedInventoryNumber",
            "applyAsnShipmentProcessing"
        );
        assertThat(acceptanceService).doesNotContain("StockMovementService", "StockBalanceService", "StockBatchService", "MaterialUnit");
        String acceptedRemainingMethod = fulfillment.substring(fulfillment.indexOf("acceptedFulfillmentRemaining"));
        assertThat(acceptedRemainingMethod).contains("subtract(nvl(item.getAcceptedQuantity()))")
            .doesNotContain("subtract(nvl(item.getRejectedQuantity()))", "subtract(nvl(item.getDamagedQuantity()))");
    }

    @Test
    void assetRoutesUseWaveZeroProvisionalRoleMapping() throws Exception {
        String security = source("security/SecurityConfig.java");

        assertThat(security).contains(
            "ASSET_READ_ROLES",
            "ASSET_ACCEPTANCE_ROLES",
            "ASSET_QUALITY_DECISION_ROLES",
            "ASSET_NUMBERING_ROLES",
            "ASSET_HANDOVER_REQUEST_ROLES",
            "ASSET_HANDOVER_TRANSFER_ROLES",
            "ASSET_COMMISSION_REPAIR_ROLES",
            "ASSET_VERIFICATION_ROLES",
            "ASSET_DISPOSAL_IMPORT_ROLES",
            "/api/asset-management/settings/**",
            "/api/asset-acceptances/**",
            "/api/fixed-assets/**",
            "/api/asset-handovers/**",
            "/api/asset-verifications/**",
            "/api/asset-opening-imports/**"
        );
    }

    @Test
    void fixedAssetResponseIncludesLinkedMaterialNameForOperationalLists() {
        UUID materialId = UUID.randomUUID();
        MXIK mxik = new MXIK();
        mxik.setName("Air conditioners");
        Material material = new Material();
        material.setId(materialId);
        material.setName("Air conditioner");
        material.setMxik(mxik);
        FixedAsset asset = new FixedAsset();
        asset.setId(UUID.randomUUID());
        asset.setMaterialId(materialId);
        asset.setMaterial(material);

        assertThat(new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)).toResponse(asset).getMaterialName())
            .isEqualTo("Air conditioner");
        assertThat(new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)).toResponse(asset).getMaterialMxikName())
            .isEqualTo("Air conditioners");
    }

    @Test
    void fixedAssetRegisterSearchCoversAssetAndMaterialIdentityFields() throws Exception {
        assertThat(source("payload/FixedAssetSearchRequest.java")).contains(
            "String search",
            "String inventoryNumber",
            "String serialNumber"
        );
        assertThat(source("controller/FixedAssetController.java")).contains(
            "@RequestParam(required = false) String search",
            "search, inventoryNumber, serialNumber, category, depreciationMethod, query"
        );
        assertThat(source("repository/specification/FixedAssetSpecification.java")).contains(
            "request.search()",
            "root.get(\"name\")",
            "root.get(\"assetCode\")",
            "root.get(\"inventoryNumber\")",
            "root.get(\"serialNumber\")",
            "root.join(\"material\", JoinType.LEFT).get(\"name\")",
            "root.join(\"material\", JoinType.LEFT).get(\"code\")"
        );
    }

    private void assertEnum(String name, String... values) throws Exception {
        String source = source("enums/" + name + ".java");
        assertThat(source).contains("public enum " + name);
        for (String value : values) {
            assertThat(source).contains(value);
        }
    }

    private String source(String relative) throws Exception {
        return Files.readString(MAIN.resolve(relative));
    }
}
