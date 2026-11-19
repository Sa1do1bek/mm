package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.payload.MaterialUnitResponse;
import com.enterprise.mm.payload.MaterialUnitSearchRequest;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialUnitQueryService {
    private final MaterialUnitRepository materialUnitRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final StockBatchRepository stockBatchRepository;
    private final WarehouseRepository warehouseRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final StorageBinRepository storageBinRepository;

    @Transactional(readOnly = true)
    public Page<MaterialUnitResponse> search(MaterialUnitSearchRequest request, Pageable pageable) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<com.enterprise.mm.enums.MaterialUnitStatus> statuses =
            request.status() == null ? List.of() : request.status();
        Page<MaterialUnit> units = materialUnitRepository.search(
            tenantId,
            companyId,
            blankToNull(request.search()),
            request.materialId(),
            roleDataScopeService.availabilityWarehouseId(request.warehouseId()),
            request.storageLocationId(),
            request.storageBinId(),
            request.stockBatchId(),
            statuses,
            statuses.isEmpty(),
            blankToNull(request.sourceReferenceType()),
            request.sourceReferenceId(),
            request.sourceOperationType(),
            pageable
        );
        List<MaterialUnit> content = units.getContent();
        Map<UUID, StockBatch> batches = loadById(content.stream().map(MaterialUnit::getStockBatchId).toList(), stockBatchRepository::findAllById);
        Map<UUID, Warehouse> warehouses = loadById(content.stream().map(MaterialUnit::getWarehouseId).toList(), warehouseRepository::findAllById);
        Map<UUID, StorageLocation> locations = loadById(content.stream().map(MaterialUnit::getStorageLocationId).toList(), storageLocationRepository::findAllById);
        Map<UUID, StorageBin> bins = loadById(content.stream().map(MaterialUnit::getStorageBinId).toList(), storageBinRepository::findAllById);
        return units.map(unit -> toResponse(unit, batches, warehouses, locations, bins));
    }

    private MaterialUnitResponse toResponse(
        MaterialUnit unit,
        Map<UUID, StockBatch> batches,
        Map<UUID, Warehouse> warehouses,
        Map<UUID, StorageLocation> locations,
        Map<UUID, StorageBin> bins
    ) {
        StockBatch batch = unit.getStockBatchId() == null ? null : batches.get(unit.getStockBatchId());
        Warehouse warehouse = unit.getWarehouseId() == null ? null : warehouses.get(unit.getWarehouseId());
        StorageLocation location = unit.getStorageLocationId() == null ? null : locations.get(unit.getStorageLocationId());
        StorageBin bin = unit.getStorageBinId() == null ? null : bins.get(unit.getStorageBinId());
        return new MaterialUnitResponse(
            unit.getId(),
            unit.getMaterialId(),
            unit.getManufacturerSerialNumber(),
            unit.getInternalUnitNumber(),
            unit.getUnitStatus(),
            unit.getWarehouseId(),
            displayName(warehouse),
            unit.getStorageLocationId(),
            displayName(location),
            unit.getStorageBinId(),
            binName(bin),
            unit.getStockBatchId(),
            batchNumber(batch),
            unit.getReceivedAt()
        );
    }

    private static <T extends com.enterprise.mm.common.BusinessEntity> Map<UUID, T> loadById(
        Collection<UUID> ids,
        Function<Collection<UUID>, Iterable<T>> loader
    ) {
        List<UUID> presentIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (presentIds.isEmpty()) {
            return Map.of();
        }
        return toMap(loader.apply(presentIds));
    }

    private static <T extends com.enterprise.mm.common.BusinessEntity> Map<UUID, T> toMap(Iterable<T> values) {
        return StreamSupport.stream(values.spliterator(), false)
            .filter(entity -> !entity.isDeleted())
            .collect(Collectors.toMap(com.enterprise.mm.common.BusinessEntity::getId, Function.identity()));
    }

    private static String displayName(com.enterprise.mm.common.BusinessEntity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getName() != null && !entity.getName().isBlank()) {
            return entity.getName();
        }
        return entity.getCode();
    }

    private static String binName(StorageBin bin) {
        if (bin == null) {
            return null;
        }
        if (bin.getBinCode() != null && !bin.getBinCode().isBlank()) {
            return bin.getBinCode();
        }
        return displayName(bin);
    }

    private static String batchNumber(StockBatch batch) {
        if (batch == null) {
            return null;
        }
        if (batch.getBatchNumber() != null && !batch.getBatchNumber().isBlank()) {
            return batch.getBatchNumber();
        }
        if (batch.getManufacturerBatchNumber() != null && !batch.getManufacturerBatchNumber().isBlank()) {
            return batch.getManufacturerBatchNumber();
        }
        return displayName(batch);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
