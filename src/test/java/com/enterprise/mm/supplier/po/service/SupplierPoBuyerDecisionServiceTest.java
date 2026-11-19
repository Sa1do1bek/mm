package com.enterprise.mm.supplier.po.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.entity.SupplierPoResponseLine;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoResponseLineRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
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
class SupplierPoBuyerDecisionServiceTest {
    @Mock private SupplierPoResponseRepository responseRepository;
    @Mock private SupplierPoResponseLineRepository responseLineRepository;
    @Mock private SupplierPoCommitmentRepository commitmentRepository;
    @Mock private DeliveryScheduleRepository deliveryScheduleRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private SupplierPoBuyerDecisionService service;

    private UUID responseId;
    private UUID purchaseOrderId;
    private UUID purchaseOrderItemId;
    private UUID supplierId;
    private UUID lineId;
    private UUID commitmentId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        responseId = UUID.randomUUID();
        purchaseOrderId = UUID.randomUUID();
        purchaseOrderItemId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        lineId = UUID.randomUUID();
        commitmentId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void acceptCreatesExactlyOneDeliveryScheduleForAConfirmedCommitment() {
        SupplierPoResponse response = submittedResponse();
        SupplierPoResponseLine line = acceptedLine();
        SupplierPoCommitment commitment = proposedCommitment();
        PurchaseOrder purchaseOrder = purchaseOrder();

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(response));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, responseId))
            .thenReturn(List.of(line));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndResponseLineIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, lineId
        )).thenReturn(List.of(commitment));
        when(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, commitmentId
        )).thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(deliveryScheduleRepository.save(any(DeliverySchedule.class))).thenAnswer(invocation -> {
            DeliverySchedule schedule = invocation.getArgument(0);
            schedule.setId(UUID.randomUUID());
            return schedule;
        });
        when(responseRepository.save(any(SupplierPoResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.accept(responseId, "looks good");

        verify(deliveryScheduleRepository, times(1)).save(any(DeliverySchedule.class));
        assertThat(commitment.getStatus()).isEqualTo("CONFIRMED");
        assertThat(commitment.getConfirmedQuantity()).isEqualByComparingTo("6");
        assertThat(commitment.getDeliveryScheduleId()).isNotNull();
        assertThat(response.getResponseStatus()).isEqualTo("BUYER_ACCEPTED");
    }

    @Test
    void acceptRetryDoesNotCreateADuplicateScheduleWhenOneAlreadyExistsForTheCommitment() {
        SupplierPoResponse response = submittedResponse();
        SupplierPoResponseLine line = acceptedLine();
        SupplierPoCommitment commitment = proposedCommitment();

        DeliverySchedule existingSchedule = new DeliverySchedule();
        existingSchedule.setId(UUID.randomUUID());

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(response));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, responseId))
            .thenReturn(List.of(line));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndResponseLineIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, lineId
        )).thenReturn(List.of(commitment));
        when(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, commitmentId
        )).thenReturn(Optional.of(existingSchedule));
        when(responseRepository.save(any(SupplierPoResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.accept(responseId, "retry after network timeout");

        verify(deliveryScheduleRepository, never()).save(any(DeliverySchedule.class));
        verify(purchaseOrderRepository, never()).findByIdAndIsDeletedFalse(any());
        assertThat(commitment.getDeliveryScheduleId()).isEqualTo(existingSchedule.getId());
    }

    @Test
    void buyerAcceptsFullResponseWithoutCommitmentsCreatesConfirmedCommitment() {
        SupplierPoResponse response = submittedResponse();
        SupplierPoResponseLine line = acceptedLine();
        PurchaseOrder purchaseOrder = purchaseOrder();
        purchaseOrder.setExpectedDeliveryDate(LocalDate.now().plusDays(5));
        PurchaseOrderItem item = purchaseOrderItem();

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(response));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, responseId))
            .thenReturn(List.of(line));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndResponseLineIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, lineId
        )).thenReturn(List.of());
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(purchaseOrderItemId)).thenReturn(Optional.of(item));
        when(commitmentRepository.save(any(SupplierPoCommitment.class))).thenAnswer(invocation -> {
            SupplierPoCommitment commitment = invocation.getArgument(0);
            if (commitment.getId() == null) {
                commitment.setId(commitmentId);
            }
            return commitment;
        });
        when(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, commitmentId
        )).thenReturn(Optional.empty());
        when(deliveryScheduleRepository.save(any(DeliverySchedule.class))).thenAnswer(invocation -> {
            DeliverySchedule schedule = invocation.getArgument(0);
            schedule.setId(UUID.randomUUID());
            return schedule;
        });
        when(responseRepository.save(any(SupplierPoResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.accept(responseId, "accepted");

        SupplierPoCommitment commitment = capturedCommitmentSaves().get(0);
        assertThat(commitment.getStatus()).isEqualTo("CONFIRMED");
        assertThat(commitment.getResponseLineId()).isEqualTo(lineId);
        assertThat(commitment.getPurchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(commitment.getPurchaseOrderItemId()).isEqualTo(purchaseOrderItemId);
        assertThat(commitment.getSupplierId()).isEqualTo(supplierId);
        assertThat(commitment.getProposedQuantity()).isEqualByComparingTo("6");
        assertThat(commitment.getConfirmedQuantity()).isEqualByComparingTo("6");
        assertThat(commitment.getProposedDate()).isEqualTo(purchaseOrder.getExpectedDeliveryDate());
        assertThat(commitment.getConfirmedDate()).isEqualTo(purchaseOrder.getExpectedDeliveryDate());
        assertThat(commitment.isCurrent()).isTrue();
        assertThat(commitment.getDeliveryScheduleId()).isNotNull();
        verify(deliveryScheduleRepository).save(any(DeliverySchedule.class));
    }

    @Test
    void buyerAcceptsResponseWithExistingCommitmentsDoesNotDuplicate() {
        SupplierPoResponse response = submittedResponse();
        SupplierPoResponseLine line = acceptedLine();
        SupplierPoCommitment commitment = proposedCommitment();
        PurchaseOrder purchaseOrder = purchaseOrder();

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(response));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, responseId))
            .thenReturn(List.of(line));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndResponseLineIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, lineId
        )).thenReturn(List.of(commitment));
        when(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, commitmentId
        )).thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(deliveryScheduleRepository.save(any(DeliverySchedule.class))).thenAnswer(invocation -> {
            DeliverySchedule schedule = invocation.getArgument(0);
            schedule.setId(UUID.randomUUID());
            return schedule;
        });
        when(responseRepository.save(any(SupplierPoResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.accept(responseId, "existing proposal accepted");

        List<SupplierPoCommitment> savedCommitments = capturedCommitmentSaves();
        assertThat(savedCommitments).hasSize(2);
        assertThat(savedCommitments).allMatch(saved -> saved == commitment);
        assertThat(commitment.getStatus()).isEqualTo("CONFIRMED");
        assertThat(commitment.getConfirmedQuantity()).isEqualByComparingTo("6");
        assertThat(commitment.getDeliveryScheduleId()).isNotNull();
    }

    @Test
    void buyerAcceptsSplitCommitmentsConfirmsEachProposedQuantity() {
        SupplierPoResponse response = submittedResponse();
        SupplierPoResponseLine line = acceptedLine();
        line.setAcceptedQuantity(new BigDecimal("10"));
        SupplierPoCommitment first = proposedCommitment();
        first.setProposedQuantity(new BigDecimal("4"));
        SupplierPoCommitment second = proposedCommitment();
        second.setId(UUID.randomUUID());
        second.setProposedQuantity(new BigDecimal("6"));
        PurchaseOrder purchaseOrder = purchaseOrder();

        when(responseRepository.findByIdAndIsDeletedFalse(responseId)).thenReturn(Optional.of(response));
        when(responseLineRepository.findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, responseId))
            .thenReturn(List.of(line));
        when(commitmentRepository.findByTenantIdAndCompanyIdAndResponseLineIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, lineId
        )).thenReturn(List.of(first, second));
        when(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, first.getId()
        )).thenReturn(Optional.empty());
        when(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, second.getId()
        )).thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(deliveryScheduleRepository.save(any(DeliverySchedule.class))).thenAnswer(invocation -> {
            DeliverySchedule schedule = invocation.getArgument(0);
            schedule.setId(UUID.randomUUID());
            return schedule;
        });
        when(responseRepository.save(any(SupplierPoResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.accept(responseId, "split proposal accepted");

        assertThat(first.getStatus()).isEqualTo("CONFIRMED");
        assertThat(first.getConfirmedQuantity()).isEqualByComparingTo("4");
        assertThat(second.getStatus()).isEqualTo("CONFIRMED");
        assertThat(second.getConfirmedQuantity()).isEqualByComparingTo("6");
    }

    private SupplierPoResponse submittedResponse() {
        SupplierPoResponse response = new SupplierPoResponse();
        response.setId(responseId);
        response.setTenantId("default");
        response.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        response.setPurchaseOrderId(purchaseOrderId);
        response.setSupplierId(supplierId);
        response.setResponseStatus("SUBMITTED");
        return response;
    }

    private SupplierPoResponseLine acceptedLine() {
        SupplierPoResponseLine line = new SupplierPoResponseLine();
        line.setId(lineId);
        line.setDecision("ACCEPTED");
        line.setPurchaseOrderItemId(purchaseOrderItemId);
        line.setAcceptedQuantity(new BigDecimal("6"));
        return line;
    }

    private SupplierPoCommitment proposedCommitment() {
        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(commitmentId);
        commitment.setResponseLineId(lineId);
        commitment.setPurchaseOrderId(purchaseOrderId);
        commitment.setPurchaseOrderItemId(purchaseOrderItemId);
        commitment.setSupplierId(supplierId);
        commitment.setStatus("PROPOSED");
        commitment.setProposedQuantity(new BigDecimal("10"));
        commitment.setProposedDate(LocalDate.now().plusDays(7));
        commitment.setCurrent(true);
        return commitment;
    }

    private PurchaseOrder purchaseOrder() {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setCode("PO-1");
        purchaseOrder.setName("PO-1");
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setSupplierId(supplierId);
        return purchaseOrder;
    }

    private PurchaseOrderItem purchaseOrderItem() {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(purchaseOrderItemId);
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setName("Line 1");
        item.setPurchaseOrderId(purchaseOrderId);
        return item;
    }

    private List<SupplierPoCommitment> capturedCommitmentSaves() {
        ArgumentCaptor<SupplierPoCommitment> captor = ArgumentCaptor.forClass(SupplierPoCommitment.class);
        verify(commitmentRepository, times(2)).save(captor.capture());
        return captor.getAllValues();
    }
}
