package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.AssetHandoverRepository;
import com.enterprise.mm.repository.AssetOpeningImportRepository;
import com.enterprise.mm.repository.AssetVerificationRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AttachmentEntityAccessPolicyFixedAssetTest {
    private final FixedAssetRepository fixedAssetRepository = mock(FixedAssetRepository.class);
    private final AssetAcceptanceRepository acceptanceRepository = mock(AssetAcceptanceRepository.class);
    private final AssetAcceptanceUnitRepository acceptanceUnitRepository = mock(AssetAcceptanceUnitRepository.class);
    private final AssetHandoverRepository handoverRepository = mock(AssetHandoverRepository.class);
    private final AssetVerificationRepository verificationRepository = mock(AssetVerificationRepository.class);
    private final AssetOpeningImportRepository openingImportRepository = mock(AssetOpeningImportRepository.class);
    private final StockMovementDocumentRepository movementDocuments = mock(StockMovementDocumentRepository.class);

    private AttachmentEntityAccessPolicy policy;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-attachment-policy-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "asset.manager",
            "n/a",
            java.util.List.of(new SimpleGrantedAuthority("ROLE_MM_MANAGER"))
        ));
        policy = new AttachmentEntityAccessPolicy(
            mock(com.enterprise.mm.service.inventory.InventoryAccessService.class),
            mock(com.enterprise.mm.service.transfer.TransferExceptionStateService.class),
            mock(ServiceRequestRepository.class),
            mock(ServiceExecutionRepository.class),
            mock(ServiceOrderRepository.class),
            mock(ServiceAcceptanceActRepository.class),
            mock(ServiceClaimRepository.class),
            mock(WorkCompletionActRepository.class),
            mock(PurchaseOrderRepository.class),
            mock(PurchaseOrderItemRepository.class),
            mock(ServiceWorkflowAccessService.class),
            mock(SupplierQuotationRepository.class),
            mock(RfqPublishedVersionRepository.class),
            mock(ProcurementMethodDecisionRepository.class),
            fixedAssetRepository,
            acceptanceRepository,
            acceptanceUnitRepository,
            handoverRepository,
            verificationRepository,
            openingImportRepository,
            movementDocuments,
            mock(com.enterprise.mm.repository.InventoryAiDraftRepository.class),
            mock(com.enterprise.mm.repository.InventoryOrderRepository.class),
            mock(com.enterprise.mm.repository.InventoryOrderAllocationRepository.class),
            mock(ResourceVersionAccessService.class),
            mock(org.springframework.beans.factory.ObjectProvider.class)
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void fixedAssetEvidenceUploadRequiresAnInScopeAssetAggregate() {
        UUID assetId = UUID.randomUUID();

        assertThat(policy.canUpload("FIXED_ASSET", assetId, "COMMISSIONING_EVIDENCE")).isFalse();
    }

    @Test
    void profilePhotoAccessIsLimitedToTokenOwner() {
        UUID ownerId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "asset.manager",
            new com.enterprise.mm.security.AuthenticatedTokenState(ownerId, 0),
            java.util.List.of(new SimpleGrantedAuthority("ROLE_MM_MANAGER"))
        ));

        policy.requireRead("USER_PROFILE", ownerId);
        policy.requireDelete("USER_PROFILE", ownerId, "asset.manager");
        assertThatThrownBy(() -> policy.requireRead("USER_PROFILE", UUID.randomUUID()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireUpload("USER_PROFILE", ownerId, "PROFILE_PHOTO"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void fixedAssetEvidenceUploadUsesExistingAttachmentPolicyWhenAggregateIsInScope() {
        UUID assetId = UUID.randomUUID();
        FixedAsset asset = new FixedAsset();
        asset.setId(assetId);
        asset.setTenantId(TenantContext.currentTenant());
        asset.setCompanyId(companyId);
        when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, assetId
        )).thenReturn(Optional.of(asset));

        assertThat(policy.canUpload("FIXED_ASSET", assetId, "COMMISSIONING_EVIDENCE")).isTrue();
    }

    @Test
    void assetAcceptanceEvidenceUploadRequiresAnInScopeAcceptanceAggregate() {
        when(acceptanceRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(Optional.empty());

        assertThat(policy.canUpload("ASSET_ACCEPTANCE", UUID.randomUUID(), "QUALITY_EVIDENCE")).isFalse();
    }

    @Test
    void postedPhysicalStockProofCannotBeDeletedEvenByItsUploader() {
        var document = new com.enterprise.mm.entity.StockMovementDocument();
        document.setId(UUID.randomUUID()); document.setTenantId(TenantContext.currentTenant()); document.setCompanyId(companyId);
        document.setReferenceType(WarehouseStockFactService.REFERENCE_TYPE); document.setStatus("POSTED");
        when(movementDocuments.findByIdAndIsDeletedFalse(document.getId())).thenReturn(Optional.of(document));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.requireDelete("STOCK_MOVEMENT_DOCUMENT", document.getId(), "asset.manager"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
            .hasMessageContaining("cannot be removed");
    }
}
