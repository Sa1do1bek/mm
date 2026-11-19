package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ContractMaterialLinkMigrationService {
    private static final UUID ZERO = new UUID(0, 0);
    private static final List<BigDecimal> FACTORS = List.of(BigDecimal.ONE, new BigDecimal("0.001"), new BigDecimal("1000"));
    private static final Set<String> FIELDS = Set.of("materialId", "materialSourceId", "materialSourceEntityType",
        "expectedMaterialCode", "expectedVersion", "expectedContractId", "expectedMaterialGroupId",
        "expectedContractUnitOfMeasureId", "expectedStockUnitOfMeasureId", "contractQuantityPerStockQuantity",
        "originalPreparedManifestSha256", "materialPreparedManifestSha256", "evidence");
    private final CompanyRepository companies;
    private final ContractMaterialLinkRepository links;
    private final StockMovementRepository movements;
    private final InventoryScopeLockService locks;
    private final ExactOpeningImportGuard openingGuard;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final Validator validator;

    @Transactional
    public MasterChunkResponse importChunk(String tenant, UUID company, MasterChunkRequest request) {
        requireScope(tenant, company);
        validate(request);
        locks.lock(company, List.of());
        openingGuard.assertOperationsAllowed(company);
        return idempotency.execute("MIGRATION_CONTRACT_MATERIAL_LINK_CHUNK", request.importId(), request.chunkId().toString(),
            request, MasterChunkResponse.class, () -> {
                var identity = new ManifestIdentity(request.sourceManifestSha256(), request.preparedManifestSha256());
                idempotency.execute("MIGRATION_CONTRACT_MATERIAL_LINK_IMPORT", request.importId(), "manifests", identity,
                    ManifestIdentity.class, () -> identity);
                List<MasterRowResponse> result = new ArrayList<>();
                for (var row : request.rows()) result.add(idempotency.execute("MIGRATION_CONTRACT_MATERIAL_LINK_ROW", request.importId(),
                    row.sourceEntityType() + ":" + row.sourceId() + ":" + uuid(row.payload(), "materialId"), row,
                    MasterRowResponse.class, () -> insert(tenant, company, request, row)));
                em.flush();
                return new MasterChunkResponse(request.importId(), request.chunkId(), request.sourceManifestSha256(),
                    request.preparedManifestSha256(), tenant, company, "material-links", List.copyOf(result));
            });
    }

    @Transactional(readOnly = true)
    public List<ContractMaterialLink> links(String tenant, UUID company, UUID specificationId) {
        requireScope(tenant, company);
        ContractSpecification spec = em.find(ContractSpecification.class, specificationId);
        if (spec == null || spec.isDeleted() || !tenant.equals(spec.getTenantId()) || !company.equals(spec.getCompanyId()))
            throw new NotFoundException("Contract specification not found");
        return links.findByTenantIdAndCompanyIdAndSpecificationIdOrderByCreatedAtAscIdAsc(tenant, company, specificationId);
    }

    private MasterRowResponse insert(String tenant, UUID company, MasterChunkRequest request, MasterRow row) {
        JsonNode p = row.payload();
        ContractSpecification spec = requireActive(ContractSpecification.class, row.expectedMasterId(), tenant, company, LockModeType.PESSIMISTIC_WRITE);
        String code = "1C-" + row.sourceEntityType().toUpperCase(Locale.ROOT) + "-" + row.sourceId();
        if (!code.equals(spec.getCode()) || spec.getDescription() == null
            || !spec.getDescription().endsWith(marker(p.path("originalPreparedManifestSha256").asText(), row.sourceEntityType(), row.sourceId()))
            || !Objects.equals(spec.getVersion(), p.path("expectedVersion").longValue())
            || !Objects.equals(spec.getContractId(), uuid(p, "expectedContractId"))
            || !Objects.equals(spec.getMaterialGroupId(), uuid(p, "expectedMaterialGroupId"))
            || !Objects.equals(spec.getUnitOfMeasureId(), uuid(p, "expectedContractUnitOfMeasureId"))
            || spec.getMaterialId() != null || spec.getLineType() != ProcurementLineType.MATERIAL
            || spec.getQuantityLimit() == null || spec.getQuantityLimit().signum() <= 0
            || spec.getPrice() == null || spec.getPrice().signum() < 0) {
            throw new ConflictException("Material link target changed or does not match the imported source specification");
        }
        requireActive(Contract.class, spec.getContractId(), tenant, company, LockModeType.PESSIMISTIC_READ);
        requireActive(MaterialGroup.class, spec.getMaterialGroupId(), tenant, company, LockModeType.PESSIMISTIC_READ);
        Material material = requireActive(Material.class, uuid(p, "materialId"), tenant, company, LockModeType.PESSIMISTIC_READ);
        if (material.getMaterialType() != MaterialType.TMB || !p.path("expectedMaterialCode").asText().equals(material.getCode())
            || !Objects.equals(material.getUnitOfMeasureId(), uuid(p, "expectedStockUnitOfMeasureId"))
            || material.getDescription() == null || !material.getDescription().contains(marker(p.path("materialPreparedManifestSha256").asText(),
                p.path("materialSourceEntityType").asText(), p.path("materialSourceId").asText())))
            throw new ConflictException("Material link does not match the active imported source material");
        UnitOfMeasure contractUnit = requireActive(UnitOfMeasure.class, spec.getUnitOfMeasureId(), tenant, company, LockModeType.PESSIMISTIC_READ);
        UnitOfMeasure stockUnit = requireActive(UnitOfMeasure.class, material.getUnitOfMeasureId(), tenant, company, LockModeType.PESSIMISTIC_READ);
        BigDecimal factor = factor(p);
        if (factor.compareTo(ContractMaterialLinkUnits.factor(contractUnit, stockUnit, p.path("evidence"))) != 0)
            throw new BadRequestException("Supplied conversion factor differs from the physical target units");
        JsonNode contractSnapshot = mapper.valueToTree(ContractMaterialLinkUnits.snapshot(contractUnit));
        JsonNode stockSnapshot = mapper.valueToTree(ContractMaterialLinkUnits.snapshot(stockUnit));
        var existing = links.findByTenantIdAndCompanyIdAndSpecificationIdAndMaterialId(tenant, company, spec.getId(), material.getId());
        if (existing.isPresent()) {
            ContractMaterialLink link = existing.get();
            if (!request.sourceManifestSha256().equals(link.getSourceManifestSha256())
                || !request.preparedManifestSha256().equals(link.getPreparedManifestSha256())
                || !row.sourceEntityType().equals(link.getSourceEntityType()) || !row.sourceId().equals(link.getSourceId())
                || !p.equals(link.getSourcePayload()) || !contractSnapshot.equals(link.getContractUnitSnapshot())
                || !stockSnapshot.equals(link.getStockUnitSnapshot())
                || !Objects.equals(material.getMaterialGroupId(), link.getStockMaterialGroupId()))
                throw new ConflictException("Existing contract material link is immutable and has different evidence or snapshots");
            return new MasterRowResponse(row.sourceId(), spec.getId(), code, false);
        }
        // Before a binding existed, every historical receipt was counted with factor one, including later reversals.
        if (factor.compareTo(BigDecimal.ONE) != 0 && movements.existsByTenantIdAndCompanyIdAndContractSpecificationIdAndMaterialIdAndMovementType(
            tenant, company, spec.getId(), material.getId(), MovementType.RECEIPT))
            throw new ConflictException("A new conversion factor cannot reinterpret historical contract receipts");
        var link = new ContractMaterialLink();
        link.setTenantId(tenant); link.setCompanyId(company); link.setSpecificationId(spec.getId()); link.setMaterialId(material.getId());
        link.setContractId(spec.getContractId()); link.setContractMaterialGroupId(spec.getMaterialGroupId()); link.setStockMaterialGroupId(material.getMaterialGroupId());
        link.setContractUnitOfMeasureId(contractUnit.getId()); link.setStockUnitOfMeasureId(stockUnit.getId());
        link.setContractQuantityPerStockQuantity(factor); link.setContractUnitSnapshot(contractSnapshot); link.setStockUnitSnapshot(stockSnapshot);
        link.setSourceManifestSha256(request.sourceManifestSha256()); link.setPreparedManifestSha256(request.preparedManifestSha256());
        link.setSourceEntityType(row.sourceEntityType()); link.setSourceId(row.sourceId()); link.setMaterialSourceId(p.path("materialSourceId").asText());
        link.setEvidence(p.path("evidence").deepCopy()); link.setSourcePayload(p.deepCopy()); link.setCreatedAt(LocalDateTime.now());
        em.persist(link);
        audit.logInCurrentTransaction("ContractMaterialLink", link.getId(), "MIGRATION_BIND", code + " -> " + material.getCode(), null, link);
        return new MasterRowResponse(row.sourceId(), spec.getId(), code, true);
    }

    private void validate(MasterChunkRequest request) {
        if (request == null || !validator.validate(request).isEmpty() || ZERO.equals(request.importId()) || ZERO.equals(request.chunkId()))
            throw new BadRequestException("Invalid contract material link chunk");
        Set<String> identities = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (var row : request.rows()) {
            JsonNode p = row.payload();
            if (!Set.of("Document9197Materials", "Document9197Goods").contains(row.sourceEntityType()) || !row.sourceId().matches("[a-f0-9]{32}")
                || row.expectedMasterId() == null || ZERO.equals(row.expectedMasterId()) || !p.isObject() || p.size() != FIELDS.size())
                throw new BadRequestException("Invalid material link source identity or payload fields");
            p.fieldNames().forEachRemaining(field -> { if (!FIELDS.contains(field)) throw new BadRequestException("Unsupported material link field: " + field); });
            for (String field : List.of("materialId", "expectedContractId", "expectedMaterialGroupId", "expectedContractUnitOfMeasureId", "expectedStockUnitOfMeasureId")) uuid(p, field);
            if (!identities.add(row.sourceEntityType() + ":" + row.sourceId() + ":" + uuid(p, "materialId"))
                || !targets.add(row.expectedMasterId() + ":" + uuid(p, "materialId")))
                throw new BadRequestException("Duplicate contract material link in chunk");
            if (!p.path("expectedVersion").isIntegralNumber() || !p.path("expectedVersion").canConvertToLong() || p.path("expectedVersion").longValue() < 0
                || !Set.of("Catalog54", "Catalog55", "Catalog5876").contains(p.path("materialSourceEntityType").asText())
                || !p.path("materialSourceId").asText().matches("[a-f0-9]{32}")
                || !p.path("originalPreparedManifestSha256").asText().matches("[a-f0-9]{64}")
                || !p.path("materialPreparedManifestSha256").asText().matches("[a-f0-9]{64}")
                || !p.path("expectedMaterialCode").isTextual() || p.path("expectedMaterialCode").asText().isBlank() || p.path("expectedMaterialCode").asText().length() > 80
                || !p.path("evidence").isObject() || p.path("evidence").isEmpty() || p.path("evidence").toString().length() > 32768)
                throw new BadRequestException("Material link requires exact version, source markers, material code and review evidence");
            factor(p);
        }
    }

    private static BigDecimal factor(JsonNode p) {
        JsonNode value = p.path("contractQuantityPerStockQuantity");
        if ((!value.isTextual() && !value.isNumber()) || !value.asText().matches("[0-9]{1,4}(\\.[0-9]{1,7})?"))
            throw new BadRequestException("Invalid contract quantity conversion factor");
        BigDecimal factor = new BigDecimal(value.asText());
        if (FACTORS.stream().noneMatch(allowed -> allowed.compareTo(factor) == 0))
            throw new BadRequestException("Unsupported contract quantity conversion factor");
        return factor.stripTrailingZeros();
    }
    private static UUID uuid(JsonNode p, String field) {
        try {
            String raw = p.path(field).asText(); UUID value = UUID.fromString(raw);
            if (!p.path(field).isTextual() || !value.toString().equals(raw) || ZERO.equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException ex) { throw new BadRequestException("Material link requires " + field); }
    }
    private <T extends BusinessEntity> T requireActive(Class<T> type, UUID id, String tenant, UUID company, LockModeType lock) {
        T entity = em.find(type, id, lock);
        if (entity == null || entity.isDeleted() || !"ACTIVE".equals(entity.getStatus()) || !tenant.equals(entity.getTenantId()) || !company.equals(entity.getCompanyId()))
            throw new ConflictException("Material link requires an ACTIVE scoped " + type.getSimpleName());
        return entity;
    }
    private void requireScope(String tenant, UUID company) {
        if (!TenantContext.valid(tenant) || !tenant.equals(TenantContext.currentTenant()) || company == null || ZERO.equals(company)
            || CompanyContext.DEFAULT_COMPANY_ID.equals(company) || !company.equals(CompanyContext.currentCompany())
            || companies.findByTenantIdAndIdAndIsDeletedFalse(tenant, company).isEmpty())
            throw new BadRequestException("Contract material links require an explicit company in the current tenant");
    }
    static String marker(String manifest, String type, String source) { return "[1c-mm-migration:" + manifest + ":" + type + ":" + source + "]"; }
    private record ManifestIdentity(String sourceManifestSha256, String preparedManifestSha256) {}
}
