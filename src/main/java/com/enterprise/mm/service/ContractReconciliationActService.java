package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ContractReconciliationActResponse;
import com.enterprise.mm.payload.ContractReconciliationActUpdateRequest;
import com.enterprise.mm.payload.ContractReconciliationActCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ContractReconciliationActMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractReconciliationAct;
import com.enterprise.mm.repository.ContractReconciliationActRepository;
import com.enterprise.mm.repository.ContractRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ContractReconciliationActSearchRequest;

@Service
@RequiredArgsConstructor
public class ContractReconciliationActService {
    private final ContractReconciliationActRepository repository;
    private final ContractReconciliationActMapper mapper;
    private final String entityName = "ContractReconciliationAct";
    private final AuditService auditService;
    private final ContractRepository contractRepository;

    protected void beforeCreate(ContractReconciliationAct entity, ContractReconciliationActCreateRequest dto) {
        Contract contract = validateContract(entity);
        entity.setContract(contract);
        entity.setSupplier(contract.getSupplier());
        if (isBlank(entity.getStatus())) {
            entity.setStatus("DRAFT");
        }
        if (isBlank(entity.getCode()) && !isBlank(entity.getActNumber())) {
            entity.setCode(entity.getActNumber());
        }
        if (isBlank(entity.getName()) && !isBlank(entity.getActNumber())) {
            entity.setName(entity.getActNumber());
        }
        fillZeroAmounts(entity);
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
    protected void beforeUpdate(ContractReconciliationAct entity, ContractReconciliationActUpdateRequest dto) {
        Contract contract = validateContract(entity);
        entity.setContract(contract);
        entity.setSupplier(contract.getSupplier());
        fillZeroAmounts(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private Contract validateContract(ContractReconciliationAct entity) {
        if (entity.getContract() == null && entity.getContractId() == null) {
            throw new BusinessException("contractId is required");
        }
        UUID contractId = entity.getContract() != null ? entity.getContract().getId() : entity.getContractId();
        return contractRepository.findByIdAndIsDeletedFalse(contractId)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Contract not found"));
    }
    private void fillZeroAmounts(ContractReconciliationAct entity) {
        if (entity.getOpeningBalance() == null) {
            entity.setOpeningBalance(BigDecimal.ZERO);
        }
        if (entity.getDebitAmount() == null) {
            entity.setDebitAmount(BigDecimal.ZERO);
        }
        if (entity.getCreditAmount() == null) {
            entity.setCreditAmount(BigDecimal.ZERO);
        }
        if (entity.getClosingBalance() == null) {
            entity.setClosingBalance(BigDecimal.ZERO);
        }
    }
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    @Transactional(readOnly = true)
    public Page<ContractReconciliationActResponse> search(ContractReconciliationActSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ContractReconciliationAct.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ContractReconciliationActResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ContractReconciliationActResponse create(ContractReconciliationActCreateRequest dto) {
        ContractReconciliationAct entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ContractReconciliationAct saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ContractReconciliationActResponse update(UUID id, ContractReconciliationActUpdateRequest dto) {
        ContractReconciliationAct entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ContractReconciliationAct saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ContractReconciliationAct entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ContractReconciliationActResponse restore(UUID id) {
        ContractReconciliationAct entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ContractReconciliationAct saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ContractReconciliationAct findEntity(UUID id) {
        ContractReconciliationAct entity = repository.findByIdAndIsDeletedFalse(id)
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

