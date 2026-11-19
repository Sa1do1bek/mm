package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.TenderCommissionMemberResponse;
import com.enterprise.mm.payload.TenderCommissionMemberUpdateRequest;
import com.enterprise.mm.payload.TenderCommissionMemberCreateRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.TenderCommissionMemberMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TenderCommissionMember;
import com.enterprise.mm.repository.TenderCommissionMemberRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.TenderCommissionMemberSearchRequest;

@Service
@RequiredArgsConstructor
public class TenderCommissionMemberService {
    private final TenderCommissionMemberRepository repository;
    private final TenderCommissionMemberMapper mapper;
    private final String entityName = "TenderCommissionMember";
    private final AuditService auditService;
    @Transactional(readOnly = true)
    public Page<TenderCommissionMemberResponse> search(TenderCommissionMemberSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            TenderCommissionMember.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public TenderCommissionMemberResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public TenderCommissionMemberResponse create(TenderCommissionMemberCreateRequest dto) {
        TenderCommissionMember entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        TenderCommissionMember saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public TenderCommissionMemberResponse update(UUID id, TenderCommissionMemberUpdateRequest dto) {
        TenderCommissionMember entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        TenderCommissionMember saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        TenderCommissionMember entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public TenderCommissionMemberResponse restore(UUID id) {
        TenderCommissionMember entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        TenderCommissionMember saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected TenderCommissionMember findEntity(UUID id) {
        TenderCommissionMember entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(TenderCommissionMember entity, TenderCommissionMemberCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getMemberRole() == null || entity.getMemberRole().isBlank()) {
            entity.setMemberRole("MEMBER");
        }
        if (entity.getVotingWeight() == null) {
            entity.setVotingWeight(BigDecimal.ONE);
        }
        if (entity.isConflictDeclarationSigned() && entity.getDeclaredAt() == null) {
            entity.setDeclaredAt(LocalDateTime.now());
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
    protected void beforeUpdate(TenderCommissionMember entity, TenderCommissionMemberUpdateRequest dto) {
        if (entity.getMemberRole() == null || entity.getMemberRole().isBlank()) {
            entity.setMemberRole("MEMBER");
        }
        if (entity.getVotingWeight() == null) {
            entity.setVotingWeight(BigDecimal.ONE);
        }
        if (entity.isConflictDeclarationSigned() && entity.getDeclaredAt() == null) {
            entity.setDeclaredAt(LocalDateTime.now());
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}
