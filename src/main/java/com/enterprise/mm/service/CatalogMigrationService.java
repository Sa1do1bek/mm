package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkRequest;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkResponse;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogRow;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogRowResponse;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.payload.MaterialResponse;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CatalogMigrationService {
    private static final UUID ZERO_UUID = new UUID(0, 0);
    private static final int ROWS_PER_FLUSH = 20;
    private final CompanyRepository companies;
    private final MaterialGroupRepository groups;
    private final UnitOfMeasureRepository units;
    private final MaterialRepository materials;
    private final MaterialService materialService;
    private final InventoryScopeLockService locks;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final EntityManager entityManager;

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER')")
    public MaterialCatalogChunkResponse importMaterials(
        String tenantId, UUID companyId, MaterialCatalogChunkRequest request
    ) {
        requireScope(tenantId, companyId);
        validateCommand(request);
        // The lock precedes the idempotency lookup so simultaneous retries cannot both execute.
        locks.lock(companyId, List.of());
        return idempotency.execute(
            "MIGRATION_MATERIAL_CATALOG_CHUNK", request.importId(), request.chunkId().toString(),
            request, MaterialCatalogChunkResponse.class,
            () -> importChunk(tenantId, companyId, request)
        );
    }

    private MaterialCatalogChunkResponse importChunk(
        String tenantId, UUID companyId, MaterialCatalogChunkRequest request
    ) {
        ManifestIdentity manifests = new ManifestIdentity(request.sourceManifestSha256(), request.preparedManifestSha256());
        idempotency.execute(
            "MIGRATION_MATERIAL_CATALOG_IMPORT", request.importId(), "manifests", manifests,
            ManifestIdentity.class, () -> manifests
        );
        // Do not rely on global ADMIN read scope: every mandatory reference must belong here.
        for (MaterialCatalogRow row : request.rows()) {
            requireReferences(tenantId, companyId, row.payload());
        }
        List<MaterialCatalogRowResponse> results = new ArrayList<>(request.rows().size());
        int completedRows = 0;
        for (MaterialCatalogRow row : request.rows()) {
            // Source-row identity also survives a client repartitioning its chunks on resume.
            results.add(idempotency.execute(
                "MIGRATION_MATERIAL_CATALOG_ROW", request.importId(), row.sourceEntityType()==null?row.sourceId():row.effectiveSourceEntityType()+":"+row.sourceId(), row,
                MaterialCatalogRowResponse.class,
                () -> importRow(tenantId, companyId, request.preparedManifestSha256(), row)
            ));
            if (++completedRows % ROWS_PER_FLUSH == 0) {
                // Flush queued row, idempotency, audit and outbox writes before detaching
                // the whole persistence context. The chunk transaction is not committed here.
                entityManager.flush();
                entityManager.clear();
            }
        }
        return new MaterialCatalogChunkResponse(
            request.importId(), request.chunkId(), request.sourceManifestSha256(), request.preparedManifestSha256(),
            tenantId, companyId, List.copyOf(results)
        );
    }

    private MaterialCatalogRowResponse importRow(
        String tenantId, UUID companyId, String preparedManifestSha256, MaterialCatalogRow row
    ) {
        String code = normalizedCode(row.payload());
        String marker = sourceMarker(preparedManifestSha256, row.sourceId()).replace(":Catalog54:",":"+row.effectiveSourceEntityType()+":");
        Material existing = materials.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(tenantId, companyId, code)
            .orElse(null);
        if (existing != null) {
            if (!Objects.equals(tenantId, existing.getTenantId()) || !Objects.equals(companyId, existing.getCompanyId())
                || !Objects.equals(code, existing.getCode()) || existing.getDescription() == null
                || !existing.getDescription().contains(marker)
                || !Objects.equals(row.payload().getUnitOfMeasureId(), existing.getUnitOfMeasureId())
                || !Objects.equals(row.payload().getMaterialGroupId(), existing.getMaterialGroupId())) {
                throw new ConflictException("Material code already exists without matching 1C source identity and references: " + code);
            }
            return new MaterialCatalogRowResponse(row.sourceId(), existing.getId(), code, false);
        }

        // Never mutate the request that IdempotencyService hashes, including its description.
        MaterialCreateRequest payload = objectMapper.convertValue(row.payload(), MaterialCreateRequest.class);
        payload.setCode(code);
        String description = payload.getDescription();
        if (description == null || !description.contains(marker)) {
            payload.setDescription(marker + (description == null || description.isBlank() ? "" : "\n" + description));
        }
        MaterialResponse created = materialService.create(payload);
        if (created == null || created.getId() == null || !Objects.equals(tenantId, created.getTenantId())
            || !Objects.equals(companyId, created.getCompanyId()) || !Objects.equals(code, created.getCode())
            || created.getDescription() == null || !created.getDescription().contains(marker)) {
            throw new ConflictException("Created material did not retain the requested company and 1C source identity");
        }
        return new MaterialCatalogRowResponse(row.sourceId(), created.getId(), code, true);
    }

    private void requireScope(String tenantId, UUID companyId) {
        if (!TenantContext.valid(tenantId) || !Objects.equals(tenantId, TenantContext.currentTenant())
            || companyId == null || companyId.equals(ZERO_UUID) || companyId.equals(CompanyContext.DEFAULT_COMPANY_ID)
            || !Objects.equals(companyId, CompanyContext.currentCompany())) {
            throw new BadRequestException("Catalog migration requires explicit tenant and a nondefault current company");
        }
        if (companies.findByTenantIdAndIdAndIsDeletedFalse(tenantId, companyId).isEmpty()) {
            throw new BadRequestException("Catalog migration company was not found in the current tenant");
        }
    }

    private void validateCommand(MaterialCatalogChunkRequest request) {
        if (request == null || !validator.validate(request).isEmpty()
            || request.importId().equals(ZERO_UUID) || request.chunkId().equals(ZERO_UUID)) {
            throw new BadRequestException("Invalid material catalog chunk; provide manifest digests and 1 to 200 source rows");
        }
        Set<String> sourceIds = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (MaterialCatalogRow row : request.rows()) {
            String code = normalizedCode(row.payload());
            if (!sourceIds.add(row.sourceId()) || !codes.add(code)) {
                throw new BadRequestException("Material catalog chunk contains duplicate source IDs or material codes");
            }
            if (row.payload().getName() == null || row.payload().getName().isBlank()) {
                throw new BadRequestException("Imported material name is required");
            }
        }
    }

    private void requireReferences(String tenantId, UUID companyId, MaterialCreateRequest payload) {
        if (payload.getMaterialGroupId() == null
            || groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, payload.getMaterialGroupId()).isEmpty()) {
            throw new BadRequestException("Imported material group must belong to the current tenant and company");
        }
        if (payload.getUnitOfMeasureId() == null
            || units.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, payload.getUnitOfMeasureId()).isEmpty()) {
            throw new BadRequestException("Imported material unit must belong to the current tenant and company");
        }
    }

    private static String normalizedCode(MaterialCreateRequest payload) {
        if (payload.getCode() == null || payload.getCode().isBlank()) {
            throw new BadRequestException("Imported material requires a stable source code");
        }
        return payload.getCode().trim().toUpperCase(Locale.ROOT);
    }

    public static String sourceMarker(String preparedManifestSha256, String sourceId) {
        return "[1c-mm-migration:" + preparedManifestSha256 + ":Catalog54:" + sourceId + "]";
    }

    private record ManifestIdentity(String sourceManifestSha256, String preparedManifestSha256) {
    }
}
