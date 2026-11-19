package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PurchaseOrderItemMapper;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemUpdateRequest;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
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
class PurchaseOrderItemLifecycleHardeningTest {
    @Mock private PurchaseOrderItemRepository repository; @Mock private PurchaseOrderItemMapper mapper;
    @Mock private MaterialCatalogGuard materialCatalogGuard; @Mock private ProcurementLineValidationService lineValidationService;
    @Mock private AuditService auditService; @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    @Mock private MaterialCostObservationService materialCostObservationService;
    @Mock private ProcurementLineageEnricher procurementLineageEnricher;
    @Mock private ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    @Mock private ProcurementDocumentCategoryValidator documentCategoryValidator;
    @Mock private PurchaseOrderReceivingModeService receivingModeService;
    @InjectMocks private PurchaseOrderItemService service;
    @AfterEach void clearContext() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void createRejectsApprovedParent() { UUID parentId = UUID.randomUUID(); PurchaseOrderItemCreateRequest dto = new PurchaseOrderItemCreateRequest(); dto.setPurchaseOrderId(parentId); PurchaseOrderItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item); parent(parentId, PurchaseOrderStatus.APPROVED); assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class).hasMessage("Purchase order items can be changed only while parent is DRAFT or RETURNED"); }
    @Test void updateRejectsApprovedParent() { PurchaseOrderItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getPurchaseOrderId(), PurchaseOrderStatus.APPROVED); when(repository.save(item)).thenReturn(item); assertThatThrownBy(() -> service.update(item.getId(), update(item.getPurchaseOrderId()))).isInstanceOf(BusinessException.class).hasMessage("Purchase order items can be changed only while parent is DRAFT or RETURNED"); }
    @Test void updateRejectsReparenting() { PurchaseOrderItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getPurchaseOrderId(), PurchaseOrderStatus.DRAFT); PurchaseOrderItemUpdateRequest dto = update(UUID.randomUUID()); org.mockito.Mockito.doAnswer(invocation -> { item.setPurchaseOrderId(dto.getPurchaseOrderId()); return null; }).when(mapper).updateEntity(item, dto); when(repository.save(item)).thenReturn(item); assertThatThrownBy(() -> service.update(item.getId(), dto)).isInstanceOf(BusinessException.class).hasMessage("Purchase order item cannot be moved to another purchase order"); }
    @Test void deleteRejectsApprovedParent() { PurchaseOrderItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getPurchaseOrderId(), PurchaseOrderStatus.APPROVED); assertThatThrownBy(() -> service.softDelete(item.getId())).isInstanceOf(BusinessException.class); }
    @Test void restoreRejectsApprovedParent() { PurchaseOrderItem item = item(UUID.randomUUID(), UUID.randomUUID()); item.softDelete(); when(repository.findAnyById(item.getId())).thenReturn(Optional.of(item)); parent(item.getPurchaseOrderId(), PurchaseOrderStatus.APPROVED); assertThatThrownBy(() -> service.restore(item.getId())).isInstanceOf(BusinessException.class); }

    @Test void createRejectsParentPurchaseRequestOutsideDocumentScope() { UUID parentId = UUID.randomUUID(); PurchaseOrderItemCreateRequest dto = new PurchaseOrderItemCreateRequest(); dto.setPurchaseOrderId(parentId); PurchaseOrderItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item); parent(parentId, PurchaseOrderStatus.DRAFT); when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(false); assertThatThrownBy(() -> service.create(dto)).isInstanceOf(NotFoundException.class); }

    @Test void updateRejectsAwardFinalizedPurchaseOrderLineMutation() { PurchaseOrderItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getPurchaseOrderId(), PurchaseOrderStatus.DRAFT); when(procurementAwardOutcomeRepository.existsByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, item.getPurchaseOrderId())).thenReturn(true); assertThatThrownBy(() -> service.update(item.getId(), update(item.getPurchaseOrderId()))).isInstanceOf(BusinessException.class).hasMessage("Award-finalized purchase order items cannot be changed; create an amendment instead"); }

    @Test void updatePreservesWorkflowDerivedFields() {
        PurchaseOrderItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getPurchaseOrderId(), PurchaseOrderStatus.DRAFT);
        UUID serviceOrderId = UUID.randomUUID(), serviceActId = UUID.randomUUID(), warehouseId = UUID.randomUUID(), locationId = UUID.randomUUID();
        item.setStatus("SERVICE_ACT_LINKED"); item.setServiceOrderId(serviceOrderId); item.setServiceActId(serviceActId);
        item.setReceivedQuantity(BigDecimal.ONE); item.setRejectedQuantity(BigDecimal.valueOf(2)); item.setDamagedQuantity(BigDecimal.TEN);
        item.setShortageReason("short"); item.setReceiptBatchNumber("B-1"); item.setReceiptWarehouseId(warehouseId);
        item.setReceiptStorageLocationId(locationId); item.setQualityStatus("ACCEPTED"); item.setAccreditationWarning("warning");
        PurchaseOrderItemUpdateRequest dto = update(item.getPurchaseOrderId()); dto.setStatus("OPEN"); dto.setServiceOrderId(UUID.randomUUID()); dto.setServiceActId(UUID.randomUUID());
        dto.setReceivedQuantity(BigDecimal.ZERO); dto.setRejectedQuantity(BigDecimal.ZERO); dto.setDamagedQuantity(BigDecimal.ZERO);
        dto.setShortageReason(null); dto.setReceiptBatchNumber(null); dto.setReceiptWarehouseId(null); dto.setReceiptStorageLocationId(null); dto.setQualityStatus(null); dto.setAccreditationWarning(null);
        org.mockito.Mockito.doAnswer(invocation -> { item.setStatus(dto.getStatus()); item.setServiceOrderId(dto.getServiceOrderId()); item.setServiceActId(dto.getServiceActId()); item.setReceivedQuantity(dto.getReceivedQuantity()); item.setRejectedQuantity(dto.getRejectedQuantity()); item.setDamagedQuantity(dto.getDamagedQuantity()); item.setShortageReason(dto.getShortageReason()); item.setReceiptBatchNumber(dto.getReceiptBatchNumber()); item.setReceiptWarehouseId(dto.getReceiptWarehouseId()); item.setReceiptStorageLocationId(dto.getReceiptStorageLocationId()); item.setQualityStatus(dto.getQualityStatus()); item.setAccreditationWarning(dto.getAccreditationWarning()); return null; }).when(mapper).updateEntity(item, dto);
        when(repository.save(item)).thenReturn(item);

        service.update(item.getId(), dto);

        assertThat(item.getStatus()).isEqualTo("SERVICE_ACT_LINKED"); assertThat(item.getServiceOrderId()).isEqualTo(serviceOrderId); assertThat(item.getServiceActId()).isEqualTo(serviceActId);
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("1"); assertThat(item.getRejectedQuantity()).isEqualByComparingTo("2"); assertThat(item.getDamagedQuantity()).isEqualByComparingTo("10");
        assertThat(item.getShortageReason()).isEqualTo("short"); assertThat(item.getReceiptBatchNumber()).isEqualTo("B-1"); assertThat(item.getReceiptWarehouseId()).isEqualTo(warehouseId); assertThat(item.getReceiptStorageLocationId()).isEqualTo(locationId); assertThat(item.getQualityStatus()).isEqualTo("ACCEPTED"); assertThat(item.getAccreditationWarning()).isEqualTo("warning");
    }

    private void existing(PurchaseOrderItem item) { when(repository.findByIdAndIsDeletedFalse(item.getId())).thenReturn(Optional.of(item)); }
    private void parent(UUID id, PurchaseOrderStatus status) { PurchaseOrder parent = new PurchaseOrder(); parent.setId(id); parent.setTenantId("default"); parent.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); parent.setStatus(status.name()); PurchaseRequest request = new PurchaseRequest(); request.setId(UUID.randomUUID()); request.setTenantId("default"); request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); parent.setPurchaseRequest(request); parent.setPurchaseRequestId(request.getId()); lenient().when(purchaseOrderRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(parent)); lenient().when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(true); }
    private PurchaseOrderItem item(UUID id, UUID parentId) { TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID); PurchaseOrderItem item = new PurchaseOrderItem(); item.setId(id); item.setPurchaseOrderId(parentId); item.setTenantId("default"); item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); item.setStatus("ACTIVE"); item.setLineType(ProcurementLineType.MATERIAL); item.setMaterialId(UUID.randomUUID()); return item; }
    private PurchaseOrderItemUpdateRequest update(UUID parentId) { PurchaseOrderItemUpdateRequest dto = new PurchaseOrderItemUpdateRequest(); dto.setPurchaseOrderId(parentId); return dto; }
}
