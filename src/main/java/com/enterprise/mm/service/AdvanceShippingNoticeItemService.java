package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.AdvanceShippingNoticeItemResponse;
import com.enterprise.mm.payload.AdvanceShippingNoticeItemUpdateRequest;
import com.enterprise.mm.payload.AdvanceShippingNoticeItemCreateRequest;
import com.enterprise.mm.payload.AdvanceShippingNoticeItemSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AdvanceShippingNoticeItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdvanceShippingNoticeItemService {
    private final AdvanceShippingNoticeItemRepository repository;
    private final AdvanceShippingNoticeItemMapper mapper;
    private final String entityName = "AdvanceShippingNoticeItem";
    private final AuditService auditService;
    private final SupplyLogisticsGuard guard;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final AdvanceShippingNoticeService asnService;
    private final RoleDataScopeService roleDataScopeService;
    @Transactional
    public AdvanceShippingNoticeItemResponse create(AdvanceShippingNoticeItemCreateRequest dto) {
        AdvanceShippingNoticeItem entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        AdvanceShippingNoticeItem savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        AdvanceShippingNoticeItemResponse saved = mapper.toResponse(savedEntity);
        asnService.recalculateTotals(saved.getAsnId());
        return saved;
    }
    @Transactional
    public AdvanceShippingNoticeItemResponse update(UUID id, AdvanceShippingNoticeItemUpdateRequest dto) {
        AdvanceShippingNoticeItem entity = findEntity(id);
        UUID asnId = entity.getAsnId();
        requireMutableParent(asnId);
        if (!Objects.equals(asnId, dto.getAsnId())) {
            throw new BusinessException("ASN item cannot be moved to another ASN");
        }
        String status = entity.getStatus();
        mapper.updateEntity(entity, dto);
        entity.setAsnId(asnId);
        entity.setStatus(status);
        beforeUpdate(entity, dto);
        AdvanceShippingNoticeItem savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "UPDATE", savedEntity.getCode(), null, null);
        AdvanceShippingNoticeItemResponse saved = mapper.toResponse(savedEntity);
        asnService.recalculateTotals(saved.getAsnId());
        return saved;
    }
    @Transactional
    public void softDelete(UUID id) {
        AdvanceShippingNoticeItem item = findEntity(id);
        UUID asnId = item.getAsnId();
        requireMutableParent(asnId);
        item.softDelete();
        repository.save(item);
        auditService.log(entityName, id, "SOFT_DELETE", item.getCode(), null, null);

        asnService.recalculateTotals(asnId);
    }
    protected void beforeCreate(AdvanceShippingNoticeItem entity, AdvanceShippingNoticeItemCreateRequest dto) {
        validate(entity);
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
    protected void beforeUpdate(AdvanceShippingNoticeItem entity, AdvanceShippingNoticeItemUpdateRequest dto) {
        validate(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void validate(AdvanceShippingNoticeItem entity) {
        var asn = guard.asn(entity.getAsnId());
        if (asn == null) {
            throw new BusinessException("asnId is required");
        }
        if (!AdvanceShippingNoticeStatus.DRAFT.name().equals(asn.getStatus())) {
            throw new BusinessException("ASN items can be changed only while ASN is DRAFT");
        }
        materialCatalogGuard.requireUsableMaterial(entity.getMaterialId(), "materialId");
        entity.setQuantity(nvl(entity.getQuantity()));
        entity.setPackageCount(nvl(entity.getPackageCount()));
        entity.setGrossWeight(nvl(entity.getGrossWeight()));
        entity.setNetWeight(nvl(entity.getNetWeight()));
        if (entity.getQuantity().signum() <= 0) {
            throw new BusinessException("ASN item quantity must be greater than zero");
        }
        if (entity.getGrossWeight().signum() < 0 || entity.getNetWeight().signum() < 0) {
            throw new BusinessException("ASN weights cannot be negative");
        }
        if (entity.getGrossWeight().signum() > 0 && entity.getNetWeight().compareTo(entity.getGrossWeight()) > 0) {
            throw new BusinessException("ASN net weight cannot exceed gross weight");
        }
        asnService.validateItemReadiness(entity);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<AdvanceShippingNoticeItemResponse> search(AdvanceShippingNoticeItemSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var pageRequest = dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable;
        var records = repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageRequest
        );
        if (!dataScope.hasDocumentScope()) {
            return records.map(mapper::toResponse);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> entity.getAsn() != null
                && entity.getAsn().getPurchaseOrder() != null
                && entity.getAsn().getPurchaseOrder().getPurchaseRequest() != null
                && roleDataScopeService.canAccessDocument(
                    entity.getAsn().getPurchaseOrder().getPurchaseRequest().getCreatedBy(),
                    entity.getAsn().getPurchaseOrder().getPurchaseRequest().getRequestedBy(),
                    entity.getAsn().getPurchaseOrder().getPurchaseRequest().getDepartmentId()
                ),
            mapper::toResponse
        );
    }
    @Transactional(readOnly = true)
    public AdvanceShippingNoticeItemResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public AdvanceShippingNoticeItemResponse restore(UUID id) {
        AdvanceShippingNoticeItem entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutableParent(entity.getAsnId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        AdvanceShippingNoticeItem saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected AdvanceShippingNoticeItem findEntity(UUID id) {
        AdvanceShippingNoticeItem entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (entity.getAsn() != null
            && entity.getAsn().getPurchaseOrder() != null
            && entity.getAsn().getPurchaseOrder().getPurchaseRequest() != null
            && !roleDataScopeService.canAccessDocument(
                entity.getAsn().getPurchaseOrder().getPurchaseRequest().getCreatedBy(),
                entity.getAsn().getPurchaseOrder().getPurchaseRequest().getRequestedBy(),
                entity.getAsn().getPurchaseOrder().getPurchaseRequest().getDepartmentId()
            )) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private void requireMutableParent(UUID asnId) {
        var asn = guard.asn(asnId);
        if (asn == null) {
            throw new BusinessException("asnId is required");
        }
        if (!AdvanceShippingNoticeStatus.DRAFT.name().equals(asn.getStatus())) {
            throw new BusinessException("ASN items can be changed only while ASN is DRAFT");
        }
    }
}
