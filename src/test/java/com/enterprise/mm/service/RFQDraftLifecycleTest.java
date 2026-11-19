package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.payload.RFQCreateRequest;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.payload.RFQUpdateRequest;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RFQDraftLifecycleTest {
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
    @Mock private UserRepository userRepository;
    @Spy private ProcurementMethodPolicy procurementMethodPolicy = new ProcurementMethodPolicy();
    @InjectMocks private RFQService service;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createAlwaysStartsDraftAndIgnoresClientStatus() {
        RFQCreateRequest request = new RFQCreateRequest();
        request.setCode("RFQ-DRAFT-1");
        request.setName("Draft RFQ");
        request.setStatus("OPEN");
        RFQ entity = new RFQ();
        UUID rfqId = UUID.randomUUID();
        entity.setId(rfqId);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setStatus(request.getStatus());
        entity.setPublishedAt(LocalDateTime.now());
        entity.setPublishedBy("unexpected");
        entity.setActivePublishedVersionId(UUID.randomUUID());

        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(entity);
        when(invitationRepository.findActiveForUpdate("default", CompanyContext.DEFAULT_COMPANY_ID, rfqId))
            .thenReturn(List.of());
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, rfqId)).thenReturn(List.of());
        when(mapper.toResponse(entity, List.of())).thenAnswer(invocation -> {
            RFQResponse response = new RFQResponse();
            response.setStatus(entity.getStatus());
            return response;
        });

        RFQResponse created = service.create(request);

        assertThat(created.getStatus()).isEqualTo("DRAFT");
        assertThat(entity.getPublishedAt()).isNull();
        assertThat(entity.getPublishedBy()).isNull();
        assertThat(entity.getActivePublishedVersionId()).isNull();
        assertThat(entity.getPackageLanguage()).isEqualTo("ru");
    }

    @Test
    void createFlushesRootBeforePersistingChildLines() {
        RFQCreateRequest request = new RFQCreateRequest();
        request.setName("Bearing RFQ");
        var line = new com.enterprise.mm.payload.RFQItemCreateRequest();
        request.setItems(List.of(line));
        RFQ entity = persistedRfq(UUID.randomUUID(), "OPEN");
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(entity);
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, entity.getId())).thenReturn(List.of());
        when(mapper.toResponse(entity, List.of())).thenReturn(new RFQResponse());

        service.create(request);

        var order = inOrder(repository, itemService);
        order.verify(repository).saveAndFlush(entity);
        order.verify(itemService).create(line);
        assertThat(line.getRfqId()).isEqualTo(entity.getId());
    }

    @Test
    void updateRejectsPublishedSupplierFacingMutation() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = persistedRfq(rfqId, "OPEN");
        RFQUpdateRequest request = new RFQUpdateRequest();
        request.setDescription("changed after publication");
        when(repository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());

        assertThatThrownBy(() -> service.update(rfqId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Published RFQ requires an amendment");
    }

    @Test
    void draftRfqCannotOverrideApprovedProcedureMethod() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = persistedRfq(rfqId, "DRAFT");
        rfq.setTenderProcedureType("REQUEST_FOR_QUOTATION");
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setMethod("OPEN_TENDER");
        when(procedureRepository.findByRfqIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(procedure));

        assertThatThrownBy(() -> service.beforeUpdate(rfq, new RFQUpdateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot override approved procurement procedure method");
    }

    @Test
    void getReturnsDisplayNamesForAuditBuyerAndPublisherFields() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = persistedRfq(rfqId, "CLOSED");
        rfq.setCreatedBy("admin_navazot");
        rfq.setUpdatedBy("buyer.user");
        rfq.setResponsibleBuyer("responsible.user");
        rfq.setPublishedBy("publisher.user");
        RFQResponse mapped = new RFQResponse();
        mapped.setCreatedBy(rfq.getCreatedBy());
        mapped.setUpdatedBy(rfq.getUpdatedBy());
        mapped.setResponsibleBuyer(rfq.getResponsibleBuyer());
        mapped.setPublishedBy(rfq.getPublishedBy());

        when(repository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, rfqId)).thenReturn(List.of());
        when(mapper.toResponse(rfq, List.of())).thenReturn(mapped);
        when(userRepository.resolveActiveUserByUsername("default", CompanyContext.DEFAULT_COMPANY_ID, "admin_navazot"))
            .thenReturn(Optional.of(user("admin_navazot", "Admin Navazot")));
        when(userRepository.resolveActiveUserByUsername("default", CompanyContext.DEFAULT_COMPANY_ID, "buyer.user"))
            .thenReturn(Optional.of(user("buyer.user", "Buyer Full Name")));
        when(userRepository.resolveActiveUserByUsername("default", CompanyContext.DEFAULT_COMPANY_ID, "responsible.user"))
            .thenReturn(Optional.of(user("responsible.user", "Responsible Buyer")));
        when(userRepository.resolveActiveUserByUsername("default", CompanyContext.DEFAULT_COMPANY_ID, "publisher.user"))
            .thenReturn(Optional.of(user("publisher.user", "Publisher Full Name")));

        RFQResponse response = service.get(rfqId);

        assertThat(response.getCreatedBy()).isEqualTo("Admin Navazot");
        assertThat(response.getUpdatedBy()).isEqualTo("Buyer Full Name");
        assertThat(response.getResponsibleBuyer()).isEqualTo("Responsible Buyer");
        assertThat(response.getPublishedBy()).isEqualTo("Publisher Full Name");
    }

    private RFQ persistedRfq(UUID id, String status) {
        RFQ rfq = new RFQ();
        rfq.setId(id);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setCode("RFQ-OPEN-1");
        rfq.setName("Published RFQ");
        rfq.setStatus(status);
        return rfq;
    }

    private User user(String username, String fullName) {
        User user = new User();
        user.setUsername(username);
        user.setFullName(fullName);
        return user;
    }
}
