package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileSignatureRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileWarehouseOperationDto;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileWarehouseRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineOperationRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineSyncRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineSyncResponse;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineSyncResult;
import com.enterprise.mm.payload.WmsDtos.PutawayRecommendationRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MobileWarehouseOperationType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.TsdOfflineOperationStatus;
import com.enterprise.mm.entity.MobileWarehouseOperation;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.TsdOfflineOperation;
import com.enterprise.mm.repository.MobileWarehouseOperationRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.TsdOfflineOperationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MobileWarehouseService {
    private final MobileWarehouseOperationRepository repository;
    private final StockBatchRepository stockBatchRepository;
    private final TsdOfflineOperationRepository offlineOperationRepository;
    private final WarehouseIdentificationService identificationService;
    private final StockMovementService stockMovementService;
    private final PutawayRuleService putawayRuleService;
    private final EImzoVerificationService eImzoVerificationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional
    public MobileWarehouseOperationDto receive(MobileWarehouseRequest request) {
        return execute(MobileWarehouseOperationType.RECEIVE, MovementType.RECEIPT, request);
    }
    @Transactional
    public MobileWarehouseOperationDto putAway(MobileWarehouseRequest request) {
        return execute(MobileWarehouseOperationType.PUT_AWAY, MovementType.TRANSFER, request);
    }
    @Transactional
    public MobileWarehouseOperationDto pick(MobileWarehouseRequest request) {
        return execute(MobileWarehouseOperationType.PICK, MovementType.ISSUE, request);
    }
    @Transactional
    public MobileWarehouseOperationDto ship(MobileWarehouseRequest request) {
        return execute(MobileWarehouseOperationType.SHIP, MovementType.WRITE_OFF, request);
    }
    @Transactional
    public OfflineSyncResponse syncOffline(OfflineSyncRequest request) {
        if (request == null || request.deviceId() == null || request.deviceId().isBlank()) {
            throw new BusinessException("Device ID is required for offline sync");
        }
        List<OfflineOperationRequest> operations = request.operations() == null ? List.of() : request.operations();
        List<OfflineSyncResult> results = new ArrayList<>();
        int synced = 0;
        int failed = 0;
        for (OfflineOperationRequest operation : operations) {
            OfflineSyncResult result = syncOfflineOperation(request.deviceId().trim(), operation);
            results.add(result);
            if (TsdOfflineOperationStatus.SYNCED.name().equals(result.status()) || result.duplicate()) {
                synced++;
            } else {
                failed++;
            }
        }
        return new OfflineSyncResponse(request.deviceId().trim(), operations.size(), synced, failed, results);
    }
    @Transactional
    public MobileWarehouseOperationDto sign(UUID id, MobileSignatureRequest request) {
        MobileWarehouseOperation operation = repository.findByIdAndIsDeletedFalse(id)
            .filter(item -> !item.isDeleted())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Mobile operation not found: " + id));
        if (request == null || request.signaturePayload() == null || request.signaturePayload().isBlank()) {
            throw new BusinessException("Signature payload is required");
        }
        eImzoVerificationService.verify(operation, request);
        operation.setSigned(true);
        operation.setSignaturePayload(request.signaturePayload());
        operation.setSignedAt(LocalDateTime.now());
        operation.setStatus("SIGNED");
        MobileWarehouseOperation saved = repository.save(operation);
        auditService.log("MobileWarehouseOperation", saved.getId(), "E_IMZO_SIGN", saved.getOperationType().name(), null, null);
        return dto(saved);
    }
    private MobileWarehouseOperationDto execute(MobileWarehouseOperationType operationType, MovementType movementType, MobileWarehouseRequest request) {
        if (request == null) {
            throw new BusinessException("Mobile warehouse request is required");
        }
        UUID materialId = resolveMaterialId(request);
        UUID stockBatchId = resolveBatchId(request);
        BigDecimal quantity = nvl(request.quantity());
        if (quantity.signum() <= 0) {
            throw new BusinessException("Quantity must be positive");
        }
        UUID targetStorageLocationId = request.targetStorageLocationId();
        UUID targetWarehouseId = request.targetWarehouseId() == null ? request.warehouseId() : request.targetWarehouseId();
        if (MobileWarehouseOperationType.PUT_AWAY.equals(operationType) && targetStorageLocationId == null) {
            targetStorageLocationId = putawayRuleService.recommend(new PutawayRecommendationRequest(
                materialId,
                required(targetWarehouseId, "Target warehouse is required for putaway recommendation"),
                quantity,
                null,
                null,
                null,
                null,
                false
            )).storageLocationId();
        }
        StockMovement movement = new StockMovement();
        movement.setCode("MOB-" + operationType.name() + "-" + UUID.randomUUID().toString().substring(0, 8));
        movement.setName(operationType.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        movement.setMaterialId(materialId);
        movement.setStockBatchId(stockBatchId);
        movement.setWarehouseId(required(request.warehouseId(), "Warehouse is required"));
        movement.setTargetWarehouseId(targetWarehouseId);
        movement.setStorageLocationId(request.storageLocationId());
        movement.setTargetStorageLocationId(targetStorageLocationId);
        movement.setMovementType(movementType);
        movement.setQuantity(quantity);
        movement.setUnitCost(nvl(request.unitCost()));
        movement.setReferenceType("MOBILE_" + operationType.name());
        var posted = stockMovementService.createAndPost(movement);
        MobileWarehouseOperation operation = new MobileWarehouseOperation();
        operation.setCode(movement.getCode());
        operation.setName(movement.getName());
        operation.setStatus("COMPLETED");
        operation.setOperationType(operationType);
        operation.setMaterialId(materialId);
        operation.setWarehouseId(request.warehouseId());
        operation.setTargetWarehouseId(targetWarehouseId);
        operation.setStorageLocationId(request.storageLocationId());
        operation.setTargetStorageLocationId(targetStorageLocationId);
        operation.setQuantity(quantity);
        operation.setUnitCost(nvl(request.unitCost()));
        operation.setStockMovementId(posted.getId());
        operation.setBarcodeOrRfid(request.barcodeOrRfid());
        MobileWarehouseOperation saved = repository.save(operation);
        auditService.log("MobileWarehouseOperation", saved.getId(), operationType.name(), posted.getId().toString(), null, null);
        return dto(saved);
    }
    private OfflineSyncResult syncOfflineOperation(String deviceId, OfflineOperationRequest request) {
        if (request == null || request.clientOperationId() == null || request.clientOperationId().isBlank()) {
            return new OfflineSyncResult(null, null, null, TsdOfflineOperationStatus.FAILED.name(), null, null, "Client operation ID is required", false);
        }
        String clientOperationId = request.clientOperationId().trim();
        TsdOfflineOperation queueItem = offlineOperationRepository
            .findByTenantIdAndCompanyIdAndDeviceIdAndClientOperationIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                deviceId,
                clientOperationId
            )
            .orElseGet(TsdOfflineOperation::new);
        if (queueItem.getId() != null && TsdOfflineOperationStatus.SYNCED.name().equals(queueItem.getStatus())) {
            return new OfflineSyncResult(
                queueItem.getId(),
                clientOperationId,
                queueItem.getOperationType(),
                TsdOfflineOperationStatus.SYNCED.name(),
                queueItem.getServerOperationId(),
                queueItem.getStockMovementId(),
                "Duplicate ignored: operation already synced",
                true
            );
        }
        queueItem.setDeviceId(deviceId);
        queueItem.setClientOperationId(clientOperationId);
        queueItem.setOperationType(normalizeOperationType(request.operationType()));
        queueItem.setName("Mobile warehouse offline operation");
        queueItem.setCode("TSD-" + clientOperationId.substring(0, Math.min(clientOperationId.length(), 32)));
        queueItem.setStatus(TsdOfflineOperationStatus.QUEUED.name());
        queueItem.setPayloadJson(toJson(request.payload()));
        queueItem.setCreatedOfflineAt(request.createdOfflineAt() == null ? LocalDateTime.now() : request.createdOfflineAt());
        queueItem.setAttemptCount(queueItem.getAttemptCount() + 1);
        try {
            MobileWarehouseOperationDto operation = routeOffline(queueItem.getOperationType(), request.payload());
            queueItem.setServerOperationId(operation.id());
            queueItem.setStockMovementId(operation.stockMovementId());
            queueItem.setStatus(TsdOfflineOperationStatus.SYNCED.name());
            queueItem.setSyncedAt(LocalDateTime.now());
            queueItem.setLastError(null);
            TsdOfflineOperation saved = offlineOperationRepository.save(queueItem);
            auditService.log("TsdOfflineOperation", saved.getId(), "SYNCED", operation.id().toString(), null, null);
            return new OfflineSyncResult(saved.getId(), clientOperationId, saved.getOperationType(), saved.getStatus(), operation.id(), operation.stockMovementId(), "Synced", false);
        } catch (RuntimeException ex) {
            queueItem.setStatus(TsdOfflineOperationStatus.FAILED.name());
            queueItem.setLastError(truncate(ex.getMessage()));
            TsdOfflineOperation saved = offlineOperationRepository.save(queueItem);
            auditService.log("TsdOfflineOperation", saved.getId(), "FAILED", saved.getLastError(), null, null);
            return new OfflineSyncResult(saved.getId(), clientOperationId, saved.getOperationType(), saved.getStatus(), null, null, saved.getLastError(), false);
        }
    }
    private MobileWarehouseOperationDto routeOffline(MobileWarehouseOperationType operationType, MobileWarehouseRequest payload) {
        return switch (operationType) {
            case RECEIVE -> execute(MobileWarehouseOperationType.RECEIVE, MovementType.RECEIPT, payload);
            case PUT_AWAY -> execute(MobileWarehouseOperationType.PUT_AWAY, MovementType.TRANSFER, payload);
            case PICK -> execute(MobileWarehouseOperationType.PICK, MovementType.ISSUE, payload);
            case SHIP -> execute(MobileWarehouseOperationType.SHIP, MovementType.WRITE_OFF, payload);
        };
    }
    private MobileWarehouseOperationType normalizeOperationType(MobileWarehouseOperationType value) {
        if (value == null) {
            throw new BusinessException("Operation type is required");
        }
        return value;
    }
    private String toJson(MobileWarehouseRequest payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize offline operation payload");
        }
    }
    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Operation failed";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
    private UUID resolveMaterialId(MobileWarehouseRequest request) {
        if (request.materialId() != null) {
            return request.materialId();
        }
        if (request.stockBatchId() != null) {
            return batch(request.stockBatchId()).getMaterialId();
        }
        if (request.barcodeOrRfid() != null && !request.barcodeOrRfid().isBlank()) {
            var found = identificationService.lookup(request.barcodeOrRfid());
            if ("MATERIAL".equals(found.entityType())) {
                return found.entityId();
            }
            if ("STOCK_BATCH".equals(found.entityType())) {
                return batch(found.entityId()).getMaterialId();
            }
        }
        throw new BusinessException("Material or material identifier is required");
    }
    private UUID resolveBatchId(MobileWarehouseRequest request) {
        if (request.stockBatchId() != null) {
            return request.stockBatchId();
        }
        if (request.barcodeOrRfid() != null && !request.barcodeOrRfid().isBlank()) {
            var found = identificationService.lookup(request.barcodeOrRfid());
            if ("STOCK_BATCH".equals(found.entityType())) {
                return found.entityId();
            }
        }
        return null;
    }
    private StockBatch batch(UUID id) {
        return stockBatchRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> !item.isDeleted())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Stock batch not found: " + id));
    }
    private UUID required(UUID value, String message) {
        if (value == null) {
            throw new BusinessException(message);
        }
        return value;
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private MobileWarehouseOperationDto dto(MobileWarehouseOperation operation) {
        return new MobileWarehouseOperationDto(
            operation.getId(),
            operation.getOperationType(),
            operation.getStatus(),
            operation.getMaterialId(),
            operation.getWarehouseId(),
            operation.getTargetWarehouseId(),
            operation.getStorageLocationId(),
            operation.getTargetStorageLocationId(),
            operation.getQuantity(),
            operation.getUnitCost(),
            operation.getStockMovementId(),
            operation.getBarcodeOrRfid(),
            operation.isSigned(),
            operation.getSignedAt()
        );
    }
}
