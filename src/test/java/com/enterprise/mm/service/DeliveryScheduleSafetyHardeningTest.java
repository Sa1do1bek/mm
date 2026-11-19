package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DeliveryScheduleMapper;
import com.enterprise.mm.payload.DeliveryScheduleCreateRequest;
import com.enterprise.mm.payload.DeliveryScheduleUpdateRequest;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryScheduleSafetyHardeningTest {
    @Mock private DeliveryScheduleRepository repository;
    @Spy private DeliveryScheduleMapper mapper = new DeliveryScheduleMapper();
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private ContractRepository contractRepository;
    @InjectMocks private DeliveryScheduleService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        when(repository.save(any(DeliverySchedule.class))).thenAnswer(invocation -> {
            DeliverySchedule schedule = invocation.getArgument(0);
            if (schedule.getId() == null) {
                schedule.setId(UUID.randomUUID());
            }
            return schedule;
        });
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createRejectsInaccessiblePurchaseOrder() {
        DeliveryScheduleCreateRequest dto = createRequest(UUID.randomUUID(), UUID.randomUUID());
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(dto.getPurchaseOrderId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void createRejectsInaccessibleContract() {
        DeliveryScheduleCreateRequest dto = createRequest(UUID.randomUUID(), UUID.randomUUID());
        PurchaseOrder po = purchaseOrder(dto.getPurchaseOrderId(), dto.getContractId(), true);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(po.getId())).thenReturn(Optional.of(po));
        when(contractRepository.findByIdAndIsDeletedFalse(dto.getContractId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void createRejectsContractThatDoesNotBelongToPurchaseOrder() {
        DeliveryScheduleCreateRequest dto = createRequest(UUID.randomUUID(), UUID.randomUUID());
        linkedParents(dto.getPurchaseOrderId(), UUID.randomUUID(), dto.getContractId(), true);

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Delivery schedule contract must match purchase order contract");
    }

    @Test
    void updateRejectsPurchaseOrderReparenting() {
        DeliverySchedule schedule = existingSchedule();
        existing(schedule);
        linkedParents(schedule.getPurchaseOrderId(), schedule.getContractId(), schedule.getContractId(), true);
        DeliveryScheduleUpdateRequest dto = updateRequest(UUID.randomUUID(), schedule.getContractId());

        assertThatThrownBy(() -> service.update(schedule.getId(), dto))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Delivery schedule cannot be moved to another purchase order");
    }

    @Test
    void updateRejectsContractReparenting() {
        DeliverySchedule schedule = existingSchedule();
        existing(schedule);
        linkedParents(schedule.getPurchaseOrderId(), schedule.getContractId(), schedule.getContractId(), true);
        DeliveryScheduleUpdateRequest dto = updateRequest(schedule.getPurchaseOrderId(), UUID.randomUUID());

        assertThatThrownBy(() -> service.update(schedule.getId(), dto))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Delivery schedule cannot be moved to another contract");
    }

    @Test
    void createIgnoresClientControlledWorkflowFields() {
        UUID poId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        DeliveryScheduleCreateRequest dto = createRequest(poId, contractId);
        LocalDateTime attackerTime = LocalDateTime.of(2000, 1, 1, 0, 0);
        dto.setStatus("RECEIVED");
        dto.setReceivedQuantity(BigDecimal.valueOf(5));
        dto.setReceivedAmount(BigDecimal.valueOf(50));
        dto.setOverdueFlag(true);
        dto.setControlledAt(attackerTime);
        linkedParents(poId, contractId, contractId, true);

        var response = service.create(dto);

        assertThat(response.getStatus()).isEqualTo("PLANNED");
        assertThat(response.getReceivedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getReceivedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.isOverdueFlag()).isFalse();
        assertThat(response.getControlledAt()).isNotNull().isNotEqualTo(attackerTime);
    }

    @Test
    void updatePreservesServerControlledWorkflowFields() {
        DeliverySchedule schedule = existingSchedule();
        schedule.setStatus("PARTIALLY_RECEIVED");
        schedule.setReceivedQuantity(BigDecimal.valueOf(4));
        schedule.setReceivedAmount(BigDecimal.valueOf(40));
        schedule.setOverdueFlag(true);
        schedule.setControlledAt(LocalDateTime.of(2025, 1, 1, 12, 0));
        existing(schedule);
        linkedParents(schedule.getPurchaseOrderId(), schedule.getContractId(), schedule.getContractId(), true);
        DeliveryScheduleUpdateRequest dto = updateRequest(schedule.getPurchaseOrderId(), schedule.getContractId());
        dto.setStatus("HACKED");
        dto.setReceivedQuantity(BigDecimal.ZERO);
        dto.setReceivedAmount(BigDecimal.ZERO);
        dto.setOverdueFlag(false);
        dto.setControlledAt(LocalDateTime.of(2030, 1, 1, 0, 0));

        var response = service.update(schedule.getId(), dto);

        assertThat(response.getStatus()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(response.getReceivedQuantity()).isEqualByComparingTo("4");
        assertThat(response.getReceivedAmount()).isEqualByComparingTo("40");
        assertThat(response.isOverdueFlag()).isTrue();
        assertThat(response.getControlledAt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 12, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIVED", "CANCELLED"})
    void updateRejectsTerminalScheduleBeforeMappingParentLookupPersistenceOrAudit(String status) {
        DeliverySchedule schedule = existingSchedule();
        schedule.setStatus(status);
        existing(schedule);
        DeliveryScheduleUpdateRequest dto = updateRequest(schedule.getPurchaseOrderId(), schedule.getContractId());

        assertThatThrownBy(() -> service.update(schedule.getId(), dto))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Delivery schedule in terminal status cannot be updated: " + status);

        verify(mapper, never()).updateEntity(any(), any());
        verifyNoInteractions(purchaseOrderRepository, contractRepository);
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNED", "PARTIALLY_RECEIVED", "OVERDUE"})
    void updateKeepsNonterminalSchedulePlanningFieldsEditable(String status) {
        DeliverySchedule schedule = existingSchedule();
        schedule.setStatus(status);
        if ("PARTIALLY_RECEIVED".equals(status)) {
            schedule.setReceivedQuantity(BigDecimal.valueOf(4));
        }
        schedule.setOverdueFlag(!"PLANNED".equals(status));
        existing(schedule);
        linkedParents(schedule.getPurchaseOrderId(), schedule.getContractId(), schedule.getContractId(), true);
        DeliveryScheduleUpdateRequest dto = updateRequest(schedule.getPurchaseOrderId(), schedule.getContractId());
        LocalDate revisedDate = LocalDate.now().plusDays(12);
        dto.setScheduleDate(revisedDate);
        dto.setPlannedQuantity(BigDecimal.valueOf(12));
        dto.setPlannedAmount(BigDecimal.valueOf(120));
        dto.setDescription("Revised plan");

        var response = service.update(schedule.getId(), dto);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getScheduleDate()).isEqualTo(revisedDate);
        assertThat(response.getPlannedQuantity()).isEqualByComparingTo("12");
        assertThat(response.getPlannedAmount()).isEqualByComparingTo("120");
        assertThat(response.getDescription()).isEqualTo("Revised plan");
    }

    @Test
    void createRejectsNegativePlannedQuantity() {
        DeliveryScheduleCreateRequest dto = validCreateRequest();
        dto.setPlannedQuantity(BigDecimal.valueOf(-1));

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class)
            .hasMessage("plannedQuantity cannot be negative");
    }

    @Test
    void createRejectsNegativeReceivedQuantity() {
        DeliveryScheduleCreateRequest dto = validCreateRequest();
        dto.setReceivedQuantity(BigDecimal.valueOf(-1));

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class)
            .hasMessage("receivedQuantity cannot be negative");
    }

    @Test
    void createRejectsReceivedQuantityAbovePlannedQuantity() {
        DeliveryScheduleCreateRequest dto = validCreateRequest();
        dto.setReceivedQuantity(BigDecimal.valueOf(11));

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(BusinessException.class)
            .hasMessage("receivedQuantity cannot exceed plannedQuantity");
    }

    @Test
    void updateRejectsPlannedQuantityBelowAlreadyReceivedQuantity() {
        DeliverySchedule schedule = existingSchedule();
        schedule.setReceivedQuantity(BigDecimal.valueOf(5));
        existing(schedule);
        linkedParents(schedule.getPurchaseOrderId(), schedule.getContractId(), schedule.getContractId(), true);
        DeliveryScheduleUpdateRequest dto = updateRequest(schedule.getPurchaseOrderId(), schedule.getContractId());
        dto.setPlannedQuantity(BigDecimal.valueOf(4));

        assertThatThrownBy(() -> service.update(schedule.getId(), dto)).isInstanceOf(BusinessException.class)
            .hasMessage("receivedQuantity cannot exceed plannedQuantity");
    }

    @Test
    void createRejectsParentPurchaseRequestOutsideDocumentScope() {
        DeliveryScheduleCreateRequest dto = createRequest(UUID.randomUUID(), UUID.randomUUID());
        linkedParents(dto.getPurchaseOrderId(), dto.getContractId(), dto.getContractId(), false);

        assertThatThrownBy(() -> service.create(dto)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateRejectsParentPurchaseRequestOutsideDocumentScope() {
        DeliverySchedule schedule = existingSchedule();
        existing(schedule);
        linkedParents(schedule.getPurchaseOrderId(), schedule.getContractId(), schedule.getContractId(), false);

        assertThatThrownBy(() -> service.update(
            schedule.getId(),
            updateRequest(schedule.getPurchaseOrderId(), schedule.getContractId())
        )).isInstanceOf(NotFoundException.class);
    }

    @Test
    void restoreRejectsParentPurchaseRequestOutsideDocumentScope() {
        DeliverySchedule schedule = existingSchedule();
        schedule.softDelete();
        when(repository.findAnyById(schedule.getId())).thenReturn(Optional.of(schedule));
        linkedParents(schedule.getPurchaseOrderId(), schedule.getContractId(), schedule.getContractId(), false);

        assertThatThrownBy(() -> service.restore(schedule.getId())).isInstanceOf(NotFoundException.class);
    }

    private DeliveryScheduleCreateRequest validCreateRequest() {
        DeliveryScheduleCreateRequest dto = createRequest(UUID.randomUUID(), UUID.randomUUID());
        linkedParents(dto.getPurchaseOrderId(), dto.getContractId(), dto.getContractId(), true);
        return dto;
    }

    private DeliveryScheduleCreateRequest createRequest(UUID poId, UUID contractId) {
        DeliveryScheduleCreateRequest dto = new DeliveryScheduleCreateRequest();
        dto.setCode("DS-1");
        dto.setName("Delivery schedule");
        dto.setPurchaseOrderId(poId);
        dto.setContractId(contractId);
        dto.setScheduleDate(LocalDate.now().plusDays(5));
        dto.setPlannedQuantity(BigDecimal.TEN);
        dto.setReceivedQuantity(BigDecimal.ZERO);
        return dto;
    }

    private DeliveryScheduleUpdateRequest updateRequest(UUID poId, UUID contractId) {
        DeliveryScheduleUpdateRequest dto = new DeliveryScheduleUpdateRequest();
        dto.setCode("DS-1");
        dto.setName("Updated delivery schedule");
        dto.setPurchaseOrderId(poId);
        dto.setContractId(contractId);
        dto.setScheduleDate(LocalDate.now().plusDays(5));
        dto.setPlannedQuantity(BigDecimal.TEN);
        return dto;
    }

    private DeliverySchedule existingSchedule() {
        DeliverySchedule schedule = new DeliverySchedule();
        schedule.setId(UUID.randomUUID());
        schedule.setTenantId("default");
        schedule.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        schedule.setCode("DS-1");
        schedule.setName("Delivery schedule");
        schedule.setStatus("PLANNED");
        schedule.setPurchaseOrderId(UUID.randomUUID());
        schedule.setContractId(UUID.randomUUID());
        schedule.setScheduleDate(LocalDate.now().plusDays(5));
        schedule.setPlannedQuantity(BigDecimal.TEN);
        schedule.setReceivedQuantity(BigDecimal.ZERO);
        schedule.setReceivedAmount(BigDecimal.ZERO);
        return schedule;
    }

    private void existing(DeliverySchedule schedule) {
        when(repository.findByIdAndIsDeletedFalse(schedule.getId())).thenReturn(Optional.of(schedule));
    }

    private void linkedParents(UUID poId, UUID poContractId, UUID scheduleContractId, boolean canAccessPr) {
        PurchaseOrder po = purchaseOrder(poId, poContractId, canAccessPr);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        if (scheduleContractId != null) {
            Contract contract = new Contract();
            contract.setId(scheduleContractId);
            contract.setTenantId("default");
            contract.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
            contract.setSupplierId(po.getSupplierId());
            when(contractRepository.findByIdAndIsDeletedFalse(scheduleContractId)).thenReturn(Optional.of(contract));
        }
    }

    private PurchaseOrder purchaseOrder(UUID id, UUID contractId, boolean canAccessPr) {
        PurchaseRequest request = new PurchaseRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setTenantId("default");
        po.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        po.setSupplierId(UUID.randomUUID());
        po.setContractId(contractId);
        po.setPurchaseRequest(request);
        po.setPurchaseRequestId(request.getId());
        when(roleDataScopeService.canAccessDocument(null, null, null)).thenReturn(canAccessPr);
        return po;
    }
}
