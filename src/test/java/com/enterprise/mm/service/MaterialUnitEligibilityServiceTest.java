package com.enterprise.mm.service;

import com.enterprise.mm.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.MaterialUnitEvent;
import com.enterprise.mm.entity.MaterialUnitOperation;
import com.enterprise.mm.enums.MaterialUnitEventType;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.repository.MaterialUnitEventRepository;
import com.enterprise.mm.repository.MaterialUnitOperationRepository;
import com.enterprise.mm.repository.MaterialUnitRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialUnitEligibilityServiceTest {
    @Mock private MaterialUnitRepository materialUnitRepository;
    @Mock private MaterialUnitEventRepository eventRepository;
    @Mock private MaterialUnitOperationRepository operationRepository;

    private MaterialUnitEligibilityService service;
    private UUID companyId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID locationId;
    private UUID batchId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("unit-eligibility-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        materialId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        batchId = UUID.randomUUID();
        service = new MaterialUnitEligibilityService(materialUnitRepository, eventRepository, operationRepository);
        org.mockito.Mockito.lenient()
            .when(materialUnitRepository.save(any(MaterialUnit.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient()
            .when(eventRepository.save(any(MaterialUnitEvent.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        org.mockito.Mockito.lenient()
            .when(operationRepository.save(any(MaterialUnitOperation.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void reserveRejectsDuplicateUnitSelectionBeforeLoadingRows() {
        UUID unitId = UUID.randomUUID();

        assertThatThrownBy(() -> service.reserve(
            materialId,
            warehouseId,
            locationId,
            null,
            BigDecimal.valueOf(2),
            List.of(unitId, unitId),
            "MaterialReservation",
            UUID.randomUUID(),
            null
        ))
            .hasMessageContaining("DUPLICATE_MATERIAL_UNIT_SELECTION");

        org.mockito.Mockito.verifyNoInteractions(materialUnitRepository);
    }

    @Test
    void reserveRejectsQuantitySelectionMismatch() {
        assertThatThrownBy(() -> service.reserve(
            materialId,
            warehouseId,
            locationId,
            null,
            BigDecimal.valueOf(2),
            List.of(UUID.randomUUID()),
            "MaterialReservation",
            UUID.randomUUID(),
            null
        ))
            .hasMessageContaining("SERIAL_UNIT_COUNT_MISMATCH");
    }

    @Test
    void reserveRejectsUnitFromWrongWarehouseOrMaterial() {
        UUID wrongWarehouseUnitId = UUID.randomUUID();
        UUID wrongMaterialUnitId = UUID.randomUUID();
        when(materialUnitRepository.findAllForUpdate(
            TenantContext.currentTenant(),
            companyId,
            List.of(wrongWarehouseUnitId)
        )).thenReturn(List.of(unit(wrongWarehouseUnitId, materialId, UUID.randomUUID(), locationId, MaterialUnitStatus.IN_STOCK)));
        when(materialUnitRepository.findAllForUpdate(
            TenantContext.currentTenant(),
            companyId,
            List.of(wrongMaterialUnitId)
        )).thenReturn(List.of(unit(wrongMaterialUnitId, UUID.randomUUID(), warehouseId, locationId, MaterialUnitStatus.IN_STOCK)));

        assertThatThrownBy(() -> service.reserve(
            materialId,
            warehouseId,
            locationId,
            null,
            BigDecimal.ONE,
            List.of(wrongWarehouseUnitId),
            "MaterialReservation",
            UUID.randomUUID(),
            null
        ))
            .hasMessageContaining("MATERIAL_UNIT_WRONG_WAREHOUSE");

        assertThatThrownBy(() -> service.reserve(
            materialId,
            warehouseId,
            locationId,
            null,
            BigDecimal.ONE,
            List.of(wrongMaterialUnitId),
            "MaterialReservation",
            UUID.randomUUID(),
            null
        ))
            .hasMessageContaining("MATERIAL_UNIT_WRONG_MATERIAL");
    }

    @Test
    void reserveTransitionsUnitsAndRecordsOperationAndEvent() {
        UUID unitA = UUID.randomUUID();
        UUID unitB = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        when(materialUnitRepository.findAllForUpdate(
            TenantContext.currentTenant(),
            companyId,
            List.of(unitA, unitB)
        )).thenReturn(List.of(
            unit(unitA, materialId, warehouseId, locationId, MaterialUnitStatus.IN_STOCK),
            unit(unitB, materialId, warehouseId, locationId, MaterialUnitStatus.IN_STOCK)
        ));

        List<MaterialUnit> reserved = service.reserve(
            materialId,
            warehouseId,
            locationId,
            null,
            BigDecimal.valueOf(2),
            List.of(unitA, unitB),
            "MaterialReservation",
            reservationId,
            null
        );

        assertThat(reserved).extracting(MaterialUnit::getUnitStatus)
            .containsExactly(MaterialUnitStatus.RESERVED, MaterialUnitStatus.RESERVED);
        org.mockito.Mockito.verify(materialUnitRepository, times(2)).save(any(MaterialUnit.class));

        ArgumentCaptor<MaterialUnitOperation> operationCaptor = ArgumentCaptor.forClass(MaterialUnitOperation.class);
        org.mockito.Mockito.verify(operationRepository, times(2)).save(operationCaptor.capture());
        assertThat(operationCaptor.getAllValues())
            .extracting(MaterialUnitOperation::getOperationType, MaterialUnitOperation::getReferenceType, MaterialUnitOperation::getReferenceId)
            .containsOnly(org.assertj.core.groups.Tuple.tuple(
                MaterialUnitOperationType.RESERVATION,
                "MaterialReservation",
                reservationId
            ));

        ArgumentCaptor<MaterialUnitEvent> eventCaptor = ArgumentCaptor.forClass(MaterialUnitEvent.class);
        org.mockito.Mockito.verify(eventRepository, times(2)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .extracting(MaterialUnitEvent::getEventType, MaterialUnitEvent::getSourceType, MaterialUnitEvent::getSourceId)
            .containsOnly(org.assertj.core.groups.Tuple.tuple(
                MaterialUnitEventType.RESERVED,
                "MaterialReservation",
                reservationId
            ));
    }

    @Test
    void exactTransferRejectsWildcardLocationAndBinBeforeTransition() {
        UUID id = UUID.randomUUID();
        var selected = unit(id, materialId, warehouseId, locationId, MaterialUnitStatus.IN_STOCK);
        when(materialUnitRepository.findForTransferUpdate(TenantContext.currentTenant(), companyId, List.of(id)))
            .thenReturn(List.of(selected));
        assertThatThrownBy(() -> service.dispatchTransferExact(materialId, warehouseId, null, null,
            BigDecimal.ONE, List.of(id), "TransferDocument", UUID.randomUUID(), UUID.randomUUID()))
            .hasMessageContaining("WRONG_LOCATION");
        selected.setStorageBinId(UUID.randomUUID());
        assertThatThrownBy(() -> service.dispatchTransferExact(materialId, warehouseId, locationId, null,
            BigDecimal.ONE, List.of(id), "TransferDocument", UUID.randomUUID(), UUID.randomUUID()))
            .hasMessageContaining("WRONG_LOCATION");
        assertThat(selected.getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_STOCK);
        org.mockito.Mockito.verifyNoInteractions(eventRepository, operationRepository);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void receiptUpdatesPhysicalUnitDispositionAndDestinationBatch(boolean quarantine) {
        UUID id = UUID.randomUUID(), destination = UUID.randomUUID(), targetBatch = UUID.randomUUID(), document = UUID.randomUUID(), line = UUID.randomUUID();
        var selected = unit(id, materialId, warehouseId, locationId, MaterialUnitStatus.IN_TRANSIT);
        when(materialUnitRepository.findAllForUpdate(TenantContext.currentTenant(), companyId, List.of(id))).thenReturn(List.of(selected));
        when(operationRepository.findTransferDispatchUnitIds(TenantContext.currentTenant(), companyId, "TransferDocument", document, line)).thenReturn(List.of(id));
        service.receiveTransferWithDisposition(materialId, warehouseId, batchId, selected.getManufacturerSerialNumber(), destination,
            locationId, null, targetBatch, BigDecimal.ONE, List.of(id), quarantine, "TransferDocument", document, line);
        assertThat(selected.getUnitStatus()).isEqualTo(quarantine ? MaterialUnitStatus.QUARANTINE : MaterialUnitStatus.IN_STOCK);
        assertThat(selected.getWarehouseId()).isEqualTo(destination);
        assertThat(selected.getStockBatchId()).isEqualTo(targetBatch);
        org.mockito.Mockito.verify(eventRepository).save(any(MaterialUnitEvent.class));
        org.mockito.Mockito.verify(operationRepository).save(any(MaterialUnitOperation.class));
    }

    @Test void receiptRejectsWrongSerialOrBatchBeforeChangingPhysicalUnit() {
        UUID id = UUID.randomUUID();
        var selected = unit(id, materialId, warehouseId, locationId, MaterialUnitStatus.IN_TRANSIT);
        when(materialUnitRepository.findAllForUpdate(TenantContext.currentTenant(), companyId, List.of(id))).thenReturn(List.of(selected));
        assertThatThrownBy(() -> service.receiveTransferWithDisposition(materialId, warehouseId, UUID.randomUUID(), null,
            UUID.randomUUID(), locationId, null, UUID.randomUUID(), BigDecimal.ONE, List.of(id), true,
            "TransferDocument", UUID.randomUUID(), UUID.randomUUID())).hasMessageContaining("WRONG_BATCH");
        assertThatThrownBy(() -> service.receiveTransferWithDisposition(materialId, warehouseId, batchId, "OTHER-SERIAL",
            UUID.randomUUID(), locationId, null, UUID.randomUUID(), BigDecimal.ONE, List.of(id), true,
            "TransferDocument", UUID.randomUUID(), UUID.randomUUID())).hasMessageContaining("WRONG_SERIAL");
        assertThat(selected.getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_TRANSIT);
        assertThat(selected.getStockBatchId()).isEqualTo(batchId);
        org.mockito.Mockito.verifyNoInteractions(eventRepository, operationRepository);
    }

    @Test void receiptRejectsPhysicalUnitsDispatchedForAnotherPositionInTheSameDocument() {
        UUID id = UUID.randomUUID(); var selected = unit(id, materialId, warehouseId, locationId, MaterialUnitStatus.IN_TRANSIT);
        when(materialUnitRepository.findAllForUpdate(TenantContext.currentTenant(), companyId, List.of(id))).thenReturn(List.of(selected));
        assertThatThrownBy(() -> service.receiveTransferWithDisposition(materialId, warehouseId, batchId, selected.getManufacturerSerialNumber(),
            UUID.randomUUID(), locationId, null, UUID.randomUUID(), BigDecimal.ONE, List.of(id), false,
            "TransferDocument", UUID.randomUUID(), UUID.randomUUID())).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo("transfers.receipt.serialWrongLine"));
        assertThat(selected.getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_TRANSIT);
        assertThat(selected.getStockBatchId()).isEqualTo(batchId);
        org.mockito.Mockito.verifyNoInteractions(eventRepository);
    }

    private MaterialUnit unit(
        UUID id,
        UUID material,
        UUID warehouse,
        UUID location,
        MaterialUnitStatus status
    ) {
        MaterialUnit unit = new MaterialUnit();
        unit.setId(id);
        unit.setTenantId(TenantContext.currentTenant());
        unit.setCompanyId(companyId);
        unit.setMaterialId(material);
        unit.setWarehouseId(warehouse);
        unit.setStorageLocationId(location);
        unit.setStockBatchId(batchId);
        unit.setManufacturerSerialNumber("SN-" + id.toString().substring(0, 8));
        unit.setNormalizedSerialNumber(unit.getManufacturerSerialNumber());
        unit.setGoodsReceiptLineId(UUID.randomUUID());
        unit.setGoodsReceiptUnitId(UUID.randomUUID());
        unit.setReceivedAt(java.time.LocalDateTime.now());
        unit.setUnitStatus(status);
        return unit;
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T withId(T entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        return entity;
    }
}
