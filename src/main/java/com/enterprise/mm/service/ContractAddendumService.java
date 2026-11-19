package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ContractAddendumResponse;
import com.enterprise.mm.payload.ContractAddendumUpdateRequest;
import com.enterprise.mm.payload.ContractAddendumCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ContractAddendumMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractAddendum;
import com.enterprise.mm.repository.ContractAddendumRepository;
import com.enterprise.mm.repository.ContractRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ContractAddendumSearchRequest;

@Service
@RequiredArgsConstructor
public class ContractAddendumService {
    private final String entityName = "ContractAddendum";
    private final ContractAddendumRepository repository;
    private final ContractRepository contractRepository;
    private final ContractAddendumMapper mapper;
    private final AuditService auditService;

    protected void beforeCreate(ContractAddendum entity, ContractAddendumCreateRequest dto) {
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

        entity.setStatus("DRAFT");
        normalize(entity);
    }
    protected void beforeUpdate(ContractAddendum entity, ContractAddendumUpdateRequest dto) {
        if (!"DRAFT".equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT addendum can be edited");
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
    }
    @Transactional
    public ContractAddendumResponse post(UUID id) {
        ContractAddendum addendum = findEntity(id);
        if (!"DRAFT".equals(addendum.getStatus())) {
            throw new BusinessException("Only DRAFT addendum can be posted");
        }
        Contract contract = contract(addendum.getContract() != null ? addendum.getContract().getId() : addendum.getContractId());
        if (ContractStatus.CLOSED.name().equals(contract.getStatus())) {
            throw new BusinessException("Closed contract cannot be changed by addendum");
        }
        if (addendum.getNewEndDate() != null) {
            contract.setEndDate(addendum.getNewEndDate());
        }
        if (addendum.getNewLimitAmount() != null) {
            if (addendum.getNewLimitAmount().compareTo(nvl(contract.getUsedAmount())) < 0) {
                throw new BusinessException("New contract limit cannot be less than used amount");
            }
            contract.setLimitAmount(addendum.getNewLimitAmount());
        } else if (nvl(addendum.getLimitDelta()).signum() != 0) {
            BigDecimal nextLimit = nvl(contract.getLimitAmount()).add(addendum.getLimitDelta());
            if (nextLimit.compareTo(nvl(contract.getUsedAmount())) < 0) {
                throw new BusinessException("Contract limit cannot be less than used amount");
            }
            contract.setLimitAmount(nextLimit);
        }
        addendum.setStatus("POSTED");
        addendum.setPostedAt(LocalDateTime.now());
        contractRepository.save(contract);
        ContractAddendum saved = repository.save(addendum);
        auditService.log("ContractAddendum", saved.getId(), "POST", saved.getAddendumType(), null, null);
        auditService.log("Contract", contract.getId(), "ADDENDUM_POSTED", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }
    private void normalize(ContractAddendum entity) {
        if (entity.getAddendumType() == null || entity.getAddendumType().isBlank()) {
            entity.setAddendumType("PROLONGATION");
        }
        if (entity.getAddendumDate() == null) {
            entity.setAddendumDate(LocalDate.now());
        }
        entity.setLimitDelta(nvl(entity.getLimitDelta()));
    }
    private Contract contract(UUID id) {
        return contractRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> !item.isDeleted())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Contract not found: " + id));
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<ContractAddendumResponse> search(ContractAddendumSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ContractAddendum.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ContractAddendumResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ContractAddendumResponse create(ContractAddendumCreateRequest dto) {
        ContractAddendum entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ContractAddendum saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ContractAddendumResponse update(UUID id, ContractAddendumUpdateRequest dto) {
        ContractAddendum entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ContractAddendum saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ContractAddendum entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ContractAddendumResponse restore(UUID id) {
        ContractAddendum entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ContractAddendum saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ContractAddendum findEntity(UUID id) {
        ContractAddendum entity = repository.findByIdAndIsDeletedFalse(id)
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

