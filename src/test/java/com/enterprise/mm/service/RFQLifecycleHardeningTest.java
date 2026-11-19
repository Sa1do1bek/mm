package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.payload.RFQCreateRequest;
import com.enterprise.mm.payload.RFQUpdateRequest;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.rfq.SupplierPortalEligibilityValidator;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RFQLifecycleHardeningTest {
    @Mock private RFQRepository repository;
    @Mock private RFQMapper mapper;
    @Mock private RFQItemRepository itemRepository;
    @Mock private RFQItemService itemService;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private RfqAggregationService rfqAggregationService;
    @Mock private RFQPurchaseRequestSourceRepository sourceRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private RfqSupplierInvitationRepository invitationRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProcurementProcedureRepository procedureRepository;
    @Spy private ProcurementMethodPolicy procurementMethodPolicy = new ProcurementMethodPolicy();
    @Mock private SupplierPortalEligibilityValidator supplierPortalEligibilityValidator;
    @Mock private SupplierEligibilityService supplierEligibilityService;
    @Mock private SupplierAccreditationDemandResolver accreditationDemandResolver;
    @Mock private SupplierGovernanceActionPolicy supplierGovernanceActionPolicy;
    @Mock private UserRepository userRepository;
    @InjectMocks private RFQService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createIgnoresClientSuppliedWorkflowStatus() {
        RFQCreateRequest request = new RFQCreateRequest();
        request.setStatus("CLOSED");
        RFQ entity = new RFQ();
        entity.setStatus("CLOSED");

        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        service.create(request);

        assertThat(entity.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void updateRejectsDirectChangesToOpenRfq() {
        UUID id = UUID.randomUUID();
        RFQ entity = rfq(id, "OPEN");
        RFQUpdateRequest update = new RFQUpdateRequest();
        update.setName("Updated RFQ");
        update.setStatus("CLOSED");

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(id, update))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Published RFQ requires an amendment");
        verifyNoInteractions(mapper);
    }

    @Test
    void updateRejectsClosedRfq() {
        UUID id = UUID.randomUUID();
        RFQ entity = rfq(id, "CLOSED");
        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(id, new RFQUpdateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Published RFQ requires an amendment");
        verifyNoInteractions(mapper);
    }

    private RFQ rfq(UUID id, String status) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());
        RFQ entity = new RFQ();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setStatus(status);
        return entity;
    }
}
