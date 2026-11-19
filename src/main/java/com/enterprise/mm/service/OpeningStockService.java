package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.repository.StockBalanceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Signed administrative opening uses the same valued receipt posting as procurement. */
@Service @RequiredArgsConstructor
public class OpeningStockService {
    private final StockMovementService posting;
    private final MaterialCatalogGuard materials;
    private final StockBalanceRepository balances;
    private final com.enterprise.mm.repository.StorageLocationRepository locations;
    private final com.enterprise.mm.repository.StorageBinRepository bins;
    private final InventoryScopeLockService locks;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    public record Row(String rowId, UUID materialId, UUID warehouseId, UUID storageLocationId,
                      UUID storageBinId, String unit, BigDecimal quantity, BigDecimal unitValue) {}
    public record Command(UUID importId, String sourceEvidence, LocalDate postingDate, List<Row> rows) {}
    public record Result(UUID importId, List<StockMovementResponse> movements, BigDecimal quantity, BigDecimal value) {}

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public Result post(Command command) {
        if (command == null || command.importId() == null || command.sourceEvidence() == null || command.sourceEvidence().isBlank()
            || command.postingDate() == null || command.rows() == null || command.rows().isEmpty())
            throw new BusinessException("Opening requires import identity, signed source evidence, posting date and rows");
        // Lock before replay lookup, including competing imports and same-key transport retries.
        locks.lock(CompanyContext.currentCompany(), List.of());
        return idempotency.execute("OPENING_STOCK", command.importId(), command.importId().toString(), command, Result.class, () -> {
            Set<String> rows = new HashSet<>(); Set<String> positions = new HashSet<>();
            for (Row row : command.rows()) {
                if (row == null || row.rowId() == null || row.rowId().isBlank() || !rows.add(row.rowId()))
                    throw new BusinessException("Opening row identity must be unique");
                if (row.warehouseId() == null || row.storageLocationId() == null)
                    throw new BusinessException("Opening warehouse and physical storage location are required");
                if (!positions.add(row.materialId()+":"+row.warehouseId()+":"+row.storageLocationId()+":"+row.storageBinId()))
                    throw new BusinessException("Duplicate opening stock position");
                locations.findByIdAndIsDeletedFalse(row.storageLocationId())
                    .filter(l -> TenantContext.currentTenant().equals(l.getTenantId()) && CompanyContext.currentCompany().equals(l.getCompanyId()) && row.warehouseId().equals(l.getWarehouseId()))
                    .orElseThrow(() -> new BusinessException("Opening location does not belong to the warehouse"));
                if (row.storageBinId() != null) bins.findByIdAndIsDeletedFalse(row.storageBinId())
                    .filter(b -> TenantContext.currentTenant().equals(b.getTenantId()) && CompanyContext.currentCompany().equals(b.getCompanyId()) && row.storageLocationId().equals(b.getStorageLocationId()))
                    .orElseThrow(() -> new BusinessException("Opening bin does not belong to the location"));
                Material material = materials.requireActiveTmbMaterial(row.materialId(), "materialId");
                StockUomPolicy.purchasing(material, row.unit(), row.quantity());
                if (material.isIncomingInspectionRequired())
                    throw new BusinessException("QC-required opening stock must first have an approved disposition; use controlled receiving");
                if (row.unitValue() == null || row.unitValue().signum() <= 0 || row.unitValue().stripTrailingZeros().scale() > 4)
                    throw new BusinessException("Opening unit value must be positive with at most four decimal places");
                if (!balances.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), row.materialId(), row.warehouseId()).isEmpty())
                    throw new BusinessException("Opening cannot overwrite an existing material/warehouse stock position; reconcile through inventory count");
            }
            List<StockMovementResponse> movements = new ArrayList<>(); BigDecimal quantity=BigDecimal.ZERO, value=BigDecimal.ZERO;
            for (Row row : command.rows()) {
                StockMovement m=new StockMovement(); m.setMaterialId(row.materialId()); m.setWarehouseId(row.warehouseId());
                m.setStorageLocationId(row.storageLocationId()); m.setStorageBinId(row.storageBinId());
                m.setMovementType(MovementType.RECEIPT); m.setMovementDate(command.postingDate());
                m.setQuantity(row.quantity()); m.setUnitCost(row.unitValue());
                m.setBatchNumber("OPEN-"+command.importId()+"-"+row.rowId());
                m.setReferenceType("OPENING_STOCK"); m.setReferenceId(command.importId()); m.setDescription(command.sourceEvidence());
                movements.add(posting.createAndPost(m)); quantity=quantity.add(row.quantity()); value=value.add(row.quantity().multiply(row.unitValue()));
            }
            Result result=new Result(command.importId(), List.copyOf(movements), quantity, value);
            audit.log("OpeningStock", command.importId(), "POST", command.sourceEvidence(), null, result);
            return result;
        });
    }
}
