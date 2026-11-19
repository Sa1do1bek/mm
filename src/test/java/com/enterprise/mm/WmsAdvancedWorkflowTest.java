package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.CrossDockingTaskResponse;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileWarehouseRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineOperationRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineSyncRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineSyncResponse;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineSyncResult;
import com.enterprise.mm.payload.PutawayRuleResponse;
import com.enterprise.mm.payload.PutawayRuleSearchRequest;
import com.enterprise.mm.payload.WmsDtos.CrossDockingActionRequest;
import com.enterprise.mm.payload.CrossDockingTaskCreateRequest;
import com.enterprise.mm.payload.PutawayRuleCreateRequest;
import com.enterprise.mm.service.CrossDockingTaskService;
import com.enterprise.mm.service.MobileWarehouseService;
import com.enterprise.mm.service.PutawayRuleService;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.CrossDockingOutboundReferenceType;
import com.enterprise.mm.enums.CrossDockingTaskStatus;
import com.enterprise.mm.enums.MobileWarehouseOperationType;
import com.enterprise.mm.enums.StorageZoneType;
import com.enterprise.mm.enums.TsdOfflineOperationStatus;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.TsdOfflineOperationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "wms-manager", roles = "WAREHOUSE_MANAGER")
class WmsAdvancedWorkflowTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StorageLocationRepository storageLocationRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired TsdOfflineOperationRepository offlineOperationRepository;
    @Autowired PutawayRuleService putawayRuleService;
    @Autowired MobileWarehouseService mobileWarehouseService;
    @Autowired CrossDockingTaskService crossDockingTaskService;

    @Test
    @Transactional
    void offlineTsdSyncIsIdempotentPutawayUsesRulesAndCrossDockingShipsFromStaging() {
        Material material = material("WMS-MAT");
        Warehouse warehouse = warehouse("WMS-WH");
        warehouseEmployee(warehouse);
        StorageLocation staging = location("STAGE", warehouse.getId(), "XD", "01", BigDecimal.valueOf(50));
        StorageLocation target = location("A", warehouse.getId(), "FAST", "01", BigDecimal.valueOf(100));

        PutawayRuleCreateRequest rule = new PutawayRuleCreateRequest();
        rule.setName("Fast movers to A-01");
        rule.setWarehouseId(warehouse.getId());
        rule.setMaterialId(material.getId());
        rule.setStorageZoneCode("FAST");
        rule.setTargetStorageLocationId(target.getId());
        rule.setPriority(10);
        rule.setMaxQuantity(BigDecimal.valueOf(100));
        rule.setAllowMixedMaterials(true);
        putawayRuleService.create(rule);
        assertThat(putawayRuleService.search(emptySearch(), PageRequest.of(0, 10, Sort.by("name"))).getContent())
            .anyMatch(item -> item.getName() != null && item.getName().contains(rule.getName()));

        OfflineSyncRequest syncRequest = new OfflineSyncRequest(
            "TSD-01",
            List.of(new OfflineOperationRequest(
                "client-op-001",
                MobileWarehouseOperationType.RECEIVE,
                LocalDateTime.now().minusMinutes(5),
                new MobileWarehouseRequest(material.getId(), null, warehouse.getId(), null, staging.getId(), null, BigDecimal.TEN, BigDecimal.valueOf(10), null)
            ))
        );
        OfflineSyncResponse firstSync = mobileWarehouseService.syncOffline(syncRequest);
        assertThat(firstSync.synced()).isEqualTo(1);
        OfflineSyncResult firstResult = firstSync.results().getFirst();
        assertThat(firstResult.status()).isEqualTo(TsdOfflineOperationStatus.SYNCED.name());

        StockMovement receipt = stockMovementRepository.findAnyById(firstResult.stockMovementId()).orElseThrow();
        UUID stockBatchId = receipt.getStockBatchId();
        assertThat(stockBatchId).isNotNull();
        assertThat(balance(material.getId(), warehouse.getId(), staging.getId(), stockBatchId).getQuantity()).isEqualByComparingTo("10");

        OfflineSyncResponse duplicateSync = mobileWarehouseService.syncOffline(syncRequest);
        assertThat(duplicateSync.synced()).isEqualTo(1);
        assertThat(duplicateSync.results().getFirst().duplicate()).isTrue();
        assertThat(balance(material.getId(), warehouse.getId(), staging.getId(), stockBatchId).getQuantity()).isEqualByComparingTo("10");
        assertThat(offlineOperationRepository.findAll()).anyMatch(item -> "client-op-001".equals(item.getClientOperationId())
            && TsdOfflineOperationStatus.SYNCED.name().equals(item.getStatus())
            && item.getAttemptCount() == 1);

        mobileWarehouseService.putAway(new MobileWarehouseRequest(
            material.getId(),
            stockBatchId,
            warehouse.getId(),
            warehouse.getId(),
            staging.getId(),
            null,
            BigDecimal.valueOf(4),
            BigDecimal.valueOf(10),
            null
        ));
        assertThat(balance(material.getId(), warehouse.getId(), target.getId(), stockBatchId).getQuantity()).isEqualByComparingTo("4");
        assertThat(balance(material.getId(), warehouse.getId(), staging.getId(), stockBatchId).getQuantity()).isEqualByComparingTo("6");

        CrossDockingTaskCreateRequest taskRequest = new CrossDockingTaskCreateRequest();
        taskRequest.setName("Cross-dock direct shipment");
        taskRequest.setMaterialId(material.getId());
        taskRequest.setStockBatchId(stockBatchId);
        taskRequest.setWarehouseId(warehouse.getId());
        taskRequest.setStagingLocationId(staging.getId());
        taskRequest.setQuantity(BigDecimal.valueOf(3));
        taskRequest.setUnitCost(BigDecimal.valueOf(10));
        CrossDockingTaskResponse task = crossDockingTaskService.create(taskRequest);
        task = crossDockingTaskService.receiveInbound(task.getId(), new CrossDockingActionRequest(null, stockBatchId, staging.getId(), null, null, null, null, null, null, null, null));
        task = crossDockingTaskService.allocateOutbound(task.getId(), new CrossDockingActionRequest(null, null, null, null, null, CrossDockingOutboundReferenceType.DEMAND, UUID.randomUUID(), null, null, null, null));
        task = crossDockingTaskService.ship(task.getId(), new CrossDockingActionRequest(null, null, null, null, null, null, null, BigDecimal.valueOf(3), BigDecimal.valueOf(10), null, null));

        assertThat(task.getStatus()).isEqualTo(CrossDockingTaskStatus.SHIPPED.name());
        assertThat(task.getOutboundStockMovementId()).isNotNull();
        assertThat(balance(material.getId(), warehouse.getId(), staging.getId(), stockBatchId).getQuantity()).isEqualByComparingTo("3");
    }

    @Test
    @Transactional
    void putawayRuleResponsesIncludeReadableReferenceNames() {
        MaterialGroup group = materialGroup("Reactive liquids");
        Material material = material("CAUSTIC-SODA");
        material.setName("Kaustik soda");
        material.setMaterialGroupId(group.getId());
        materialRepository.save(material);
        Warehouse warehouse = warehouse("PUTAWAY-WH");
        warehouseEmployee(warehouse);
        StorageLocation target = location("CHEM", warehouse.getId(), "CHEMICAL", "01", BigDecimal.valueOf(100));

        PutawayRuleCreateRequest request = new PutawayRuleCreateRequest();
        request.setName("Chemicals to safe zone");
        request.setWarehouseId(warehouse.getId());
        request.setMaterialId(material.getId());
        request.setMaterialGroupId(group.getId());
        request.setTargetStorageLocationId(target.getId());

        PutawayRuleResponse created = putawayRuleService.create(request);
        assertThat(created.getMaterialName()).isEqualTo("Kaustik soda");
        assertThat(created.getMaterialGroupName()).isEqualTo("Reactive liquids");
        assertThat(created.getTargetStorageLocationName()).isEqualTo("CHEM-01");

        PutawayRuleResponse listed = putawayRuleService.search(
            emptySearch(),
            PageRequest.of(0, 10, Sort.by("name"))
        ).getContent().stream()
            .filter(item -> item.getId().equals(created.getId()))
            .findFirst()
            .orElseThrow();
        assertThat(listed.getMaterialName()).isEqualTo("Kaustik soda");
        assertThat(listed.getMaterialGroupName()).isEqualTo("Reactive liquids");
        assertThat(listed.getTargetStorageLocationName()).isEqualTo("CHEM-01");
    }

    private StockBalance balance(UUID materialId, UUID warehouseId, UUID storageLocationId, UUID stockBatchId) {
        return stockBalanceRepository.findBalance(
            TenantContext.currentTenant(),
            materialId,
            warehouseId,
            storageLocationId == null,
            storageLocationId,
            stockBatchId == null,
            stockBatchId
        ).orElseThrow();
    }

    private Material material(String prefix) {
        String code = prefix + "-" + suffix();
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private MaterialGroup materialGroup(String name) {
        MaterialGroup group = new MaterialGroup();
        group.setCode("GROUP-" + suffix());
        group.setName(name);
        group.setStatus("ACTIVE");
        return materialGroupRepository.save(group);
    }

    private Employee warehouseEmployee(Warehouse warehouse) {
        Employee employee = new Employee();
        employee.setCode("wms-manager-" + suffix());
        employee.setName("WMS manager");
        employee.setStatus("ACTIVE");
        employee.setPersonnelNumber("wms-manager");
        employee.setWarehouse(warehouse);
        return employeeRepository.save(employee);
    }

    private Warehouse warehouse(String prefix) {
        String code = prefix + "-" + suffix();
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private StorageLocation location(String zone, UUID warehouseId, String type, String bin, BigDecimal capacity) {
        StorageLocation location = new StorageLocation();
        location.setCode(zone + "-" + bin + "-" + suffix());
        location.setName(zone + "-" + bin);
        location.setStatus("ACTIVE");
        location.setWarehouseId(warehouseId);
        location.setZoneCode(type);
        location.setBinCode(bin);
        location.setStorageZoneType(StorageZoneType.STORAGE);
        location.setCapacityQuantity(capacity);
        location.setAllowMixedMaterials(true);
        return storageLocationRepository.save(location);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private PutawayRuleSearchRequest emptySearch() {
        return PutawayRuleSearchRequest.empty();
    }
}
