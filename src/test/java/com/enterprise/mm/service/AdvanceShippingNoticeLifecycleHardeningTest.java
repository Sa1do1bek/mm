package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.AdvanceShippingNoticeItemMapper;
import com.enterprise.mm.mapper.AdvanceShippingNoticeMapper;
import com.enterprise.mm.payload.AdvanceShippingNoticeUpdateRequest;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanceShippingNoticeLifecycleHardeningTest {
    @Mock private AdvanceShippingNoticeRepository repository;
    @Mock private AuditService auditService;
    @Mock private AdvanceShippingNoticeItemRepository itemRepository;
    @Mock private AdvanceShippingNoticeItemMapper itemMapper;
    @Mock private SupplyLogisticsGuard guard;
    @Mock private MaterialCatalogGuard materialCatalogGuard;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private MaterialRepository materialRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void updateCannotRollSentAsnBackToDraft() {
        AdvanceShippingNotice asn = asn(AdvanceShippingNoticeStatus.SENT);
        existing(asn);
        AdvanceShippingNoticeUpdateRequest request = updateFor(asn);
        request.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());

        assertThatThrownBy(() -> service().update(asn.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Only DRAFT ASN can be edited");

        verify(repository, never()).save(asn);
    }

    @Test
    void updateCannotMoveDraftAsnToAnotherPurchaseOrder() {
        AdvanceShippingNotice asn = asn(AdvanceShippingNoticeStatus.DRAFT);
        existing(asn);
        AdvanceShippingNoticeUpdateRequest request = updateFor(asn);
        request.setPurchaseOrderId(UUID.randomUUID());

        assertThatThrownBy(() -> service().update(asn.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN cannot be moved to another purchase order");
    }

    @Test
    void updatePreservesWorkflowFieldsAndComputedTotals() {
        AdvanceShippingNotice asn = asn(AdvanceShippingNoticeStatus.DRAFT);
        LocalDateTime sentAt = LocalDateTime.now().minusDays(3);
        LocalDateTime confirmedAt = LocalDateTime.now().minusDays(2);
        LocalDateTime receivedAt = LocalDateTime.now().minusDays(1);
        asn.setSentAt(sentAt);
        asn.setConfirmedAt(confirmedAt);
        asn.setReceivedAt(receivedAt);
        asn.setTotalQuantity(BigDecimal.valueOf(12));
        asn.setTotalAmount(BigDecimal.valueOf(450));
        existing(asn);
        when(repository.save(asn)).thenReturn(asn);
        AdvanceShippingNoticeUpdateRequest request = updateFor(asn);
        request.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
        request.setSentAt(LocalDateTime.now());
        request.setConfirmedAt(LocalDateTime.now());
        request.setReceivedAt(LocalDateTime.now());
        request.setTotalQuantity(BigDecimal.ZERO);
        request.setTotalAmount(BigDecimal.ZERO);

        service().update(asn.getId(), request);

        assertThat(asn.getStatus()).isEqualTo(AdvanceShippingNoticeStatus.DRAFT.name());
        assertThat(asn.getSentAt()).isEqualTo(sentAt);
        assertThat(asn.getConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(asn.getReceivedAt()).isEqualTo(receivedAt);
        assertThat(asn.getTotalQuantity()).isEqualByComparingTo("12");
        assertThat(asn.getTotalAmount()).isEqualByComparingTo("450");
    }

    @Test
    void markReceivedDirectsCallersToPurchaseOrderReceipt() {
        AdvanceShippingNotice asn = asn(AdvanceShippingNoticeStatus.CONFIRMED);
        existing(asn);

        assertThatThrownBy(() -> service().markReceived(asn.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN receipt must be posted through purchase order receipt orchestration");

        verify(repository, never()).save(asn);
    }

    private AdvanceShippingNoticeService service() {
        return new AdvanceShippingNoticeService(
            repository,
            new AdvanceShippingNoticeMapper(),
            auditService,
            itemRepository,
            itemMapper,
            guard,
            materialCatalogGuard,
            integrationEventService,
            roleDataScopeService,
            supplierAccreditationService,
            purchaseOrderItemRepository,
            materialRepository
        );
    }

    private void existing(AdvanceShippingNotice asn) {
        when(repository.findByIdAndIsDeletedFalse(asn.getId())).thenReturn(Optional.of(asn));
    }

    private AdvanceShippingNotice asn(AdvanceShippingNoticeStatus status) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(UUID.randomUUID());
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setStatus(status.name());
        asn.setSupplierId(UUID.randomUUID());
        asn.setPurchaseOrderId(UUID.randomUUID());
        asn.setContractId(UUID.randomUUID());
        asn.setCode("ASN-1");
        asn.setAsnNumber("ASN-1");
        return asn;
    }

    private AdvanceShippingNoticeUpdateRequest updateFor(AdvanceShippingNotice asn) {
        AdvanceShippingNoticeUpdateRequest request = new AdvanceShippingNoticeUpdateRequest();
        request.setCode(asn.getCode());
        request.setAsnNumber(asn.getAsnNumber());
        request.setStatus(asn.getStatus());
        request.setSupplierId(asn.getSupplierId());
        request.setPurchaseOrderId(asn.getPurchaseOrderId());
        request.setContractId(asn.getContractId());
        return request;
    }
}
