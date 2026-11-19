package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.CustomsDeclarationResponse;
import com.enterprise.mm.payload.CustomsDeclarationUpdateRequest;
import com.enterprise.mm.payload.CustomsDeclarationCreateRequest;
import com.enterprise.mm.payload.CustomsDeclarationSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.CustomsDeclarationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.CustomsDeclarationStatus;
import com.enterprise.mm.enums.ImportDeliveryStatus;
import com.enterprise.mm.entity.CustomsDeclaration;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.CustomsDeclarationRepository;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.TnvedCodeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomsDeclarationService {
    private final String entityName = "CustomsDeclaration";
    private final CustomsDeclarationRepository repository;
    private final CustomsDeclarationMapper mapper;
    private final SupplyLogisticsGuard guard;
    private final ImportDeliveryRepository importDeliveryRepository;
    private final ImportDeliveryService importDeliveryService;
    private final TnvedCodeRepository tnvedCodeRepository;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;

    protected void beforeCreate(CustomsDeclaration entity, CustomsDeclarationCreateRequest dto) {
        normalize(entity);
        validateBusinessLinks(entity);
        ensureUniqueDeclaration(entity);
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

        entity.setStatus(CustomsDeclarationStatus.DRAFT.name());
    }
    protected void beforeUpdate(CustomsDeclaration entity, CustomsDeclarationUpdateRequest dto) {
        requireEditable(entity);
        normalize(entity);
        validateBusinessLinks(entity);
        ensureUniqueDeclaration(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void requireEditable(CustomsDeclaration entity) {
        if (CustomsDeclarationStatus.RELEASED.name().equals(entity.getStatus()) || CustomsDeclarationStatus.CANCELLED.name().equals(entity.getStatus())) {
            throw new BusinessException("Released or cancelled customs declaration cannot be edited");
        }
    }
    @Transactional
    public CustomsDeclarationResponse submit(UUID id) {
        CustomsDeclaration declaration = findEntity(id);
        if (!CustomsDeclarationStatus.DRAFT.name().equals(declaration.getStatus())) {
            throw new BusinessException("Only DRAFT customs declaration can be submitted");
        }
        declaration.setStatus(CustomsDeclarationStatus.SUBMITTED.name());
        declaration.setSubmittedAt(LocalDateTime.now());
        auditService.log("CustomsDeclaration", id, "SUBMIT", declaration.getDeclarationNumber(), null, null);
        return mapper.toResponse(repository.save(declaration));
    }
    @Transactional
    public CustomsDeclarationResponse accept(UUID id) {
        CustomsDeclaration declaration = findEntity(id);
        if (!CustomsDeclarationStatus.SUBMITTED.name().equals(declaration.getStatus())) {
            throw new BusinessException("Only SUBMITTED customs declaration can be accepted");
        }
        declaration.setStatus(CustomsDeclarationStatus.ACCEPTED.name());
        auditService.log("CustomsDeclaration", id, "ACCEPT", declaration.getDeclarationNumber(), null, null);
        return mapper.toResponse(repository.save(declaration));
    }
    @Transactional
    public CustomsDeclarationResponse release(UUID id) {
        CustomsDeclaration declaration = findEntity(id);
        if (!CustomsDeclarationStatus.SUBMITTED.name().equals(declaration.getStatus()) && !CustomsDeclarationStatus.ACCEPTED.name().equals(declaration.getStatus())) {
            throw new BusinessException("Only SUBMITTED or ACCEPTED customs declaration can be released");
        }
        declaration.setStatus(CustomsDeclarationStatus.RELEASED.name());
        declaration.setReleasedAt(LocalDateTime.now());
        CustomsDeclaration saved = repository.save(declaration);
        if (saved.getImportDeliveryId() != null) {
            ImportDelivery delivery = saved.getImportDelivery() != null ? saved.getImportDelivery() : guard.importDelivery(saved.getImportDeliveryId());
            delivery.setCustomsDeclarationId(saved.getId());
            if (!ImportDeliveryStatus.DELIVERED.name().equals(delivery.getStatus()) && !ImportDeliveryStatus.CLOSED.name().equals(delivery.getStatus())) {
                delivery.setStatus(ImportDeliveryStatus.CLEARED.name());
                delivery.setCurrentMilestone("CUSTOMS_RELEASED");
                delivery.setLastEventAt(LocalDateTime.now());
            }
            importDeliveryRepository.save(delivery);
            importDeliveryService.recalculateForImportDelivery(delivery.getId());
        }
        integrationEventService.emit("CUSTOMS.DECLARATION_RELEASED", "CUSTOMS", saved.getId(), Map.of(
            "customsDeclarationId", saved.getId(),
            "importDeliveryId", saved.getImportDeliveryId() == null ? "" : saved.getImportDeliveryId().toString(),
            "declarationNumber", saved.getDeclarationNumber() == null ? "" : saved.getDeclarationNumber()
        ));
        auditService.log("CustomsDeclaration", id, "RELEASE", declaration.getDeclarationNumber(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CustomsDeclarationResponse reject(UUID id) {
        CustomsDeclaration declaration = findEntity(id);
        if (CustomsDeclarationStatus.RELEASED.name().equals(declaration.getStatus())) {
            throw new BusinessException("Released customs declaration cannot be rejected");
        }
        declaration.setStatus(CustomsDeclarationStatus.REJECTED.name());
        auditService.log("CustomsDeclaration", id, "REJECT", declaration.getDeclarationNumber(), null, null);
        return mapper.toResponse(repository.save(declaration));
    }
    @Transactional
    public CustomsDeclarationResponse cancel(UUID id) {
        CustomsDeclaration declaration = findEntity(id);
        if (CustomsDeclarationStatus.RELEASED.name().equals(declaration.getStatus())) {
            throw new BusinessException("Released customs declaration cannot be cancelled");
        }
        declaration.setStatus(CustomsDeclarationStatus.CANCELLED.name());
        auditService.log("CustomsDeclaration", id, "CANCEL", declaration.getDeclarationNumber(), null, null);
        return mapper.toResponse(repository.save(declaration));
    }
    private void normalize(CustomsDeclaration entity) {
        if (entity.getDeclarationDate() == null) {
            entity.setDeclarationDate(LocalDate.now());
        }
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode("UZS");
        } else {
            entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }
        entity.setCustomsValue(nvl(entity.getCustomsValue()));
        entity.setDutyAmount(nvl(entity.getDutyAmount()));
        entity.setVatAmount(nvl(entity.getVatAmount()));
        entity.setExciseAmount(nvl(entity.getExciseAmount()));
        entity.setFeesAmount(nvl(entity.getFeesAmount()));
        entity.setTotalCustomsAmount(entity.getDutyAmount().add(entity.getVatAmount()).add(entity.getExciseAmount()).add(entity.getFeesAmount()));
    }
    private void validateBusinessLinks(CustomsDeclaration entity) {
        Supplier supplier = entity.getSupplier() != null ? entity.getSupplier() : guard.supplier(entity.getSupplierId());
        entity.setSupplier(supplier);
        ImportDelivery delivery = entity.getImportDelivery() != null ? entity.getImportDelivery()
            : entity.getImportDeliveryId() == null ? null : guard.importDelivery(entity.getImportDeliveryId());
        if (delivery != null) {
            entity.setImportDelivery(delivery);
        }
        if (entity.getAsnId() != null || entity.getAsn() != null) {
            entity.setAsn(entity.getAsn() != null ? entity.getAsn() : guard.asn(entity.getAsnId()));
        }
        if (entity.getPurchaseOrderId() != null || entity.getPurchaseOrder() != null) {
            entity.setPurchaseOrder(entity.getPurchaseOrder() != null ? entity.getPurchaseOrder() : guard.purchaseOrder(entity.getPurchaseOrderId()));
        }
        if (delivery != null) {
            if (!Objects.equals(delivery.getSupplierId(), supplier.getId())) {
                throw new BusinessException("Customs declaration supplier must match import delivery supplier");
            }
            if (entity.getAsn() == null && delivery.getAsn() != null) {
                entity.setAsn(delivery.getAsn());
            } else if (entity.getAsnId() == null) {
                entity.setAsnId(delivery.getAsnId());
            }
            if (entity.getPurchaseOrder() == null && delivery.getPurchaseOrder() != null) {
                entity.setPurchaseOrder(delivery.getPurchaseOrder());
            } else if (entity.getPurchaseOrderId() == null) {
                entity.setPurchaseOrderId(delivery.getPurchaseOrderId());
            }
        }
        if (entity.getTnvedCodeId() != null) {
            var tnved = entity.getTnvedCode() != null ? entity.getTnvedCode() : tnvedCodeRepository.findByIdAndIsDeletedFalse(entity.getTnvedCodeId())
                .filter(code -> !code.isDeleted() && TenantContext.currentTenant().equals(code.getTenantId()))
                .filter(code -> com.enterprise.mm.security.AccessScope.canAccessCompany(code.getCompanyId()))
                .orElseThrow(() -> new BusinessException("TN VED code not found"));
            entity.setTnvedCode(tnved);
        }
    }
    private void ensureUniqueDeclaration(CustomsDeclaration entity) {
        if (entity.getDeclarationNumber() == null || entity.getDeclarationNumber().isBlank()) {
            return;
        }
        entity.setDeclarationNumber(entity.getDeclarationNumber().trim());
        repository.findByTenantIdAndCompanyIdAndDeclarationNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getDeclarationNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Customs declaration number already exists");
            });
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<CustomsDeclarationResponse> search(CustomsDeclarationSearchRequest request, Pageable pageable) {
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
            filter.supplierId(),
            filter.purchaseOrderId(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public CustomsDeclarationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public CustomsDeclarationResponse create(CustomsDeclarationCreateRequest dto) {
        CustomsDeclaration entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        CustomsDeclaration saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CustomsDeclarationResponse update(UUID id, CustomsDeclarationUpdateRequest dto) {
        CustomsDeclaration entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireEditable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        CustomsDeclaration saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        CustomsDeclaration entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public CustomsDeclarationResponse restore(UUID id) {
        CustomsDeclaration entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        CustomsDeclaration saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected CustomsDeclaration findEntity(UUID id) {
        CustomsDeclaration entity = repository.findByIdAndIsDeletedFalse(id)
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
