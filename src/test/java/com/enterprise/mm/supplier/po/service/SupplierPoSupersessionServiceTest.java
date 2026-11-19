package com.enterprise.mm.supplier.po.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SupplierMagicLink;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.repository.SupplierMagicLinkRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierPoSupersessionServiceTest {
    @Mock private SupplierPoDispatchRepository dispatchRepository;
    @Mock private SupplierPoResponseRepository responseRepository;
    @Mock private SupplierPoCommitmentRepository commitmentRepository;
    @Mock private SupplierMagicLinkRepository magicLinkRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private SupplierPoSupersessionService service;

    private UUID purchaseOrderId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrderId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void supersedesCurrentDispatchResponseUnfulfilledCommitmentsAndRevokesMagicLinks() {
        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setId(UUID.randomUUID());
        dispatch.setDispatchStatus("SENT");
        dispatch.setCurrent(true);

        SupplierPoResponse response = new SupplierPoResponse();
        response.setId(UUID.randomUUID());
        response.setCurrent(true);

        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(UUID.randomUUID());
        commitment.setStatus("PROPOSED");
        commitment.setCurrent(true);

        SupplierMagicLink link = new SupplierMagicLink();
        link.setId(UUID.randomUUID());
        link.setStatus("ISSUED");

        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId
        )).thenReturn(Optional.of(dispatch));
        when(dispatchRepository.save(any(SupplierPoDispatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(responseRepository.findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, dispatch.getId()
        )).thenReturn(Optional.of(response));
        when(responseRepository.save(any(SupplierPoResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId, List.of("PROPOSED", "CONFIRMED")
        )).thenReturn(List.of(commitment));
        when(commitmentRepository.save(any(SupplierPoCommitment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(magicLinkRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId, "ISSUED"
        )).thenReturn(List.of(link));
        when(magicLinkRepository.save(any(SupplierMagicLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = service.supersedeForPoChange(purchaseOrderId, "supplier changed");

        assertThat(result).isTrue();
        assertThat(dispatch.getDispatchStatus()).isEqualTo("SUPERSEDED");
        assertThat(dispatch.isCurrent()).isFalse();
        assertThat(response.getResponseStatus()).isEqualTo("SUPERSEDED");
        assertThat(response.isCurrent()).isFalse();
        assertThat(commitment.getStatus()).isEqualTo("SUPERSEDED");
        assertThat(commitment.isCurrent()).isFalse();
        assertThat(link.getStatus()).isEqualTo("REVOKED");
        assertThat(link.getRevokedAt()).isNotNull();
    }

    @Test
    void isANoOpWhenThereIsNoCurrentDispatch() {
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId
        )).thenReturn(Optional.empty());

        boolean result = service.supersedeForPoChange(purchaseOrderId, "no dispatch yet");

        assertThat(result).isFalse();
    }
}
