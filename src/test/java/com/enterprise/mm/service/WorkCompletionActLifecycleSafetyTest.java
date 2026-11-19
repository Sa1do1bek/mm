package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.WorkInspectionResult;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.WorkCompletionActMapper;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class WorkCompletionActLifecycleSafetyTest {
    @Mock private WorkCompletionActRepository repository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository itemRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private AuditService auditService;
    @Mock private ContractService contractService;
    @Mock private AttachmentService attachmentService;
    @Mock private ObjectProvider<ApprovalEngineService> approvalEngineService;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @Mock private ServiceClaimRepository serviceClaimRepository;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ServiceWorkflowAccessService accessService;
    @Mock private ProcurementPayablesService procurementPayablesService;

    private final WorkCompletionActMapper mapper = new WorkCompletionActMapper();
    private WorkCompletionActService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new WorkCompletionActService(
            repository,
            mapper,
            purchaseOrderRepository,
            itemRepository,
            contractRepository,
            auditService,
            contractService,
            attachmentService,
            approvalEngineService,
            approvalInstanceRepository,
            serviceClaimRepository,
            roleDataScopeService,
            accessService,
            procurementPayablesService
        );
        lenient().when(accessService.hasFullAccess()).thenReturn(true);
        lenient().when(accessService.canAccessPurchaseOrder(any())).thenReturn(true);
        lenient().when(procurementPayablesService.requirePayableInvoice(any(), any()))
            .thenReturn(new com.enterprise.mm.entity.ContractInvoice());
        lenient().when(procurementPayablesService.submitSourceDocumentPayment(
            any(), any(), any(), any(), any(), any()
        )).thenReturn(null);
        lenient().when(procurementPayablesService.markSourceDocumentPaymentPaid(
            any(), any(), any(), any()
        )).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "APPROVAL_PENDING", "APPROVED", "SENT_TO_PAYMENT", "PAID"})
    void softDeleteRejectsApprovalAndFinancialStates(String status) {
        Fixture fixture = fixture(status, false);
        when(repository.findByIdAndIsDeletedFalse(fixture.act().getId())).thenReturn(Optional.of(fixture.act()));
        stubReferences(fixture);

        assertThatThrownBy(() -> service.softDelete(fixture.act().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");

        assertThat(fixture.act().isDeleted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "APPROVAL_PENDING", "APPROVED", "SENT_TO_PAYMENT", "PAID"})
    void restoreRejectsApprovalAndFinancialStates(String status) {
        Fixture fixture = fixture(status, true);
        when(repository.findAnyById(fixture.act().getId())).thenReturn(Optional.of(fixture.act()));
        stubReferences(fixture);

        assertThatThrownBy(() -> service.restore(fixture.act().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be restored");

        assertThat(fixture.act().isDeleted()).isTrue();
    }

    @Test
    void draftCanBeDeletedAndRestored() {
        Fixture draft = fixture("DRAFT", false);
        when(repository.findByIdAndIsDeletedFalse(draft.act().getId())).thenReturn(Optional.of(draft.act()));
        when(repository.findAnyById(draft.act().getId())).thenReturn(Optional.of(draft.act()));
        stubReferences(draft);
        when(repository.save(any(WorkCompletionAct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.softDelete(draft.act().getId());
        service.restore(draft.act().getId());

        assertThat(draft.act().isDeleted()).isFalse();
    }

    @Test
    void markPaidRequiresExternalTreasuryReference() {
        Fixture fixture = fixture("SENT_TO_PAYMENT", false);
        when(repository.findByIdForUpdate(fixture.act().getId())).thenReturn(Optional.of(fixture.act()));
        stubReferences(fixture);

        assertThatThrownBy(() -> service.markPaid(fixture.act().getId(), " ", "Treasury callback"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("payment reference");

        assertThat(fixture.act().getStatus()).isEqualTo("SENT_TO_PAYMENT");
    }

    @Test
    void approvedWorkActCannotBeSentToPaymentWhileLineOrActClaimIsOpen() {
        Fixture fixture = fixture("APPROVED", false);
        ServiceClaim openClaim = new ServiceClaim();
        openClaim.setId(UUID.randomUUID());
        openClaim.setStatus("UNDER_RESOLUTION");
        when(repository.findByIdForUpdate(fixture.act().getId())).thenReturn(Optional.of(fixture.act()));
        stubReferences(fixture);
        when(serviceClaimRepository.findByTenantIdAndPurchaseOrderItemIdAndStatusNotAndIsDeletedFalse(
            "default", fixture.item().getId(), "CLOSED"
        )).thenReturn(List.of(openClaim));

        assertThatThrownBy(() -> service.sendToPayment(fixture.act().getId(), "PAY-WORK", "ready"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("open service claims");

        assertThat(fixture.act().getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void workActCannotBeMarkedPaidWhenClaimOpenedAfterPaymentHandoff() {
        Fixture fixture = fixture("SENT_TO_PAYMENT", false);
        fixture.act().setPaymentReference("TREASURY-PENDING");
        ServiceClaim openClaim = new ServiceClaim();
        openClaim.setId(UUID.randomUUID());
        openClaim.setStatus("UNDER_RESOLUTION");
        when(repository.findByIdForUpdate(fixture.act().getId())).thenReturn(Optional.of(fixture.act()));
        stubReferences(fixture);
        when(serviceClaimRepository.findByTenantIdAndPurchaseOrderItemIdAndStatusNotAndIsDeletedFalse(
            "default", fixture.item().getId(), "CLOSED"
        )).thenReturn(List.of(openClaim));

        assertThatThrownBy(() -> service.markPaid(fixture.act().getId(), "TREASURY-PAID", "callback"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("open service claims");

        assertThat(fixture.act().getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(fixture.act().getPaidAt()).isNull();
    }

    @Test
    void sendToPaymentRejectsStaleWorkspaceVersionBeforeMutation() {
        Fixture fixture = fixture("APPROVED", false);
        fixture.act().setVersion(8L);
        when(repository.findByIdForUpdate(fixture.act().getId())).thenReturn(Optional.of(fixture.act()));
        stubReferences(fixture);

        assertThatThrownBy(() -> service.sendToPayment(
            fixture.act().getId(), "TREASURY-8", "ready", 7L
        )).isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        assertThat(fixture.act().getStatus()).isEqualTo("APPROVED");
        assertThat(fixture.act().getSentToPaymentAt()).isNull();
        verify(repository, never()).save(any(WorkCompletionAct.class));
        verify(repository, never()).findByIdAndIsDeletedFalse(fixture.act().getId());
    }

    @Test
    void markPaidRejectsStaleWorkspaceVersionBeforeMutation() {
        Fixture fixture = fixture("SENT_TO_PAYMENT", false);
        fixture.act().setVersion(11L);
        fixture.act().setPaymentReference("TREASURY-PENDING");
        when(repository.findByIdForUpdate(fixture.act().getId())).thenReturn(Optional.of(fixture.act()));
        stubReferences(fixture);

        assertThatThrownBy(() -> service.markPaid(
            fixture.act().getId(), "TREASURY-PAID", "callback", 10L
        )).isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        assertThat(fixture.act().getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(fixture.act().getPaidAt()).isNull();
        verify(repository, never()).save(any(WorkCompletionAct.class));
        verify(repository, never()).findByIdAndIsDeletedFalse(fixture.act().getId());
    }

    @Test
    void serializedConcurrentPaymentCommandsRejectTheSecondStaleVersion() {
        Fixture fixture = fixture("APPROVED", false);
        fixture.act().setVersion(4L);
        when(repository.findByIdForUpdate(fixture.act().getId())).thenReturn(Optional.of(fixture.act()));
        stubReferences(fixture);
        when(repository.save(fixture.act())).thenAnswer(invocation -> {
            fixture.act().setVersion(5L);
            return fixture.act();
        });

        service.sendToPayment(fixture.act().getId(), "TREASURY-FIRST", "first", 4L);

        assertThatThrownBy(() -> service.sendToPayment(
            fixture.act().getId(), "TREASURY-SECOND", "second", 4L
        )).isInstanceOf(ConflictException.class)
            .hasMessageContaining("refresh");
        assertThat(fixture.act().getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(fixture.act().getPaymentReference()).isEqualTo("TREASURY-FIRST");
        verify(repository).save(fixture.act());
    }

    private void stubReferences(Fixture fixture) {
        lenient().when(purchaseOrderRepository.findByIdAndIsDeletedFalse(fixture.order().getId())).thenReturn(Optional.of(fixture.order()));
        lenient().when(itemRepository.findByIdForUpdate(fixture.item().getId())).thenReturn(Optional.of(fixture.item()));
        lenient().when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", fixture.order().getId())).thenReturn(List.of());
        lenient().when(contractRepository.findByIdAndIsDeletedFalse(fixture.contract().getId())).thenReturn(Optional.of(fixture.contract()));
        lenient().when(repository.cumulativeQuantity("default", fixture.item().getId(), List.of("DRAFT", "APPROVAL_PENDING", "APPROVED", "SENT_TO_PAYMENT", "PAID"), fixture.act().getId()))
            .thenReturn(BigDecimal.ZERO);
        lenient().when(repository.save(any(WorkCompletionAct.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Fixture fixture(String status, boolean deleted) {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();

        PurchaseOrder order = new PurchaseOrder();
        order.setId(orderId);
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setStatus(PurchaseOrderStatus.SENT.name());
        order.setRequestType(RequestType.SERVICE);
        order.setContractId(contractId);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setPurchaseOrderId(orderId);
        item.setLineType(ProcurementLineType.WORK);
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(BigDecimal.TEN);

        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setTenantId("default");
        contract.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);

        WorkCompletionAct act = new WorkCompletionAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        act.setCode("WCA-1");
        act.setName("Work act");
        act.setStatus(status);
        act.setPurchaseOrderId(orderId);
        act.setPurchaseOrderItemId(itemId);
        act.setContractId(contractId);
        act.setCompletedQuantity(BigDecimal.ONE);
        act.setAmount(BigDecimal.TEN);
        act.setInspectionResult(WorkInspectionResult.PASSED);
        if (deleted) {
            act.softDelete();
        }
        return new Fixture(act, order, item, contract);
    }

    private record Fixture(WorkCompletionAct act, PurchaseOrder order, PurchaseOrderItem item, Contract contract) {
    }
}
