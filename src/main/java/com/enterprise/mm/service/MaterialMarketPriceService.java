package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialMarketPrice;
import com.enterprise.mm.enums.MaterialMarketPriceSourceType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialMarketPriceMapper;
import com.enterprise.mm.payload.MaterialMarketPriceCreateRequest;
import com.enterprise.mm.payload.MaterialMarketPriceResponse;
import com.enterprise.mm.payload.MaterialMarketPriceUpdateRequest;
import com.enterprise.mm.payload.MaterialMarketPriceSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.repository.MaterialMarketPriceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class MaterialMarketPriceService {
    private static final String DRAFT = "DRAFT";
    private static final String APPROVED = "APPROVED";
    private static final String APPROVED_SCOPE_UNIQUE_INDEX =
        "uk_material_market_prices_approved_scope";
    private static final String APPROVED_SCOPE_CONFLICT_MESSAGE =
        "An approved market price already exists for the same material, warehouse scope, currency, and effective date";
    private static final String APPROVED_IMMUTABLE_MESSAGE =
        "APPROVED market price is immutable; create a new effective-dated draft";
    private final String entityName = "MaterialMarketPrice";
    private final MaterialMarketPriceRepository repository;
    private final MaterialMarketPriceMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final AuditService auditService;
    private final MaterialCostObservationService materialCostObservationService;



    @Transactional(readOnly = true)
    public BigDecimal approvedMarketUnitPrice(UUID materialId, UUID warehouseId, LocalDate asOfDate) {
        return effectiveApproved(materialId, warehouseId, asOfDate).getMarketUnitPrice();
    }

    @Transactional(readOnly = true)
    public BigDecimal approvedNetRealizableUnitPrice(UUID materialId, UUID warehouseId, LocalDate asOfDate) {
        MaterialMarketPrice price = effectiveApproved(materialId, warehouseId, asOfDate);
        BigDecimal net = nvl(price.getNetRealizableUnitPrice());
        return net.signum() > 0 ? net : nvl(price.getMarketUnitPrice());
    }

    private MaterialMarketPrice effectiveApproved(UUID materialId, UUID warehouseId, LocalDate asOfDate) {
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        return repository.findEffectiveApproved(TenantContext.currentTenant(), CompanyContext.currentCompany(), materialId, warehouseId, date)
            .stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException("Approved market price not found for material"));
    }

    @Transactional
    public MaterialMarketPriceResponse approve(UUID id) {
        MaterialMarketPrice entity = findEntity(id);
        if (!DRAFT.equalsIgnoreCase(entity.getStatus())) {
            throw new BusinessException("Only DRAFT market prices can be approved");
        }
        String actor = currentActor();
        if (repository.existsApprovedForScopeAndEffectiveDate(
            entity.getTenantId(),
            entity.getCompanyId(),
            entity.getMaterialId(),
            entity.getWarehouseId(),
            entity.getCurrencyCode(),
            entity.getEffectiveDate(),
            entity.getId()
        )) {
            throw new ConflictException(APPROVED_SCOPE_CONFLICT_MESSAGE);
        }
        entity.setStatus(APPROVED);
        entity.setApprovedAt(LocalDateTime.now());
        entity.setApprovedBy(actor);
        MaterialMarketPrice saved = saveAndFlushWithApprovedScopeConflict(entity);
        if (materialCostObservationService != null) {
            materialCostObservationService.recordMarketPrice(
                saved.getMaterialId(),
                saved.getMarketUnitPrice(),
                saved.getCurrencyCode(),
                saved.getId(),
                saved.getCode(),
                saved.getEffectiveDate()
            );
        }
        auditService.log(entityName, saved.getId(), "APPROVE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<MaterialMarketPriceResponse> search(MaterialMarketPriceSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.warehouseId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialMarketPriceResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public MaterialMarketPriceResponse create(MaterialMarketPriceCreateRequest dto) {
        MaterialMarketPrice entity = mapper.toEntity(dto);
        beforeCreate(entity);
        MaterialMarketPrice saved = saveAndFlushWithApprovedScopeConflict(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialMarketPriceResponse update(UUID id, MaterialMarketPriceUpdateRequest dto) {
        MaterialMarketPrice entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity);
        MaterialMarketPrice saved = saveAndFlushWithApprovedScopeConflict(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        MaterialMarketPrice entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutable(entity);
        entity.softDelete();
        saveAndFlushWithApprovedScopeConflict(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public MaterialMarketPriceResponse restore(UUID id) {
        MaterialMarketPrice entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        MaterialMarketPrice saved = saveAndFlushWithApprovedScopeConflict(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    private void beforeCreate(MaterialMarketPrice entity) {
        normalize(entity);
        entity.setStatus(DRAFT);
        entity.setApprovedBy(null);
        entity.setApprovedAt(null);
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

    private void beforeUpdate(MaterialMarketPrice entity) {
        normalize(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private void normalize(MaterialMarketPrice entity) {
        materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        entity.setMarketUnitPrice(nvl(entity.getMarketUnitPrice()));
        entity.setNetRealizableUnitPrice(nvl(entity.getNetRealizableUnitPrice()));
        if (entity.getMarketUnitPrice().signum() <= 0) {
            throw new BusinessException("marketUnitPrice must be greater than zero");
        }
        if (entity.getNetRealizableUnitPrice().signum() < 0) {
            throw new BusinessException("netRealizableUnitPrice cannot be negative");
        }
        entity.setCurrencyCode(entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank() ? "UZS" : entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        if (entity.getCurrencyCode().length() != 3) {
            throw new BusinessException("currencyCode must be a 3-letter ISO code");
        }
        if (entity.getEffectiveDate() == null) {
            entity.setEffectiveDate(LocalDate.now());
        }
        entity.setSourceType(normalizeSourceType(entity.getSourceType()));
    }

    private String normalizeSourceType(String value) {
        if (value == null || value.isBlank()) {
            return MaterialMarketPriceSourceType.MANUAL_ENTRY.name();
        }
        try {
            return MaterialMarketPriceSourceType.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unsupported material market price sourceType: " + value);
        }
    }

    private void requireMutable(MaterialMarketPrice entity) {
        if (APPROVED.equalsIgnoreCase(entity.getStatus())) {
            throw new BusinessException(APPROVED_IMMUTABLE_MESSAGE);
        }
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null ? null : authentication.getName();
        if (authentication == null
            || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken
            || username == null
            || username.isBlank()
            || "anonymousUser".equalsIgnoreCase(username)) {
            throw new AccessDeniedException("Authenticated user is required to approve a market price");
        }
        return username;
    }

    private MaterialMarketPrice saveAndFlushWithApprovedScopeConflict(MaterialMarketPrice entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            if (causedByConstraint(ex, APPROVED_SCOPE_UNIQUE_INDEX)) {
                throw new ConflictException(APPROVED_SCOPE_CONFLICT_MESSAGE);
            }
            throw ex;
        }
    }

    private boolean causedByConstraint(Throwable throwable, String constraintName) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    private MaterialMarketPrice findEntity(UUID id) {
        MaterialMarketPrice entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    protected boolean companyScoped() {
        return true;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
