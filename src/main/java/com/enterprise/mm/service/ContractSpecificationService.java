package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ContractSpecificationResponse;
import com.enterprise.mm.payload.ContractSpecificationUpdateRequest;
import com.enterprise.mm.payload.ContractSpecificationCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ContractSpecificationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ContractSpecificationSearchRequest;

@Service
@RequiredArgsConstructor
public class ContractSpecificationService {
    private final ContractSpecificationRepository repository;
    private final ContractSpecificationMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final ProcurementLineValidationService procurementLineValidationService;
    private final String entityName = "ContractSpecification";
    private final AuditService auditService;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final jakarta.persistence.EntityManager em;
    @Transactional(readOnly = true)
    public Page<ContractSpecificationResponse> search(ContractSpecificationSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ContractSpecification.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ContractSpecificationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ContractSpecificationResponse create(ContractSpecificationCreateRequest dto) {
        ContractSpecification entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ContractSpecification saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ContractSpecificationResponse createWithSourcePurchaseRequestItem(
        ContractSpecificationCreateRequest dto,
        UUID sourcePurchaseRequestItemId
    ) {
        ContractSpecification entity = mapper.toEntity(dto);
        entity.setSourcePurchaseRequestItemId(sourcePurchaseRequestItemId);
        applySourcePurchaseRequestSnapshot(entity, sourcePurchaseRequestItemId);
        beforeCreate(entity, dto);
        ContractSpecification saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ContractSpecificationResponse update(UUID id, ContractSpecificationUpdateRequest dto) {
        ContractSpecification entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        String previousStatus = entity.getStatus();
        mapper.updateEntity(entity, dto);
        if (dto.getStatus() == null) entity.setStatus(previousStatus);
        beforeUpdate(entity, dto);
        ContractSpecification saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ContractSpecification entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ContractSpecificationResponse restore(UUID id) {
        ContractSpecification entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ContractSpecification saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ContractSpecification findEntity(UUID id) {
        ContractSpecification entity = repository.findByIdAndIsDeletedFalse(id)
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
        return entity;
    }
    protected void beforeCreate(ContractSpecification entity, ContractSpecificationCreateRequest dto) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        validateReferences(entity);
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
    }
    protected void beforeUpdate(ContractSpecification entity, ContractSpecificationUpdateRequest dto) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        validateReferences(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void validateReferences(ContractSpecification entity) {
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL && entity.getServiceLimit() == null) {
            entity.setServiceLimit(java.math.BigDecimal.ZERO);
        }
        requireScopedReference(com.enterprise.mm.entity.Contract.class, entity.getContractId(), "contractId");
        if (entity.getUnitOfMeasureId() != null) {
            requireScopedReference(com.enterprise.mm.entity.UnitOfMeasure.class, entity.getUnitOfMeasureId(), "unitOfMeasureId");
        }
        if (entity.getMaterialGroupId() != null) {
            if (entity.getLineType() != com.enterprise.mm.enums.ProcurementLineType.MATERIAL || entity.getMaterialId() != null) {
                throw new BusinessException("Material group requires a MATERIAL line without an exact material");
            }
            requireScopedReference(com.enterprise.mm.entity.MaterialGroup.class, entity.getMaterialGroupId(), "materialGroupId");
            requireScopedReference(com.enterprise.mm.entity.UnitOfMeasure.class, entity.getUnitOfMeasureId(), "unitOfMeasureId");
            if (entity.getQuantityLimit() == null || entity.getQuantityLimit().signum() <= 0) {
                throw new BusinessException("Group contract quantity must be positive");
            }
            if (entity.getPrice() == null || entity.getPrice().signum() < 0) {
                throw new BusinessException("Group contract price must be nonnegative");
            }
            if (entity.getStatus() == null || entity.getStatus().isBlank() || "ACTIVE".equals(entity.getStatus())) {
                var group = em.find(com.enterprise.mm.entity.MaterialGroup.class, entity.getMaterialGroupId());
                var unit = em.find(com.enterprise.mm.entity.UnitOfMeasure.class, entity.getUnitOfMeasureId());
                if (!"ACTIVE".equals(group.getStatus()) || !"ACTIVE".equals(unit.getStatus()))
                    throw new BusinessException("Active group specification requires an ACTIVE group and unit");
            }
            return;
        }
        procurementLineValidationService.validateLine(entity.getLineType().name(), entity.getMaterialId(),
            entity.getServiceDescription(), entity.getQuantityLimit(), "contractSpecification");
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            var material = materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
            if (!CompanyContext.currentCompany().equals(material.getCompanyId())
                || (entity.getUnitOfMeasureId() != null && !entity.getUnitOfMeasureId().equals(material.getUnitOfMeasureId()))) {
                throw new BusinessException("Material must belong to the current company and match the contract unit");
            }
        }
        materialTypeSnapshotService.applySnapshot(entity);
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T requireScopedReference(Class<T> type, UUID id, String field) {
        T value = id == null ? null : em.find(type, id);
        if (value == null || value.isDeleted() || !TenantContext.currentTenant().equals(value.getTenantId())
            || !CompanyContext.currentCompany().equals(value.getCompanyId())) {
            throw new BusinessException(field + " must reference a record in the current company");
        }
        return value;
    }

    protected boolean companyScoped() {
        return true;
    }

    private void applySourcePurchaseRequestSnapshot(ContractSpecification entity, UUID sourcePurchaseRequestItemId) {
        if (sourcePurchaseRequestItemId == null) {
            return;
        }
        PurchaseRequestItem source = purchaseRequestItemRepository.findByIdAndIsDeletedFalse(sourcePurchaseRequestItemId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("PurchaseRequestItem not found: " + sourcePurchaseRequestItemId));
        if (source.getMaterialTypeSnapshot() == null) {
            return;
        }
        if (entity.getMaterialTypeSnapshot() != null && entity.getMaterialTypeSnapshot() != source.getMaterialTypeSnapshot()) {
            throw new BusinessException(
                "PROCUREMENT_MATERIAL_TYPE_SNAPSHOT_MISMATCH: contractSpecification snapshot does not match purchase request item snapshot",
                "material.type.snapshot_mismatch",
                "contractSpecification",
                entity.getMaterialTypeSnapshot(),
                source.getMaterialTypeSnapshot()
            );
        }
        entity.setMaterialTypeSnapshot(source.getMaterialTypeSnapshot());
    }
}
