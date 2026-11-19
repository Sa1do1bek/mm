package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCompletionRequest;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeBatchRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeSerialRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseLineRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.supplier.po.service.SupplierPoAsnService;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers portal cross-supplier access rejection and superseded-dispatch response
 * rejection (docs/CURRENT_TASK.md section 7). Verification not run -- see final
 * report.
 */
@ExtendWith(MockitoExtension.class)
class SupplierPortalPurchaseOrderExternalServiceTest {
    @Mock private SupplierPortalAccountRepository accountRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private SupplierPoDispatchRepository dispatchRepository;
    @Mock private SupplierPoResponseRepository responseRepository;
    @Mock private SupplierPoResponseLineRepository responseLineRepository;
    @Mock private SupplierPoCommitmentRepository commitmentRepository;
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AdvanceShippingNoticeItemRepository asnItemRepository;
    @Mock private AdvanceShippingNoticeBatchRepository asnBatchRepository;
    @Mock private AdvanceShippingNoticeSerialRepository asnSerialRepository;
    @Mock private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Mock private WorkCompletionActRepository workCompletionActRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private SupplierPoResponseService responseService;
    @Mock private SupplierPoAsnService asnService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private PurchaseOrderServiceOrderLinkService serviceOrderLinkService;
    @Mock private ServiceAcceptanceActService serviceAcceptanceActService;
    @Mock private WorkCompletionActService workCompletionActService;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @InjectMocks private SupplierPortalPurchaseOrderExternalService service;

    @Test
    void detailRejectsPurchaseOrderBelongingToAnotherSupplier() {
        String token = "raw-token";
        SupplierPortalAccount account = account(token, UUID.randomUUID());
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = new PurchaseOrder();
        po.setId(UUID.randomUUID());
        po.setTenantId(account.getTenantId());
        po.setCompanyId(account.getCompanyId());
        po.setSupplierId(UUID.randomUUID()); // different supplier
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(po.getId())).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.detail(token, po.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not belong to the authenticated supplier");
    }

    @Test
    void createResponseDraftRejectsSupersededDispatch() {
        String token = "raw-token";
        UUID supplierId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = new PurchaseOrder();
        po.setId(UUID.randomUUID());
        po.setTenantId(account.getTenantId());
        po.setCompanyId(account.getCompanyId());
        po.setSupplierId(supplierId);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(po.getId())).thenReturn(Optional.of(po));

        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setId(UUID.randomUUID());
        dispatch.setSupplierId(supplierId);
        dispatch.setCurrent(true);
        dispatch.setDispatchStatus("SUPERSEDED");
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), po.getId()
        )).thenReturn(Optional.of(dispatch));

        var request = new com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalResponseDraftRequest(
            "comment", java.util.List.of()
        );

        assertThatThrownBy(() -> service.createResponseDraft(token, po.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no longer current");
    }

    @Test
    void randomTokenReturnsSessionNotFound() {
        String token = "random-token";
        String hash = sha256(token);
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(hash)).thenReturn(Optional.empty());
        when(accountRepository.findByPortalTokenHash(hash)).thenReturn(List.of());
        when(accountRepository.countByPortalTokenHash(hash)).thenReturn(0L);

        assertThatThrownBy(() -> service.commitments(token, UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier portal session not found");
    }

    @Test
    void expiredTokenReturnsSessionExpired() {
        String token = "expired-token";
        SupplierPortalAccount account = account(token, UUID.randomUUID());
        account.setPortalTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.commitments(token, UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier portal session expired");
    }

    @Test
    void inactiveAccountReturnsAccountNotActive() {
        String token = "inactive-token";
        SupplierPortalAccount account = account(token, UUID.randomUUID());
        account.setStatus("SUSPENDED");
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.commitments(token, UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier portal account is not active");
    }

    @Test
    void detailReturnsMaterialAndShippableFieldsForGoodsLines() throws Exception {
        String token = "raw-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID purchaseOrderItemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setName("Supplier-facing purchase order name");
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));

        SupplierPoDispatch dispatch = dispatch(purchaseOrderId, supplierId);
        dispatch.setViewedAt(LocalDateTime.now());
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(purchaseOrderItemId);
        item.setName("Purchase Request Item");
        item.setPurchaseOrderId(purchaseOrderId);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);
        item.setMaterial(material(materialId));
        item.setQuantity(new BigDecimal("10"));
        item.setReceivedQuantity(new BigDecimal("2"));
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            account.getTenantId(), purchaseOrderId
        )).thenReturn(List.of(item));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            account.getTenantId(), account.getCompanyId(), "PURCHASEORDER", purchaseOrderId
        )).thenReturn(List.of());
        when(responseRepository.findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), dispatch.getId()
        )).thenReturn(Optional.empty());

        var detail = service.detail(token, purchaseOrderId);

        assertThat(Arrays.stream(detail.getClass().getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
            .contains("poName");
        assertThat(detail.getClass().getMethod("poName").invoke(detail)).isEqualTo("Supplier-facing purchase order name");
        assertThat(detail.lines()).hasSize(1);
        var line = detail.lines().get(0);
        assertThat(line.purchaseOrderItemId()).isEqualTo(purchaseOrderItemId);
        assertThat(line.materialId()).isEqualTo(materialId);
        assertThat(line.materialCode()).isEqualTo("MAT-1");
        assertThat(line.materialName()).isEqualTo("Actual Material");
        assertThat(line.openQuantity()).isEqualByComparingTo("8");
        assertThat(line.shippable()).isTrue();
        assertThat(line.shippableAsGoods()).isTrue();
        assertThat(detail.fulfillmentMode()).isEqualTo("GOODS_SHIPMENT");
        assertThat(detail.allowedActions()).containsExactly("ACKNOWLEDGE_PO", "SUBMIT_RESPONSE", "CREATE_SHIPMENT");
    }

    @Test
    void detailReturnsServiceFulfillmentActionsWithoutShipment() {
        String token = "raw-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));

        SupplierPoDispatch dispatch = dispatch(purchaseOrderId, supplierId);
        dispatch.setViewedAt(LocalDateTime.now());
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(purchaseOrderId);
        item.setLineType(ProcurementLineType.SERVICE);
        item.setServiceDescription("Monthly support");
        item.setQuantity(new BigDecimal("1"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            account.getTenantId(), purchaseOrderId
        )).thenReturn(List.of(item));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            account.getTenantId(), account.getCompanyId(), "PURCHASEORDER", purchaseOrderId
        )).thenReturn(List.of());
        when(responseRepository.findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), dispatch.getId()
        )).thenReturn(Optional.empty());

        var detail = service.detail(token, purchaseOrderId);

        assertThat(detail.fulfillmentMode()).isEqualTo("SERVICE_CONFIRMATION");
        assertThat(detail.allowedActions()).containsExactly("ACKNOWLEDGE_PO", "SUBMIT_RESPONSE", "SUBMIT_SERVICE_COMPLETION");
        assertThat(detail.allowedActions()).doesNotContain("CREATE_SHIPMENT");
        assertThat(detail.lines().get(0).shippable()).isFalse();
        assertThat(detail.lines().get(0).shippableAsGoods()).isFalse();
    }

    @Test
    void confirmedCommitmentWithRemainingAndMaterialIsShippableAsGoods() {
        String token = "raw-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID purchaseOrderItemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(purchaseOrderItemId);
        item.setName("Material A");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);
        item.setMaterial(material(materialId));
        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(UUID.randomUUID());
        commitment.setTenantId(account.getTenantId());
        commitment.setCompanyId(account.getCompanyId());
        commitment.setSupplierId(supplierId);
        commitment.setPurchaseOrderId(purchaseOrderId);
        commitment.setPurchaseOrderItemId(purchaseOrderItemId);
        commitment.setPurchaseOrderItem(item);
        commitment.setStatus("CONFIRMED");
        commitment.setCurrent(true);
        commitment.setProposedQuantity(new BigDecimal("6"));
        commitment.setConfirmedQuantity(new BigDecimal("6"));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId, List.of("PROPOSED", "CONFIRMED")
        )).thenReturn(List.of(commitment));
        when(asnService.remainingQuantity(commitment)).thenReturn(new BigDecimal("6"));

        var commitments = service.commitments(token, purchaseOrderId);

        assertThat(commitments).hasSize(1);
        assertThat(commitments.get(0).id()).isEqualTo(commitment.getId());
        assertThat(commitments.get(0).materialId()).isEqualTo(materialId);
        assertThat(commitments.get(0).materialCode()).isEqualTo("MAT-1");
        assertThat(commitments.get(0).materialName()).isEqualTo("Actual Material");
        assertThat(commitments.get(0).status()).isEqualTo("CONFIRMED");
        assertThat(commitments.get(0).remainingQuantity()).isEqualByComparingTo("6");
        assertThat(commitments.get(0).shippable()).isTrue();
        assertThat(commitments.get(0).shippableAsGoods()).isTrue();
    }

    @Test
    void confirmedCommitmentWithRemainingButNoMaterialIsNotShippableAsGoods() {
        String token = "raw-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID purchaseOrderItemId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(purchaseOrderItemId);
        item.setName("Purchase Request Item");
        item.setLineType(ProcurementLineType.MATERIAL);
        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(UUID.randomUUID());
        commitment.setTenantId(account.getTenantId());
        commitment.setCompanyId(account.getCompanyId());
        commitment.setSupplierId(supplierId);
        commitment.setPurchaseOrderId(purchaseOrderId);
        commitment.setPurchaseOrderItemId(purchaseOrderItemId);
        commitment.setPurchaseOrderItem(item);
        commitment.setStatus("CONFIRMED");
        commitment.setCurrent(true);
        commitment.setProposedQuantity(new BigDecimal("6"));
        commitment.setConfirmedQuantity(new BigDecimal("6"));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId, List.of("PROPOSED", "CONFIRMED")
        )).thenReturn(List.of(commitment));
        when(asnService.remainingQuantity(commitment)).thenReturn(new BigDecimal("6"));

        var commitments = service.commitments(token, purchaseOrderId);

        assertThat(commitments).hasSize(1);
        assertThat(commitments.get(0).materialId()).isNull();
        assertThat(commitments.get(0).materialCode()).isNull();
        assertThat(commitments.get(0).materialName()).isNull();
        assertThat(commitments.get(0).remainingQuantity()).isEqualByComparingTo("6");
        assertThat(commitments.get(0).shippable()).isFalse();
        assertThat(commitments.get(0).shippableAsGoods()).isFalse();
    }

    @Test
    void confirmedServiceCommitmentWithRemainingIsNotShippableAsGoods() {
        String token = "raw-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setLineType(ProcurementLineType.SERVICE);
        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(UUID.randomUUID());
        commitment.setTenantId(account.getTenantId());
        commitment.setCompanyId(account.getCompanyId());
        commitment.setSupplierId(supplierId);
        commitment.setPurchaseOrderId(purchaseOrderId);
        commitment.setPurchaseOrderItemId(item.getId());
        commitment.setPurchaseOrderItem(item);
        commitment.setStatus("CONFIRMED");
        commitment.setCurrent(true);
        commitment.setConfirmedQuantity(new BigDecimal("6"));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId, List.of("PROPOSED", "CONFIRMED")
        )).thenReturn(List.of(commitment));
        when(asnService.remainingQuantity(commitment)).thenReturn(new BigDecimal("6"));

        var commitments = service.commitments(token, purchaseOrderId);

        assertThat(commitments).hasSize(1);
        assertThat(commitments.get(0).remainingQuantity()).isEqualByComparingTo("6");
        assertThat(commitments.get(0).shippable()).isFalse();
        assertThat(commitments.get(0).shippableAsGoods()).isFalse();
    }

    @Test
    void serviceCompletionSubmitCreatesDraftServiceAcceptanceAct() {
        String token = "service-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        po.setTotalAmount(new BigDecimal("300000"));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch(purchaseOrderId, supplierId)));

        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setSupplierId(supplierId);
        order.setPurchaseOrderId(purchaseOrderId);
        when(serviceOrderLinkService.ensureForPurchaseOrder(
            po, "RECOVERY_FROM_SUPPLIER_PORTAL_SERVICE_COMPLETION"
        )).thenReturn(Optional.of(order));
        when(serviceAcceptanceActRepository.saveAndFlush(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> {
            ServiceAcceptanceAct act = invocation.getArgument(0);
            act.setId(UUID.randomUUID());
            return act;
        });
        var result = service.submitServiceCompletion(token, purchaseOrderId, new SupplierPortalCompletionRequest(
            new BigDecimal("100000"), null, LocalDate.of(2026, 8, 1), "Completed service evidence"
        ), List.of(), "idem-1");

        ArgumentCaptor<ServiceAcceptanceAct> captor = ArgumentCaptor.forClass(ServiceAcceptanceAct.class);
        verify(serviceAcceptanceActRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("100000");
        assertThat(captor.getValue().getAcceptedBy()).isEqualTo(account.getLoginEmail());
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.completedAmount()).isEqualByComparingTo("100000");
        verify(serviceAcceptanceActService, never()).submitForApproval(any(UUID.class));
        verify(purchaseOrderRepository).save(po);
    }

    @Test
    void serviceCompletionSubmitRecoversMissingServiceOrderAndLinksAct() {
        String token = "service-recovery-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        po.setContractId(UUID.randomUUID());
        po.setTotalAmount(new BigDecimal("300000"));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch(purchaseOrderId, supplierId)));

        ServiceOrder recovered = new ServiceOrder();
        recovered.setId(UUID.randomUUID());
        recovered.setTenantId(account.getTenantId());
        recovered.setCompanyId(account.getCompanyId());
        recovered.setSupplierId(supplierId);
        recovered.setContractId(po.getContractId());
        recovered.setPurchaseOrderId(purchaseOrderId);
        when(serviceOrderLinkService.ensureForPurchaseOrder(
            po, "RECOVERY_FROM_SUPPLIER_PORTAL_SERVICE_COMPLETION"
        )).thenReturn(Optional.of(recovered));
        when(serviceAcceptanceActRepository.saveAndFlush(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> {
            ServiceAcceptanceAct act = invocation.getArgument(0);
            act.setId(UUID.randomUUID());
            return act;
        });
        var result = service.submitServiceCompletion(token, purchaseOrderId, new SupplierPortalCompletionRequest(
            new BigDecimal("150000"), null, LocalDate.of(2026, 8, 1), "Recovered service completion"
        ), List.of(), "idem-recovery");

        ArgumentCaptor<ServiceAcceptanceAct> captor = ArgumentCaptor.forClass(ServiceAcceptanceAct.class);
        verify(serviceAcceptanceActRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getServiceOrderId()).isEqualTo(recovered.getId());
        assertThat(captor.getValue().getPurchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(captor.getValue().getTenantId()).isEqualTo(account.getTenantId());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(account.getCompanyId());
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(result.status()).isEqualTo("DRAFT");
        verify(serviceAcceptanceActService, never()).submitForApproval(any(UUID.class));
    }

    @Test
    void workCompletionSubmitCreatesApprovalPendingWorkAct() {
        String token = "work-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        po.setContractId(UUID.randomUUID());
        po.setTotalAmount(new BigDecimal("500"));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch(purchaseOrderId, supplierId)));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(purchaseOrderId);
        item.setLineType(ProcurementLineType.WORK);
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("50"));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            account.getTenantId(), purchaseOrderId
        )).thenReturn(List.of(item));
        when(workCompletionActRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(
                account.getTenantId(), account.getCompanyId(), item.getId()
            )).thenReturn(List.of());
        when(workCompletionActRepository.save(any(WorkCompletionAct.class))).thenAnswer(invocation -> {
            WorkCompletionAct act = invocation.getArgument(0);
            act.setId(UUID.randomUUID());
            return act;
        });
        when(workCompletionActRepository.findByIdAndIsDeletedFalse(any(UUID.class))).thenAnswer(invocation -> {
            WorkCompletionAct act = new WorkCompletionAct();
            act.setId(invocation.getArgument(0));
            act.setStatus("APPROVAL_PENDING");
            act.setCompletedQuantity(new BigDecimal("4"));
            act.setAmount(new BigDecimal("200"));
            act.setCode("WCA-PORTAL");
            return Optional.of(act);
        });

        var result = service.submitWorkCompletion(token, purchaseOrderId, new SupplierPortalCompletionRequest(
            null, new BigDecimal("4"), LocalDate.of(2026, 8, 1), "Completed work evidence"
        ), List.of(), "idem-2");

        ArgumentCaptor<WorkCompletionAct> captor = ArgumentCaptor.forClass(WorkCompletionAct.class);
        verify(workCompletionActRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(captor.getValue().getCompletedQuantity()).isEqualByComparingTo("4");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("200");
        verify(workCompletionActService).submitForApproval(captor.getValue().getId());
        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.completedQuantity()).isEqualByComparingTo("4");
        verify(purchaseOrderRepository).save(po);
    }

    @Test
    void serviceCompletionSubmitUpdatesExistingDraftActInsteadOfCreatingDuplicate() {
        String token = "service-draft-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch(purchaseOrderId, supplierId)));

        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setSupplierId(supplierId);
        order.setPurchaseOrderId(purchaseOrderId);
        when(serviceOrderLinkService.ensureForPurchaseOrder(
            po, "RECOVERY_FROM_SUPPLIER_PORTAL_SERVICE_COMPLETION"
        )).thenReturn(Optional.of(order));
        ServiceAcceptanceAct existing = new ServiceAcceptanceAct();
        existing.setId(UUID.randomUUID());
        existing.setCode("SAA-DRAFT");
        existing.setName("Existing draft");
        existing.setStatus("DRAFT");
        existing.setTenantId(account.getTenantId());
        existing.setCompanyId(account.getCompanyId());
        existing.setServiceOrderId(order.getId());
        when(serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), order.getId()
        )).thenReturn(List.of(existing));
        when(serviceAcceptanceActRepository.saveAndFlush(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(existing.getId())).thenReturn(Optional.of(existing));

        var result = service.submitServiceCompletion(token, purchaseOrderId, new SupplierPortalCompletionRequest(
            new BigDecimal("250000"), null, LocalDate.of(2026, 8, 1), "Updated existing service draft"
        ), List.of(), "idem-draft");

        ArgumentCaptor<ServiceAcceptanceAct> captor = ArgumentCaptor.forClass(ServiceAcceptanceAct.class);
        verify(serviceAcceptanceActRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(existing.getStatus()).isEqualTo("DRAFT");
        assertThat(existing.getAmount()).isEqualByComparingTo("250000");
        assertThat(existing.getAcceptedBy()).isEqualTo(account.getLoginEmail());
        assertThat(existing.getRemarks()).isEqualTo("Updated existing service draft");
        assertThat(result.id()).isEqualTo(existing.getId());
        assertThat(result.status()).isEqualTo("DRAFT");
        verify(serviceAcceptanceActService, never()).submitForApproval(any(UUID.class));
    }

    @Test
    void serviceCompletionSubmitRejectsExistingTechnicalReviewAct() {
        String token = "service-submitted-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch(purchaseOrderId, supplierId)));

        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setSupplierId(supplierId);
        order.setPurchaseOrderId(purchaseOrderId);
        when(serviceOrderLinkService.ensureForPurchaseOrder(
            po, "RECOVERY_FROM_SUPPLIER_PORTAL_SERVICE_COMPLETION"
        )).thenReturn(Optional.of(order));
        ServiceAcceptanceAct existing = new ServiceAcceptanceAct();
        existing.setId(UUID.randomUUID());
        existing.setStatus("TECHNICAL_REVIEW");
        when(serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), order.getId()
        )).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.submitServiceCompletion(token, purchaseOrderId, new SupplierPortalCompletionRequest(
            new BigDecimal("250000"), null, LocalDate.of(2026, 8, 1), "Submitted service duplicate"
        ), List.of(), "idem-submitted"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Service completion is already submitted for buyer review");
        verify(serviceAcceptanceActRepository, never()).saveAndFlush(any(ServiceAcceptanceAct.class));
        verify(serviceAcceptanceActService, never()).submitForApproval(any(UUID.class));
    }

    @Test
    void workCompletionSubmitUpdatesExistingDraftActInsteadOfCreatingDuplicate() {
        String token = "work-draft-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        po.setContractId(UUID.randomUUID());
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch(purchaseOrderId, supplierId)));

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(purchaseOrderId);
        item.setLineType(ProcurementLineType.WORK);
        item.setUnitPrice(new BigDecimal("50"));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            account.getTenantId(), purchaseOrderId
        )).thenReturn(List.of(item));
        WorkCompletionAct existing = new WorkCompletionAct();
        existing.setId(UUID.randomUUID());
        existing.setCode("WCA-DRAFT");
        existing.setName("Existing work draft");
        existing.setStatus("DRAFT");
        existing.setTenantId(account.getTenantId());
        existing.setCompanyId(account.getCompanyId());
        existing.setPurchaseOrderItemId(item.getId());
        when(workCompletionActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(
            account.getTenantId(), account.getCompanyId(), item.getId()
        )).thenReturn(List.of(existing));
        when(workCompletionActRepository.save(any(WorkCompletionAct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workCompletionActRepository.findByIdAndIsDeletedFalse(existing.getId())).thenAnswer(invocation -> {
            WorkCompletionAct submitted = new WorkCompletionAct();
            submitted.setId(existing.getId());
            submitted.setStatus("APPROVAL_PENDING");
            submitted.setCompletedQuantity(existing.getCompletedQuantity());
            submitted.setAmount(existing.getAmount());
            submitted.setCode(existing.getCode());
            return Optional.of(submitted);
        });

        var result = service.submitWorkCompletion(token, purchaseOrderId, new SupplierPortalCompletionRequest(
            null, new BigDecimal("4"), LocalDate.of(2026, 8, 1), "Updated existing work draft"
        ), List.of(), "idem-work-draft");

        ArgumentCaptor<WorkCompletionAct> captor = ArgumentCaptor.forClass(WorkCompletionAct.class);
        verify(workCompletionActRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(existing.getCompletedQuantity()).isEqualByComparingTo("4");
        assertThat(existing.getAmount()).isEqualByComparingTo("200");
        verify(workCompletionActService).submitForApproval(existing.getId());
        assertThat(result.id()).isEqualTo(existing.getId());
        assertThat(result.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void serviceCompletionRejectsZeroAmountAndWrongMode() {
        String token = "service-zero-token";
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierPortalAccount account = account(token, supplierId);
        account.setTermsAcceptedAt(LocalDateTime.now());
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        PurchaseOrder po = purchaseOrder(purchaseOrderId, account, supplierId);
        po.setRequestType(RequestType.SERVICE);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), purchaseOrderId
        )).thenReturn(Optional.of(dispatch(purchaseOrderId, supplierId)));

        assertThatThrownBy(() -> service.submitServiceCompletion(token, purchaseOrderId, new SupplierPortalCompletionRequest(
            BigDecimal.ZERO, null, LocalDate.of(2026, 8, 1), "Completed service evidence"
        ), List.of(), "idem-3"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Completed service amount must be greater than zero");

        assertThatThrownBy(() -> service.submitWorkCompletion(token, purchaseOrderId, new SupplierPortalCompletionRequest(
            null, BigDecimal.ONE, LocalDate.of(2026, 8, 1), "Completed work evidence"
        ), List.of(), "idem-4"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("work-completions cannot be used for SERVICE purchase orders");
    }

    private PurchaseOrder purchaseOrder(UUID purchaseOrderId, SupplierPortalAccount account, UUID supplierId) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(purchaseOrderId);
        po.setTenantId(account.getTenantId());
        po.setCompanyId(account.getCompanyId());
        po.setSupplierId(supplierId);
        po.setRequestType(RequestType.GOODS);
        return po;
    }

    private Material material(UUID materialId) {
        Material material = new Material();
        material.setId(materialId);
        material.setCode("MAT-1");
        material.setName("Actual Material");
        return material;
    }

    private SupplierPoDispatch dispatch(UUID purchaseOrderId, UUID supplierId) {
        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setId(UUID.randomUUID());
        dispatch.setTenantId("default");
        dispatch.setCompanyId(com.enterprise.mm.common.CompanyContext.DEFAULT_COMPANY_ID);
        dispatch.setPurchaseOrderId(purchaseOrderId);
        dispatch.setSupplierId(supplierId);
        dispatch.setCurrent(true);
        dispatch.setDispatchStatus("SENT");
        dispatch.setPurchaseOrderVersion(1L);
        return dispatch;
    }

    private SupplierPortalAccount account(String rawToken, UUID supplierId) {
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setId(UUID.randomUUID());
        account.setTenantId("default");
        account.setCompanyId(com.enterprise.mm.common.CompanyContext.DEFAULT_COMPANY_ID);
        account.setSupplierId(supplierId);
        account.setStatus("ACTIVE");
        account.setLoginEmail("supplier@example.com");
        account.setPortalTokenExpiresAt(LocalDateTime.now().plusHours(1));
        return account;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
