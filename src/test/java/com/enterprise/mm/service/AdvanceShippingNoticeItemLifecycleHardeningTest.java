package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.AdvanceShippingNoticeItemMapper;
import com.enterprise.mm.payload.AdvanceShippingNoticeItemUpdateRequest;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanceShippingNoticeItemLifecycleHardeningTest {
    @Mock private AdvanceShippingNoticeItemRepository repository;
    @Mock private AuditService auditService;
    @Mock private SupplyLogisticsGuard guard;
    @Mock private MaterialCatalogGuard materialCatalogGuard;
    @Mock private AdvanceShippingNoticeService asnService;
    @Mock private RoleDataScopeService roleDataScopeService;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void updateRejectsSentOriginalParent() {
        AdvanceShippingNoticeItem item = item();
        existing(item);
        parent(item.getAsnId(), AdvanceShippingNoticeStatus.SENT);
        UUID replacementAsnId = UUID.randomUUID();
        parent(replacementAsnId, AdvanceShippingNoticeStatus.DRAFT);

        assertThatThrownBy(() -> service().update(item.getId(), updateFor(item, replacementAsnId)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN items can be changed only while ASN is DRAFT");
    }

    @Test
    void updateRejectsReparentingBetweenDraftAsns() {
        AdvanceShippingNoticeItem item = item();
        existing(item);
        parent(item.getAsnId(), AdvanceShippingNoticeStatus.DRAFT);
        UUID newAsnId = UUID.randomUUID();
        parent(newAsnId, AdvanceShippingNoticeStatus.DRAFT);

        assertThatThrownBy(() -> service().update(item.getId(), updateFor(item, newAsnId)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN item cannot be moved to another ASN");
    }

    @Test
    void deleteRejectsSentParent() {
        AdvanceShippingNoticeItem item = item();
        existing(item);
        parent(item.getAsnId(), AdvanceShippingNoticeStatus.SENT);

        assertThatThrownBy(() -> service().softDelete(item.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN items can be changed only while ASN is DRAFT");
    }

    @Test
    void restoreRejectsSentParent() {
        AdvanceShippingNoticeItem item = item();
        item.softDelete();
        when(repository.findAnyById(item.getId())).thenReturn(Optional.of(item));
        parent(item.getAsnId(), AdvanceShippingNoticeStatus.SENT);

        assertThatThrownBy(() -> service().restore(item.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN items can be changed only while ASN is DRAFT");
    }

    @Test
    void updatePreservesItemStatus() {
        AdvanceShippingNoticeItem item = item();
        existing(item);
        parent(item.getAsnId(), AdvanceShippingNoticeStatus.DRAFT);
        when(repository.save(any(AdvanceShippingNoticeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AdvanceShippingNoticeItemUpdateRequest request = updateFor(item, item.getAsnId());
        request.setStatus("CANCELLED");

        service().update(item.getId(), request);

        assertThat(item.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void createDelegatesPurchaseOrderLineReadinessValidation() {
        AdvanceShippingNoticeItem item = item();
        parent(item.getAsnId(), AdvanceShippingNoticeStatus.DRAFT);
        var request = updateFor(item, item.getAsnId());
        AdvanceShippingNoticeService readinessService = mock(AdvanceShippingNoticeService.class, invocation -> {
            if ("validateItemReadiness".equals(invocation.getMethod().getName())) {
                throw new BusinessException("invalid ASN purchase order line");
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        assertThatThrownBy(() -> service(readinessService).create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("invalid ASN purchase order line");
    }

    private AdvanceShippingNoticeItemService service() {
        return service(asnService);
    }

    private AdvanceShippingNoticeItemService service(AdvanceShippingNoticeService readinessService) {
        return new AdvanceShippingNoticeItemService(
            repository,
            new AdvanceShippingNoticeItemMapper(),
            auditService,
            guard,
            materialCatalogGuard,
            readinessService,
            roleDataScopeService
        );
    }

    private void existing(AdvanceShippingNoticeItem item) {
        when(repository.findByIdAndIsDeletedFalse(item.getId())).thenReturn(Optional.of(item));
    }

    private void parent(UUID id, AdvanceShippingNoticeStatus status) {
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(id);
        asn.setStatus(status.name());
        lenient().when(guard.asn(id)).thenReturn(asn);
    }

    private AdvanceShippingNoticeItem item() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        AdvanceShippingNoticeItem item = new AdvanceShippingNoticeItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setAsnId(UUID.randomUUID());
        item.setMaterialId(UUID.randomUUID());
        item.setQuantity(BigDecimal.ONE);
        item.setStatus("ACTIVE");
        item.setCode("ASN-ITEM-1");
        return item;
    }

    private AdvanceShippingNoticeItemUpdateRequest updateFor(AdvanceShippingNoticeItem item, UUID asnId) {
        AdvanceShippingNoticeItemUpdateRequest request = new AdvanceShippingNoticeItemUpdateRequest();
        request.setCode(item.getCode());
        request.setName(item.getName());
        request.setStatus(item.getStatus());
        request.setAsnId(asnId);
        request.setMaterialId(item.getMaterialId());
        request.setQuantity(item.getQuantity());
        return request;
    }
}
