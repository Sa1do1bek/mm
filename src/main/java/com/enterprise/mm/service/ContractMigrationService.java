package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.mapper.ContractMapper;
import com.enterprise.mm.mapper.ContractAddendumMapper;
import com.enterprise.mm.mapper.ContractSpecificationMapper;
import com.enterprise.mm.repository.CompanyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Source-bound procurement records and explicit legacy lifecycle mapping. No stock or approval effects. */
@Service
@RequiredArgsConstructor
public class ContractMigrationService {
    private static final UUID ZERO = new UUID(0, 0);
    private static final Map<String, Set<String>> TYPES = Map.of(
        "contracts", Set.of("Catalog34"), "addendums", Set.of("Catalog9560"),
        "specifications", Set.of("Document9197Materials", "Document9197Goods"),
        "lines", Set.of("Document9197Materials", "Document9197Goods"));
    private static final Map<String, Class<? extends BusinessEntity>> ENTITIES = Map.of(
        "contracts", Contract.class, "addendums", ContractAddendum.class,
        "specifications", ContractSpecification.class, "lines", ContractSpecification.class);
    private static final Map<String, Class<?>> PAYLOADS = Map.of(
        "contracts", ContractCreateRequest.class, "addendums", ContractAddendumCreateRequest.class,
        "specifications", ContractSpecificationCreateRequest.class, "lines", ContractSpecificationCreateRequest.class);
    private static final Map<String, Set<String>> FIELDS = Map.of(
        "contracts", Set.of("code", "name", "status", "description", "supplierId", "contractNumber",
            "startDate", "endDate", "limitAmount", "currencyCode"),
        "addendums", Set.of("code", "name", "status", "description", "contractId", "addendumNumber",
            "addendumDate", "addendumType", "effectiveDate", "newEndDate", "limitDelta", "newLimitAmount", "reason"),
        "specifications", Set.of("code", "name", "status", "description", "contractId", "lineType",
            "materialId", "materialTypeSnapshot", "unit", "quantityLimit", "price"),
        "lines", Set.of("code", "name", "status", "description", "contractId", "lineType", "materialId",
            "materialGroupId", "unitOfMeasureId", "materialTypeSnapshot", "unit", "quantityLimit", "price",
            "originalPreparedManifestSha256", "reviewReason"));
    private static final Set<String> DECIMALS = Set.of("limitAmount", "limitDelta", "newLimitAmount", "quantityLimit", "price");
    private final CompanyRepository companies;
    private final ContractService contracts;
    private final ContractAddendumService addendums;
    private final ContractSpecificationService specifications;
    private final ContractMapper contractMapper;
    private final ContractAddendumMapper addendumMapper;
    private final ContractSpecificationMapper specificationMapper;
    private final AuditService audit;
    private final InventoryScopeLockService locks;
    private final ExactOpeningImportGuard openingGuard;
    private final IdempotencyService idempotency;
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final Validator validator;

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> capabilities(String tenant, UUID company) {
        requireScope(tenant, company);
        return Map.ofEntries(Map.entry("contractVersion", 1), Map.entry("companyId", company), Map.entry("tenantId", tenant),
            Map.entry("maximumRows", 200), Map.entry("kinds", TYPES.keySet()), Map.entry("draftOnly", true),
            Map.entry("stockEffects", false), Map.entry("approvalEffects", false), Map.entry("statusMappingVersion", 1),
            Map.entry("contractLineVersion", 1), Map.entry("contractMaterialLinkVersion", 1));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public MasterChunkResponse importChunk(String kind, String tenant, UUID company, MasterChunkRequest request) {
        requireScope(tenant, company);
        validate(kind, request);
        locks.lock(company, List.of());
        openingGuard.assertOperationsAllowed(company);
        return idempotency.execute("MIGRATION_CONTRACT_CHUNK_" + kind, request.importId(),
            request.chunkId().toString(), request, MasterChunkResponse.class, () -> {
                var identity = new ManifestIdentity(request.sourceManifestSha256(), request.preparedManifestSha256());
                idempotency.execute(kind.equals("lines") ? "MIGRATION_CONTRACT_LINE_IMPORT" : "MIGRATION_CONTRACT_IMPORT", request.importId(), "manifests", identity,
                    ManifestIdentity.class, () -> identity);
                List<MasterRowResponse> result = new ArrayList<>();
                for (var row : request.rows()) {
                    result.add(idempotency.execute("MIGRATION_CONTRACT_ROW_" + kind, request.importId(),
                        row.sourceEntityType() + ":" + row.sourceId(), row, MasterRowResponse.class,
                        () -> importRow(kind, tenant, company, request.preparedManifestSha256(), row)));
                }
                em.flush();
                return new MasterChunkResponse(request.importId(), request.chunkId(), request.sourceManifestSha256(),
                    request.preparedManifestSha256(), tenant, company, kind, List.copyOf(result));
            });
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public MasterChunkResponse importStatusChunk(String tenant, UUID company, MasterChunkRequest request) {
        requireScope(tenant, company);
        validateStatusChunk(request);
        locks.lock(company, List.of());
        openingGuard.assertOperationsAllowed(company);
        return idempotency.execute("MIGRATION_CONTRACT_STATUS_CHUNK", request.importId(),
            request.chunkId().toString(), request, MasterChunkResponse.class, () -> {
                var identity = new ManifestIdentity(request.sourceManifestSha256(), request.preparedManifestSha256());
                idempotency.execute("MIGRATION_CONTRACT_STATUS_IMPORT", request.importId(), "manifests", identity,
                    ManifestIdentity.class, () -> identity);
                List<MasterRowResponse> result = new ArrayList<>();
                for (var row : request.rows()) {
                    result.add(idempotency.execute("MIGRATION_CONTRACT_STATUS_ROW", request.importId(),
                        row.sourceId(), row, MasterRowResponse.class,
                        () -> mapStatus(tenant, company, request.preparedManifestSha256(), row)));
                }
                em.flush();
                return new MasterChunkResponse(request.importId(), request.chunkId(), request.sourceManifestSha256(),
                    request.preparedManifestSha256(), tenant, company, "statuses", List.copyOf(result));
            });
    }

    private void validateStatusChunk(MasterChunkRequest request) {
        if (request == null || !validator.validate(request).isEmpty()
            || ZERO.equals(request.importId()) || ZERO.equals(request.chunkId())) {
            throw new BadRequestException("Invalid contract status migration chunk");
        }
        Set<String> fields = Set.of("status", "expectedVersion", "originalPreparedManifestSha256",
            "registrationType", "registrationSourceId", "sourcePosted", "sourceCancelled");
        Set<String> sources = new HashSet<>();
        Set<UUID> targets = new HashSet<>();
        for (var row : request.rows()) {
            JsonNode p = row.payload();
            if (!"Catalog34".equals(row.sourceEntityType()) || !row.sourceId().matches("[a-f0-9]{32}")
                || row.expectedMasterId() == null || ZERO.equals(row.expectedMasterId())
                || !sources.add(row.sourceId()) || !targets.add(row.expectedMasterId()) || !p.isObject()) {
                throw new BadRequestException("Invalid contract status source identity");
            }
            p.fieldNames().forEachRemaining(field -> {
                if (!fields.contains(field)) throw new BadRequestException("Status mapping cannot change contract fields");
            });
            if (!Set.of("ACTIVE", "EXPIRED", "CLOSED").contains(p.path("status").asText())
                || !p.path("expectedVersion").isIntegralNumber() || !p.path("expectedVersion").canConvertToLong()
                || p.path("expectedVersion").asLong() < 0
                || !p.path("originalPreparedManifestSha256").asText().matches("[a-f0-9]{64}")) {
                throw new BadRequestException("Status mapping requires a source manifest and exact record version");
            }
            if (!"EXPIRED".equals(p.path("status").asText())) {
                if (!Set.of("Document4974", "Document9197").contains(p.path("registrationType").asText())
                    || !p.path("registrationSourceId").asText().matches("[a-f0-9]{32}")
                    || !p.path("sourcePosted").isBoolean() || !p.path("sourcePosted").booleanValue()
                    || !p.path("sourceCancelled").isBoolean()
                    || p.path("sourceCancelled").booleanValue() != "CLOSED".equals(p.path("status").asText())
                    || ("CLOSED".equals(p.path("status").asText())
                        && !"Document4974".equals(p.path("registrationType").asText()))) {
                    throw new BadRequestException("Lifecycle status requires matching posted registration evidence");
                }
            }
        }
    }

    private MasterRowResponse mapStatus(String tenant, UUID company, String manifest, MasterRow row) {
        Contract entity = em.find(Contract.class, row.expectedMasterId(), LockModeType.PESSIMISTIC_WRITE);
        String code = "1C-CATALOG34-" + row.sourceId();
        String marker = "[1c-mm-migration:" + row.payload().path("originalPreparedManifestSha256").asText()
            + ":Catalog34:" + row.sourceId() + "]";
        if (entity == null || entity.isDeleted() || !tenant.equals(entity.getTenantId())
            || !company.equals(entity.getCompanyId()) || !code.equals(entity.getCode())
            || entity.getDescription() == null || !entity.getDescription().endsWith(marker)) {
            throw new ConflictException("Status mapping target does not match the imported source contract");
        }
        if (!"DRAFT".equals(entity.getStatus())
            || !Objects.equals(entity.getVersion(), row.payload().path("expectedVersion").longValue())) {
            throw new ConflictException("Contract changed after status migration was prepared");
        }
        String target = row.payload().path("status").asText();
        LocalDate today = LocalDate.now();
        boolean future = entity.getStartDate() != null && entity.getStartDate().isAfter(today);
        boolean expired = entity.getEndDate() != null && entity.getEndDate().isBefore(today);
        boolean reversed = entity.getStartDate() != null && entity.getEndDate() != null
            && entity.getEndDate().isBefore(entity.getStartDate());
        if (("ACTIVE".equals(target) && (future || expired || reversed))
            || ("EXPIRED".equals(target) && (!expired || reversed))) {
            throw new BadRequestException("Mapped contract status conflicts with its validity dates");
        }
        var before = contractMapper.toResponse(entity);
        // Import the historical lifecycle only; supplier accreditation and usage are independent.
        entity.setStatus(target);
        audit.logInCurrentTransaction("Contract", entity.getId(), "LEGACY_STATUS_MAPPING",
            code + " -> " + target + "; mapping=" + manifest + "; evidence=" + row.payload(),
            before, contractMapper.toResponse(entity));
        return new MasterRowResponse(row.sourceId(), entity.getId(), code, false);
    }

    private MasterRowResponse importRow(String kind, String tenant, UUID company, String manifest, MasterRow row) {
        var type = ENTITIES.get(kind);
        String code = row.payload().path("code").asText();
        var existing = em.createQuery("select e from " + type.getSimpleName()
            + " e where e.tenantId=:tenant and e.companyId=:company and e.code=:code", type)
            .setParameter("tenant", tenant).setParameter("company", company).setParameter("code", code).getResultList();
        if (existing.size() > 1) throw new ConflictException("Multiple records claim this source identity");
        validateLinks(kind, row.payload(), tenant, company, manifest);
        if (!existing.isEmpty()) {
            var entity = existing.getFirst();
            if (entity.isDeleted() || !Objects.equals(entity.getDescription(), row.payload().path("description").asText())
                || (row.expectedMasterId() != null && !row.expectedMasterId().equals(entity.getId()))) {
                throw new ConflictException("Contract source identity already exists with different evidence");
            }
            if (kind.equals("lines")) validateLineRecoveryEvidence(entity.getId(), tenant, company, row.payload());
            JsonNode actual = mapper.valueToTree(response(kind, entity.getId()));
            row.payload().fields().forEachRemaining(field -> {
                String key = field.getKey(); JsonNode value = field.getValue();
                if (kind.equals("lines") && Set.of("originalPreparedManifestSha256", "reviewReason").contains(key)) return;
                if (kind.equals("lines") && key.equals("materialTypeSnapshot") && value.isNull()
                    && entity instanceof ContractSpecification spec && spec.getMaterialId() != null) {
                    value = mapper.valueToTree(em.find(Material.class, spec.getMaterialId()).getMaterialType());
                }
                boolean same;
                if (key.equals("name")) {
                    same = Objects.equals(entity.getName(),
                        LocalizedText.ensureComplete(BusinessDisplayNamePolicy.requireHumanName(type.getSimpleName(), value.asText(), code)));
                } else if (DECIMALS.contains(key) && !value.isNull() && !actual.path(key).isNull()) {
                    same = decimal(value).compareTo(decimal(actual.path(key))) == 0;
                } else {
                    same = Objects.equals(value, actual.path(key)) || (value.isNull() && actual.path(key).isMissingNode());
                }
                if (!same) throw new ConflictException("Contract source recovery payload changed: " + key);
            });
            return new MasterRowResponse(row.sourceId(), entity.getId(), code, false);
        }
        if (row.expectedMasterId() != null) throw new ConflictException("Journal record missing; refusing replacement");
        BusinessEntity created = switch (kind) {
            case "contracts" -> {
                var dto = convert(row.payload(), ContractCreateRequest.class);
                var entity = contractMapper.toEntity(dto);
                contracts.beforeCreate(entity, dto);
                yield entity;
            }
            case "addendums" -> {
                var dto = convert(row.payload(), ContractAddendumCreateRequest.class);
                var entity = addendumMapper.toEntity(dto);
                addendums.beforeCreate(entity, dto);
                yield entity;
            }
            case "lines" -> {
                var dto = convert(linePayload(row.payload()), ContractSpecificationCreateRequest.class);
                var entity = specificationMapper.toEntity(dto);
                entity.setServiceLimit(BigDecimal.ZERO);
                entity.setTenantId(tenant);
                entity.setCompanyId(company);
                entity.setName(BusinessDisplayNamePolicy.requireHumanName("ContractSpecification", entity.getName(), code));
                if (entity.getMaterialId() != null && entity.getMaterialTypeSnapshot() == null) {
                    entity.setMaterialTypeSnapshot(em.find(Material.class, entity.getMaterialId()).getMaterialType());
                }
                yield entity;
            }
            case "specifications" -> {
                var dto = convert(row.payload(), ContractSpecificationCreateRequest.class);
                var entity = specificationMapper.toEntity(dto);
                // Only material lines are imported; PostgreSQL requires a non-null service budget.
                entity.setServiceLimit(BigDecimal.ZERO);
                specifications.beforeCreate(entity, dto);
                yield entity;
            }
            default -> throw new BadRequestException("Unsupported contract migration kind");
        };
        created.setName(LocalizedText.ensureComplete(created.getName()));
        em.persist(created);
        if (!tenant.equals(created.getTenantId()) || !company.equals(created.getCompanyId())
            || !(kind.equals("lines") ? row.payload().path("status").asText() : "DRAFT").equals(created.getStatus())) throw new ConflictException("Imported record scope or status changed");
        audit.logInCurrentTransaction(type.getSimpleName(), created.getId(), kind.equals("lines") ? "LEGACY_LINE_IMPORT" : "LEGACY_IMPORT",
            code, null, kind.equals("lines")
                ? Map.of("record", specificationMapper.toResponse((ContractSpecification) created), "sourcePayload", row.payload()) : created);
        return new MasterRowResponse(row.sourceId(), created.getId(), code, true);
    }

    private Object response(String kind, UUID id) {
        return switch (kind) {
            case "contracts" -> contractMapper.toResponse(em.find(Contract.class, id));
            case "addendums" -> addendumMapper.toResponse(em.find(ContractAddendum.class, id));
            case "specifications", "lines" -> specificationMapper.toResponse(em.find(ContractSpecification.class, id));
            default -> throw new BadRequestException("Unsupported contract migration kind");
        };
    }

    private void validate(String kind, MasterChunkRequest request) {
        if (!TYPES.containsKey(kind) || request == null || !validator.validate(request).isEmpty()
            || ZERO.equals(request.importId()) || ZERO.equals(request.chunkId())) {
            throw new BadRequestException("Invalid contract migration chunk");
        }
        Set<String> keys = new HashSet<>();
        for (var row : request.rows()) {
            if (!TYPES.get(kind).contains(row.sourceEntityType()) || !row.sourceId().matches("[a-f0-9]{32}")
                || !keys.add(row.sourceEntityType() + ":" + row.sourceId()) || !row.payload().isObject()
                || ZERO.equals(row.expectedMasterId())) throw new BadRequestException("Invalid contract source identity");
            row.payload().fieldNames().forEachRemaining(field -> {
                if (!FIELDS.get(kind).contains(field)) throw new BadRequestException("Unsupported contract migration field");
            });
            String code = "1C-" + row.sourceEntityType().toUpperCase(Locale.ROOT) + "-" + row.sourceId();
            String marker = CatalogMasterMigrationService.sourceMarker(request.preparedManifestSha256(), row);
            String description = row.payload().path("description").asText("");
            if (!code.equals(row.payload().path("code").asText()) || !description.endsWith(marker)
                || description.length() > 16000 || !(kind.equals("lines") ? Set.of("DRAFT", "ACTIVE") : Set.of("DRAFT")).contains(row.payload().path("status").asText())) {
                throw new BadRequestException("Contract migration requires source evidence and DRAFT status");
            }
            if (kind.equals("lines") && description.replace(marker, "").isBlank()) {
                throw new BadRequestException("Contract line requires source evidence beyond its migration marker");
            }
            var matches = java.util.regex.Pattern.compile("\\[1c-mm-migration:[^\\]\\r\\n]+\\]").matcher(description);
            while (matches.find()) if (!matches.group().equals(marker)) throw new BadRequestException("Foreign migration marker");
            for (String field : DECIMALS) if (row.payload().hasNonNull(field)) {
                BigDecimal value = decimal(row.payload().get(field));
                int scale = field.equals("quantityLimit") ? 7 : 4;
                if (value.stripTrailingZeros().scale() > scale || value.abs().compareTo(BigDecimal.TEN.pow(15)) >= 0) {
                    throw new BadRequestException("Source decimal does not fit the target exactly");
                }
            }
            if (kind.equals("contracts")) {
                // Explicit null preserves unknown source currency; omission must not silently default to UZS.
                if (!row.payload().has("currencyCode") || !row.payload().hasNonNull("limitAmount")
                    || (row.payload().hasNonNull("currencyCode")
                        && !row.payload().path("currencyCode").asText().matches("[A-Z]{3}"))) {
                    throw new BadRequestException("Explicit contract currency and amount evidence required");
                }
            }
            if (kind.equals("addendums") && !row.payload().hasNonNull("addendumDate")) {
                throw new BadRequestException("Source addendum date required; current date must not be inferred");
            }
            if (Set.of("specifications", "lines").contains(kind) && !"MATERIAL".equals(row.payload().path("lineType").asText())) {
                throw new BadRequestException("Only source-mapped material specifications are supported");
            }
            if (kind.equals("lines")) validateLineEvidence(row.payload(), request.preparedManifestSha256());
            convert(kind.equals("lines") ? linePayload(row.payload()) : row.payload(), PAYLOADS.get(kind));
        }
    }

    private static BigDecimal decimal(JsonNode value) {
        try { return new BigDecimal(value.asText()); }
        catch (NumberFormatException ex) { throw new BadRequestException("Invalid source decimal"); }
    }

    private void validateLinks(String kind, JsonNode payload, String tenant, UUID company, String manifest) {
        if (kind.equals("lines")) { validateLineLinks(payload, tenant, company); return; }
        if (kind.equals("contracts")) requireLink(payload, "supplierId", Supplier.class, tenant, company);
        else {
            var parent = requireLink(payload, "contractId", Contract.class, tenant, company);
            if (!"DRAFT".equals(parent.getStatus()) || !parent.getCode().startsWith("1C-CATALOG34-")
                || parent.getDescription() == null
                || !parent.getDescription().contains("[1c-mm-migration:" + manifest + ":Catalog34:")) {
                throw new BadRequestException("Related records require an imported draft contract");
            }
        }
        if (kind.equals("specifications")) requireLink(payload, "materialId", Material.class, tenant, company);
    }

    private void validateLineRecoveryEvidence(UUID id, String tenant, UUID company, JsonNode payload) {
        List<String> evidence = em.createQuery("select a.newSnapshot from AuditLog a where a.entityId=:id"
            + " and a.tenantId=:tenant and a.companyId=:company and a.isDeleted=false and a.action='LEGACY_LINE_IMPORT'", String.class)
            .setParameter("id", id).setParameter("tenant", tenant).setParameter("company", company).getResultList();
        if (evidence.size() != 1) throw new ConflictException("Contract line recovery requires its original import evidence");
        try {
            if (!payload.equals(mapper.readTree(evidence.getFirst()).path("sourcePayload"))) {
                throw new ConflictException("Contract line recovery source evidence changed");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ConflictException("Contract line import evidence cannot be recovered");
        }
    }

    private JsonNode linePayload(JsonNode payload) {
        var result = ((com.fasterxml.jackson.databind.node.ObjectNode) payload).deepCopy();
        result.remove(List.of("originalPreparedManifestSha256", "reviewReason"));
        return result;
    }

    private void validateLineEvidence(JsonNode p, String manifest) {
        boolean active = "ACTIVE".equals(p.path("status").asText());
        String original = p.path("originalPreparedManifestSha256").asText();
        if (!original.matches("[a-f0-9]{64}") || original.equals(manifest)
            || !p.has("quantityLimit") || !p.has("price")
            || (!active && (!p.path("reviewReason").isTextual() || p.path("reviewReason").asText().isBlank()))
            || (p.hasNonNull("reviewReason") && (!p.path("reviewReason").isTextual() || p.path("reviewReason").asText().length() > 500))) {
            throw new BadRequestException("Contract lines require original manifest and explicit source/review evidence");
        }
        boolean material = p.hasNonNull("materialId"), group = p.hasNonNull("materialGroupId");
        if (material && group) throw new BadRequestException("A contract line cannot identify both material and group");
        if (active && ((!material && !group) || (group && !p.hasNonNull("unitOfMeasureId"))
            || !p.hasNonNull("quantityLimit") || decimal(p.get("quantityLimit")).signum() <= 0
            || !p.hasNonNull("price") || decimal(p.get("price")).signum() < 0)) {
            throw new BadRequestException("Unresolved contract line must remain DRAFT");
        }
    }

    private void validateLineLinks(JsonNode p, String tenant, UUID company) {
        Contract parent = requireLink(p, "contractId", Contract.class, tenant, company);
        String code = parent.getCode();
        if (code == null || !code.matches("1C-CATALOG34-[a-f0-9]{32}")
            || !Set.of("DRAFT", "ACTIVE", "EXPIRED", "CLOSED").contains(parent.getStatus())
            || parent.getDescription() == null || !parent.getDescription().endsWith("[1c-mm-migration:"
                + p.path("originalPreparedManifestSha256").asText() + ":Catalog34:" + code.substring(13) + "]")) {
            throw new ConflictException("Contract line parent does not match the original imported source contract");
        }
        if (p.hasNonNull("materialId")) {
            Material material = requireLink(p, "materialId", Material.class, tenant, company);
            if (p.hasNonNull("materialTypeSnapshot") && !material.getMaterialType().name().equals(p.path("materialTypeSnapshot").asText()))
                throw new BadRequestException("Material type snapshot differs from source material");
            if (p.hasNonNull("unitOfMeasureId") && !Objects.equals(material.getUnitOfMeasureId(), UUID.fromString(p.path("unitOfMeasureId").asText()))
                && "ACTIVE".equals(p.path("status").asText()))
                throw new BadRequestException("Contract line unit differs from material unit");
        }
        if (p.hasNonNull("materialGroupId")) requireLink(p, "materialGroupId", MaterialGroup.class, tenant, company);
        if (p.hasNonNull("unitOfMeasureId")) requireLink(p, "unitOfMeasureId", UnitOfMeasure.class, tenant, company);
    }

    private <T extends BusinessEntity> T requireLink(JsonNode payload, String field, Class<T> type, String tenant, UUID company) {
        UUID id;
        try { id = UUID.fromString(payload.path(field).asText()); }
        catch (IllegalArgumentException ex) { throw new BadRequestException("Required source dependency missing"); }
        T entity = em.find(type, id);
        if (entity == null || entity.isDeleted() || !tenant.equals(entity.getTenantId()) || !company.equals(entity.getCompanyId())) {
            throw new BadRequestException("Contract reference does not belong to the current company");
        }
        return entity;
    }

    private <T> T convert(JsonNode payload, Class<T> type) {
        try {
            T result = mapper.convertValue(payload, type);
            if (!validator.validate(result).isEmpty()) throw new BadRequestException("Invalid contract migration payload");
            return result;
        } catch (IllegalArgumentException ex) { throw new BadRequestException("Invalid contract migration payload"); }
    }

    private void requireScope(String tenant, UUID company) {
        if (!TenantContext.valid(tenant) || !tenant.equals(TenantContext.currentTenant()) || company == null
            || ZERO.equals(company) || CompanyContext.DEFAULT_COMPANY_ID.equals(company)
            || !company.equals(CompanyContext.currentCompany())
            || companies.findByTenantIdAndIdAndIsDeletedFalse(tenant, company).isEmpty()) {
            throw new BadRequestException("Contract migration requires an explicit company in the current tenant");
        }
    }

    private record ManifestIdentity(String sourceManifestSha256, String preparedManifestSha256) {}
}
