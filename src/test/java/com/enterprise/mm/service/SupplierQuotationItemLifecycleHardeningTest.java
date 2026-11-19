package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierQuotationItemMapper;
import com.enterprise.mm.payload.SupplierQuotationItemCreateRequest;
import com.enterprise.mm.payload.SupplierQuotationItemUpdateRequest;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.RFQItemRepository;
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
class SupplierQuotationItemLifecycleHardeningTest {
    @Mock private SupplierQuotationItemRepository repository; @Mock private SupplierQuotationItemMapper mapper;
    @Mock private MaterialCatalogGuard materialCatalogGuard; @Mock private ProcurementLineValidationService lineValidationService;
    @Mock private AuditService auditService; @Mock private RoleDataScopeService roleDataScopeService; @Mock private SupplierQuotationRepository quotationRepository;
    @Mock private RFQItemRepository rfqItemRepository;
    @Mock private MaterialCostObservationService materialCostObservationService;
    @Mock private ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    @InjectMocks private SupplierQuotationItemService service;
    @AfterEach void clearContext() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void createRejectsSelectedParent() { UUID parentId = UUID.randomUUID(); SupplierQuotationItemCreateRequest dto = new SupplierQuotationItemCreateRequest(); dto.setQuotationId(parentId); SupplierQuotationItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item); parent(parentId, "SELECTED"); assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class).hasMessage("Selected or rejected quotation items cannot be changed"); }
    @Test void updateRejectsRejectedParent() { SupplierQuotationItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getQuotationId(), "REJECTED"); when(repository.save(item)).thenReturn(item); assertThatThrownBy(() -> service.update(item.getId(), update(item.getQuotationId()))).isInstanceOf(BusinessException.class).hasMessage("Selected or rejected quotation items cannot be changed"); }
    @Test void updateRejectsReparenting() { SupplierQuotationItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getQuotationId(), "SUBMITTED"); SupplierQuotationItemUpdateRequest dto = update(UUID.randomUUID()); org.mockito.Mockito.doAnswer(invocation -> { item.setQuotationId(dto.getQuotationId()); return null; }).when(mapper).updateEntity(item, dto); when(repository.save(item)).thenReturn(item); assertThatThrownBy(() -> service.update(item.getId(), dto)).isInstanceOf(BusinessException.class).hasMessage("Quotation item cannot be moved to another quotation"); }
    @Test void deleteRejectsSelectedParent() { SupplierQuotationItem item = item(UUID.randomUUID(), UUID.randomUUID()); existing(item); parent(item.getQuotationId(), "SELECTED"); assertThatThrownBy(() -> service.softDelete(item.getId())).isInstanceOf(BusinessException.class); }
    @Test void restoreRejectsRejectedParent() { SupplierQuotationItem item = item(UUID.randomUUID(), UUID.randomUUID()); item.softDelete(); when(repository.findAnyById(item.getId())).thenReturn(Optional.of(item)); parent(item.getQuotationId(), "REJECTED"); assertThatThrownBy(() -> service.restore(item.getId())).isInstanceOf(BusinessException.class); }

    @Test void createRejectsParentPurchaseRequestOutsideDocumentScope() {
        UUID parentId = UUID.randomUUID(); SupplierQuotationItemCreateRequest dto = new SupplierQuotationItemCreateRequest(); dto.setQuotationId(parentId);
        SupplierQuotationItem item = item(UUID.randomUUID(), parentId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item); parent(parentId, "SUBMITTED");
        when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(NotFoundException.class);
    }

    @Test void updatePreservesWorkflowStatus() {
        SupplierQuotationItem item = item(UUID.randomUUID(), UUID.randomUUID()); item.setStatus("EVALUATED"); existing(item); parent(item.getQuotationId(), "SUBMITTED");
        SupplierQuotationItemUpdateRequest dto = update(item.getQuotationId()); dto.setStatus("ACTIVE");
        org.mockito.Mockito.doAnswer(invocation -> { item.setStatus(dto.getStatus()); return null; }).when(mapper).updateEntity(item, dto);
        when(repository.save(item)).thenReturn(item);
        service.update(item.getId(), dto);
        assertThat(item.getStatus()).isEqualTo("EVALUATED");
    }

    @Test void createRejectsRfqItemOutsideTenantCompanyScope() {
        UUID parentId = UUID.randomUUID(); SupplierQuotation parent = parent(parentId, "SUBMITTED"); UUID sourceId = UUID.randomUUID();
        SupplierQuotationItemCreateRequest dto = new SupplierQuotationItemCreateRequest(); dto.setQuotationId(parentId); dto.setRfqItemId(sourceId);
        SupplierQuotationItem item = item(UUID.randomUUID(), parentId); item.setRfqItemId(sourceId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item);
        RFQItem source = sourceItem(sourceId, parent.getRfqId()); source.setCompanyId(UUID.randomUUID());
        when(rfqItemRepository.findByIdAndIsDeletedFalse(sourceId)).thenReturn(Optional.of(source));
        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(NotFoundException.class);
    }

    @Test void createRejectsRfqItemFromAnotherRfq() {
        UUID parentId = UUID.randomUUID(); parent(parentId, "SUBMITTED"); UUID sourceId = UUID.randomUUID();
        SupplierQuotationItemCreateRequest dto = new SupplierQuotationItemCreateRequest(); dto.setQuotationId(parentId); dto.setRfqItemId(sourceId);
        SupplierQuotationItem item = item(UUID.randomUUID(), parentId); item.setRfqItemId(sourceId); when(mapper.toEntity(dto)).thenReturn(item); when(repository.save(item)).thenReturn(item);
        RFQItem source = sourceItem(sourceId, UUID.randomUUID()); when(rfqItemRepository.findByIdAndIsDeletedFalse(sourceId)).thenReturn(Optional.of(source));
        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class).hasMessage("RFQ item does not belong to quotation RFQ");
    }

    @Test void updateRejectsSourceRfqItemChange() {
        SupplierQuotationItem item = item(UUID.randomUUID(), UUID.randomUUID()); item.setRfqItemId(UUID.randomUUID()); existing(item); parent(item.getQuotationId(), "SUBMITTED"); when(repository.save(item)).thenReturn(item);
        SupplierQuotationItemUpdateRequest dto = update(item.getQuotationId()); dto.setRfqItemId(UUID.randomUUID());
        assertThatThrownBy(() -> service.update(item.getId(), dto)).isInstanceOf(BusinessException.class).hasMessage("Quotation item source RFQ item cannot be changed");
    }

    private void existing(SupplierQuotationItem item) { when(repository.findByIdAndIsDeletedFalse(item.getId())).thenReturn(Optional.of(item)); }
    private SupplierQuotation parent(UUID id, String status) { SupplierQuotation parent = new SupplierQuotation(); parent.setId(id); parent.setTenantId("default"); parent.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); parent.setStatus(status); RFQ rfq = new RFQ(); rfq.setId(UUID.randomUUID()); rfq.setTenantId("default"); rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); PurchaseRequest request = new PurchaseRequest(); request.setId(UUID.randomUUID()); request.setTenantId("default"); request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); rfq.setPurchaseRequest(request); rfq.setPurchaseRequestId(request.getId()); parent.setRfq(rfq); parent.setRfqId(rfq.getId()); lenient().when(quotationRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(parent)); lenient().when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(true); return parent; }
    private RFQItem sourceItem(UUID id, UUID rfqId) { RFQItem source = new RFQItem(); source.setId(id); source.setTenantId("default"); source.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); source.setRfqId(rfqId); return source; }
    private SupplierQuotationItem item(UUID id, UUID parentId) { TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID); SupplierQuotationItem item = new SupplierQuotationItem(); item.setId(id); item.setQuotationId(parentId); item.setTenantId("default"); item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); item.setStatus("ACTIVE"); return item; }
    private SupplierQuotationItemUpdateRequest update(UUID parentId) { SupplierQuotationItemUpdateRequest dto = new SupplierQuotationItemUpdateRequest(); dto.setQuotationId(parentId); return dto; }
}
