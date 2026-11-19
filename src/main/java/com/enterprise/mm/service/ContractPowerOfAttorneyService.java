package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ContractPowerOfAttorneyResponse;
import com.enterprise.mm.payload.ContractPowerOfAttorneyUpdateRequest;
import com.enterprise.mm.payload.ContractPowerOfAttorneyCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ContractPowerOfAttorneyMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractPowerOfAttorney;
import com.enterprise.mm.repository.ContractPowerOfAttorneyRepository;
import com.enterprise.mm.repository.ContractRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ContractPowerOfAttorneySearchRequest;

@Service
@RequiredArgsConstructor
public class ContractPowerOfAttorneyService {
    private final ContractPowerOfAttorneyRepository repository;
    private final ContractPowerOfAttorneyMapper mapper;
    private final String entityName = "ContractPowerOfAttorney";
    private final AuditService auditService;
    private final ContractRepository contractRepository;

    protected void beforeCreate(ContractPowerOfAttorney entity, ContractPowerOfAttorneyCreateRequest dto) {
        Contract contract = validateContract(entity);
        entity.setContract(contract);
        entity.setSupplier(contract.getSupplier());
        if (isBlank(entity.getStatus())) {
            entity.setStatus("ISSUED");
        }
        if (isBlank(entity.getCode()) && !isBlank(entity.getAttorneyNumber())) {
            entity.setCode(entity.getAttorneyNumber());
        }
        if (isBlank(entity.getName()) && !isBlank(entity.getAttorneyNumber())) {
            entity.setName(entity.getAttorneyNumber());
        }
        if (entity.getAmountLimit() == null) {
            entity.setAmountLimit(BigDecimal.ZERO);
        }
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
    protected void beforeUpdate(ContractPowerOfAttorney entity, ContractPowerOfAttorneyUpdateRequest dto) {
        Contract contract = validateContract(entity);
        entity.setContract(contract);
        entity.setSupplier(contract.getSupplier());
        if (entity.getAmountLimit() == null) {
            entity.setAmountLimit(BigDecimal.ZERO);
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private Contract validateContract(ContractPowerOfAttorney entity) {
        if (entity.getContract() == null && entity.getContractId() == null) {
            throw new BusinessException("contractId is required");
        }
        UUID contractId = entity.getContract() != null ? entity.getContract().getId() : entity.getContractId();
        return contractRepository.findByIdAndIsDeletedFalse(contractId)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Contract not found"));
    }
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    @Transactional(readOnly = true)
    public Page<ContractPowerOfAttorneyResponse> search(ContractPowerOfAttorneySearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ContractPowerOfAttorney.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ContractPowerOfAttorneyResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ContractPowerOfAttorneyResponse create(ContractPowerOfAttorneyCreateRequest dto) {
        ContractPowerOfAttorney entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ContractPowerOfAttorney saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ContractPowerOfAttorneyResponse update(UUID id, ContractPowerOfAttorneyUpdateRequest dto) {
        ContractPowerOfAttorney entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ContractPowerOfAttorney saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ContractPowerOfAttorney entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ContractPowerOfAttorneyResponse restore(UUID id) {
        ContractPowerOfAttorney entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ContractPowerOfAttorney saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ContractPowerOfAttorney findEntity(UUID id) {
        ContractPowerOfAttorney entity = repository.findByIdAndIsDeletedFalse(id)
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

