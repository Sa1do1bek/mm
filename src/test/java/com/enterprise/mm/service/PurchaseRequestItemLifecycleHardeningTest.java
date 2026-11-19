package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PurchaseRequestItemMapper;
import com.enterprise.mm.payload.PurchaseRequestItemCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestItemUpdateRequest;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PurchaseRequestItemLifecycleHardeningTest {
    @Mock private PurchaseRequestItemRepository repository;
    @Mock private PurchaseRequestItemMapper mapper;
    @Mock private MaterialCatalogGuard materialCatalogGuard;
    @Mock private ProcurementLineValidationService lineValidationService;
    @Mock private AuditService auditService;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private RfqAggregationService rfqAggregationService;
    @Mock private com.enterprise.mm.repository.DemandPlanItemPurchaseRequestAllocationRepository demandAllocationRepository;
    @Mock private com.enterprise.mm.repository.DemandPlanItemRepository demandItemRepository;
    @Mock private ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    @Mock private ProcurementDocumentCategoryValidator documentCategoryValidator;
    @InjectMocks private PurchaseRequestItemService service;

    @AfterEach void clearContext() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void createRejectsApprovedParent() {
        UUID parentId = UUID.randomUUID();
        PurchaseRequestItemCreateRequest dto = new PurchaseRequestItemCreateRequest();
        dto.setPurchaseRequestId(parentId);
        PurchaseRequestItem item = item(UUID.randomUUID(), parentId);
        when(mapper.toEntity(dto)).thenReturn(item);
        when(repository.save(item)).thenReturn(item);
        parent(parentId, PurchaseRequestStatus.APPROVED);

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class)
            .hasMessage("Purchase request items can be changed only while parent is DRAFT or RETURNED");
    }

    @Test void updateRejectsApprovedParent() {
        PurchaseRequestItem item = item(UUID.randomUUID(), UUID.randomUUID());
        existing(item);
        parent(item.getPurchaseRequestId(), PurchaseRequestStatus.APPROVED);
        when(repository.save(item)).thenReturn(item);

        assertThatThrownBy(() -> service.update(item.getId(), update(item.getPurchaseRequestId())))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase request items can be changed only while parent is DRAFT or RETURNED");
    }

    @Test void updateRejectsReparenting() {
        PurchaseRequestItem item = item(UUID.randomUUID(), UUID.randomUUID());
        existing(item);
        parent(item.getPurchaseRequestId(), PurchaseRequestStatus.DRAFT);
        PurchaseRequestItemUpdateRequest dto = update(UUID.randomUUID());
        org.mockito.Mockito.doAnswer(invocation -> { item.setPurchaseRequestId(dto.getPurchaseRequestId()); return null; })
            .when(mapper).updateEntity(item, dto);
        when(repository.save(item)).thenReturn(item);

        assertThatThrownBy(() -> service.update(item.getId(), dto)).isInstanceOf(BusinessException.class)
            .hasMessage("Purchase request item cannot be moved to another purchase request");
    }

    @Test void deleteRejectsApprovedParent() {
        PurchaseRequestItem item = item(UUID.randomUUID(), UUID.randomUUID());
        existing(item);
        parent(item.getPurchaseRequestId(), PurchaseRequestStatus.APPROVED);
        assertThatThrownBy(() -> service.softDelete(item.getId())).isInstanceOf(BusinessException.class);
    }

    @Test void restoreRejectsApprovedParent() {
        PurchaseRequestItem item = item(UUID.randomUUID(), UUID.randomUUID());
        item.softDelete();
        when(repository.findAnyById(item.getId())).thenReturn(Optional.of(item));
        parent(item.getPurchaseRequestId(), PurchaseRequestStatus.APPROVED);
        assertThatThrownBy(() -> service.restore(item.getId())).isInstanceOf(BusinessException.class);
    }

    @Test void createRejectsParentOutsideDocumentScope() {
        UUID parentId = UUID.randomUUID();
        PurchaseRequestItemCreateRequest dto = new PurchaseRequestItemCreateRequest();
        dto.setPurchaseRequestId(parentId);
        PurchaseRequestItem item = item(UUID.randomUUID(), parentId);
        when(mapper.toEntity(dto)).thenReturn(item);
        when(repository.save(item)).thenReturn(item);
        parent(parentId, PurchaseRequestStatus.DRAFT);
        when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(false);

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(NotFoundException.class);
    }

    @Test void updatePreservesWorkflowStatus() {
        PurchaseRequestItem item = item(UUID.randomUUID(), UUID.randomUUID());
        item.setStatus("ALLOCATED");
        existing(item);
        parent(item.getPurchaseRequestId(), PurchaseRequestStatus.DRAFT);
        PurchaseRequestItemUpdateRequest dto = update(item.getPurchaseRequestId());
        dto.setStatus("ACTIVE");
        org.mockito.Mockito.doAnswer(invocation -> { item.setStatus(dto.getStatus()); return null; })
            .when(mapper).updateEntity(item, dto);
        when(repository.save(item)).thenReturn(item);

        service.update(item.getId(), dto);

        assertThat(item.getStatus()).isEqualTo("ALLOCATED");
    }

    private void existing(PurchaseRequestItem item) { when(repository.findByIdAndIsDeletedFalse(item.getId())).thenReturn(Optional.of(item)); }
    private void parent(UUID id, PurchaseRequestStatus status) {
        PurchaseRequest parent = new PurchaseRequest(); parent.setId(id); parent.setTenantId("default");
        parent.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); parent.setStatus(status.name());
        lenient().when(purchaseRequestRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(parent));
        lenient().when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(true);
    }
    private PurchaseRequestItem item(UUID id, UUID parentId) {
        TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        PurchaseRequestItem item = new PurchaseRequestItem(); item.setId(id); item.setPurchaseRequestId(parentId);
        item.setTenantId("default"); item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); item.setStatus("ACTIVE"); return item;
    }
    private PurchaseRequestItemUpdateRequest update(UUID parentId) { PurchaseRequestItemUpdateRequest dto = new PurchaseRequestItemUpdateRequest(); dto.setPurchaseRequestId(parentId); return dto; }
}
