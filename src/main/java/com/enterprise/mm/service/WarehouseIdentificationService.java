package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.payload.WarehouseIdentificationDtos.IdentificationLookupDto;
import com.enterprise.mm.payload.WarehouseIdentificationDtos.WarehouseInventoryBatchDto;
import com.enterprise.mm.payload.WarehouseIdentificationDtos.WarehouseInventoryLookupDto;
import com.enterprise.mm.payload.WarehouseIdentificationDtos.WarehouseInventoryMaterialDto;
import com.enterprise.mm.payload.WarehouseIdentificationDtos.WarehouseInventoryStorageLocationDto;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.RfidTagRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WarehouseIdentificationService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final MaterialRepository materialRepository;
    private final FixedAssetRepository fixedAssetRepository;
    private final StockBatchRepository stockBatchRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final WarehouseRepository warehouseRepository;
    private final RfidTagRepository rfidTagRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public IdentificationLookupDto lookup(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Identifier is required");
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return materialRepository.findFirstByTenantIdAndBarcodeAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> material(item, "BARCODE", value))
            .or(() -> materialRepository.findFirstByTenantIdAndQrCodeAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> material(item, "QR", value)))
            .or(() -> materialRepository.findFirstByTenantIdAndRfidTagAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> material(item, "RFID", value)))
            .or(() -> stockBatchRepository.findFirstByTenantIdAndBarcodeAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> batch(item, "BARCODE", value)))
            .or(() -> stockBatchRepository.findFirstByTenantIdAndQrCodeAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> batch(item, "QR", value)))
            .or(() -> stockBatchRepository.findFirstByTenantIdAndRfidTagAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> batch(item, "RFID", value)))
            .or(() -> storageLocationRepository.findFirstByTenantIdAndBarcodeAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> location(item, "BARCODE", value)))
            .or(() -> storageLocationRepository.findFirstByTenantIdAndQrCodeAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> location(item, "QR", value)))
            .or(() -> storageLocationRepository.findFirstByTenantIdAndRfidTagAndIsDeletedFalse(tenantId, value).filter(item -> companyId.equals(item.getCompanyId())).map(item -> location(item, "RFID", value)))
            .or(() -> fixedAssetRepository.findByTenantIdAndCompanyIdAndInventoryNumberAndIsDeletedFalse(tenantId, companyId, value)
                .filter(this::sameScope)
                .map(item -> asset(item, value)))
            .or(() -> rfidTagRepository.findFirstByTenantIdAndCompanyIdAndTagValueAndIsDeletedFalse(tenantId, companyId, value).map(item -> new IdentificationLookupDto(item.getEntityType(), item.getEntityId(), item.getCode(), item.getName(), "RFID", value)))
            .orElseThrow(() -> new NotFoundException("Identifier not found: " + value));
    }

    @Transactional(readOnly = true)
    public WarehouseInventoryLookupDto getWarehouseInventory(UUID warehouseId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        Warehouse warehouse = warehouseRepository.findByIdAndIsDeletedFalse(warehouseId)
            .filter(this::sameScope)
            .orElseThrow(() -> new NotFoundException("Warehouse not found: " + warehouseId));

        List<StorageLocation> storageLocations = storageLocationRepository
            .findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, companyId, warehouseId);
        List<StockBatch> batches = stockBatchRepository
            .findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalseOrderByReceivedDateAscCreatedAtAsc(tenantId, companyId, warehouseId);

        Map<UUID, WarehouseInventoryMaterialDto> materials = new LinkedHashMap<>();
        for (StockBatch batch : batches) {
            Material material = batch.getMaterial();
            if (material == null || material.isDeleted() || !sameScope(material)) {
                continue;
            }
            materials.putIfAbsent(material.getId(), new WarehouseInventoryMaterialDto(
                material.getId(),
                material.getCode(),
                material.getName(),
                material.getStatus(),
                material.getMaterialGroupId(),
                material.getUnitOfMeasureId(),
                material.getBarcode(),
                material.getQrCode(),
                material.getRfidTag()
            ));
        }

        List<WarehouseInventoryStorageLocationDto> locationDtos = storageLocations.stream()
            .map(location -> new WarehouseInventoryStorageLocationDto(
                location.getId(),
                location.getCode(),
                location.getName(),
                location.getStatus(),
                location.getZoneCode(),
                location.getBinCode(),
                location.getBarcode(),
                location.getQrCode(),
                location.getRfidTag()
            ))
            .toList();

        List<WarehouseInventoryBatchDto> batchDtos = batches.stream()
            .filter(this::sameScope)
            .map(batch -> {
                Material material = batch.getMaterial();
                return new WarehouseInventoryBatchDto(
                    batch.getId(),
                    batch.getMaterialId(),
                    material == null ? null : material.getCode(),
                    material == null ? null : material.getName(),
                    batch.getCode(),
                    batch.getName(),
                    batch.getStatus(),
                    batch.getBatchNumber(),
                    batch.getSerialNumber(),
                    batch.getQuantity(),
                    batch.getUnitCost(),
                    batch.getReceivedDate(),
                    batch.getExpirationDate(),
                    batch.getBarcode(),
                    batch.getQrCode(),
                    batch.getRfidTag()
                );
            })
            .sorted(Comparator.comparing(WarehouseInventoryBatchDto::receivedDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WarehouseInventoryBatchDto::batchNumber, Comparator.nullsLast(String::compareTo))
                .thenComparing(WarehouseInventoryBatchDto::name, Comparator.nullsLast(String::compareTo)))
            .toList();

        return new WarehouseInventoryLookupDto(
            warehouse.getId(),
            warehouse.getCode(),
            warehouse.getName(),
            List.copyOf(materials.values()),
            locationDtos,
            batchDtos
        );
    }

    @Transactional
    public IdentificationLookupDto generate(String entityType, UUID entityId, String identifierType) {
        String normalizedEntity = entityType.toUpperCase(Locale.ROOT);
        String normalizedType = identifierType == null || identifierType.isBlank() ? "BARCODE" : identifierType.toUpperCase(Locale.ROOT);
        String value = normalizedType + "-" + normalizedEntity + "-" + entityId.toString().substring(0, 8);
        IdentificationLookupDto result = switch (normalizedEntity) {
            case "MATERIAL" -> {
                Material material = materialRepository.findByIdAndIsDeletedFalse(entityId).filter(this::sameScope).orElseThrow(() -> new NotFoundException("Material not found: " + entityId));
                guardOpeningImport(material.getCompanyId());
                setMaterialIdentifier(material, normalizedType, value);
                yield material(materialRepository.save(material), normalizedType, value);
            }
            case "STOCK_BATCH" -> {
                StockBatch batch = stockBatchRepository.findByIdAndIsDeletedFalse(entityId).filter(this::sameScope).orElseThrow(() -> new NotFoundException("Batch not found: " + entityId));
                guardOpeningImport(batch.getCompanyId());
                setBatchIdentifier(batch, normalizedType, value);
                yield batch(stockBatchRepository.save(batch), normalizedType, value);
            }
            case "STORAGE_LOCATION" -> {
                StorageLocation location = storageLocationRepository.findByIdAndIsDeletedFalse(entityId).filter(this::sameScope).orElseThrow(() -> new NotFoundException("Storage location not found: " + entityId));
                guardOpeningImport(location.getCompanyId());
                setLocationIdentifier(location, normalizedType, value);
                yield location(storageLocationRepository.save(location), normalizedType, value);
            }
            default -> throw new BusinessException("Unsupported entity type for identifier generation: " + entityType);
        };
        auditService.log(normalizedEntity, entityId, "GENERATE_IDENTIFIER", normalizedType + ":" + value, null, null);
        return result;
    }
    private void setMaterialIdentifier(Material material, String type, String value) {
        switch (type) {
            case "BARCODE" -> material.setBarcode(value);
            case "QR" -> material.setQrCode(value);
            case "RFID" -> material.setRfidTag(value);
            default -> throw new BusinessException("Unsupported identifier type: " + type);
        }
    }
    private void setBatchIdentifier(StockBatch batch, String type, String value) {
        switch (type) {
            case "BARCODE" -> batch.setBarcode(value);
            case "QR" -> batch.setQrCode(value);
            case "RFID" -> batch.setRfidTag(value);
            default -> throw new BusinessException("Unsupported identifier type: " + type);
        }
    }
    private void setLocationIdentifier(StorageLocation location, String type, String value) {
        switch (type) {
            case "BARCODE" -> location.setBarcode(value);
            case "QR" -> location.setQrCode(value);
            case "RFID" -> location.setRfidTag(value);
            default -> throw new BusinessException("Unsupported identifier type: " + type);
        }
    }
    private boolean sameScope(BusinessEntity entity) {
        return !entity.isDeleted() && TenantContext.currentTenant().equals(entity.getTenantId()) && com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId());
    }
    private IdentificationLookupDto material(Material item, String type, String value) {
        return new IdentificationLookupDto("MATERIAL", item.getId(), item.getCode(), item.getName(), type, value);
    }
    private IdentificationLookupDto batch(StockBatch item, String type, String value) {
        return new IdentificationLookupDto("STOCK_BATCH", item.getId(), item.getCode(), item.getName(), type, value);
    }
    private IdentificationLookupDto location(StorageLocation item, String type, String value) {
        return new IdentificationLookupDto("STORAGE_LOCATION", item.getId(), item.getCode(), item.getName(), type, value);
    }

    private IdentificationLookupDto asset(FixedAsset item, String value) {
        return new IdentificationLookupDto("FIXED_ASSET", item.getId(), item.getAssetCode(), item.getName(), "INVENTORY_NUMBER", value);
    }
}

