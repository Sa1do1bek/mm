package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.CrossDockingTaskResponse;
import com.enterprise.mm.payload.CrossDockingTaskUpdateRequest;
import com.enterprise.mm.payload.CrossDockingTaskCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.WmsDtos.CrossDockingActionRequest;
import com.enterprise.mm.payload.CrossDockingTaskSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.CrossDockingTaskMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.CrossDockingTaskStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.entity.CrossDockingTask;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.repository.CrossDockingTaskRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CrossDockingTaskService {
    private final CrossDockingTaskRepository repository;
    private final CrossDockingTaskMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "CrossDockingTask";
    private final AuditService auditService;
    private final StockMovementService stockMovementService;
    private final RoleDataScopeService roleDataScopeService;
    protected void beforeCreate(CrossDockingTask entity, CrossDockingTaskCreateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        if (entity.getMaterialId() == null) {
            throw new BusinessException("Material is required for cross-docking task");
        }
        if (entity.getWarehouseId() == null) {
            throw new BusinessException("Warehouse is required for cross-docking task");
        }
        entity.setQuantity(nvl(entity.getQuantity()));
        if (entity.getQuantity().signum() <= 0) {
            throw new BusinessException("Cross-docking quantity must be positive");
        }
        entity.setUnitCost(nvl(entity.getUnitCost()));
        if (entity.getTaskNumber() == null || entity.getTaskNumber().isBlank()) {
            entity.setTaskNumber("XD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        entity.setCode(entity.getTaskNumber());
        entity.setStatus(CrossDockingTaskStatus.PLANNED.name());
    }
    @Transactional
    public CrossDockingTaskResponse receiveInbound(UUID id, CrossDockingActionRequest request) {
        CrossDockingTask task = findEntity(id);
        if (!CrossDockingTaskStatus.PLANNED.name().equals(task.getStatus())) {
            throw new BusinessException("Only PLANNED cross-docking task can receive inbound goods");
        }
        if (request != null && request.stockMovementId() != null) {
            task.setInboundStockMovementId(request.stockMovementId());
        }
        if (request != null && request.stockBatchId() != null) {
            task.setStockBatchId(request.stockBatchId());
        }
        if (request != null && request.storageLocationId() != null) {
            task.setStagingLocationId(request.storageLocationId());
        }
        task.setStartedAt(LocalDateTime.now());
        task.setStatus(CrossDockingTaskStatus.INBOUND_RECEIVED.name());
        CrossDockingTask saved = repository.save(task);
        auditService.log("CrossDockingTask", id, "INBOUND_RECEIVED", saved.getTaskNumber(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CrossDockingTaskResponse stage(UUID id, CrossDockingActionRequest request) {
        CrossDockingTask task = findEntity(id);
        requireStatus(task, CrossDockingTaskStatus.INBOUND_RECEIVED, CrossDockingTaskStatus.PLANNED);
        if (request == null || request.storageLocationId() == null) {
            throw new BusinessException("Staging location is required");
        }
        task.setStagingLocationId(request.storageLocationId());
        task.setStatus(CrossDockingTaskStatus.STAGED.name());
        CrossDockingTask saved = repository.save(task);
        auditService.log("CrossDockingTask", id, "STAGED", saved.getStagingLocationId().toString(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CrossDockingTaskResponse allocateOutbound(UUID id, CrossDockingActionRequest request) {
        CrossDockingTask task = findEntity(id);
        requireStatus(task, CrossDockingTaskStatus.INBOUND_RECEIVED, CrossDockingTaskStatus.STAGED);
        if (request != null) {
            task.setOutboundReferenceType(request.outboundReferenceType());
            task.setOutboundReferenceId(request.outboundReferenceId());
            if (request.destinationWarehouseId() != null) {
                task.setDestinationWarehouseId(request.destinationWarehouseId());
            }
            if (request.destinationStorageLocationId() != null) {
                task.setDestinationStorageLocationId(request.destinationStorageLocationId());
            }
            if (request.stockBatchId() != null) {
                task.setStockBatchId(request.stockBatchId());
            }
            if (request.plannedShipAt() != null) {
                task.setPlannedShipAt(request.plannedShipAt());
            }
        }
        task.setStatus(CrossDockingTaskStatus.OUTBOUND_ALLOCATED.name());
        CrossDockingTask saved = repository.save(task);
        auditService.log("CrossDockingTask", id, "OUTBOUND_ALLOCATED", saved.getTaskNumber(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CrossDockingTaskResponse ship(UUID id, CrossDockingActionRequest request) {
        CrossDockingTask task = findEntity(id);
        requireStatus(task, CrossDockingTaskStatus.INBOUND_RECEIVED, CrossDockingTaskStatus.STAGED, CrossDockingTaskStatus.OUTBOUND_ALLOCATED);
        BigDecimal quantity = request != null && request.quantity() != null ? request.quantity() : task.getQuantity();
        if (quantity.signum() <= 0 || quantity.compareTo(task.getQuantity()) > 0) {
            throw new BusinessException("Shipping quantity must be positive and not exceed task quantity");
        }
        if (request != null) {
            if (request.destinationWarehouseId() != null) {
                task.setDestinationWarehouseId(request.destinationWarehouseId());
            }
            if (request.destinationStorageLocationId() != null) {
                task.setDestinationStorageLocationId(request.destinationStorageLocationId());
            }
            if (request.unitCost() != null) {
                task.setUnitCost(request.unitCost());
            }
            if (request.stockBatchId() != null) {
                task.setStockBatchId(request.stockBatchId());
            }
        }
        StockMovement movement = new StockMovement();
        movement.setCode("XD-SHIP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        movement.setName("Cross-docking shipment " + task.getTaskNumber());
        movement.setMaterialId(task.getMaterialId());
        movement.setStockBatchId(task.getStockBatchId());
        movement.setWarehouseId(task.getWarehouseId());
        movement.setStorageLocationId(task.getStagingLocationId());
        movement.setQuantity(quantity);
        movement.setUnitCost(nvl(task.getUnitCost()));
        movement.setReferenceType("CROSS_DOCKING_TASK");
        movement.setReferenceId(task.getId());
        if (task.getDestinationWarehouseId() != null) {
            movement.setMovementType(MovementType.TRANSFER);
            movement.setTargetWarehouseId(task.getDestinationWarehouseId());
            movement.setTargetStorageLocationId(task.getDestinationStorageLocationId());
        } else {
            movement.setMovementType(MovementType.ISSUE);
        }
        StockMovementResponse posted = stockMovementService.createAndPost(movement);
        task.setOutboundStockMovementId(posted.getId());
        task.setStatus(CrossDockingTaskStatus.SHIPPED.name());
        task.setCompletedAt(LocalDateTime.now());
        CrossDockingTask saved = repository.save(task);
        auditService.log("CrossDockingTask", id, "SHIPPED", posted.getId().toString(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CrossDockingTaskResponse cancel(UUID id, CrossDockingActionRequest request) {
        CrossDockingTask task = findEntity(id);
        if (CrossDockingTaskStatus.SHIPPED.name().equals(task.getStatus())) {
            throw new BusinessException("Shipped cross-docking task cannot be cancelled");
        }
        task.setStatus(CrossDockingTaskStatus.CANCELLED.name());
        if (request != null && request.comment() != null && !request.comment().isBlank()) {
            task.setDescription(request.comment());
        }
        CrossDockingTask saved = repository.save(task);
        auditService.log("CrossDockingTask", id, "CANCELLED", saved.getTaskNumber(), null, null);
        return mapper.toResponse(saved);
    }
    private void requireStatus(CrossDockingTask task, CrossDockingTaskStatus... statuses) {
        for (CrossDockingTaskStatus status : statuses) {
            if (status.name().equals(task.getStatus())) {
                return;
            }
        }
        throw new BusinessException("Cross-docking task status does not allow this action");
    }
    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? "%%" : "%" + value.trim().toLowerCase() + "%";
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<CrossDockingTaskResponse> search(CrossDockingTaskSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            filter.supplierId(),
            filter.purchaseOrderId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public CrossDockingTaskResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public CrossDockingTaskResponse create(CrossDockingTaskCreateRequest dto) {
        CrossDockingTask entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        CrossDockingTask saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CrossDockingTaskResponse update(UUID id, CrossDockingTaskUpdateRequest dto) {
        CrossDockingTask entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        CrossDockingTask saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        CrossDockingTask entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public CrossDockingTaskResponse restore(UUID id) {
        CrossDockingTask entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        CrossDockingTask saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected CrossDockingTask findEntity(UUID id) {
        CrossDockingTask entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        return entity;
    }
    protected void beforeUpdate(CrossDockingTask entity, CrossDockingTaskUpdateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}

