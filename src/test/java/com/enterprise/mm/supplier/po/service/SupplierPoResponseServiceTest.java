package com.enterprise.mm.supplier.po.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.entity.SupplierPoResponseLine;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseLineRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseCommands.CommitmentCommand;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseCommands.LineDecisionCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierPoResponseServiceTest {
    @Mock private SupplierPoDispatchRepository dispatchRepository;
    @Mock private SupplierPoResponseRepository responseRepository;
    @Mock private SupplierPoResponseLineRepository responseLineRepository;
    @Mock private SupplierPoCommitmentRepository commitmentRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private SupplierPoSnapshotService snapshotService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private SupplierPoResponseService service;

    private UUID dispatchId;
    private UUID purchaseOrderId;
    private UUID responseId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        dispatchId = UUID.randomUUID();
        purchaseOrderId = UUID.randomUUID();
        responseId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void submittedResponseCannotBeEditedViaSaveDraftLines() {
        SupplierPoResponse submitted = response("SUBMITTED");
        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.saveDraftLines(responseId, List.of(), "comment"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    void submitRequiresEveryDispatchedLineToHaveADecision() {
        SupplierPoResponse draft = response("DRAFT");
        SupplierPoDispatch dispatch = dispatch();
        PurchaseOrderItem decidedItem = item("ITEM-1");
        PurchaseOrderItem missingItem = item("ITEM-2");

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(draft));
        when(dispatchRepository.findByIdAndIsDeletedFalse(dispatchId)).thenReturn(Optional.of(dispatch));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, responseId))
            .thenReturn(List.of(responseLine(decidedItem.getId())));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(decidedItem, missingItem));

        assertThatThrownBy(() -> service.submit(responseId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ITEM-2");
    }

    @Test
    void rejectedLineRequiresAReasonCode() {
        SupplierPoResponse draft = response("DRAFT");
        SupplierPoDispatch dispatch = dispatch();
        PurchaseOrderItem item = item("ITEM-1");

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(draft));
        when(dispatchRepository.findByIdAndIsDeletedFalse(dispatchId)).thenReturn(Optional.of(dispatch));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of());
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));

        LineDecisionCommand rejectedNoReason = new LineDecisionCommand(item.getId(), "REJECTED", null, null, List.of());

        assertThatThrownBy(() -> service.saveDraftLines(responseId, List.of(rejectedNoReason), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requires a reason");
    }

    @Test
    void partiallyAcceptedCommitmentTotalCannotReachOrExceedOpenQuantity() {
        SupplierPoResponse draft = response("DRAFT");
        SupplierPoDispatch dispatch = dispatch();
        PurchaseOrderItem item = item("ITEM-1");
        item.setQuantity(new BigDecimal("10"));

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(draft));
        when(dispatchRepository.findByIdAndIsDeletedFalse(dispatchId)).thenReturn(Optional.of(dispatch));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of());
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));

        LineDecisionCommand overCommitted = new LineDecisionCommand(
            item.getId(), "PARTIALLY_ACCEPTED", "SHORTAGE", null,
            List.of(new CommitmentCommand(new BigDecimal("10"), LocalDate.now()))
        );

        assertThatThrownBy(() -> service.saveDraftLines(responseId, List.of(overCommitted), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("between 0 and the open quantity");
    }

    @Test
    void acceptedLineAllowsAcceptedQuantityWithLowerScaleThanOpenQuantity() {
        PurchaseOrderItem item = item("ITEM-1");
        item.setQuantity(new BigDecimal("6.0000"));
        stubDraftLineReplacement(item);

        LineDecisionCommand accepted = new LineDecisionCommand(
            item.getId(), "ACCEPTED", new BigDecimal("6"), null, null, List.of()
        );

        assertThatCode(() -> service.saveDraftLines(responseId, List.of(accepted), null))
            .doesNotThrowAnyException();

        SupplierPoResponseLine savedLine = capturedSavedLine();
        assertThat(savedLine.getAcceptedQuantity()).isEqualByComparingTo("6");
        assertThat(savedLine.getUnconfirmedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void acceptedLineAllowsAcceptedQuantityWithHigherScaleThanOpenQuantity() {
        PurchaseOrderItem item = item("ITEM-1");
        item.setQuantity(new BigDecimal("6"));
        stubDraftLineReplacement(item);

        LineDecisionCommand accepted = new LineDecisionCommand(
            item.getId(), "ACCEPTED", new BigDecimal("6.0000"), null, null, List.of()
        );

        assertThatCode(() -> service.saveDraftLines(responseId, List.of(accepted), null))
            .doesNotThrowAnyException();

        SupplierPoResponseLine savedLine = capturedSavedLine();
        assertThat(savedLine.getAcceptedQuantity()).isEqualByComparingTo("6");
        assertThat(savedLine.getUnconfirmedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void acceptedLineRejectsAcceptedQuantityBelowOpenQuantity() {
        PurchaseOrderItem item = item("ITEM-1");
        item.setQuantity(new BigDecimal("6.0000"));
        stubDraftLineReplacement(item);

        LineDecisionCommand accepted = new LineDecisionCommand(
            item.getId(), "ACCEPTED", new BigDecimal("5.9999"), null, null, List.of()
        );

        assertThatThrownBy(() -> service.saveDraftLines(responseId, List.of(accepted), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("must propose the full open quantity (6.0000)");
    }

    @Test
    void partiallyAcceptedLineAllowsAcceptedQuantityBelowOpenQuantity() {
        PurchaseOrderItem item = item("ITEM-1");
        item.setQuantity(new BigDecimal("6.0000"));
        stubDraftLineReplacement(item);

        LineDecisionCommand partiallyAccepted = new LineDecisionCommand(
            item.getId(), "PARTIALLY_ACCEPTED", new BigDecimal("3"), "SHORTAGE", null, List.of()
        );

        assertThatCode(() -> service.saveDraftLines(responseId, List.of(partiallyAccepted), null))
            .doesNotThrowAnyException();

        SupplierPoResponseLine savedLine = capturedSavedLine();
        assertThat(savedLine.getAcceptedQuantity()).isEqualByComparingTo("3");
        assertThat(savedLine.getUnconfirmedQuantity()).isEqualByComparingTo("3");
    }

    @Test
    void rejectedLineAllowsZeroAcceptedQuantity() {
        PurchaseOrderItem item = item("ITEM-1");
        item.setQuantity(new BigDecimal("6.0000"));
        stubDraftLineReplacement(item);

        LineDecisionCommand rejected = new LineDecisionCommand(
            item.getId(), "REJECTED", BigDecimal.ZERO, "NO_STOCK", null, List.of()
        );

        assertThatCode(() -> service.saveDraftLines(responseId, List.of(rejected), null))
            .doesNotThrowAnyException();

        SupplierPoResponseLine savedLine = capturedSavedLine();
        assertThat(savedLine.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(savedLine.getUnconfirmedQuantity()).isEqualByComparingTo("6");
    }

    @Test
    void rejectedLineRejectsPositiveAcceptedQuantity() {
        PurchaseOrderItem item = item("ITEM-1");
        item.setQuantity(new BigDecimal("6.0000"));
        stubDraftLineReplacement(item);

        LineDecisionCommand rejected = new LineDecisionCommand(
            item.getId(), "REJECTED", BigDecimal.ONE, "NO_STOCK", null, List.of()
        );

        assertThatThrownBy(() -> service.saveDraftLines(responseId, List.of(rejected), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot have accepted quantity");
    }

    @Test
    void replaceSupersedesCurrentResponseAndStartsTheNextRevisionAsDraft() {
        SupplierPoResponse submitted = response("SUBMITTED");
        submitted.setResponseRevision(1);
        UUID cancelledCommitmentId = UUID.randomUUID();
        submitted.setReconfirmationOriginCommitmentId(cancelledCommitmentId);
        submitted.setReconfirmationReason("Cancelled delivery date");
        SupplierPoDispatch dispatch = dispatch();

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(submitted));
        when(dispatchRepository.findByIdAndIsDeletedFalse(dispatchId)).thenReturn(Optional.of(dispatch));
        when(responseRepository.saveAndFlush(any(SupplierPoResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierPoResponse next = service.replace(responseId, "resubmitting with new terms");

        assertThat(submitted.getResponseStatus()).isEqualTo("SUPERSEDED");
        assertThat(submitted.isCurrent()).isFalse();
        assertThat(next.getResponseStatus()).isEqualTo("DRAFT");
        assertThat(next.getResponseRevision()).isEqualTo(2);
        assertThat(next.getSupersededResponseId()).isEqualTo(submitted.getId());
        assertThat(next.getReconfirmationOriginCommitmentId()).isEqualTo(cancelledCommitmentId);
        assertThat(next.getReconfirmationReason()).isEqualTo("Cancelled delivery date");
        assertThat(next.isCurrent()).isTrue();
    }

    private SupplierPoResponse response(String status) {
        SupplierPoResponse response = new SupplierPoResponse();
        response.setId(responseId);
        response.setTenantId("default");
        response.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        response.setDispatchId(dispatchId);
        response.setPurchaseOrderId(purchaseOrderId);
        response.setResponseStatus(status);
        response.setResponseRevision(1);
        response.setCurrent(true);
        return response;
    }

    private SupplierPoDispatch dispatch() {
        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setId(dispatchId);
        dispatch.setTenantId("default");
        dispatch.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        dispatch.setPurchaseOrderId(purchaseOrderId);
        dispatch.setCurrent(true);
        dispatch.setDispatchStatus("SENT");
        return dispatch;
    }

    private PurchaseOrderItem item(String code) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setCode(code);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(BigDecimal.TEN);
        return item;
    }

    private void stubDraftLineReplacement(PurchaseOrderItem item) {
        SupplierPoResponse draft = response("DRAFT");
        SupplierPoDispatch dispatch = dispatch();
        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(draft));
        when(dispatchRepository.findByIdAndIsDeletedFalse(dispatchId)).thenReturn(Optional.of(dispatch));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of());
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
    }

    private SupplierPoResponseLine capturedSavedLine() {
        ArgumentCaptor<SupplierPoResponseLine> captor = ArgumentCaptor.forClass(SupplierPoResponseLine.class);
        verify(responseLineRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private SupplierPoResponseLine responseLine(UUID purchaseOrderItemId) {
        SupplierPoResponseLine line = new SupplierPoResponseLine();
        line.setId(UUID.randomUUID());
        line.setPurchaseOrderItemId(purchaseOrderItemId);
        line.setDecision("ACCEPTED");
        return line;
    }
}
