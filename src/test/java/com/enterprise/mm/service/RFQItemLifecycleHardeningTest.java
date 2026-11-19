package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.RFQItemMapper;
import com.enterprise.mm.payload.RFQItemCreateRequest;
import com.enterprise.mm.payload.RFQItemUpdateRequest;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
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
class RFQItemLifecycleHardeningTest {
    @Mock private RFQItemRepository repository; @Mock private RFQItemMapper mapper;
    @Mock private MaterialCatalogGuard materialCatalogGuard; @Mock private ProcurementLineValidationService lineValidationService;
    @Mock private AuditService auditService; @Mock private RoleDataScopeService roleDataScopeService; @Mock private RFQRepository rfqRepository;
    @Mock private RfqAggregationService rfqAggregationService; @Mock private RFQPurchaseRequestSourceRepository sourceRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    @InjectMocks private RFQItemService service;
    @AfterEach void clearContext() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void createAllowsDraftParent() {
        UUID parentId = UUID.randomUUID(); RFQItemCreateRequest dto = new RFQItemCreateRequest(); dto.setRfqId(parentId);
        RFQItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item); parent(parentId, "DRAFT");
        assertThat(service.create(dto)).isNull();
    }
    @Test void createResolvesPurchaseRequestByIdAfterRootUpdateClearsAssociation() {
        UUID parentId = UUID.randomUUID(); UUID requestId = UUID.randomUUID();
        RFQItemCreateRequest dto = new RFQItemCreateRequest(); dto.setRfqId(parentId);
        RFQItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item);
        RFQ parent = new RFQ(); parent.setId(parentId); parent.setTenantId("default"); parent.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); parent.setStatus("DRAFT"); parent.setPurchaseRequestId(requestId); parent.setPurchaseRequest(null);
        PurchaseRequest request = new PurchaseRequest(); request.setId(requestId); request.setTenantId("default"); request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        when(rfqRepository.findByIdAndIsDeletedFalse(parentId)).thenReturn(Optional.of(parent));
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(requestId)).thenReturn(Optional.of(request));
        when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(true);

        assertThat(service.create(dto)).isNull();
    }
    @Test void createRejectsOpenParent() {
        UUID parentId = UUID.randomUUID(); RFQItemCreateRequest dto = new RFQItemCreateRequest(); dto.setRfqId(parentId);
        RFQItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item); parent(parentId, "OPEN");
        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class).hasMessage("RFQ items can be changed only while parent is DRAFT or AMENDMENT_DRAFT");
    }
    @Test void createRejectsClosedParent() {
        UUID parentId = UUID.randomUUID(); RFQItemCreateRequest dto = new RFQItemCreateRequest(); dto.setRfqId(parentId);
        RFQItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item); parent(parentId, "CLOSED");
        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class).hasMessage("RFQ items can be changed only while parent is DRAFT or AMENDMENT_DRAFT");
    }
    @Test void updateRejectsClosedParent() {
        RFQItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getRfqId(), "CLOSED"); when(repository.save(item)).thenReturn(item);
        assertThatThrownBy(() -> service.update(item.getId(), update(item.getRfqId()))).isInstanceOf(BusinessException.class).hasMessage("RFQ items can be changed only while parent is DRAFT or AMENDMENT_DRAFT");
    }
    @Test void updateRejectsReparenting() {
        RFQItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getRfqId(), "DRAFT"); RFQItemUpdateRequest dto = update(UUID.randomUUID());
        org.mockito.Mockito.doAnswer(invocation -> { item.setRfqId(dto.getRfqId()); return null; }).when(mapper).updateEntity(item, dto); when(repository.save(item)).thenReturn(item);
        assertThatThrownBy(() -> service.update(item.getId(), dto)).isInstanceOf(BusinessException.class).hasMessage("RFQ item cannot be moved to another RFQ");
    }
    @Test void deleteRejectsClosedParent() { RFQItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getRfqId(), "CLOSED"); assertThatThrownBy(() -> service.softDelete(item.getId())).isInstanceOf(BusinessException.class); }
    @Test void restoreRejectsClosedParent() { RFQItem item = item(UUID.randomUUID(), UUID.randomUUID()); item.softDelete(); when(repository.findAnyById(item.getId())).thenReturn(Optional.of(item)); parent(item.getRfqId(), "CLOSED"); assertThatThrownBy(() -> service.restore(item.getId())).isInstanceOf(BusinessException.class); }

    @Test void createRejectsParentPurchaseRequestOutsideDocumentScope() {
        UUID parentId = UUID.randomUUID(); RFQItemCreateRequest dto = new RFQItemCreateRequest(); dto.setRfqId(parentId);
        RFQItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item); parent(parentId, "DRAFT");
        when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(NotFoundException.class);
    }

    @Test void updatePreservesWorkflowStatus() {
        RFQItem item = item(UUID.randomUUID(), UUID.randomUUID()); item.setStatus("EVALUATED"); existing(item); parent(item.getRfqId(), "DRAFT");
        RFQItemUpdateRequest dto = update(item.getRfqId()); dto.setStatus("ACTIVE");
        org.mockito.Mockito.doAnswer(invocation -> { item.setStatus(dto.getStatus()); return null; }).when(mapper).updateEntity(item, dto);
        when(repository.save(item)).thenReturn(item);
        service.update(item.getId(), dto);
        assertThat(item.getStatus()).isEqualTo("EVALUATED");
    }

    private void existing(RFQItem item) { when(repository.findByIdAndIsDeletedFalse(item.getId())).thenReturn(Optional.of(item)); }
    private void parent(UUID id, String status) { RFQ parent = new RFQ(); parent.setId(id); parent.setTenantId("default"); parent.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); parent.setStatus(status); PurchaseRequest request = new PurchaseRequest(); request.setId(UUID.randomUUID()); request.setTenantId("default"); request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); parent.setPurchaseRequest(request); parent.setPurchaseRequestId(request.getId()); lenient().when(rfqRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(parent)); lenient().when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted()); lenient().when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(true); }
    private RFQItem item(UUID id, UUID parentId) { TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID); RFQItem item = new RFQItem(); item.setId(id); item.setRfqId(parentId); item.setTenantId("default"); item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); item.setStatus("ACTIVE"); return item; }
    private RFQItemUpdateRequest update(UUID parentId) { RFQItemUpdateRequest dto = new RFQItemUpdateRequest(); dto.setRfqId(parentId); return dto; }
}
