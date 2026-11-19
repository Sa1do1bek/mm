package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetOpeningImport;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetOpeningImportStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.security.AssetCapabilities;
import com.enterprise.mm.service.AssetOpeningImportService;
import com.enterprise.mm.service.FixedAssetService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixedAssetApprovalSecurityContractTest {
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-approval-security-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void approvalRegistryExposesAllFrozenFixedAssetApprovalModules() {
        AssetOpeningImportService openingImportService = mock(AssetOpeningImportService.class);
        FixedAssetService fixedAssetService = mock(FixedAssetService.class);

        ApprovalModuleRegistry registry = new ApprovalModuleRegistry(List.of(
            new AssetOpeningImportApprovalAdapter(openingImportService),
            new FixedAssetDisposalApprovalAdapter(fixedAssetService)
        ));

        assertThat(registry.supportedModules())
            .containsExactly("ASSET_DISPOSAL", "ASSET_OPENING_IMPORT");
    }

    @Test
    void openingImportApprovalAdapterUsesSharedEngineCallbacksOnly() {
        AssetOpeningImportService service = mock(AssetOpeningImportService.class);
        UUID importId = UUID.randomUUID();
        AssetOpeningImport openingImport = new AssetOpeningImport();
        openingImport.setId(importId);
        openingImport.setTenantId(TenantContext.currentTenant());
        openingImport.setCompanyId(companyId);
        openingImport.setCode("AOI-1");
        openingImport.setOpeningImportStatus(AssetOpeningImportStatus.SUBMITTED);
        when(service.findForApproval(importId)).thenReturn(openingImport);

        AssetOpeningImportApprovalAdapter adapter = new AssetOpeningImportApprovalAdapter(service);

        assertThat(adapter.moduleKey()).isEqualTo("ASSET_OPENING_IMPORT");
        assertThat(adapter.companyId(importId)).isEqualTo(companyId);
        assertThat(adapter.displayName(importId)).isEqualTo("AOI-1");

        adapter.validateCanSubmit(importId);
        adapter.markSubmitted(importId);
        adapter.markApproved(importId, "approved");
        adapter.markRejected(importId, "rejected");

        verify(service).markSubmittedFromApproval(importId);
        verify(service).markApprovedFromApproval(importId);
        verify(service).markRejectedFromApproval(importId);
    }

    @Test
    void disposalApprovalAdapterDisposesThroughAssetServiceAfterApproval() {
        FixedAssetService service = mock(FixedAssetService.class);
        UUID assetId = UUID.randomUUID();
        FixedAsset asset = new FixedAsset();
        asset.setId(assetId);
        asset.setTenantId(TenantContext.currentTenant());
        asset.setCompanyId(companyId);
        asset.setCode("FA-1");
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IDLE);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
        asset.setVersion(7L);
        when(service.findForApproval(assetId)).thenReturn(asset);

        FixedAssetDisposalApprovalAdapter adapter = new FixedAssetDisposalApprovalAdapter(service);

        assertThat(adapter.moduleKey()).isEqualTo("ASSET_DISPOSAL");
        assertThat(adapter.companyId(assetId)).isEqualTo(companyId);
        assertThat(adapter.displayName(assetId)).isEqualTo("FA-1");

        adapter.validateCanSubmit(assetId);
        adapter.markApproved(assetId, "retired");
        adapter.markRejected(assetId, "not enough evidence");

        verify(service).disposeFromApproval(assetId, "retired");
        verify(service).rejectDisposalFromApproval(assetId, "not enough evidence");
    }

    @Test
    void frozenCapabilityNamesAreAvailableForRoleMappingAndFutureAuthorities() {
        assertThat(AssetCapabilities.all()).containsExactly(
            "ASSET_SETTINGS_MANAGE",
            "ASSET_RECEIVE",
            "ASSET_QUALITY_DECIDE",
            "ASSET_NUMBER_ASSIGN",
            "ASSET_NUMBER_CORRECT",
            "ASSET_HANDOVER_REQUEST",
            "ASSET_APPROVE",
            "ASSET_DISPATCH",
            "ASSET_ACKNOWLEDGE",
            "ASSET_COMMISSION",
            "ASSET_REPAIR",
            "ASSET_DISPOSE",
            "ASSET_OPENING_IMPORT",
            "ASSET_VERIFY",
            "ASSET_READ",
            "ASSET_EXPORT"
        );
    }
}
