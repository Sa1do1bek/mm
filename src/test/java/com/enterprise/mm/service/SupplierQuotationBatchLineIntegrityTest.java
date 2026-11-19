package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.SupplierQuotationMapper;
import com.enterprise.mm.payload.SupplierQuotationCreateRequest;
import com.enterprise.mm.payload.SupplierQuotationItemCreateRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierQuotationBatchLineIntegrityTest {
    @Mock private SupplierQuotationRepository repository;
    @Mock private SupplierQuotationMapper mapper;
    @Mock private AuditService auditService;
    @Mock private SupplierQuotationItemRepository quotationItemRepository;
    @Mock private SupplierQuotationItemService quotationItemService;
    @Mock private RFQRepository rfqRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseOrderMapper purchaseOrderMapper;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private TenderProcessService tenderProcessService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private QuotationSelectionValidationService quotationSelectionValidationService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private MaterialRepository materialRepository;
    @Mock private ProcurementAwardFinalizationService awardFinalizationService;
    @InjectMocks private SupplierQuotationService service;

    @Test
    void createRejectsDuplicateRfqLinesInOneQuotationPayloadBeforeSavingParent() {
        UUID rfqItemId = UUID.randomUUID();
        SupplierQuotationItemCreateRequest first = new SupplierQuotationItemCreateRequest();
        first.setRfqItemId(rfqItemId);
        SupplierQuotationItemCreateRequest duplicate = new SupplierQuotationItemCreateRequest();
        duplicate.setRfqItemId(rfqItemId);
        SupplierQuotationCreateRequest request = new SupplierQuotationCreateRequest();
        request.setItems(List.of(first, duplicate));
        when(mapper.toEntity(request)).thenReturn(new SupplierQuotation());

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("duplicate RFQ line");

        verify(repository, never()).save(any());
        verify(quotationItemService, never()).create(any());
    }
}
