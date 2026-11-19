package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.warehouse.production.ProductionOperationDtos.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Physical identity only. Stock and value remain exclusively owned by native movements. */
@Service
@RequiredArgsConstructor
public class ProductionUnitService {
    private final MaterialUnitRepository units;
    private final MaterialUnitEligibilityService eligibility;
    private final JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    void validateInput(Material material, Input input, Set<UUID> selected) {
        var ids = input.materialUnitIds() == null ? List.<UUID>of() : input.materialUnitIds();
        if (material.getSerialTrackingPolicy() != SerialTrackingPolicy.REQUIRED) {
            if (!ids.isEmpty()) throw new BadRequestException("Unit selection requires a serial-controlled material");
            return;
        }
        if (ids.size() != count(input.quantity())) throw new BadRequestException("Select one physical unit per input quantity");
        for (UUID id : ids) if (id == null || !selected.add(id))
            throw new BadRequestException("A physical component can be selected only once per operation");
    }

    void validateOutput(Material material, Output output, Set<String> selected) {
        var serials = output.serialNumbers() == null ? List.<String>of() : output.serialNumbers();
        if (material.getSerialTrackingPolicy() != SerialTrackingPolicy.REQUIRED) {
            if (!serials.isEmpty()) throw new BadRequestException("Serial registration requires a serial-controlled material");
            return;
        }
        if (serials.size() != count(output.quantity())) throw new BadRequestException("Enter one serial number per output unit");
        for (String serial : serials) {
            String normalized = normalize(serial);
            if (!selected.add(material.getId() + ":" + normalized)
                || units.existsByTenantIdAndCompanyIdAndMaterialIdAndNormalizedSerialNumberAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), material.getId(), normalized))
                throw new BadRequestException("Output serial number is already registered or repeated: " + serial);
        }
    }

    List<MaterialUnit> lockInput(Input input, StockBalance position) {
        if (input.materialUnitIds() == null || input.materialUnitIds().isEmpty()) return List.of();
        var locked = eligibility.lockAndValidate(input.materialId(), position.getWarehouseId(),
            position.getStorageLocationId(), position.getStorageBinId(), input.quantity(), input.materialUnitIds(), null);
        // An earlier query may have populated JPA's cache before waiting for a warehouse lock.
        for (var unit : locked) {
            em.refresh(unit);
            if (unit.getUnitStatus() != MaterialUnitStatus.IN_STOCK
                || !Objects.equals(input.materialId(), unit.getMaterialId())
                || !Objects.equals(position.getWarehouseId(), unit.getWarehouseId())
                || !Objects.equals(position.getStockBatchId(), unit.getStockBatchId())
                || !Objects.equals(position.getStorageLocationId(), unit.getStorageLocationId())
                || !Objects.equals(position.getStorageBinId(), unit.getStorageBinId()))
                throw new BadRequestException("Selected physical unit is unavailable or belongs to another stock position");
        }
        return locked;
    }

    void consume(List<MaterialUnit> selected, UUID operationId, UUID movementId) {
        change(selected, MaterialUnitStatus.CONSUMED, MaterialUnitEventType.PRODUCTION_CONSUMED,
            "PRODUCTION_OPERATION", operationId, movementId, MaterialUnitOperationType.PRODUCTION);
    }

    void register(Output output, StockMovementResponse movement, UUID operationId) {
        if (output.serialNumbers() == null) return;
        for (String serial : output.serialNumbers()) {
            MaterialUnit unit = new MaterialUnit();
            unit.setName("Produced unit " + serial.trim());
            unit.setCode("PU-" + UUID.randomUUID().toString().substring(0, 8));
            unit.setTenantId(TenantContext.currentTenant()); unit.setCompanyId(CompanyContext.currentCompany());
            unit.setMaterialId(output.materialId()); unit.setWarehouseId(movement.getWarehouseId());
            unit.setStockBatchId(movement.getStockBatchId()); unit.setProductionStockMovementId(movement.getId());
            unit.setManufacturerSerialNumber(serial.trim()); unit.setNormalizedSerialNumber(normalize(serial));
            unit.setReceivedAt(LocalDateTime.now()); unit.setUnitStatus(MaterialUnitStatus.IN_STOCK); unit.setStatus("IN_STOCK");
            unit = units.saveAndFlush(unit);
            change(List.of(unit), MaterialUnitStatus.IN_STOCK, MaterialUnitEventType.PRODUCTION_CREATED,
                "PRODUCTION_OPERATION", operationId, movement.getId(), MaterialUnitOperationType.PRODUCTION);
        }
        em.flush();
    }

    void reverse(Line line, UUID operationId, UUID warehouseId) {
        if (line.units().isEmpty()) return;
        boolean input = "INPUT".equals(line.lineType());
        var selected = units.findAllForUpdate(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            line.units().stream().map(Unit::id).toList());
        if (selected.size() != count(line.quantity())) throw new BadRequestException("Production unit evidence is incomplete");
        for (var unit : selected) {
            em.refresh(unit);
            if (unit.getUnitStatus() != (input ? MaterialUnitStatus.CONSUMED : MaterialUnitStatus.IN_STOCK)
                || !Objects.equals(unit.getStockBatchId(), line.stockBatchId())
                || !Objects.equals(unit.getMaterialId(), line.materialId()) || !Objects.equals(unit.getWarehouseId(), warehouseId))
                throw new BadRequestException("Production units have downstream activity; reverse that activity first");
            if (input) {
                Integer exact = jdbc.queryForObject("select count(*) from (select reference_type,reference_id,reference_line_id,to_status from material_unit_operations where tenant_id=? and company_id=? and material_unit_id=? and is_delete=false order by occurred_at desc,created_at desc,id desc limit 1) latest where reference_type='PRODUCTION_OPERATION' and reference_id=? and reference_line_id=? and to_status='CONSUMED'",
                    Integer.class, TenantContext.currentTenant(), CompanyContext.currentCompany(), unit.getId(), operationId, line.movementId());
                if (exact == null || exact != 1) throw new BadRequestException("Consumed component has later unit activity");
            }
        }
        change(selected, input ? MaterialUnitStatus.IN_STOCK : MaterialUnitStatus.DISPOSED,
            MaterialUnitEventType.PRODUCTION_REVERSED, "PRODUCTION_REVERSAL", operationId, line.movementId(), MaterialUnitOperationType.PRODUCTION);
        em.flush();
    }

    /** Serialized production lots use a full-lot decision; split output lines for separate decisions. */
    void quality(StockMovement movement, UUID inspectionId, boolean release, BigDecimal accepted, BigDecimal rejected) {
        if (movement == null || !"PRODUCTION_OPERATION".equals(movement.getReferenceType())) return;
        var ids = jdbc.query("select id from material_units where production_stock_movement_id=? and is_delete=false order by id",
            (rs, n) -> rs.getObject(1, UUID.class), movement.getId());
        if (ids.isEmpty()) return;
        if (release && accepted.signum() > 0 && rejected.signum() > 0)
            throw new BadRequestException("Serialized production inspection requires a full-lot decision; use separate output lines");
        var selected = units.findAllForUpdate(TenantContext.currentTenant(), CompanyContext.currentCompany(), ids);
        for (var unit : selected) {
            em.refresh(unit);
            if (unit.getUnitStatus() != (release ? MaterialUnitStatus.QUARANTINE : MaterialUnitStatus.IN_STOCK)
                || !Objects.equals(unit.getWarehouseId(), movement.getWarehouseId())
                || !Objects.equals(unit.getStockBatchId(), movement.getStockBatchId()))
                throw new BadRequestException("Production inspection physical units do not match quarantined stock");
        }
        if (selected.size() != count(movement.getQuantity())) throw new BadRequestException("Production unit evidence is incomplete");
        if (!release || accepted.signum() > 0) {
            change(selected, release ? MaterialUnitStatus.IN_STOCK : MaterialUnitStatus.QUARANTINE,
                release ? MaterialUnitEventType.QUALITY_RELEASED : MaterialUnitEventType.QUALITY_HELD,
                "PRODUCTION_QUALITY", inspectionId, movement.getId(), MaterialUnitOperationType.QUALITY);
        }
    }

    private void change(List<MaterialUnit> selected, MaterialUnitStatus status, MaterialUnitEventType event,
        String reference, UUID id, UUID lineId, MaterialUnitOperationType operationType) {
        for (var unit : selected) eligibility.transition(List.of(unit), status, event, operationType,
            reference, id, lineId, unit.getWarehouseId(), unit.getStorageLocationId(), unit.getStorageBinId(), null);
    }
    private static int count(BigDecimal quantity) {
        try { int count = quantity.intValueExact(); if (count > 0 && count <= 1000) return count; }
        catch (ArithmeticException ignored) { }
        throw new BadRequestException("Serialized production quantity must be a whole number between 1 and 1000 per line");
    }
    private static String normalize(String serial) {
        if (serial == null || serial.isBlank() || serial.trim().length() > 160)
            throw new BadRequestException("Serial number is required (up to 160 characters)");
        return serial.trim().toUpperCase(Locale.ROOT);
    }
}
