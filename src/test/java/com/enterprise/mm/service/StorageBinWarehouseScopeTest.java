package com.enterprise.mm.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.StorageBinMapper;
import com.enterprise.mm.payload.StorageBinCreateRequest;
import com.enterprise.mm.payload.StorageBinUpdateRequest;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageBinWarehouseScopeTest {
    private static final UUID BIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CURRENT_LOCATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ACCESSIBLE_WAREHOUSE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_WAREHOUSE_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Mock StorageBinRepository repository;
    @Mock StorageLocationRepository storageLocationRepository;
    @Mock UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock AuditService auditService;
    @Mock RoleDataScopeService roleDataScopeService;

    private StorageBinService service;

    @BeforeEach
    void setUp() {
        service = new StorageBinService(
            repository,
            storageLocationRepository,
            unitOfMeasureRepository,
            new StorageBinMapper(),
            auditService,
            roleDataScopeService
        );
    }

    @Test
    void getHidesBinOutsideWarehouseScope() {
        StorageBin bin = bin(OTHER_WAREHOUSE_ID, OTHER_LOCATION_ID, false);
        when(repository.findByIdAndIsDeletedFalse(BIN_ID)).thenReturn(Optional.of(bin));
        when(roleDataScopeService.canAccessWarehouse(OTHER_WAREHOUSE_ID)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.get(BIN_ID));

        verify(roleDataScopeService).canAccessWarehouse(OTHER_WAREHOUSE_ID);
    }

    @Test
    void updateHidesBinOutsideWarehouseScope() {
        StorageBin bin = bin(OTHER_WAREHOUSE_ID, OTHER_LOCATION_ID, false);
        StorageLocation location = location(OTHER_LOCATION_ID, OTHER_WAREHOUSE_ID);
        StorageBinUpdateRequest request = updateRequest(OTHER_LOCATION_ID, OTHER_WAREHOUSE_ID);
        when(repository.findByIdAndIsDeletedFalse(BIN_ID)).thenReturn(Optional.of(bin));
        lenient().when(storageLocationRepository.findByIdAndIsDeletedFalse(OTHER_LOCATION_ID)).thenReturn(Optional.of(location));
        lenient().when(repository.save(bin)).thenReturn(bin);
        when(roleDataScopeService.canAccessWarehouse(OTHER_WAREHOUSE_ID)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.update(BIN_ID, request));

        verify(roleDataScopeService).canAccessWarehouse(OTHER_WAREHOUSE_ID);
    }

    @Test
    void softDeleteHidesBinOutsideWarehouseScope() {
        StorageBin bin = bin(OTHER_WAREHOUSE_ID, OTHER_LOCATION_ID, false);
        when(repository.findByIdAndIsDeletedFalse(BIN_ID)).thenReturn(Optional.of(bin));
        lenient().when(repository.save(bin)).thenReturn(bin);
        when(roleDataScopeService.canAccessWarehouse(OTHER_WAREHOUSE_ID)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.softDelete(BIN_ID));

        verify(roleDataScopeService).canAccessWarehouse(OTHER_WAREHOUSE_ID);
    }

    @Test
    void restoreHidesBinOutsideWarehouseScope() {
        StorageBin bin = bin(OTHER_WAREHOUSE_ID, OTHER_LOCATION_ID, true);
        when(repository.findAnyById(BIN_ID)).thenReturn(Optional.of(bin));
        lenient().when(repository.save(bin)).thenReturn(bin);
        when(roleDataScopeService.canAccessWarehouse(OTHER_WAREHOUSE_ID)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.restore(BIN_ID));

        verify(roleDataScopeService).canAccessWarehouse(OTHER_WAREHOUSE_ID);
    }

    @Test
    void createChecksWarehouseResolvedFromStorageLocation() {
        StorageLocation location = location(OTHER_LOCATION_ID, OTHER_WAREHOUSE_ID);
        StorageBinCreateRequest request = createRequest(OTHER_LOCATION_ID, null);
        when(storageLocationRepository.findByIdAndIsDeletedFalse(OTHER_LOCATION_ID)).thenReturn(Optional.of(location));
        lenient().when(repository.save(any(StorageBin.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleDataScopeService.canAccessWarehouse(OTHER_WAREHOUSE_ID)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.create(request));

        verify(roleDataScopeService).canAccessWarehouse(OTHER_WAREHOUSE_ID);
    }

    @Test
    void updateChecksWarehouseResolvedFromNewStorageLocation() {
        StorageBin bin = bin(ACCESSIBLE_WAREHOUSE_ID, CURRENT_LOCATION_ID, false);
        StorageLocation otherLocation = location(OTHER_LOCATION_ID, OTHER_WAREHOUSE_ID);
        StorageBinUpdateRequest request = updateRequest(OTHER_LOCATION_ID, null);
        when(repository.findByIdAndIsDeletedFalse(BIN_ID)).thenReturn(Optional.of(bin));
        when(storageLocationRepository.findByIdAndIsDeletedFalse(OTHER_LOCATION_ID)).thenReturn(Optional.of(otherLocation));
        lenient().when(repository.save(bin)).thenReturn(bin);
        when(roleDataScopeService.canAccessWarehouse(ACCESSIBLE_WAREHOUSE_ID)).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(OTHER_WAREHOUSE_ID)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.update(BIN_ID, request));

        verify(roleDataScopeService).canAccessWarehouse(ACCESSIBLE_WAREHOUSE_ID);
        verify(roleDataScopeService).canAccessWarehouse(OTHER_WAREHOUSE_ID);
    }

    private StorageBin bin(UUID warehouseId, UUID storageLocationId, boolean deleted) {
        StorageBin bin = new StorageBin();
        bin.setId(BIN_ID);
        bin.setTenantId(TenantContext.DEFAULT_TENANT);
        bin.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        bin.setWarehouseId(warehouseId);
        bin.setStorageLocationId(storageLocationId);
        bin.setCode("BIN-1");
        bin.setName("Bin 1");
        bin.setStatus("ACTIVE");
        bin.setBinCode("BIN-1");
        if (deleted) {
            bin.softDelete();
        }
        return bin;
    }

    private StorageLocation location(UUID id, UUID warehouseId) {
        StorageLocation location = new StorageLocation();
        location.setId(id);
        location.setTenantId(TenantContext.DEFAULT_TENANT);
        location.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        location.setWarehouseId(warehouseId);
        return location;
    }

    private StorageBinCreateRequest createRequest(UUID storageLocationId, UUID warehouseId) {
        StorageBinCreateRequest request = new StorageBinCreateRequest();
        request.setCode("BIN-1");
        request.setName("Bin 1");
        request.setStatus("ACTIVE");
        request.setBinCode("BIN-1");
        request.setStorageLocationId(storageLocationId);
        request.setWarehouseId(warehouseId);
        return request;
    }

    private StorageBinUpdateRequest updateRequest(UUID storageLocationId, UUID warehouseId) {
        StorageBinUpdateRequest request = new StorageBinUpdateRequest();
        request.setCode("BIN-1");
        request.setName("Bin 1");
        request.setStatus("ACTIVE");
        request.setBinCode("BIN-1");
        request.setStorageLocationId(storageLocationId);
        request.setWarehouseId(warehouseId);
        return request;
    }
}
