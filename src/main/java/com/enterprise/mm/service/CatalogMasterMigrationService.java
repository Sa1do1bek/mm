package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.payload.MaterialGroupCreateRequest;
import com.enterprise.mm.payload.UnitOfMeasureCreateRequest;
import com.enterprise.mm.payload.WarehouseCreateRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CatalogMasterMigrationService {
    private static final UUID ZERO = new UUID(0, 0);
    private static final Map<String, String> SOURCE_TYPES = Map.of(
        "units", "Catalog41", "categories", "Catalog6295", "warehouses", "Catalog85");
    private static final Set<String> UNIT_FIELDS = Set.of("code", "name", "measurementNameShort", "measurementNameLong",
        "measurementNameShortRu", "measurementNameLongRu", "measurementNameShortUz", "measurementNameLongUz", "unitType", "stockQuantityScale");
    private static final Set<String> GROUP_FIELDS = Set.of("code", "name", "status", "description");
    private static final Set<String> WAREHOUSE_FIELDS = Set.of("name", "status", "description", "address", "responsibleEmployeeIds", "sourceGroup", "sourceHasBalance");
    private final CompanyRepository companies;
    private final UnitOfMeasureRepository units;
    private final MaterialGroupRepository groups;
    private final WarehouseRepository warehouses;
    private final UnitOfMeasureService unitService;
    private final MaterialGroupService groupService;
    private final WarehouseService warehouseService;
    private final InventoryScopeLockService locks;
    private final ExactOpeningImportGuard openingGuard;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final EntityManager entityManager;

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER')")
    public MasterChunkResponse importMasters(String kind, String tenantId, UUID companyId, MasterChunkRequest request) {
        requireScope(tenantId, companyId);
        validateCommand(kind, request);
        locks.lock(companyId, List.of());
        openingGuard.assertOperationsAllowed(companyId);
        return idempotency.execute("MIGRATION_MASTER_CHUNK_" + kind.toUpperCase(Locale.ROOT), request.importId(),
            request.chunkId().toString(), request, MasterChunkResponse.class,
            () -> importChunk(kind, tenantId, companyId, request));
    }

    private MasterChunkResponse importChunk(String kind, String tenantId, UUID companyId, MasterChunkRequest request) {
        var identity = new ManifestIdentity(request.sourceManifestSha256(), request.preparedManifestSha256());
        idempotency.execute("MIGRATION_MASTER_IMPORT", request.importId(), "manifests", identity,
            ManifestIdentity.class, () -> identity);
        List<MasterRowResponse> results = new ArrayList<>();
        for (MasterRow row : request.rows()) {
            results.add(idempotency.execute("MIGRATION_MASTER_ROW_" + kind.toUpperCase(Locale.ROOT), request.importId(),
                row.sourceId(), row, MasterRowResponse.class,
                () -> importRow(kind, tenantId, companyId, request.preparedManifestSha256(), row)));
        }
        return new MasterChunkResponse(request.importId(), request.chunkId(), request.sourceManifestSha256(),
            request.preparedManifestSha256(), tenantId, companyId, kind, List.copyOf(results));
    }

    private MasterRowResponse importRow(String kind, String tenantId, UUID companyId, String manifest, MasterRow row) {
        String marker = sourceMarker(manifest, row);
        if (kind.equals("units")) {
            UnitOfMeasureCreateRequest payload = convert(row.payload(), UnitOfMeasureCreateRequest.class);
            String code = payload.getCode().trim();
            UnitOfMeasure existing = units.findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalse(tenantId, companyId, code).orElse(null);
            if (existing == null) existing = findMarked(UnitOfMeasure.class, tenantId, companyId, marker);
            if (existing != null) {
                requireIdentity(existing, tenantId, companyId, row.expectedMasterId());
                rejectForeignMarker(existing.getDescription(), marker);
                if (!code.equalsIgnoreCase(existing.getCode()) || !sameUnit(existing, payload)) throw new ConflictException("Unit recovery code exists with different prepared labels or precision");
                // Legacy unit creates cannot carry a description; the full recovery code is their source identity.
                bindUnitMarker(existing, marker);
                return result(row, existing, false);
            }
            requireNotMissingExpected(row);
            UUID id = unitService.create(payload, Locale.ENGLISH).getId();
            UnitOfMeasure created = units.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, id)
                .orElseThrow(() -> new ConflictException("Created unit did not retain its company"));
            requireIdentity(created, tenantId, companyId, null);
            bindUnitMarker(created, marker);
            return result(row, created, true);
        }
        if (kind.equals("categories")) {
            MaterialGroupCreateRequest payload = convert(row.payload(), MaterialGroupCreateRequest.class);
            payload.setCode(payload.getCode().trim().toUpperCase(Locale.ROOT));
            payload.setDescription(withMarker(payload.getDescription(), marker));
            MaterialGroup existing = groups.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(tenantId, companyId, payload.getCode()).orElse(null);
            if (existing == null) existing = findMarked(MaterialGroup.class, tenantId, companyId, marker);
            if (existing != null) {
                requireIdentity(existing, tenantId, companyId, row.expectedMasterId());
                requireMarked(existing, marker);
                if (!Objects.equals(payload.getCode(), existing.getCode())
                    || !Objects.equals(com.enterprise.mm.util.BusinessNameGenerator.normalize(trim(payload.getName()), existing.getClass().getSimpleName()), existing.getName())
                    || !Objects.equals(trim(payload.getDescription()), existing.getDescription())
                    || !Objects.equals(payload.getStatus() == null ? "ACTIVE" : payload.getStatus().name(), existing.getStatus())) {
                    throw new ConflictException("Category code exists with different prepared source metadata");
                }
                return result(row, existing, false);
            }
            requireNotMissingExpected(row);
            UUID id = groupService.create(payload).getId();
            MaterialGroup created = groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, id)
                .orElseThrow(() -> new ConflictException("Created category did not retain its company"));
            requireMarked(created, marker);
            return result(row, created, true);
        }
        WarehouseCreateRequest payload = warehousePayload(row.payload());
        payload.setDescription(withMarker(payload.getDescription(), marker));
        List<Warehouse> matches = entityManager.createQuery("select w from Warehouse w where w.tenantId = :tenant and w.companyId = :company and w.isDeleted = false and w.description like :marker", Warehouse.class)
            .setParameter("tenant", tenantId).setParameter("company", companyId).setParameter("marker", "%" + marker + "%").getResultList();
        if (matches.size() > 1) throw new ConflictException("Multiple warehouses claim this exact source marker");
        if (!matches.isEmpty()) {
            Warehouse existing = matches.getFirst();
            requireIdentity(existing, tenantId, companyId, row.expectedMasterId());
            requireMarked(existing, marker);
            if (!Objects.equals(com.enterprise.mm.util.BusinessNameGenerator.normalize(trim(payload.getName()), existing.getClass().getSimpleName()), existing.getName())
                || !Objects.equals(payload.getDescription(), existing.getDescription())
                || !Objects.equals(payload.getStatus() == null ? "ACTIVE" : payload.getStatus(), existing.getStatus())
                || !Objects.equals(payload.getAddress(), existing.getAddress())
                || existing.isSourceGroup() != row.payload().path("sourceGroup").asBoolean()
                || existing.isSourceHasBalance() != row.payload().path("sourceHasBalance").asBoolean()
                || existing.getCode() == null || existing.getCode().isBlank()) {
                throw new ConflictException("Warehouse marker exists with different prepared source metadata");
            }
            return result(row, existing, false);
        }
        requireNotMissingExpected(row);
        UUID id = warehouseService.create(payload).getId();
        Warehouse created = warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, id)
            .orElseThrow(() -> new ConflictException("Created warehouse did not retain its company"));
        requireMarked(created, marker);
        created.setSourceGroup(row.payload().path("sourceGroup").asBoolean());
        created.setSourceHasBalance(row.payload().path("sourceHasBalance").asBoolean());
        warehouses.save(created);
        return result(row, created, true);
    }

    private <T extends BusinessEntity> T findMarked(Class<T> type, String tenant, UUID company, String marker) {
        List<T> matches = entityManager.createQuery("select e from " + type.getSimpleName()
            + " e where e.tenantId=:tenant and e.companyId=:company and e.isDeleted=false and e.description like :marker", type)
            .setParameter("tenant", tenant).setParameter("company", company).setParameter("marker", "%" + marker + "%").getResultList();
        if (matches.size() > 1) throw new ConflictException("Multiple masters claim this exact source marker");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private void bindUnitMarker(UnitOfMeasure unit, String marker) {
        if (unit.getDescription() != null && unit.getDescription().contains(marker)) return;
        String previous = audit.snapshot(unit);
        unit.setDescription(withMarker(unit.getDescription(), marker));
        units.save(unit);
        audit.logInCurrentTransaction("UnitOfMeasure", unit.getId(), "MIGRATION_BIND", marker, previous, unit);
    }

    private void validateCommand(String kind, MasterChunkRequest request) {
        if (!SOURCE_TYPES.containsKey(kind) || request == null || !validator.validate(request).isEmpty()
            || ZERO.equals(request.importId()) || ZERO.equals(request.chunkId())) {
            throw new BadRequestException("Master migration requires a supported kind, manifests and 1 to 200 rows");
        }
        Set<String> sourceIds = new HashSet<>(), codes = new HashSet<>();
        Set<String> allowed = kind.equals("units") ? UNIT_FIELDS : kind.equals("categories") ? GROUP_FIELDS : WAREHOUSE_FIELDS;
        for (MasterRow row : request.rows()) {
            boolean validSource=SOURCE_TYPES.get(kind).equals(row.sourceEntityType())
                || (kind.equals("categories") && Set.of("Catalog30","Catalog55","Catalog56","Catalog5876").contains(row.sourceEntityType()));
            if (!validSource || !row.payload().isObject()
                || !sourceIds.add(row.sourceId()) || ZERO.equals(row.expectedMasterId())
                || (kind.equals("warehouses") && row.sourceId().equals("source-not-specified"))) {
                throw new BadRequestException("Master rows require unique full source identities for the selected catalog");
            }
            row.payload().fieldNames().forEachRemaining(field -> {
                if (!allowed.contains(field)) throw new BadRequestException("Unsupported master payload field: " + field);
                JsonNode value = row.payload().get(field);
                if (!value.isNull() && !field.equals("stockQuantityScale") && !field.equals("responsibleEmployeeIds") && !Set.of("sourceGroup","sourceHasBalance").contains(field) && !value.isTextual()) {
                    throw new BadRequestException("Master text fields must be strings");
                }
            });
            for(String flag:List.of("sourceGroup","sourceHasBalance")) if(row.payload().hasNonNull(flag)&&!row.payload().get(flag).isBoolean()) throw new BadRequestException("Source warehouse flags must be boolean");
            requiredText(row.payload(), "name", 240);
            if (!kind.equals("warehouses")) {
                String code = requiredText(row.payload(), "code", 80);
                if (!codes.add(code.toUpperCase(Locale.ROOT))) throw new BadRequestException("Duplicate master codes in chunk");
                if (kind.equals("units") && !code.equalsIgnoreCase("1C-UOM-" + row.sourceId())) {
                    throw new BadRequestException("Unit code must preserve the full source recovery identity");
                }
            }
            if (kind.equals("units")) {
                for (String field : UNIT_FIELDS) {
                    if (field.startsWith("measurementName")) optionalText(row.payload(), field, field.contains("Short") ? 120 : 240);
                }
                JsonNode scale = row.payload().get("stockQuantityScale");
                if (scale != null && !scale.isNull() && (!scale.isIntegralNumber() || !scale.canConvertToInt())) {
                    throw new BadRequestException("Unit stock precision must be an integer");
                }
                convert(row.payload(), UnitOfMeasureCreateRequest.class);
            } else {
                rejectForeignMarker(row.payload().path("description").asText(null), sourceMarker(request.preparedManifestSha256(), row));
                if (kind.equals("categories")) convert(row.payload(), MaterialGroupCreateRequest.class);
                else {
                    WarehouseCreateRequest payload = warehousePayload(row.payload());
                    if (payload.getResponsibleEmployeeIds() != null && !payload.getResponsibleEmployeeIds().isEmpty()) {
                        throw new BadRequestException("Source warehouse migration cannot invent employee assignments");
                    }
                }
            }
        }
    }

    private WarehouseCreateRequest warehousePayload(JsonNode value) {
        var payload=value.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)payload).remove(List.of("sourceGroup","sourceHasBalance"));
        return convert(payload,WarehouseCreateRequest.class);
    }

    private <T> T convert(JsonNode payload, Class<T> type) {
        try {
            T result = mapper.convertValue(payload, type);
            if (!validator.validate(result).isEmpty()) throw new BadRequestException("Master create payload violates normal validation");
            return result;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid normal master create payload");
        }
    }

    private void requireScope(String tenantId, UUID companyId) {
        if (!TenantContext.valid(tenantId) || !Objects.equals(tenantId, TenantContext.currentTenant()) || companyId == null
            || ZERO.equals(companyId) || CompanyContext.DEFAULT_COMPANY_ID.equals(companyId)
            || !Objects.equals(companyId, CompanyContext.currentCompany())
            || companies.findByTenantIdAndIdAndIsDeletedFalse(tenantId, companyId).isEmpty()) {
            throw new BadRequestException("Master migration requires an explicit nondefault company in the current tenant");
        }
    }
    private static void requireIdentity(BusinessEntity entity, String tenant, UUID company, UUID expected) {
        if (entity.isDeleted() || !Objects.equals(tenant, entity.getTenantId()) || !Objects.equals(company, entity.getCompanyId())
            || (expected != null && !Objects.equals(expected, entity.getId()))) {
            throw new ConflictException("Master recovery identity belongs to another company or target ID");
        }
    }
    private static void requireNotMissingExpected(MasterRow row) {
        if (row.expectedMasterId() != null) throw new ConflictException("The journal's existing master is missing; refusing replacement");
    }
    private static void requireMarked(BusinessEntity entity, String marker) {
        rejectForeignMarker(entity.getDescription(), marker);
        if (entity.getDescription() == null || !entity.getDescription().contains(marker)) {
            throw new ConflictException("Existing master lacks the full source and manifest marker");
        }
    }
    private static void rejectForeignMarker(String description, String expected) {
        if (description == null) return;
        var matches = java.util.regex.Pattern.compile("\\[1c-mm-migration:[^\\]\\r\\n]+\\]").matcher(description);
        while (matches.find()) if (!matches.group().equals(expected)) {
            throw new ConflictException("Master description contains another source or manifest marker");
        }
    }
    private static String withMarker(String description, String marker) {
        rejectForeignMarker(description, marker);
        if (description != null && description.contains(marker)) return description;
        return description == null || description.isBlank() ? marker : description.stripTrailing() + "\n" + marker;
    }
    public static String sourceMarker(String manifest, MasterRow row) {
        return "[1c-mm-migration:" + manifest + ":" + row.sourceEntityType() + ":" + row.sourceId() + "]";
    }
    private static MasterRowResponse result(MasterRow row, BusinessEntity entity, boolean created) {
        return new MasterRowResponse(row.sourceId(), entity.getId(), entity.getCode(), created);
    }
    private static boolean sameUnit(UnitOfMeasure u, UnitOfMeasureCreateRequest p) {
        return Objects.equals(com.enterprise.mm.util.BusinessNameGenerator.normalize(trim(p.getName()), "UnitOfMeasure"), u.getName())
            && Objects.equals(trim(p.getMeasurementNameShort()), u.getMeasurementNameShort())
            && Objects.equals(trim(p.getMeasurementNameLong()), u.getMeasurementNameLong())
            && Objects.equals(trim(p.getMeasurementNameShortRu()), u.getMeasurementNameShortRu())
            && Objects.equals(trim(p.getMeasurementNameLongRu()), u.getMeasurementNameLongRu())
            && Objects.equals(trim(p.getMeasurementNameShortUz()), u.getMeasurementNameShortUz())
            && Objects.equals(trim(p.getMeasurementNameLongUz()), u.getMeasurementNameLongUz())
            && Objects.equals(p.getUnitType() == null ? MeasurementUnitType.MAIN : p.getUnitType(), u.getUnitType())
            && (p.getStockQuantityScale() == null || Objects.equals(p.getStockQuantityScale(), u.getStockQuantityScale()));
    }
    private static String requiredText(JsonNode payload, String field, int max) {
        String value = optionalText(payload, field, max);
        if (value == null) throw new BadRequestException("Imported master " + field + " is required");
        return value;
    }
    private static String optionalText(JsonNode payload, String field, int max) {
        String value = trim(payload.path(field).asText(null));
        if (value != null && value.length() > max) throw new BadRequestException("Imported master " + field + " exceeds the normal limit");
        return value;
    }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private record ManifestIdentity(String sourceManifestSha256, String preparedManifestSha256) {}
}
