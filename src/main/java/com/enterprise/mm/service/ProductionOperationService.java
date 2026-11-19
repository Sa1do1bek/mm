package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.warehouse.production.ProductionOperationDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Native movements, cost layers and document genealogy share the same transaction. */
@Service
@RequiredArgsConstructor
public class ProductionOperationService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WarehouseRepository warehouses;
    private final StockBalanceRepository balances;
    private final MaterialCatalogGuard catalog;
    private final RoleDataScopeService scope;
    private final InventoryScopeLockService locks;
    private final IdempotencyService idempotency;
    private final StockMovementService movements;
    private final InventoryCostingService costing;
    private final AuditService audit;
    private final ProductionUnitService physicalUnits;
    private final QualityInspectionService quality;

    @Transactional
    public Operation create(Draft draft, String key) {
        checkWarehouse(draft == null ? null : draft.warehouseId());
        locks.lock(company(), List.of(draft.warehouseId()));
        return idempotency.executeRequired("PRODUCTION_CREATE", draft.warehouseId(), key, draft,
            Operation.class, () -> {
                validate(draft);
                if (jdbc.queryForObject("select count(*) from warehouse_production_operations where tenant_id=? and company_id=? and operation_number=?",
                    Integer.class, tenant(), company(), draft.operationNumber().trim()) != 0)
                    throw new ConflictException("Production document number already exists");
                UUID id = UUID.randomUUID();
                jdbc.update("insert into warehouse_production_operations(id,tenant_id,company_id,warehouse_id,operation_number,operation_type,status,operation_date,payload,additional_cost,created_by) values(?,?,?,?,?,?,'DRAFT',?,?,?,?)",
                    id, tenant(), company(), draft.warehouseId(), draft.operationNumber().trim(), draft.operationType().name(),
                    draft.operationDate(), json(draft), money(draft.additionalCost()), actor());
                audit.log("ProductionOperation", id, "CREATE", draft.operationNumber(), null, null);
                return get(id);
            });
    }

    @Transactional
    public Operation update(UUID id, long version, Draft draft) {
        Operation original = lock(id);
        requireVersion(original, version);
        requireStatus(original, "DRAFT");
        if (draft == null || !original.warehouseId().equals(draft.warehouseId())
            || !original.operationNumber().equals(draft.operationNumber()))
            throw new BadRequestException("Warehouse and document number cannot change");
        validate(draft);
        jdbc.update("update warehouse_production_operations set operation_type=?,operation_date=?,payload=?,additional_cost=?,version=version+1 where id=?",
            draft.operationType().name(), draft.operationDate(), json(draft), money(draft.additionalCost()), id);
        audit.log("ProductionOperation", id, "UPDATE", original.operationNumber(), null, null);
        return get(id);
    }

    @Transactional
    public Operation post(UUID id, long version, String key) {
        Operation original = lock(id);
        return idempotency.executeRequired("PRODUCTION_POST", id, key, new VersionCommand(version), Operation.class, () -> {
            requireVersion(original, version);
            requireStatus(original, "DRAFT");
            Draft draft = original.draft();
            validate(draft);
            // Match transfer lock order: scope, materials, cost layers, then physical balances.
            var materialIds = new TreeSet<UUID>();
            draft.inputs().forEach(line -> materialIds.add(line.materialId()));
            draft.outputs().forEach(line -> materialIds.add(line.materialId()));
            for (UUID materialId : materialIds) jdbc.query("select id from materials where id=? and tenant_id=? and company_id=? for update", rs -> {}, materialId, tenant(), company());
            // Another warehouse can register the same material's serial while we wait for its lock.
            Set<String> outputSerials = new HashSet<>();
            for (Output output : draft.outputs()) physicalUnits.validateOutput(
                catalog.requireActiveTmbMaterial(output.materialId(), "materialId"), output, outputSerials);
            costing.lockPickingSources(draft.warehouseId(), draft.inputs().stream().map(Input::materialId).toList());
            Map<UUID, StockBalance> positions = new HashMap<>();
            Map<UUID, BigDecimal> required = new HashMap<>();
            draft.inputs().forEach(line -> required.merge(line.stockPositionId(), line.quantity(), BigDecimal::add));
            for (UUID positionId : required.keySet().stream().sorted().toList()) {
                StockBalance position = balances.findByIdAndIsDeletedFalseForUpdate(positionId)
                    .filter(b -> tenant().equals(b.getTenantId()) && company().equals(b.getCompanyId())
                        && draft.warehouseId().equals(b.getWarehouseId()))
                    .orElseThrow(() -> new NotFoundException("Input stock position not found"));
                if (!"OWNED".equals(position.getStockCategory()) || position.getOwnerPartyId() != null
                    || position.getStockBatchId() == null || position.getAvailableQuantity().compareTo(required.get(positionId)) < 0)
                    throw new BadRequestException("Input stock is unavailable, reserved, or not company-owned");
                positions.put(positionId, position);
            }
            BigDecimal inputCost = BigDecimal.ZERO;
            int sequence = 0;
            for (Input input : draft.inputs()) {
                StockBalance position = positions.get(input.stockPositionId());
                if (!input.materialId().equals(position.getMaterialId()))
                    throw new BadRequestException("Selected stock position belongs to another material");
                StockMovement movement = movement(original, input.materialId(), input.quantity(), ++sequence, MovementType.ISSUE);
                movement.setStockBatchId(position.getStockBatchId());
                movement.setStorageLocationId(position.getStorageLocationId());
                movement.setStorageBinId(position.getStorageBinId());
                var selectedUnits = physicalUnits.lockInput(input, position);
                var posted = movements.postProductionMovement(movement);
                physicalUnits.consume(selectedUnits, id, posted.getId());
                inputCost = inputCost.add(posted.getTotalCost());
                saveLine(id, sequence, "INPUT", input.materialId(), input.quantity(), posted.getTotalCost(),
                    input.stockPositionId(), posted.getStockBatchId(), posted.getId());
            }
            BigDecimal total = inputCost.add(money(draft.additionalCost())).setScale(4, RoundingMode.UNNECESSARY);
            List<BigDecimal> allocations = allocate(total, draft.outputs().stream().map(Output::costWeight).toList());
            for (int i = 0; i < draft.outputs().size(); i++) {
                Output output = draft.outputs().get(i);
                StockMovement movement = movement(original, output.materialId(), output.quantity(), ++sequence, MovementType.RECEIPT);
                movement.setBatchNumber(output.batchNumber());
                movement.setUnitCost(allocations.get(i).divide(output.quantity(), 4, RoundingMode.HALF_UP));
                movement.setTotalCost(allocations.get(i));
                var posted = movements.postProductionMovement(movement);
                physicalUnits.register(output, posted, id);
                if (catalog.requireActiveTmbMaterial(output.materialId(), "materialId").isIncomingInspectionRequired()) {
                    var inspection = new com.enterprise.mm.payload.QualityInspectionCreateRequest();
                    inspection.setName("Production inspection · " + original.operationNumber());
                    inspection.setInspectionType("PRODUCTION");
                    inspection.setStockMovementId(posted.getId());
                    inspection.setReceivedQuantity(output.quantity());
                    inspection.setDueDate(LocalDate.now().plusDays(1));
                    quality.startQuarantine(quality.create(inspection).getId());
                }
                saveLine(id, sequence, outputType(output), output.materialId(), output.quantity(), allocations.get(i),
                    null, posted.getStockBatchId(), posted.getId());
            }
            jdbc.update("update warehouse_production_operations set status='POSTED',version=version+1,input_cost=?,output_cost=?,posted_by=?,posted_at=current_timestamp where id=?",
                inputCost, total, actor(), id);
            audit.log("ProductionOperation", id, "POST", original.operationNumber(), null, null);
            return get(id);
        });
    }

    @Transactional
    public Operation reverse(UUID id, ReversalCommand command, String key) {
        Operation original = lock(id);
        return idempotency.executeRequired("PRODUCTION_REVERSE", id, key, command, Operation.class, () -> {
            if (command == null || command.version() == null || command.reason() == null || command.reason().isBlank() || command.reason().length() > 1000)
                throw new BadRequestException("A reversal reason is required (up to 1000 characters)");
            requireVersion(original, command.version());
            requireStatus(original, "POSTED");
            // Outputs first: native exact reversal refuses stock/cost layers already consumed downstream.
            List<Line> reverseOrder = new ArrayList<>(original.lines());
            Collections.reverse(reverseOrder);
            for (Line line : reverseOrder) {
                quality.assertProductionReversalAllowed(line.movementId());
                physicalUnits.reverse(line, id, original.warehouseId());
                var reversal = movements.reverseProductionMovement(line.movementId(), command.reason());
                jdbc.update("update warehouse_production_operation_lines set reversal_movement_id=? where id=?", reversal.getId(), line.id());
            }
            jdbc.update("update warehouse_production_operations set status='REVERSED',version=version+1,reversal_reason=?,reversed_by=?,reversed_at=current_timestamp where id=?",
                command.reason().trim(), actor(), id);
            audit.log("ProductionOperation", id, "REVERSE", command.reason(), null, null);
            return get(id);
        });
    }

    @Transactional
    public Operation cancel(UUID id, long version) {
        Operation original = lock(id);
        requireVersion(original, version);
        requireStatus(original, "DRAFT");
        jdbc.update("update warehouse_production_operations set status='CANCELLED',version=version+1 where id=?", id);
        audit.log("ProductionOperation", id, "CANCEL", original.operationNumber(), null, null);
        return get(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<Operation> list(UUID warehouseId, int page, int size) {
        checkWarehouse(warehouseId);
        int limit = Math.max(1, Math.min(size, 50));
        if (page < 0) throw new BadRequestException("Page cannot be negative");
        Long count = jdbc.queryForObject("select count(*) from warehouse_production_operations where tenant_id=? and company_id=? and warehouse_id=?",
            Long.class, tenant(), company(), warehouseId);
        var ids = jdbc.query("select id from warehouse_production_operations where tenant_id=? and company_id=? and warehouse_id=? order by created_at desc,id limit ? offset ?",
            (rs, row) -> rs.getObject(1, UUID.class), tenant(), company(), warehouseId, limit, (long) page * limit);
        return PageResponse.from(new PageImpl<>(ids.stream().map(this::get).toList(), PageRequest.of(page, limit), count == null ? 0 : count));
    }

    @Transactional(readOnly = true)
    public Operation get(UUID id) {
        var found = jdbc.query("select * from warehouse_production_operations where id=? and tenant_id=? and company_id=?",
            (rs, row) -> operation(rs), id, tenant(), company());
        if (found.isEmpty()) throw new NotFoundException("Production document not found");
        Operation operation = found.getFirst();
        checkWarehouse(operation.warehouseId());
        return operation;
    }

    private Operation lock(UUID id) {
        Operation original = get(id);
        locks.lock(company(), List.of(original.warehouseId()));
        jdbc.query("select id from warehouse_production_operations where id=? and tenant_id=? and company_id=? for update",
            rs -> { }, id, tenant(), company());
        return get(id);
    }

    private Operation operation(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String status = rs.getString("status");
        boolean canViewCost = writable() || financialReader();
        Map<UUID, List<Unit>> unitsByMovement = new HashMap<>();
        jdbc.query("select distinct o.reference_line_id,u.id,u.manufacturer_serial_number,u.unit_status from material_unit_operations o join material_units u on u.id=o.material_unit_id where o.tenant_id=? and o.company_id=? and o.reference_type='PRODUCTION_OPERATION' and o.reference_id=? and o.is_delete=false order by o.reference_line_id,u.id",
            r -> { unitsByMovement.computeIfAbsent(r.getObject("reference_line_id", UUID.class), ignored -> new ArrayList<>())
                .add(new Unit(r.getObject("id", UUID.class), r.getString("manufacturer_serial_number"), r.getString("unit_status"))); }, tenant(), company(), id);
        var lines = jdbc.query("select l.*,q.id as quality_inspection_id,q.status as quality_status from warehouse_production_operation_lines l left join quality_inspections q on q.stock_movement_id=l.movement_id and q.tenant_id=? and q.company_id=? and q.inspection_type='PRODUCTION' and q.is_delete=false where l.operation_id=? order by l.sequence_number", (r, n) ->
            new Line(r.getObject("id", UUID.class), r.getString("line_type"), r.getObject("material_id", UUID.class),
                r.getString("material_code"), r.getString("material_name"), r.getString("unit_code"), r.getBigDecimal("quantity"),
                canViewCost ? r.getBigDecimal("allocated_cost") : null, r.getObject("stock_position_id", UUID.class), r.getObject("stock_batch_id", UUID.class),
                r.getObject("movement_id", UUID.class), r.getObject("reversal_movement_id", UUID.class),
                unitsByMovement.getOrDefault(r.getObject("movement_id", UUID.class), List.of()), r.getObject("quality_inspection_id", UUID.class),
                r.getString("quality_status")), tenant(), company(), id);
        var unitLabels = unitDisplay.shortNames(tenant(), company(), lines.stream().map(Line::unitCode).toList());
        lines = lines.stream().map(line -> line.withUnitShortName(unitLabels.get(line.unitCode()))).toList();
        Draft draft = read(rs.getString("payload"));
        if (!canViewCost) draft = new Draft(draft.operationNumber(), draft.operationType(), draft.warehouseId(), draft.operationDate(),
            draft.reference(), draft.notes(), null, null, draft.inputs(), draft.outputs());
        return new Operation(id, rs.getString("operation_number"), OperationType.valueOf(rs.getString("operation_type")),
            rs.getObject("warehouse_id", UUID.class), status, rs.getLong("version"), rs.getDate("operation_date").toLocalDate(),
            draft, canViewCost ? rs.getBigDecimal("input_cost") : null, canViewCost ? rs.getBigDecimal("additional_cost") : null, canViewCost ? rs.getBigDecimal("output_cost") : null,
            rs.getString("created_by"), instant(rs, "created_at"), rs.getString("posted_by"), instant(rs, "posted_at"),
            rs.getString("reversal_reason"), lines, writable() ? switch (status) {
                case "DRAFT" -> List.of("EDIT", "POST", "CANCEL"); case "POSTED" -> List.of("REVERSE"); default -> List.of();
            } : List.of(), canViewCost);
    }

    private void validate(Draft draft) {
        if (draft != null && !"ACTIVE".equals(checkWarehouse(draft.warehouseId()).getStatus()))
            throw new BadRequestException("Production requires an active warehouse");
        if (draft == null || draft.operationType() == null || draft.operationDate() == null
            || draft.operationNumber() == null || draft.operationNumber().isBlank() || draft.operationNumber().length() > 120
            || !draft.operationNumber().equals(draft.operationNumber().trim()))
            throw new BadRequestException("Operation type, date and document number are required");
        if (draft.operationDate().isAfter(LocalDate.now())) throw new BadRequestException("Production date cannot be in the future");
        if (draft.inputs() == null || draft.inputs().isEmpty() || draft.outputs() == null || draft.outputs().isEmpty()
            || draft.inputs().size() > 100 || draft.outputs().size() > 100)
            throw new BadRequestException("Provide 1–100 input and output lines");
        if (money(draft.additionalCost()).signum() > 0
            && (draft.additionalCostReason() == null || draft.additionalCostReason().isBlank()))
            throw new BadRequestException("Explain additional production costs");
        Set<UUID> selectedUnits = new HashSet<>();
        Set<String> outputSerials = new HashSet<>();
        for (Input input : draft.inputs()) {
            if (input == null || input.stockPositionId() == null) throw new BadRequestException("Select each input stock position");
            physicalUnits.validateInput(material(input.materialId(), input.quantity()), input, selectedUnits);
            StockBalance position = balances.findByIdAndIsDeletedFalse(input.stockPositionId())
                .filter(b -> tenant().equals(b.getTenantId()) && company().equals(b.getCompanyId())
                    && draft.warehouseId().equals(b.getWarehouseId()) && input.materialId().equals(b.getMaterialId()))
                .orElseThrow(() -> new NotFoundException("Input stock position not found"));
            if (!"OWNED".equals(position.getStockCategory()) || position.getOwnerPartyId() != null || position.getStockBatchId() == null)
                throw new BadRequestException("Production inputs must be company-owned batch stock");
        }
        for (Output output : draft.outputs()) {
            if (output == null) throw new BadRequestException("Output is required");
            physicalUnits.validateOutput(material(output.materialId(), output.quantity()), output, outputSerials);
            if (output.batchNumber() != null && output.batchNumber().length() > 120)
                throw new BadRequestException("Batch number is too long");
            if (!Set.of("OUTPUT", "SCRAP", "BYPRODUCT").contains(outputType(output)))
                throw new BadRequestException("Invalid output line type");
        }
        allocate(BigDecimal.ZERO, draft.outputs().stream().map(Output::costWeight).toList());
    }

    private Material material(UUID id, BigDecimal quantity) {
        Material material = catalog.requireActiveTmbMaterial(id, "materialId");
        if (!company().equals(material.getCompanyId())) throw new NotFoundException("Material not found in current company");
        StockUomPolicy.quantity(material, quantity, false);
        if (quantity.precision() - quantity.scale() > 15) throw new BadRequestException("Quantity exceeds supported precision");
        return material;
    }


    /** Last positive weight receives the rounding remainder, preserving the exact total. */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        BigDecimal sum = BigDecimal.ZERO;
        int last = -1;
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal weight = weights.get(i);
            if (weight == null || weight.signum() < 0 || weight.stripTrailingZeros().scale() > 7 || weight.precision() > 22)
                throw new BadRequestException("Output cost weights must be nonnegative decimals");
            sum = sum.add(weight);
            if (weight.signum() > 0) last = i;
        }
        if (last < 0) throw new BadRequestException("At least one output cost weight must be positive");
        List<BigDecimal> result = new ArrayList<>();
        BigDecimal remaining = total.setScale(4, RoundingMode.UNNECESSARY);
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal value = i == last ? remaining : total.multiply(weights.get(i)).divide(sum, 4, RoundingMode.DOWN);
            result.add(value);
            remaining = remaining.subtract(value);
        }
        return result;
    }

    private void saveLine(UUID id, int sequence, String type, UUID materialId, BigDecimal quantity, BigDecimal cost,
        UUID position, UUID batch, UUID movementId) {
        Material m = catalog.requireActiveTmbMaterial(materialId, "materialId");
        jdbc.update("insert into warehouse_production_operation_lines(id,operation_id,sequence_number,line_type,material_id,material_code,material_name,unit_code,quantity,allocated_cost,stock_position_id,stock_batch_id,movement_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), id, sequence, type, materialId, m.getCode(), m.getName(), m.getUnitOfMeasure().getCode(),
            quantity, cost, position, batch, movementId);
    }

    private StockMovement movement(Operation operation, UUID material, BigDecimal quantity, int sequence, MovementType type) {
        StockMovement movement = new StockMovement();
        movement.setCompanyId(company()); movement.setTenantId(tenant());
        movement.setCode("PROD-" + operation.id() + "-" + sequence);
        movement.setStatus("DRAFT"); movement.setReferenceType("PRODUCTION_OPERATION"); movement.setReferenceId(operation.id());
        movement.setMaterialId(material); movement.setWarehouseId(operation.warehouseId());
        movement.setMovementType(type); movement.setQuantity(quantity); movement.setMovementDate(operation.operationDate());
        movement.setDescription(operation.operationNumber() + (operation.draft().notes() == null ? "" : ": " + operation.draft().notes()));
        return movement;
    }

    private Warehouse checkWarehouse(UUID id) {
        company();
        if (id == null) throw new BadRequestException("Select a warehouse");
        scope.assertWarehouseAccess(id);
        return warehouses.findByIdAndIsDeletedFalse(id)
            .filter(w -> tenant().equals(w.getTenantId()) && company().equals(w.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Warehouse not found"));
    }
    private static BigDecimal money(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 4 || value.precision() - value.scale() > 15)
            throw new BadRequestException("Cost must be nonnegative with at most four decimal places");
        return value;
    }
    private static String outputType(Output output) { return output.lineType() == null ? "OUTPUT" : output.lineType(); }
    private static void requireVersion(Operation operation, long version) {
        if (operation.version() != version) throw new ConflictException("Production document changed; reload before continuing");
    }
    private static void requireStatus(Operation operation, String status) {
        if (!operation.status().equals(status)) throw new ConflictException("Production document must be " + status);
    }
    static String tenant() { return TenantContext.currentTenant(); }
    static UUID company() {
        UUID company = CompanyContext.currentCompany();
        if (company == null) throw new BadRequestException("Select one company for production operations");
        return company;
    }
    private static String actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
    private static boolean writable() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a ->
            Set.of("ROLE_ADMIN", "ROLE_MM_MANAGER", "ROLE_WAREHOUSE_MANAGER", "ROLE_WAREHOUSE_HEAD").contains(a.getAuthority()));
    }
    private static boolean financialReader() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a ->
            Set.of("ROLE_FINANCE_CONTROLLER", "ROLE_ACCOUNTANT", "ROLE_AUDITOR").contains(a.getAuthority()));
    }
    private String json(Draft draft) {
        try { return mapper.writeValueAsString(draft); } catch (Exception e) { throw new BadRequestException("Invalid production document"); }
    }
    private Draft read(String json) {
        try { return mapper.readValue(json, Draft.class); } catch (Exception e) { throw new IllegalStateException("Invalid saved production document", e); }
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column); return timestamp == null ? null : timestamp.toInstant();
    }
}
