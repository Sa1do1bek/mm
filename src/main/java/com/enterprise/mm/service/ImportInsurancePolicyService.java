package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ImportInsurancePolicyResponse;
import com.enterprise.mm.payload.ImportInsurancePolicyUpdateRequest;
import com.enterprise.mm.payload.ImportInsurancePolicyCreateRequest;
import com.enterprise.mm.payload.LandedCostComponentCreateRequest;
import com.enterprise.mm.payload.LandedCostComponentResponse;
import com.enterprise.mm.payload.ImportInsurancePolicySearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ImportInsurancePolicyMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ImportInsuranceStatus;
import com.enterprise.mm.enums.LandedCostAllocationMethod;
import com.enterprise.mm.entity.ImportInsurancePolicy;
import com.enterprise.mm.repository.ImportInsurancePolicyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImportInsurancePolicyService {
    private final String entityName = "ImportInsurancePolicy";
    private final ImportInsurancePolicyRepository repository;
    private final ImportInsurancePolicyMapper mapper;
    private final SupplyLogisticsGuard guard;
    private final LandedCostComponentService landedCostComponentService;
    private final AuditService auditService;

    protected void beforeCreate(ImportInsurancePolicy entity, ImportInsurancePolicyCreateRequest dto) {
        normalize(entity);
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

        if (entity.getPolicyNumber() == null || entity.getPolicyNumber().isBlank()) {
            entity.setPolicyNumber(entity.getCode());
        }
        ensureUnique(entity);
        entity.setStatus(ImportInsuranceStatus.DRAFT.name());
    }
    protected void beforeUpdate(ImportInsurancePolicy entity, ImportInsurancePolicyUpdateRequest dto) {
        requireEditable(entity);
        normalize(entity);
        validate(entity);
        ensureUnique(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void requireEditable(ImportInsurancePolicy entity) {
        if (!ImportInsuranceStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT insurance policy can be edited");
        }
    }
    @Transactional
    public ImportInsurancePolicyResponse activate(UUID id) {
        ImportInsurancePolicy policy = findEntity(id);
        if (!ImportInsuranceStatus.DRAFT.name().equals(policy.getStatus())) {
            throw new BusinessException("Only DRAFT insurance policy can be activated");
        }
        LandedCostComponentResponse component = createPremiumComponent(policy);
        if (component.getId() != null) {
            policy.setLandedCostComponent(null);
            policy.setLandedCostComponentId(component.getId());
        }
        policy.setActivatedAt(LocalDateTime.now());
        policy.setStatus(ImportInsuranceStatus.ACTIVE.name());
        auditService.log("ImportInsurancePolicy", id, "ACTIVATE", policy.getPolicyNumber(), null, null);
        return mapper.toResponse(repository.save(policy));
    }
    @Transactional
    public ImportInsurancePolicyResponse registerClaim(UUID id) {
        ImportInsurancePolicy policy = findEntity(id);
        if (!ImportInsuranceStatus.ACTIVE.name().equals(policy.getStatus())) {
            throw new BusinessException("Only ACTIVE insurance policy can register a claim");
        }
        policy.setStatus(ImportInsuranceStatus.CLAIMED.name());
        policy.setClaimedAt(LocalDateTime.now());
        auditService.log("ImportInsurancePolicy", id, "CLAIM", policy.getPolicyNumber(), null, null);
        return mapper.toResponse(repository.save(policy));
    }
    @Transactional
    public ImportInsurancePolicyResponse close(UUID id) {
        ImportInsurancePolicy policy = findEntity(id);
        if (!ImportInsuranceStatus.ACTIVE.name().equals(policy.getStatus()) && !ImportInsuranceStatus.CLAIMED.name().equals(policy.getStatus())) {
            throw new BusinessException("Only ACTIVE or CLAIMED insurance policy can be closed");
        }
        policy.setStatus(ImportInsuranceStatus.CLOSED.name());
        policy.setClosedAt(LocalDateTime.now());
        auditService.log("ImportInsurancePolicy", id, "CLOSE", policy.getPolicyNumber(), null, null);
        return mapper.toResponse(repository.save(policy));
    }
    @Transactional
    public ImportInsurancePolicyResponse cancel(UUID id) {
        ImportInsurancePolicy policy = findEntity(id);
        if (ImportInsuranceStatus.ACTIVE.name().equals(policy.getStatus()) || ImportInsuranceStatus.CLAIMED.name().equals(policy.getStatus()) || ImportInsuranceStatus.CLOSED.name().equals(policy.getStatus())) {
            throw new BusinessException("Active, claimed or closed insurance policy cannot be cancelled");
        }
        policy.setStatus(ImportInsuranceStatus.CANCELLED.name());
        auditService.log("ImportInsurancePolicy", id, "CANCEL", policy.getPolicyNumber(), null, null);
        return mapper.toResponse(repository.save(policy));
    }
    private LandedCostComponentResponse createPremiumComponent(ImportInsurancePolicy policy) {
        if (policy.getLandedCostComponentId() != null) {
            throw new BusinessException("Insurance premium is already posted to landed cost");
        }
        if (policy.getPremiumAmount().signum() <= 0) {
            throw new BusinessException("Insurance premium amount must be greater than zero");
        }
        LandedCostComponentCreateRequest componentRequest = new LandedCostComponentCreateRequest();
        componentRequest.setName("Insurance " + policy.getPolicyNumber());
        componentRequest.setImportDeliveryId(policy.getImportDeliveryId());
        componentRequest.setComponentType("INSURANCE");
        componentRequest.setAmount(policy.getPremiumAmount());
        componentRequest.setCurrencyCode(policy.getCurrencyCode());
        componentRequest.setAllocationMethod(policy.getAllocationMethod());
        componentRequest.setSupplierId(policy.getInsurerSupplierId());
        componentRequest.setInvoiceNumber(policy.getPolicyNumber());
        componentRequest.setInvoiceDate(policy.getValidFrom() == null ? LocalDate.now() : policy.getValidFrom());
        componentRequest.setIncludedInInventoryCost(policy.isIncludedInInventoryCost());
        return landedCostComponentService.post(landedCostComponentService.create(componentRequest).getId());
    }
    private void normalize(ImportInsurancePolicy entity) {
        entity.setImportDelivery(entity.getImportDelivery() != null ? entity.getImportDelivery() : guard.importDelivery(entity.getImportDeliveryId()));
        if (entity.getInsurerSupplierId() != null || entity.getInsurerSupplier() != null) {
            entity.setInsurerSupplier(entity.getInsurerSupplier() != null ? entity.getInsurerSupplier() : guard.supplier(entity.getInsurerSupplierId()));
        }
        if (entity.getPolicyNumber() != null) {
            entity.setPolicyNumber(entity.getPolicyNumber().trim());
        }
        entity.setCoverageType(normalizeText(entity.getCoverageType(), "CARGO_ALL_RISKS"));
        entity.setCurrencyCode(normalizeToken(entity.getCurrencyCode(), "UZS"));
        entity.setAllocationMethod(normalizeEnum(entity.getAllocationMethod(), "VALUE", enumNames(LandedCostAllocationMethod.values()), "allocationMethod"));
        entity.setInsuredAmount(nvl(entity.getInsuredAmount()));
        entity.setPremiumAmount(nvl(entity.getPremiumAmount()));
        entity.setDeductibleAmount(nvl(entity.getDeductibleAmount()));
    }
    private void validate(ImportInsurancePolicy entity) {
        if (entity.getInsuredAmount().signum() < 0 || entity.getPremiumAmount().signum() < 0 || entity.getDeductibleAmount().signum() < 0) {
            throw new BusinessException("Insurance amounts cannot be negative");
        }
        if (entity.getValidFrom() != null && entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw new BusinessException("validTo must be greater than or equal to validFrom");
        }
    }
    private void ensureUnique(ImportInsurancePolicy entity) {
        if (entity.getPolicyNumber() == null || entity.getPolicyNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndPolicyNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getPolicyNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Insurance policy number already exists");
            });
    }
    private String normalizeEnum(String value, String fallback, String[] allowed, String field) {
        String normalized = normalizeToken(value, fallback);
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException("Unsupported " + field + ": " + value);
    }
    private String normalizeToken(String value, String fallback) {
        String token = value == null || value.isBlank() ? fallback : value;
        return token == null ? null : token.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<ImportInsurancePolicyResponse> search(ImportInsurancePolicySearchRequest request, Pageable pageable) {
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
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ImportInsurancePolicyResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ImportInsurancePolicyResponse create(ImportInsurancePolicyCreateRequest dto) {
        ImportInsurancePolicy entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ImportInsurancePolicy saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ImportInsurancePolicyResponse update(UUID id, ImportInsurancePolicyUpdateRequest dto) {
        ImportInsurancePolicy entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireEditable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ImportInsurancePolicy saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ImportInsurancePolicy entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ImportInsurancePolicyResponse restore(UUID id) {
        ImportInsurancePolicy entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ImportInsurancePolicy saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ImportInsurancePolicy findEntity(UUID id) {
        ImportInsurancePolicy entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected boolean companyScoped() {
        return true;
    }
}
