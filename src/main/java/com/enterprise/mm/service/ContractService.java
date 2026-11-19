package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.ContractResponse;
import com.enterprise.mm.payload.ContractUpdateRequest;
import com.enterprise.mm.payload.ContractCreateRequest;
import com.enterprise.mm.payload.ContractLimitResult;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ContractMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ContractSearchRequest;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.JoinType;

@Service
@RequiredArgsConstructor
public class ContractService {
    private final ContractRepository repository;
    private final ContractSpecificationRepository specificationRepository;
    private final SupplierAccreditationDemandResolver accreditationDemandResolver;
    private final ContractMapper mapper;
    private final String entityName = "Contract";
    private final SupplierAccreditationService supplierAccreditationService;
    private final AuditService auditService;
    private final PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    private final com.enterprise.mm.repository.RFQRepository rfqRepository;
    
    protected void beforeCreate(Contract entity, ContractCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        
        String parentName = null;
        if (entity.getSourceRfqId() != null) {
            parentName = rfqRepository.findByIdAndIsDeletedFalse(entity.getSourceRfqId())
                .map(com.enterprise.mm.entity.RFQ::getName).orElse(null);
        }
        
        if (parentName != null) {
            entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), parentName
            ));
        } else {
            entity.setName(BusinessDisplayNamePolicy.requireHumanName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
            ));
        }

        if ("ACTIVE".equals(entity.getStatus())) {
            entity.setStatus(ContractStatus.DRAFT.name());
        }
        // Ledger starts at zero; client must not seed used amounts on create
        entity.setUsedAmount(BigDecimal.ZERO);
        entity.setServiceUsedAmount(BigDecimal.ZERO);
        if (entity.getLimitAmount() == null) {
            entity.setLimitAmount(BigDecimal.ZERO);
        }
    }
    @Transactional
    public ContractResponse activate(UUID id) {
        Contract contract = findEntity(id);
        if (contract.getStartDate() != null && contract.getStartDate().isAfter(LocalDate.now())) {
            throw new BusinessException("Contract start date is in the future");
        }
        if (contract.getEndDate() != null && contract.getEndDate().isBefore(LocalDate.now())) {
            throw new BusinessException("Expired contract cannot be activated");
        }
        List<ContractSpecification> specifications = specificationRepository
            .findByTenantIdAndContractIdAndIsDeletedFalse(TenantContext.currentTenant(), contract.getId())
            .stream()
            .filter(specification -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                || com.enterprise.mm.security.AccessScope.canAccessCompany(specification.getCompanyId()))
            .toList();
        AccreditationDemand accreditationDemand =
            accreditationDemandResolver.forContractSpecifications(specifications);
        supplierAccreditationService.assertSupplierEligibleForDemand(
            contract.getSupplierId(),
            accreditationDemand,
            "contract activation"
        );
        contract.setStatus(ContractStatus.ACTIVE.name());
        auditService.log("Contract", id, "ACTIVATE", contract.getCode(), null, null);
        return toResponse(repository.save(contract));
    }
    @Transactional
    public ContractResponse suspend(UUID id) {
        Contract contract = findEntity(id);
        contract.setStatus(ContractStatus.SUSPENDED.name());
        auditService.log("Contract", id, "SUSPEND", contract.getCode(), null, null);
        return toResponse(repository.save(contract));
    }
    @Transactional
    public ContractResponse close(UUID id) {
        Contract contract = findEntity(id);
        contract.setStatus(ContractStatus.CLOSED.name());
        auditService.log("Contract", id, "CLOSE", contract.getCode(), null, null);
        return toResponse(repository.save(contract));
    }

    @Transactional(readOnly = true)
    public void validateWorksContract(UUID contractId, UUID supplierId, String currencyCode) {
        requireWorksContract(contractId, supplierId, currencyCode);
    }

    /**
     * WORKS send readiness: use explicit PO contractId when present; otherwise resolve an ACTIVE
     * contract for the same supplier + currency in the current company.
     */
    @Transactional(readOnly = true)
    public UUID requireWorksContract(UUID contractId, UUID supplierId, String currencyCode) {
        if (supplierId == null) {
            throw new BusinessException("WORKS purchase order requires an ACTIVE contract before sending");
        }
        Contract contract;
        if (contractId != null) {
            contract = repository.findByIdAndIsDeletedFalse(contractId)
                .filter(found -> TenantContext.currentTenant().equals(found.getTenantId()))
                .filter(found -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                    || com.enterprise.mm.security.AccessScope.canAccessCompany(found.getCompanyId()))
                .orElseThrow(() -> new BusinessException(
                    "WORKS purchase order requires an ACTIVE contract before sending"));
        } else {
            contract = resolveActiveWorksContract(supplierId, currencyCode)
                .orElseThrow(() -> new BusinessException(
                    "WORKS purchase order requires an ACTIVE contract before sending"));
        }
        assertWorksContractCompatible(contract, supplierId, currencyCode);
        return contract.getId();
    }

    private Optional<Contract> resolveActiveWorksContract(UUID supplierId, String currencyCode) {
        UUID companyId = CompanyContext.currentCompany();
        if (companyId == null) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now();
        return repository
            .findByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                companyId,
                supplierId,
                ContractStatus.ACTIVE.name()
            )
            .stream()
            .filter(candidate -> currencyMatches(candidate.getCurrencyCode(), currencyCode))
            .filter(candidate -> isWithinValidity(candidate, today))
            .max(worksContractPreference());
    }

    private static java.util.Comparator<Contract> worksContractPreference() {
        return java.util.Comparator
            .comparing((Contract c) -> remainingLimit(c))
            .thenComparing(Contract::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
    }

    private static BigDecimal remainingLimit(Contract contract) {
        BigDecimal limit = contract.getLimitAmount() == null ? BigDecimal.ZERO : contract.getLimitAmount();
        BigDecimal used = contract.getUsedAmount() == null ? BigDecimal.ZERO : contract.getUsedAmount();
        return limit.subtract(used);
    }

    private static boolean currencyMatches(String contractCurrency, String purchaseOrderCurrency) {
        return Objects.toString(contractCurrency, "")
            .equalsIgnoreCase(Objects.toString(purchaseOrderCurrency, ""));
    }

    private static boolean isWithinValidity(Contract contract, LocalDate today) {
        if (contract.getStartDate() != null && contract.getStartDate().isAfter(today)) {
            return false;
        }
        if (contract.getEndDate() != null && contract.getEndDate().isBefore(today)) {
            return false;
        }
        return true;
    }

    private void assertWorksContractCompatible(Contract contract, UUID supplierId, String currencyCode) {
        if (!ContractStatus.ACTIVE.name().equals(contract.getStatus())) {
            throw new BusinessException("WORKS purchase order requires an ACTIVE contract before sending");
        }
        if (!Objects.equals(contract.getSupplierId(), supplierId)) {
            throw new BusinessException("WORKS contract supplier must match purchase order supplier");
        }
        if (!currencyMatches(contract.getCurrencyCode(), currencyCode)) {
            throw new BusinessException("WORKS contract currency must match purchase order currency");
        }
    }

    @Transactional
    public void consumeLimit(UUID contractId, BigDecimal amount, String source) {
        consumeLimit(contractId, amount, false, source);
    }

    @Transactional
    public void consumeLimit(UUID contractId, BigDecimal amount, boolean serviceUsage, String source) {
        if (contractId == null) {
            return;
        }
        Contract contract = lockEntity(contractId);
        BigDecimal requested = nvl(amount);
        if (!ContractStatus.ACTIVE.name().equals(contract.getStatus())) {
            throw new BusinessException("Contract must be ACTIVE");
        }
        BigDecimal used = nvl(contract.getUsedAmount());
        BigDecimal limit = nvl(contract.getLimitAmount());
        if (limit.signum() > 0 && used.add(requested).compareTo(limit) > 0) {
            throw new BusinessException("Contract limit exceeded");
        }
        contract.setUsedAmount(nvl(contract.getUsedAmount()).add(requested));
        if (serviceUsage) {
            contract.setServiceUsedAmount(nvl(contract.getServiceUsedAmount()).add(requested));
        }
        repository.save(contract);
        auditService.log("Contract", contractId, "CONSUME_LIMIT", source + ": " + requested, null, null);
    }

    @Transactional(readOnly = true)
    public ContractLimitResult validateLimit(UUID contractId, BigDecimal amount) {
        if (contractId == null) {
            return new ContractLimitResult(null, true, nvl(amount), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "No contract linked");
        }
        Contract contract = findEntity(contractId);
        if (!ContractStatus.ACTIVE.name().equals(contract.getStatus())) {
            throw new BusinessException("Contract must be ACTIVE");
        }
        BigDecimal used = nvl(contract.getUsedAmount());
        BigDecimal limit = nvl(contract.getLimitAmount());
        BigDecimal requested = nvl(amount);
        BigDecimal remaining = limit.subtract(used);
        boolean approved = limit.signum() == 0 || used.add(requested).compareTo(limit) <= 0;
        return new ContractLimitResult(
            contractId,
            approved,
            requested,
            remaining,
            used,
            nvl(contract.getServiceUsedAmount()),
            approved ? "Contract limit available" : "Contract limit exceeded"
        );
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional(readOnly = true)
    public Page<ContractResponse> search(ContractSearchRequest request, Pageable pageable) {
        SearchCriteria filter = contractSearchCriteria(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            Contract.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        if (request != null && request.purchaseOrderId() != null) {
            spec = spec.and(contractLinkedToPurchaseOrder(request.purchaseOrderId(), scoped));
        }
        if (request != null && request.search() != null && !request.search().isBlank()) {
            spec = spec.and(universalContractSearch(request.search(), scoped));
        }
        return repository.findAll(spec, pageable).map(this::toResponse);
    }

    private SearchCriteria contractSearchCriteria(ContractSearchRequest request) {
        SearchCriteria delegate = SearchCriteria.of(request);
        if (request == null || request.search() == null || request.search().isBlank()) {
            return delegate;
        }
        // The contract-specific predicate below searches more than code/name.
        // Suppress only the generic text predicate while retaining all filters.
        return new SearchCriteria() {
            @Override public List<String> status() { return delegate.status(); }
            @Override public UUID supplierId() { return delegate.supplierId(); }
            @Override public UUID purchaseOrderId() { return delegate.purchaseOrderId(); }
            @Override public LocalDate dateFrom() { return delegate.dateFrom(); }
            @Override public LocalDate dateTo() { return delegate.dateTo(); }
            @Override public boolean includeDeleted() { return delegate.includeDeleted(); }
        };
    }

    /**
     * Contract pickers are used at the start of receiving, where users normally
     * know a supplier, PO, material or service description rather than the
     * internal contract name. Keep the generic code/name search and add those
     * business references as one tenant/company-scoped search predicate.
     */
    private Specification<Contract> universalContractSearch(String value, boolean scoped) {
        String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        String like = "%" + normalized + "%";
        return (root, query, cb) -> {
            jakarta.persistence.criteria.Join<Contract, Supplier> supplier = root.join("supplier", JoinType.LEFT);

            var specificationQuery = query.subquery(UUID.class);
            var specification = specificationQuery.from(ContractSpecification.class);
            specificationQuery.select(specification.get("contractId"));
            List<jakarta.persistence.criteria.Predicate> specificationPredicates = new java.util.ArrayList<>();
            specificationPredicates.add(cb.equal(specification.get("contractId"), root.get("id")));
            specificationPredicates.add(cb.equal(specification.get("tenantId"), TenantContext.currentTenant()));
            specificationPredicates.add(cb.isFalse(specification.get("isDeleted")));
            if (scoped) {
                specificationPredicates.add(cb.equal(specification.get("companyId"), CompanyContext.currentCompany()));
            }
            specificationPredicates.add(cb.or(
                cb.like(cb.lower(cb.coalesce(specification.get("code"), "")), like),
                cb.like(cb.lower(cb.coalesce(specification.get("name"), "")), like),
                cb.like(cb.lower(cb.coalesce(specification.get("serviceDescription"), "")), like),
                cb.like(cb.lower(cb.coalesce(specification.get("serviceCategory"), "")), like),
                cb.like(cb.lower(cb.coalesce(specification.get("stage"), "")), like)
            ));
            specificationQuery.where(specificationPredicates.toArray(jakarta.persistence.criteria.Predicate[]::new));

            var materialQuery = query.subquery(UUID.class);
            var materialSpecification = materialQuery.from(ContractSpecification.class);
            var material = materialQuery.from(Material.class);
            materialQuery.select(materialSpecification.get("contractId"));
            List<jakarta.persistence.criteria.Predicate> materialPredicates = new java.util.ArrayList<>();
            materialPredicates.add(cb.equal(materialSpecification.get("contractId"), root.get("id")));
            materialPredicates.add(cb.equal(materialSpecification.get("materialId"), material.get("id")));
            materialPredicates.add(cb.equal(materialSpecification.get("tenantId"), TenantContext.currentTenant()));
            materialPredicates.add(cb.equal(material.get("tenantId"), TenantContext.currentTenant()));
            materialPredicates.add(cb.isFalse(materialSpecification.get("isDeleted")));
            materialPredicates.add(cb.isFalse(material.get("isDeleted")));
            if (scoped) {
                materialPredicates.add(cb.equal(materialSpecification.get("companyId"), CompanyContext.currentCompany()));
                materialPredicates.add(cb.equal(material.get("companyId"), CompanyContext.currentCompany()));
            }
            materialPredicates.add(cb.or(
                cb.like(cb.lower(cb.coalesce(material.get("code"), "")), like),
                cb.like(cb.lower(cb.coalesce(material.get("name"), "")), like)
            ));
            materialQuery.where(materialPredicates.toArray(jakarta.persistence.criteria.Predicate[]::new));

            var purchaseOrderQuery = query.subquery(UUID.class);
            var purchaseOrder = purchaseOrderQuery.from(PurchaseOrder.class);
            purchaseOrderQuery.select(purchaseOrder.get("contractId"));
            List<jakarta.persistence.criteria.Predicate> purchaseOrderPredicates = new java.util.ArrayList<>();
            purchaseOrderPredicates.add(cb.equal(purchaseOrder.get("contractId"), root.get("id")));
            purchaseOrderPredicates.add(cb.equal(purchaseOrder.get("tenantId"), TenantContext.currentTenant()));
            purchaseOrderPredicates.add(cb.isFalse(purchaseOrder.get("isDeleted")));
            if (scoped) {
                purchaseOrderPredicates.add(cb.equal(purchaseOrder.get("companyId"), CompanyContext.currentCompany()));
            }
            purchaseOrderPredicates.add(cb.or(
                cb.like(cb.lower(cb.coalesce(purchaseOrder.get("code"), "")), like),
                cb.like(cb.lower(cb.coalesce(purchaseOrder.get("name"), "")), like)
            ));
            purchaseOrderQuery.where(purchaseOrderPredicates.toArray(jakarta.persistence.criteria.Predicate[]::new));

            query.distinct(true);
            return cb.or(
                cb.like(cb.lower(cb.coalesce(root.get("code"), "")), like),
                cb.like(cb.lower(cb.coalesce(root.get("name"), "")), like),
                cb.like(cb.lower(cb.coalesce(root.get("contractNumber"), "")), like),
                cb.like(cb.lower(cb.coalesce(supplier.get("code"), "")), like),
                cb.like(cb.lower(cb.coalesce(supplier.get("name"), "")), like),
                cb.like(cb.lower(cb.coalesce(supplier.get("legalName"), "")), like),
                cb.like(cb.lower(cb.coalesce(supplier.get("shortName"), "")), like),
                cb.exists(specificationQuery),
                cb.exists(materialQuery),
                cb.exists(purchaseOrderQuery)
            );
        };
    }

    private org.springframework.data.jpa.domain.Specification<Contract> contractLinkedToPurchaseOrder(
        UUID purchaseOrderId,
        boolean scoped
    ) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var purchaseOrder = subquery.from(PurchaseOrder.class);
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(cb.equal(purchaseOrder.get("id"), purchaseOrderId));
            predicates.add(cb.equal(purchaseOrder.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.isFalse(purchaseOrder.get("isDeleted")));
            predicates.add(cb.equal(purchaseOrder.get("contractId"), root.get("id")));
            if (scoped) {
                predicates.add(cb.equal(purchaseOrder.get("companyId"), CompanyContext.currentCompany()));
            }
            subquery.select(purchaseOrder.get("contractId"));
            subquery.where(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
            return cb.exists(subquery);
        };
    }
    @Transactional(readOnly = true)
    public ContractResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public ContractResponse create(ContractCreateRequest dto) {
        Contract entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Contract saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }
    @Transactional
    public ContractResponse update(UUID id, ContractUpdateRequest dto) {
        Contract entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Contract saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Contract entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ContractResponse restore(UUID id) {
        Contract entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Contract saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    private ContractResponse toResponse(Contract entity) {
        ContractResponse response = mapper.toResponse(entity);
        if (response == null) {
            return null;
        }
        PurchaseOrderDemandCyclePolicy.Readiness readiness =
            purchaseOrderDemandCyclePolicy.readinessForContract(entity.getId());
        response.setPurchaseOrderReady(readiness.ready());
        response.setPurchaseOrderBlockedReason(readiness.reason());
        return response;
    }

    protected Contract findEntity(UUID id) {
        Contract entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeUpdate(Contract entity, ContractUpdateRequest dto) {
        String parentName = null;
        if (entity.getSourceRfqId() != null) {
            parentName = rfqRepository.findByIdAndIsDeletedFalse(entity.getSourceRfqId())
                .map(com.enterprise.mm.entity.RFQ::getName).orElse(null);
        }
        
        if (parentName != null) {
            entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), parentName
            ));
        } else {
            entity.setName(BusinessDisplayNamePolicy.requireHumanName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
            ));
        }
    }
    protected boolean companyScoped() {
        return true;
    }

    private Contract lockEntity(UUID id) {
        Contract entity = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
}
