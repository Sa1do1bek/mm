package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.repository.CompanyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic, idempotent legacy masters. Never provisions users, roles, accreditation or stock. */
@Service
@RequiredArgsConstructor
public class OperationalMasterMigrationService {
    private static final UUID ZERO = new UUID(0, 0);
    private static final Map<String, Set<String>> TYPES = Map.of("suppliers", Set.of("Catalog45"),
        "departments", Set.of("Catalog70", "Catalog8844", "Catalog8850"),
        "positions", Set.of("Catalog36Department"), "employees", Set.of("Catalog90"));
    private static final Map<String, Class<? extends BusinessEntity>> ENTITIES = Map.of(
        "suppliers", Supplier.class, "departments", Department.class, "positions", Position.class, "employees", Employee.class);
    private static final Map<String, Class<?>> PAYLOADS = Map.of("suppliers", LegacySupplierMigrationPayload.class,
        "departments", DepartmentCreateRequest.class, "positions", PositionCreateRequest.class, "employees", EmployeeCreateRequest.class);
    private static final Map<String, Set<String>> FIELDS = Map.of(
        "suppliers", Set.of("code", "name", "legalName", "shortName", "taxId", "registrationNumber", "legalAddress",
            "actualAddress", "phone", "email", "directorName", "vatRegistrationCode", "bankAccounts", "primaryBankAccountConfirmed", "description", "markedForDeletion"),
        "departments", Set.of("code", "name", "status", "description", "externalId", "parentDepartmentId", "departmentType"),
        "positions", Set.of("code", "name", "status", "description", "externalId", "departmentId", "headcount"),
        "employees", Set.of("code", "name", "status", "description", "externalId", "personnelNumber", "departmentId",
            "positionId", "employmentStatus"));
    private final CompanyRepository companies;
    private final SupplierService suppliers;
    private final DepartmentService departments;
    private final PositionService positions;
    private final EmployeeService employees;
    private final InventoryScopeLockService locks;
    private final ExactOpeningImportGuard openingGuard;
    private final IdempotencyService idempotency;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final AuditService audit;

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> capabilities(String tenant, UUID company) {
        requireScope(tenant, company);
        long types = entityManager.createQuery("select count(t) from DepartmentType t where t.tenantId=:tenant and t.code='DEPARTMENT' and t.isDeleted=false", Long.class)
            .setParameter("tenant", tenant).getSingleResult();
        return Map.of("contractVersion", 1, "companyId", company, "tenantId", tenant,
            "maximumRows", 200, "kinds", TYPES.keySet(), "departmentTypeAvailable", types == 1,
            "supplierNameRepair", true);
    }

    /** Repairs only the name overwritten by the old workflow-name heuristic. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse repairSupplierName(UUID id, String tenant, UUID company, SupplierNameRepairRequest request) {
        requireScope(tenant, company);
        if (id == null || request == null || !validator.validate(request).isEmpty()) {
            throw new BadRequestException("Invalid supplier name repair");
        }
        locks.lock(company, List.of());
        openingGuard.assertOperationsAllowed(company);
        return idempotency.execute("MIGRATION_OPERATIONAL_SUPPLIER_NAME", id,
            request.preparedManifestSha256() + ":" + request.sourceId(), request, SupplierResponse.class, () -> {
                Supplier entity = entityManager.find(Supplier.class, id);
                String code = "1C-CATALOG45-" + request.sourceId();
                String marker = "[1c-mm-migration:" + request.preparedManifestSha256() + ":Catalog45:" + request.sourceId() + "]";
                if (entity == null || entity.isDeleted() || !tenant.equals(entity.getTenantId())
                    || !company.equals(entity.getCompanyId()) || !code.equals(entity.getCode())
                    || entity.getDescription() == null || !entity.getDescription().contains(marker)) {
                    throw new BadRequestException("Supplier does not match the scoped source manifest");
                }
                String humanName = BusinessDisplayNamePolicy.requireHumanName("Supplier", request.sourceName(), code);
                if (!humanName.equals(request.sourceName().trim())) {
                    throw new BadRequestException("Supplier source name is an internal identifier");
                }
                String desired = LocalizedText.ensureComplete(humanName);
                String previous = com.enterprise.mm.util.BusinessNameGenerator.normalize(humanName, "Supplier");
                if (!Objects.equals(entity.getVersion(), request.expectedVersion())) {
                    throw new ConflictException("Supplier changed after the name repair was prepared");
                }
                if (!Objects.equals(entity.getName(), previous) && !Objects.equals(entity.getName(), desired)) {
                    throw new ConflictException("Supplier name was edited outside the legacy import");
                }
                if (!Objects.equals(entity.getName(), desired)) {
                    String before = audit.snapshot(entity);
                    entity.setName(desired);
                    entityManager.flush();
                    audit.logInCurrentTransaction("Supplier", id, "LEGACY_NAME_REPAIR", code, before, entity);
                }
                return suppliers.get(id);
            });
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public MasterChunkResponse importMasters(String kind, String tenant, UUID company, MasterChunkRequest request) {
        requireScope(tenant, company);
        validate(kind, request);
        locks.lock(company, List.of());
        openingGuard.assertOperationsAllowed(company);
        return idempotency.execute("MIGRATION_OPERATIONAL_CHUNK_" + kind, request.importId(),
            request.chunkId().toString(), request, MasterChunkResponse.class,
            () -> importChunk(kind, tenant, company, request));
    }

    private MasterChunkResponse importChunk(String kind, String tenant, UUID company, MasterChunkRequest request) {
        var identity = new ManifestIdentity(request.sourceManifestSha256(), request.preparedManifestSha256());
        idempotency.execute("MIGRATION_OPERATIONAL_IMPORT", request.importId(), "manifests", identity,
            ManifestIdentity.class, () -> identity);
        List<MasterRowResponse> results = new ArrayList<>();
        for (var row : request.rows()) {
            results.add(idempotency.execute("MIGRATION_OPERATIONAL_ROW_" + kind, request.importId(), row.sourceId(),
                row, MasterRowResponse.class, () -> importRow(kind, tenant, company, request.preparedManifestSha256(), row)));
        }
        return new MasterChunkResponse(request.importId(), request.chunkId(), request.sourceManifestSha256(),
            request.preparedManifestSha256(), tenant, company, kind, List.copyOf(results));
    }

    private MasterRowResponse importRow(String kind, String tenant, UUID company, String manifest, MasterRow row) {
        String marker = CatalogMasterMigrationService.sourceMarker(manifest, row);
        Class<? extends BusinessEntity> type = ENTITIES.get(kind);
        var existing = entityManager.createQuery("select e from " + type.getSimpleName()
            + " e where e.tenantId=:tenant and e.companyId=:company and e.code=:code and e.isDeleted=false", type)
            .setParameter("tenant", tenant).setParameter("company", company)
            .setParameter("code", row.payload().path("code").asText()).getResultList();
        if (existing.size() > 1) throw new ConflictException("Multiple operational masters claim this source code");
        if (!existing.isEmpty()) {
            var entity = existing.getFirst();
            if (entity.getDescription() == null || !entity.getDescription().contains(marker)
                || (row.expectedMasterId() != null && !row.expectedMasterId().equals(entity.getId()))) {
                throw new ConflictException("Operational master code exists with a different source identity");
            }
            // Recovery must not silently adopt a changed payload under a different import UUID.
            if (!Objects.equals(entity.getDescription(), row.payload().path("description").asText(null))) {
                throw new ConflictException("Operational source recovery metadata changed");
            }
            requireSameRecoveredPayload(kind, entity, row.payload());
            return new MasterRowResponse(row.sourceId(), entity.getId(), entity.getCode(), false);
        }
        if (row.expectedMasterId() != null) throw new ConflictException("Journal master missing; refusing replacement");
        validateLinks(kind, row.payload(), tenant, company);
        UUID id = switch (kind) {
            case "suppliers" -> suppliers.createLegacyMigration(convert(row.payload(), LegacySupplierMigrationPayload.class)).getId();
            case "departments" -> departments.create(convert(row.payload(), DepartmentCreateRequest.class)).getId();
            case "positions" -> positions.create(convert(row.payload(), PositionCreateRequest.class)).getId();
            case "employees" -> employees.create(convert(row.payload(), EmployeeCreateRequest.class)).getId();
            default -> throw new BadRequestException("Unsupported operational master");
        };
        var entity = entityManager.find(type, id);
        if (entity == null || !tenant.equals(entity.getTenantId()) || !company.equals(entity.getCompanyId())) {
            throw new ConflictException("Created operational master did not retain its company");
        }
        return new MasterRowResponse(row.sourceId(), id, entity.getCode(), true);
    }

    private void validate(String kind, MasterChunkRequest request) {
        if (!TYPES.containsKey(kind) || request == null || !validator.validate(request).isEmpty()
            || ZERO.equals(request.importId()) || ZERO.equals(request.chunkId())) throw new BadRequestException("Invalid operational master chunk");
        Set<String> ids = new HashSet<>();
        for (var row : request.rows()) {
            if (!TYPES.get(kind).contains(row.sourceEntityType()) || !row.sourceId().matches("[a-f0-9]{32}")
                || !ids.add(row.sourceId()) || !row.payload().isObject() || ZERO.equals(row.expectedMasterId())) {
                throw new BadRequestException("Operational masters require unique full source identities");
            }
            row.payload().fieldNames().forEachRemaining(field -> {
                if (!FIELDS.get(kind).contains(field)) throw new BadRequestException("Unsupported operational master field: " + field);
            });
            String expectedCode = "1C-" + row.sourceEntityType().toUpperCase(Locale.ROOT) + "-" + row.sourceId();
            if (!expectedCode.equals(row.payload().path("code").asText())) throw new BadRequestException("Operational code must preserve full source identity");
            String marker = CatalogMasterMigrationService.sourceMarker(request.preparedManifestSha256(), row);
            String description = row.payload().path("description").asText("");
            if (!description.endsWith(marker) || description.length() > 16000) throw new BadRequestException("Operational source marker missing or description too long");
            var matches = java.util.regex.Pattern.compile("\\[1c-mm-migration:[^\\]\\r\\n]+\\]").matcher(description);
            while (matches.find()) if (!matches.group().equals(marker)) throw new BadRequestException("Foreign migration marker");
            convert(row.payload(), PAYLOADS.get(kind));
        }
    }

    private void requireSameRecoveredPayload(String kind, BusinessEntity entity, JsonNode expected) {
        JsonNode actual = mapper.valueToTree(switch (kind) {
            case "suppliers" -> suppliers.get(entity.getId());
            case "departments" -> departments.get(entity.getId());
            case "positions" -> positions.get(entity.getId());
            case "employees" -> employees.get(entity.getId());
            default -> throw new BadRequestException("Unsupported operational master");
        });
        expected.fields().forEachRemaining(field -> {
            String key = field.getKey(); JsonNode value = field.getValue();
            boolean same;
            if (key.equals("name")) {
                String normalized = kind.equals("suppliers")
                    ? LocalizedText.ensureComplete(BusinessDisplayNamePolicy.requireHumanName("Supplier", value.asText(), entity.getCode()))
                    : com.enterprise.mm.util.BusinessNameGenerator.normalize(value.asText(), entity.getClass().getSimpleName());
                same = Objects.equals(normalized, entity.getName());
            } else if (key.equals("markedForDeletion")) {
                same = actual.path("status").asText().equals(value.asBoolean() ? "ARCHIVED" : "NEW");
            } else if (key.equals("primaryBankAccountConfirmed")) {
                same = value.asBoolean() == ((Supplier) entity).getBankAccounts().stream().anyMatch(SupplierBankAccount::isPrimaryAccount);
            } else if (key.equals("bankAccounts")) {
                same = bankEvidence(value).equals(bankEvidence(actual.path(key)));
            } else if (value.isNumber() && actual.path(key).isNumber()) {
                same = value.decimalValue().compareTo(actual.path(key).decimalValue()) == 0;
            } else {
                same = Objects.equals(value, actual.path(key)) || (value.isNull() && actual.path(key).isMissingNode());
            }
            if (!same) throw new ConflictException("Operational source recovery payload changed: " + key);
        });
    }

    private List<String> bankEvidence(JsonNode accounts) {
        List<String> result = new ArrayList<>();
        if (!accounts.isArray()) return result;
        for (JsonNode account : accounts) {
            var evidence = mapper.createObjectNode();
            for (String key : List.of("bankName", "bankMfo", "bankAccount", "bankAddress", "primaryAccount")) {
                evidence.set(key, account.has(key) ? account.get(key) : mapper.nullNode());
            }
            result.add(evidence.toString());
        }
        Collections.sort(result); return result;
    }

    private void validateLinks(String kind, JsonNode payload, String tenant, UUID company) {
        if (kind.equals("departments")) requireLink(payload, "parentDepartmentId", Department.class, tenant, company);
        if (kind.equals("positions") || kind.equals("employees")) requireLink(payload, "departmentId", Department.class, tenant, company);
        if (kind.equals("employees")) {
            requireLink(payload, "positionId", Position.class, tenant, company);
            if (payload.hasNonNull("positionId")) {
                var position = entityManager.find(Position.class, UUID.fromString(payload.path("positionId").asText()));
                UUID departmentId = payload.hasNonNull("departmentId") ? UUID.fromString(payload.path("departmentId").asText()) : null;
                if (position.getDepartment() == null || !Objects.equals(departmentId, position.getDepartment().getId())) {
                    throw new BadRequestException("Employee position must belong to the selected department");
                }
            }
        }
    }

    private void requireLink(JsonNode payload, String field, Class<? extends BusinessEntity> type, String tenant, UUID company) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) return;
        UUID id;
        try { id = UUID.fromString(node.asText()); } catch (IllegalArgumentException e) { throw new BadRequestException("Invalid operational reference"); }
        var entity = entityManager.find(type, id);
        if (entity == null || entity.isDeleted() || !tenant.equals(entity.getTenantId()) || !company.equals(entity.getCompanyId())) {
            throw new BadRequestException("Operational reference does not belong to the current company");
        }
    }

    private <T> T convert(JsonNode payload, Class<T> type) {
        try {
            T result = mapper.convertValue(payload, type);
            if (!validator.validate(result).isEmpty()) throw new BadRequestException("Operational master payload violates validation");
            return result;
        } catch (IllegalArgumentException e) { throw new BadRequestException("Invalid operational master payload"); }
    }

    private void requireScope(String tenant, UUID company) {
        if (!TenantContext.valid(tenant) || !tenant.equals(TenantContext.currentTenant()) || company == null
            || ZERO.equals(company) || CompanyContext.DEFAULT_COMPANY_ID.equals(company)
            || !company.equals(CompanyContext.currentCompany()) || companies.findByTenantIdAndIdAndIsDeletedFalse(tenant, company).isEmpty()) {
            throw new BadRequestException("Operational migration requires an explicit nondefault company in the current tenant");
        }
    }

    private record ManifestIdentity(String sourceManifestSha256, String preparedManifestSha256) {}
}
