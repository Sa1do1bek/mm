package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.ExactOpeningStockDtos.*;
import com.enterprise.mm.repository.*;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded opening import with exact source amounts, durable provenance and a reconciliation gate. */
@Service @RequiredArgsConstructor
public class ExactOpeningStockService {
    private static final UUID ZERO_UUID = new UUID(0,0);
    private final CompanyRepository companies;
    private final WarehouseRepository warehouses;
    private final StorageLocationRepository locations;
    private final StorageBinRepository bins;
    private final MaterialCatalogGuard materials;
    private final ExactOpeningImportRepository imports;
    private final ExactOpeningRowRepository rows;
    private final StockMovementService posting;
    private final InventoryScopeLockService locks;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Transactional(readOnly=true)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> capabilities(String tenantId, UUID companyId) {
        requireScope(tenantId,companyId);
        return Map.of("formatVersion",1,"maxQuantityScale",7,"maxChunkRows",500,"authoritativeBookValues",true);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Status start(String tenantId, UUID companyId, StartRequest request) {
        Company company = requireScope(tenantId, companyId);
        valid(request);
        if (ZERO_UUID.equals(request.importId())) fail("Opening import identity must be nonzero");
        return OpeningImportContext.within(companyId, request.importId(), () -> {
            locks.lock(companyId, List.of());
            entityManager.refresh(company);
            if (company.isDeleted() || !"ACTIVE".equals(company.getStatus())) fail("Opening company is missing or inactive");
            return idempotency.execute("EXACT_OPENING_START", request.importId(), "start", request, Status.class, () -> {
                if (imports.findByTenantIdAndCompanyId(tenantId, companyId).isPresent() || imports.existsById(request.importId()))
                    throw new ConflictException("This company or import identity already has an opening import");
                if (company.getDescription() == null || !company.getDescription().contains(
                    "[1c-mm-migration:"+request.preparedManifestSha256()+":Catalog61:"+request.sourceOrganizationId()+"]"))
                    fail("Opening organization does not match the new company's 1C source identity");
                for (String table : List.of("stock_balances", "stock_movements", "stock_batches", "inventory_cost_layers", "stock_cost_movements")) {
                    if (count("select count(*) from "+table+" where tenant_id=? and company_id=?", tenantId, companyId) != 0)
                        fail("Exact opening requires a company with no existing stock or stock history");
                }
                ExactOpeningImport run = new ExactOpeningImport();
                run.setId(request.importId()); run.setTenantId(tenantId); run.setCompanyId(companyId);
                run.setSourceManifestSha256(request.sourceManifestSha256()); run.setPreparedManifestSha256(request.preparedManifestSha256());
                run.setSourceOrganizationId(request.sourceOrganizationId()); run.setSourceEvidence(request.sourceEvidence());
                run.setPostingDate(request.postingDate()); run.setExpectedRows(request.expectedRows());
                run.setExpectedQuantity(decimal(request.expectedQuantityChecksum(),7,true));
                run.setExpectedValue(decimal(request.expectedValueUzs(),2,false));
                imports.saveAndFlush(run);
                audit.logInCurrentTransaction("ExactOpeningImport", run.getId(), "START", request.sourceEvidence(), null, statusOf(run));
                return statusOf(run);
            });
        });
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ChunkResult postChunk(String tenantId, UUID companyId, UUID importId, ChunkRequest request) {
        requireScope(tenantId, companyId); valid(request);
        if (ZERO_UUID.equals(request.chunkId())) fail("Opening chunk identity must be nonzero");
        return OpeningImportContext.within(companyId, importId, () -> {
            locks.lock(companyId, List.of());
            ExactOpeningImport run = requireRun(tenantId, companyId, importId);
            return idempotency.execute("EXACT_OPENING_CHUNK", importId, request.chunkId().toString(), request, ChunkResult.class, () -> {
                if (!"IMPORTING".equals(run.getStatus())) fail("Opening import is already complete");
                Set<String> identities = new HashSet<>();
                BigDecimal quantity = BigDecimal.ZERO, value = BigDecimal.ZERO;
                // Validate all rows before creating any stock, including globally accessible ADMIN references.
                for (Row row : request.rows()) {
                    if (!identities.add(row.rowId()) || rows.existsByImportIdAndSourceRowId(importId, row.rowId()))
                        throw new ConflictException("Opening source row was already posted or occurs twice");
                    validateRow(run, row);
                    quantity = quantity.add(decimal(row.quantity(),7,true)); value = value.add(decimal(row.totalValueUzs(),2,false));
                }
                if (run.getAcceptedRows()+request.rows().size() > run.getExpectedRows()
                    || run.getAcceptedQuantity().add(quantity).compareTo(run.getExpectedQuantity()) > 0
                    || run.getAcceptedValue().add(value).compareTo(run.getExpectedValue()) > 0)
                    fail("Opening chunk exceeds the declared source totals");
                List<RowResult> result = new ArrayList<>();
                for (Row row : request.rows()) {
                    BigDecimal rowQuantity = decimal(row.quantity(),7,true), rowValue = decimal(row.totalValueUzs(),2,false);
                    StockMovement movement = new StockMovement();
                    movement.setTenantId(tenantId); movement.setCompanyId(companyId);
                    movement.setMaterialId(row.materialId()); movement.setWarehouseId(row.warehouseId());
                    movement.setStorageLocationId(row.storageLocationId()); movement.setStorageBinId(row.storageBinId());
                    movement.setMovementType(MovementType.RECEIPT); movement.setMovementDate(run.getPostingDate());
                    movement.setQuantity(rowQuantity); movement.setReferenceType("OPENING_STOCK_EXACT"); movement.setReferenceId(importId);
                    movement.setBatchNumber("1C-OPEN-"+UUID.nameUUIDFromBytes((importId+":"+row.rowId()).getBytes(StandardCharsets.UTF_8)));
                    movement.setDescription("1C exact opening; source="+run.getSourceManifestSha256()+"; row="+row.rowId()
                        +"; account="+row.accountCode()+"; valueUZS="+row.totalValueUzs());
                    movement = posting.postExactOpening(movement, rowValue);
                    ExactOpeningRow record = new ExactOpeningRow(); record.setId(UUID.randomUUID()); record.setImportId(importId);
                    record.setSourceRowId(row.rowId()); record.setAccountSourceId(row.accountSourceId()); record.setAccountCode(row.accountCode());
                    record.setMaterialSourceId(row.materialSourceId()); record.setWarehouseSourceId(row.warehouseSourceId());
                    record.setMovementId(movement.getId()); record.setQuantity(rowQuantity); record.setTotalValue(rowValue); rows.save(record);
                    result.add(new RowResult(row.rowId(),movement.getId(),movement.getStockBatchId(),rowQuantity,rowValue));
                }
                run.setAcceptedRows(run.getAcceptedRows()+request.rows().size());
                run.setAcceptedQuantity(run.getAcceptedQuantity().add(quantity)); run.setAcceptedValue(run.getAcceptedValue().add(value));
                imports.saveAndFlush(run);
                return new ChunkResult(importId, request.chunkId(), List.copyOf(result));
            });
        });
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Status complete(String tenantId, UUID companyId, UUID importId) {
        requireScope(tenantId, companyId);
        return OpeningImportContext.within(companyId, importId, () -> {
            locks.lock(companyId,List.of());
            ExactOpeningImport run = requireRun(tenantId,companyId,importId);
            if ("COMPLETED".equals(run.getStatus())) return statusOf(run);
            if (run.getAcceptedRows()!=run.getExpectedRows() || run.getAcceptedQuantity().compareTo(run.getExpectedQuantity())!=0
                || run.getAcceptedValue().compareTo(run.getExpectedValue())!=0) fail("Opening import is incomplete; source totals do not reconcile");
            rows.flush(); imports.flush();
            reconcile(run);
            run.setStatus("COMPLETED"); run.setCompletedAt(LocalDateTime.now()); imports.saveAndFlush(run);
            audit.logInCurrentTransaction("ExactOpeningImport",importId,"COMPLETE",run.getSourceEvidence(),null,statusOf(run));
            return statusOf(run);
        });
    }

    @Transactional(readOnly=true)
    @PreAuthorize("hasRole('ADMIN')")
    public Status status(String tenantId, UUID companyId, UUID importId) {
        requireScope(tenantId,companyId); return statusOf(requireRun(tenantId,companyId,importId));
    }

    private void validateRow(ExactOpeningImport run, Row row) {
        String prefix = run.getSourceOrganizationId()+":"+row.accountSourceId()+":"+row.materialSourceId()+":"+row.warehouseSourceId()+":";
        if (!row.rowId().startsWith(prefix) || !row.rowId().substring(prefix.length()).matches("([a-f0-9]{32})?"))
            fail("Opening row identity does not match its source organization/account/material/warehouse");
        Material material = materials.requireActiveTmbMaterial(row.materialId(),"materialId");
        scoped(material,run);
        if (material.getUnitOfMeasure() == null) fail("Opening material requires a unit of measure");
        scoped(material.getUnitOfMeasure(),run);
        if (!sourceMarker(material,run,"Catalog54",row.materialSourceId())) fail("Opening material has a different 1C source identity");
        if (material.isIncomingInspectionRequired() || material.getSerialTrackingPolicy()==SerialTrackingPolicy.REQUIRED)
            fail("QC or serial-controlled stock requires its controlled receiving workflow");
        StockUomPolicy.purchasing(material,row.unit(),decimal(row.quantity(),7,true));
        Warehouse warehouse = warehouses.findByIdAndIsDeletedFalse(row.warehouseId()).orElseThrow(()->new BusinessException("Opening warehouse is missing"));
        scoped(warehouse,run);
        if (!"ACTIVE".equals(warehouse.getStatus()) || !sourceMarker(warehouse,run,"Catalog85",row.warehouseSourceId()))
            fail("Opening warehouse is inactive or has a different 1C source identity");
        StorageLocation location = locations.findByIdAndIsDeletedFalse(row.storageLocationId()).orElseThrow(()->new BusinessException("Opening storage location is missing"));
        scoped(location,run);
        if (!row.warehouseId().equals(location.getWarehouseId()) || !"ACTIVE".equals(location.getStatus()))
            fail("Opening location is inactive or belongs to a different warehouse");
        if (row.storageBinId()!=null) {
            StorageBin bin = bins.findByIdAndIsDeletedFalse(row.storageBinId()).orElseThrow(()->new BusinessException("Opening storage bin is missing"));
            scoped(bin,run);
            if (!row.storageLocationId().equals(bin.getStorageLocationId()) || !"ACTIVE".equals(bin.getStatus()))
                fail("Opening bin is inactive or belongs to a different location");
        }
        decimal(row.totalValueUzs(),2,false);
    }

    private void reconcile(ExactOpeningImport run) {
        Company company = companies.findByTenantIdAndIdAndIsDeletedFalse(run.getTenantId(),run.getCompanyId()).orElseThrow();
        if (!sourceMarker(company,run,"Catalog61",run.getSourceOrganizationId())) fail("Opening company source identity changed during import");
        Object[] scope={run.getTenantId(),run.getCompanyId()};
        // All company stock is still frozen. One row must have one posted movement, batch, balance,
        // authoritative layer, ledger event and allocation; compare each physical row, not only totals.
        String badRows="""
            select count(*) from exact_opening_rows r
            join exact_opening_imports i on i.id=r.import_id
            left join stock_movements m on m.id=r.movement_id
            left join materials mat on mat.id=m.material_id
            left join unit_of_measures u on u.id=mat.unit_of_measure_id
            left join warehouses w on w.id=m.warehouse_id
            left join storage_locations loc on loc.id=m.storage_location_id
            left join storage_bins bin on bin.id=m.storage_bin_id
            left join stock_batches b on b.id=m.stock_batch_id
            left join stock_balances s on s.stock_batch_id=b.id and s.tenant_id=i.tenant_id and s.company_id=i.company_id and s.is_delete=false
            left join inventory_cost_layers l on l.source_movement_id=m.id and l.tenant_id=i.tenant_id and l.company_id=i.company_id and l.is_delete=false
            left join stock_cost_movements c on c.stock_movement_id=m.id and c.cost_layer_id=l.id and c.tenant_id=i.tenant_id and c.company_id=i.company_id and c.is_delete=false
            left join stock_movement_allocations a on a.movement_id=m.id and a.tenant_id=i.tenant_id and a.company_id=i.company_id
            left join stock_movement_allocation_cost_layers al on al.allocation_id=a.id and al.tenant_id=i.tenant_id and al.company_id=i.company_id
            where r.import_id=? and (m.id is null or m.is_delete or m.tenant_id is distinct from i.tenant_id or m.company_id is distinct from i.company_id
                or m.status is distinct from 'POSTED' or m.reference_type is distinct from 'OPENING_STOCK_EXACT' or m.reference_id is distinct from i.id
                or m.quantity is distinct from r.quantity or m.total_cost is distinct from r.total_value or b.id is null or b.is_delete or b.quantity is distinct from r.quantity
                or b.tenant_id is distinct from i.tenant_id or b.company_id is distinct from i.company_id or b.material_id is distinct from m.material_id or b.warehouse_id is distinct from m.warehouse_id
                or s.id is null or s.quantity is distinct from r.quantity or s.available_quantity is distinct from r.quantity or s.reserved_quantity is distinct from 0 or s.quarantine_quantity is distinct from 0 or s.total_value is distinct from r.total_value
                or s.material_id is distinct from m.material_id or s.warehouse_id is distinct from m.warehouse_id or s.storage_location_id is distinct from m.storage_location_id
                or s.storage_bin_id is distinct from m.storage_bin_id
                or mat.id is null or mat.is_delete or mat.status is distinct from 'ACTIVE' or mat.material_type is distinct from 'TMB'
                or mat.tenant_id is distinct from i.tenant_id or mat.company_id is distinct from i.company_id
                or mat.incoming_inspection_required or mat.serial_tracking_policy='REQUIRED'
                or coalesce(position('[1c-mm-migration:'||i.prepared_manifest_sha256||':Catalog54:'||r.material_source_id||']' in mat.description),0)=0
                or u.id is null or u.is_delete or u.tenant_id is distinct from i.tenant_id or u.company_id is distinct from i.company_id
                or m.uom_id_snapshot is distinct from u.id or m.uom_code_snapshot is distinct from u.code
                or w.id is null or w.is_delete or w.status is distinct from 'ACTIVE' or w.tenant_id is distinct from i.tenant_id or w.company_id is distinct from i.company_id
                or coalesce(position('[1c-mm-migration:'||i.prepared_manifest_sha256||':Catalog85:'||r.warehouse_source_id||']' in w.description),0)=0
                or loc.id is null or loc.is_delete or loc.status is distinct from 'ACTIVE' or loc.tenant_id is distinct from i.tenant_id or loc.company_id is distinct from i.company_id or loc.warehouse_id is distinct from w.id
                or (m.storage_bin_id is not null and (bin.id is null or bin.is_delete or bin.status is distinct from 'ACTIVE' or bin.tenant_id is distinct from i.tenant_id or bin.company_id is distinct from i.company_id or bin.storage_location_id is distinct from loc.id))
                or l.id is null or not l.authoritative_value or l.received_quantity is distinct from r.quantity or l.remaining_quantity is distinct from r.quantity or l.total_cost is distinct from r.total_value
                or l.status is distinct from 'ACTIVE' or l.layer_type is distinct from 'OPENING_BALANCE'
                or l.material_id is distinct from m.material_id or l.warehouse_id is distinct from m.warehouse_id or l.stock_batch_id is distinct from b.id
                or c.id is null or c.quantity is distinct from r.quantity or c.total_cost is distinct from r.total_value or c.is_reversal
                or a.id is null or a.balance_id is distinct from s.id or a.material_id is distinct from m.material_id or a.allocation_role is distinct from 'TARGET'
                or a.on_hand_delta is distinct from r.quantity or a.available_delta is distinct from r.quantity or a.total_value_delta is distinct from r.total_value
                or al.id is null or al.cost_layer_id is distinct from l.id or al.source_cost_movement_id is distinct from c.id or al.lineage_role is distinct from 'CREATED'
                or al.quantity_delta is distinct from r.quantity or al.value_delta is distinct from r.total_value)
            """;
        if (count(badRows,run.getId())!=0) fail("Opening row evidence or physical stock does not reconcile");
        if (count("select count(*) from exact_opening_rows where import_id=?",run.getId())!=run.getExpectedRows()) fail("Opening source row count differs");
        for (String table : List.of("stock_movements","stock_batches","stock_balances","inventory_cost_layers","stock_cost_movements")) {
            if (count("select count(*) from "+table+" where tenant_id=? and company_id=? and is_delete=false",scope)!=run.getExpectedRows())
                fail("Opening stock evidence count differs: "+table);
        }
        if (count("select count(*) from stock_movement_allocations where tenant_id=? and company_id=?",scope)!=run.getExpectedRows())
            fail("Opening allocation evidence count differs");
        if (count("select count(*) from stock_movement_allocation_cost_layers where tenant_id=? and company_id=?",scope)!=run.getExpectedRows())
            fail("Opening cost allocation evidence count differs");
        Map<String,Object> totals=jdbc.queryForMap("select coalesce(sum(quantity),0) q, coalesce(sum(total_value),0) v from stock_balances where tenant_id=? and company_id=? and is_delete=false",scope);
        if (new BigDecimal(totals.get("q").toString()).compareTo(run.getExpectedQuantity())!=0
            || new BigDecimal(totals.get("v").toString()).compareTo(run.getExpectedValue())!=0) fail("Opening company stock totals do not reconcile");
    }

    private Company requireScope(String tenantId,UUID companyId) {
        if (!TenantContext.valid(tenantId) || !Objects.equals(TenantContext.currentTenant(),tenantId) || companyId==null
            || ZERO_UUID.equals(companyId) || CompanyContext.DEFAULT_COMPANY_ID.equals(companyId) || !companyId.equals(CompanyContext.currentCompany()))
            throw new BusinessException("Exact opening requires explicit tenant and a nondefault current company");
        return companies.findByTenantIdAndIdAndIsDeletedFalse(tenantId,companyId)
            .filter(c->"ACTIVE".equals(c.getStatus())).orElseThrow(()->new BusinessException("Opening company is missing or inactive"));
    }
    private ExactOpeningImport requireRun(String tenantId,UUID companyId,UUID importId) {
        return imports.findById(importId).filter(r->r.getTenantId().equals(tenantId)&&r.getCompanyId().equals(companyId))
            .orElseThrow(()->new BusinessException("Opening import was not found in this company"));
    }
    private void scoped(com.enterprise.mm.common.BusinessEntity entity,ExactOpeningImport run) {
        if (entity.isDeleted() || !run.getTenantId().equals(entity.getTenantId()) || !run.getCompanyId().equals(entity.getCompanyId()))
            fail("Opening reference belongs to another tenant or company");
    }
    private boolean sourceMarker(com.enterprise.mm.common.BusinessEntity entity,ExactOpeningImport run,String kind,String sourceId) {
        return entity.getDescription()!=null && entity.getDescription().contains("[1c-mm-migration:"+run.getPreparedManifestSha256()+":"+kind+":"+sourceId+"]");
    }
    private void valid(Object request) { if(request==null || !validator.validate(request).isEmpty()) fail("Invalid exact opening command"); }
    private BigDecimal decimal(String text,int scale,boolean positive) {
        try {
            BigDecimal value=new BigDecimal(text).setScale(scale,RoundingMode.UNNECESSARY);
            if(value.signum()<0 || positive&&value.signum()==0 || value.precision()-value.scale()>15) fail("Opening amount exceeds supported range");
            return value;
        } catch (NumberFormatException|ArithmeticException e) { throw new BusinessException("Opening amounts must be exact decimal strings"); }
    }
    private int count(String sql,Object...args) { return Objects.requireNonNull(jdbc.queryForObject(sql,Integer.class,args)); }
    private Status statusOf(ExactOpeningImport run) {
        return new Status(run.getId(),run.getTenantId(),run.getCompanyId(),run.getStatus(),run.getExpectedRows(),run.getAcceptedRows(),
            run.getExpectedQuantity(),run.getAcceptedQuantity(),run.getExpectedValue(),run.getAcceptedValue(),run.getSourceManifestSha256(),run.getPreparedManifestSha256());
    }
    private void fail(String message) { throw new BusinessException(message); }
}
