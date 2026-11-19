package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.payload.RFQUpdateRequest;
import com.enterprise.mm.payload.RFQCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.RFQItemCreateRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.rfq.SupplierPortalEligibilityValidator;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.enums.RfqPurpose;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.RFQSearchRequest;

@Service
@RequiredArgsConstructor
public class RFQService {
    private final RFQRepository repository;
    private final RFQMapper mapper;
    private final String entityName = "RFQ";
    private final RFQItemRepository itemRepository;
    private final RFQItemService itemService;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final RfqAggregationService rfqAggregationService;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final SupplierRepository supplierRepository;
    private final ProcurementMethodPolicy procurementMethodPolicy;
    private final ProcurementProcedureRepository procurementProcedureRepository;
    private final SupplierPortalEligibilityValidator supplierPortalEligibilityValidator;
    private final SupplierEligibilityService supplierEligibilityService;
    private final SupplierAccreditationDemandResolver accreditationDemandResolver;
    private final com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy supplierGovernanceActionPolicy;
    private final UserRepository userRepository;

    /**
     * Persists an RFQ that another aggregate has already validated — currently only the
     * procurement campaign's create-rfq action. Routing through beforeCreate keeps that path
     * identical to POST /api/rfq: the RFQ starts in DRAFT, publication state is reset, and the
     * procedure/selection vocabulary is normalized. Writing to the repository directly skipped
     * all of it and produced RFQs that were already OPEN.
     */
    @Transactional
    public RFQ createLinked(RFQ entity) {
        beforeCreate(entity, null);
        return repository.save(entity);
    }

    protected void beforeCreate(RFQ entity, RFQCreateRequest dto) {
        entity.setStatus("DRAFT");
        entity.setPublishedAt(null);
        entity.setPublishedBy(null);
        entity.setActivePublishedVersionId(null);
        entity.setClosedAt(null);
        normalizePurpose(entity);
        if (entity.getPurchaseRequestId() != null && entity.getProcurementProcedureId() == null) {
            throw new BusinessException(
                "Purchase-request RFQs must be created through an approved procurement method procedure",
                "procurement.pmd.required"
            );
        }
        enforceProcedureOnCreate(entity);
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        String parentName = null;
        if (entity.getPurchaseRequestId() != null) {
            parentName = purchaseRequestRepository.findByIdAndIsDeletedFalse(entity.getPurchaseRequestId())
                .map(PurchaseRequest::getName).orElse(null);
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), parentName
        ));

        normalize(entity);
    }
    protected void beforeUpdate(RFQ entity, RFQUpdateRequest dto) {
        String parentName = null;
        if (entity.getPurchaseRequestId() != null) {
            parentName = purchaseRequestRepository.findByIdAndIsDeletedFalse(entity.getPurchaseRequestId())
                .map(PurchaseRequest::getName).orElse(null);
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), parentName
        ));

        normalizePurpose(entity);
        enforceApprovedProcedureMethod(entity);
        normalize(entity);
    }
    private void normalizePurpose(RFQ entity) {
        String value = entity.getPurpose() == null || entity.getPurpose().isBlank()
            ? RfqPurpose.PROCUREMENT.name()
            : entity.getPurpose().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            RfqPurpose.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Unsupported RFQ purpose: " + entity.getPurpose());
        }
        entity.setPurpose(value);
    }
    private void normalize(RFQ entity) {
        entity.setTenderProcedureType(normalizeTenderProcedure(entity.getTenderProcedureType()));
        entity.setSelectionRule(normalizeSelectionRule(entity.getSelectionRule()));
        entity.setPackageLanguage(normalizePackageLanguage(entity.getPackageLanguage()));
        if (entity.getMinQuotationsRequired() == null || entity.getMinQuotationsRequired() < 1) {
            entity.setMinQuotationsRequired(2);
        }
    }

    private void enforceProcedureOnCreate(RFQ entity) {
        if (RfqPurpose.PRICE_DISCOVERY.name().equals(entity.getPurpose())) {
            throw new BusinessException("Price discovery RFQs must be created through a procurement campaign");
        }
        if (entity.getSourceRfqId() != null) {
            throw new BusinessException("Source-derived tender RFQs must be created through finalized price discovery");
        }
        if (entity.getProcurementProcedureId() == null) {
            return;
        }
        ProcurementProcedure procedure = procurementProcedureRepository.findByIdAndIsDeletedFalse(entity.getProcurementProcedureId())
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> CompanyContext.currentCompany().equals(candidate.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Procurement procedure not found: " + entity.getProcurementProcedureId()));
        if (!ProcurementProcedureStatus.LAUNCHED.name().equals(procedure.getStatus())) {
            throw new BusinessException("Procurement procedure must be LAUNCHED before RFQ creation");
        }
        String approvedMethod = procurementMethodPolicy.normalize(procedure.getMethod());
        entity.setTenderProcedureType(approvedMethod);
        if (procedure.isRequiresPriceDiscovery()
            && Set.of(ProcurementMethodType.OPEN_TENDER.name(), ProcurementMethodType.CLOSED_TENDER.name()).contains(approvedMethod)) {
            throw new BusinessException("Open and closed tenders require finalized price discovery before tender creation");
        }
    }

    private String normalizePackageLanguage(String value) {
        String normalized = value == null || value.isBlank()
            ? "ru"
            : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ru", "uz", "en" -> normalized;
            default -> throw new BusinessException("Unsupported RFQ package language: " + value);
        };
    }
    private String normalizeTenderProcedure(String value) {
        ProcurementMethodType method = procurementMethodPolicy.require(
            value == null || value.isBlank() ? ProcurementMethodType.REQUEST_FOR_QUOTATION.name() : value
        );
        if (!method.usesRfqEngine()) {
            throw new BusinessException("Procurement method does not use the RFQ engine: " + method.name());
        }
        return method.name();
    }

    private void enforceApprovedProcedureMethod(RFQ entity) {
        if (entity.getId() == null) {
            return;
        }
        procurementProcedureRepository.findByRfqIdAndIsDeletedFalse(entity.getId()).ifPresent(procedure -> {
            String approvedMethod = procurementMethodPolicy.normalize(procedure.getMethod());
            if (entity.getTenderProcedureType() == null || entity.getTenderProcedureType().isBlank()) {
                entity.setTenderProcedureType(approvedMethod);
                return;
            }
            String requestedMethod = procurementMethodPolicy.normalize(entity.getTenderProcedureType());
            if (!approvedMethod.equals(requestedMethod)) {
                throw new BusinessException("RFQ cannot override approved procurement procedure method");
            }
            entity.setTenderProcedureType(approvedMethod);
        });
    }
    private String normalizeSelectionRule(String value) {
        String normalized = normalizeCode(value == null || value.isBlank() ? "LOWEST_PRICE" : value);
        return switch (normalized) {
            case "LOWEST_PRICE", "BEST_SCORE", "SINGLE_SUPPLIER" -> normalized;
            default -> throw new BusinessException("Unsupported selection rule: " + value);
        };
    }
    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    @Transactional(readOnly = true)
    public Page<RFQResponse> search(RFQSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var pageRequest = dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable;
        var spec = ProcurementSearchSpecifications.build(
            RFQ.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        var records = repository.findAll(spec, pageRequest);
        if (!dataScope.hasDocumentScope()) {
            return records.map(this::toResponse);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> entity.getPurchaseRequest() != null
                && roleDataScopeService.canAccessDocument(
                    entity.getPurchaseRequest().getCreatedBy(),
                    entity.getPurchaseRequest().getRequestedBy(),
                    entity.getPurchaseRequest().getDepartmentId()
                ),
            this::toResponse
        );
    }
    @Transactional(readOnly = true)
    public RFQResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public RFQResponse create(RFQCreateRequest dto) {
        RFQ entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        // Child item creation resolves its parent through RFQRepository. Flush the aggregate
        // root first so PostgreSQL and the nested service can see it in this transaction.
        RFQ saved = repository.saveAndFlush(entity);
        saveSubmittedItems(saved.getId(), dto.getItems(), false);
        synchronizeInvitations(saved, dto.getInvitedSupplierIds(), false);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }
    @Transactional
    public RFQResponse update(UUID id, RFQUpdateRequest dto) {
        RFQ entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        if (!Set.of("DRAFT", "AMENDMENT_DRAFT").contains(entity.getStatus())) {
            if (dto.changesSupplierFacingFields()) {
                throw new BusinessException("Published RFQ requires an amendment");
            }
            throw new BusinessException("Published RFQ requires an amendment");
        }
        String previousStatus = entity.getStatus();
        if (dto.getItems() != null) {
            rfqAggregationService.assertRfqStructureMutable(id);
        }
        UUID purchaseRequestId = entity.getPurchaseRequestId();
        String purpose = entity.getPurpose();
        UUID procurementProcedureId = entity.getProcurementProcedureId();
        UUID sourceRfqId = entity.getSourceRfqId();
        UUID priceBasisCalculationId = entity.getPriceBasisCalculationId();
        boolean aggregatedTender = entity.isAggregatedTender();
        var aggregationMode = entity.getAggregationMode();
        UUID materialGroupId = entity.getMaterialGroupId();
        Integer sourcePurchaseRequestCount = entity.getSourcePurchaseRequestCount();
        Integer sourceItemCount = entity.getSourceItemCount();
        String currencyCode = entity.getCurrencyCode();
        mapper.updateEntity(entity, dto);
        if (aggregatedTender || sourceRepository.existsByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id, "ACTIVE")) {
            entity.setPurchaseRequestId(purchaseRequestId);
            entity.setAggregatedTender(aggregatedTender);
            entity.setAggregationMode(aggregationMode);
            entity.setMaterialGroupId(materialGroupId);
            entity.setSourcePurchaseRequestCount(sourcePurchaseRequestCount);
            entity.setSourceItemCount(sourceItemCount);
            entity.setCurrencyCode(currencyCode);
        }
        if (procurementProcedureId != null
            || sourceRfqId != null
            || priceBasisCalculationId != null
            || RfqPurpose.PRICE_DISCOVERY.name().equals(purpose)) {
            entity.setPurpose(purpose);
            entity.setProcurementProcedureId(procurementProcedureId);
            entity.setSourceRfqId(sourceRfqId);
            entity.setPriceBasisCalculationId(priceBasisCalculationId);
        }
        entity.setStatus(previousStatus);
        beforeUpdate(entity, dto);
        // PUT may attach a purchase request while the read-only association is cleared by the
        // mapper. Flush its scalar FK before child services re-resolve the parent and its scope.
        RFQ saved = repository.saveAndFlush(entity);
        saveSubmittedItems(saved.getId(), dto.getItems(), true);
        synchronizeInvitations(saved, dto.getInvitedSupplierIds(), true);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        RFQ entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        rfqAggregationService.assertRfqStructureMutable(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public RFQResponse restore(UUID id) {
        RFQ entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        RFQ saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    protected RFQ findEntity(UUID id) {
        RFQ entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (!canAccessRfqSources(entity)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private boolean canAccessRfqSources(RFQ entity) {
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        if (!scope.hasDocumentScope()) {
            return true;
        }
        var sources = sourceRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getId());
        if (sources.isEmpty()) {
            return entity.getPurchaseRequest() == null
                || roleDataScopeService.canAccessDocument(
                    entity.getPurchaseRequest().getCreatedBy(),
                    entity.getPurchaseRequest().getRequestedBy(),
                    entity.getPurchaseRequest().getDepartmentId());
        }
        var purchaseRequestIds = sources.stream()
            .map(source -> source.getPurchaseRequestId())
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        var purchaseRequests = purchaseRequestRepository.findAllById(purchaseRequestIds);
        if (purchaseRequests.size() != purchaseRequestIds.size()) {
            return false;
        }
        for (PurchaseRequest purchaseRequest : purchaseRequests) {
            if (!roleDataScopeService.canAccessDocument(
                purchaseRequest.getCreatedBy(),
                purchaseRequest.getRequestedBy(),
                purchaseRequest.getDepartmentId())) {
                return false;
            }
        }
        return true;
    }

    private void saveSubmittedItems(UUID rfqId, List<RFQItemCreateRequest> items, boolean replaceExisting) {
        if (items == null) {
            return;
        }
        if (replaceExisting) {
            itemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfqId)
                .forEach(item -> {
                    item.softDelete();
                    itemRepository.save(item);
                });
        }
        items.forEach(item -> {
            item.setRfqId(rfqId);
            itemService.create(item);
        });
    }

    private RFQResponse toResponse(RFQ entity) {
        List<UUID> supplierIds = invitationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                entity.getTenantId(), entity.getCompanyId(), entity.getId())
            .stream()
            .filter(invitation -> "INVITED".equals(invitation.getStatus()))
            .map(RfqSupplierInvitation::getSupplierId)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
        RFQResponse response = mapper.toResponse(entity, supplierIds);
        applyUserDisplayNames(response, entity);
        return response;
    }

    private void applyUserDisplayNames(RFQResponse response, RFQ entity) {
        if (response == null || entity == null) {
            return;
        }
        response.setCreatedBy(userDisplayName(entity.getCreatedBy(), entity));
        response.setUpdatedBy(userDisplayName(entity.getUpdatedBy(), entity));
        response.setResponsibleBuyer(userDisplayName(entity.getResponsibleBuyer(), entity));
        response.setPublishedBy(userDisplayName(entity.getPublishedBy(), entity));
    }

    private String userDisplayName(String username, RFQ entity) {
        String normalized = blank(username);
        if (normalized == null || userRepository == null) {
            return normalized;
        }
        return userRepository
            .resolveActiveUserByUsername(entity.getTenantId(), entity.getCompanyId(), normalized)
            .map(this::userDisplayName)
            .orElse(normalized);
    }

    private String userDisplayName(User user) {
        return firstNonBlank(user.getFullName(), user.getName(), user.getUsername());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = blank(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void synchronizeInvitations(RFQ rfq, List<UUID> requestedSupplierIds, boolean preserveWhenNull) {
        if (preserveWhenNull && requestedSupplierIds == null) {
            return;
        }
        Set<UUID> targetSupplierIds = validateInvitedSuppliers(requestedSupplierIds, rfq);
        List<RfqSupplierInvitation> active = invitationRepository.findActiveForUpdate(
            rfq.getTenantId(), rfq.getCompanyId(), rfq.getId());
        Set<UUID> retainedSupplierIds = new LinkedHashSet<>();
        List<RfqSupplierInvitation> changed = new ArrayList<>();
        for (RfqSupplierInvitation invitation : active) {
            if (targetSupplierIds.contains(invitation.getSupplierId())) {
                retainedSupplierIds.add(invitation.getSupplierId());
            } else {
                invitation.setStatus("WITHDRAWN");
                changed.add(invitation);
            }
        }
        for (UUID supplierId : targetSupplierIds) {
            if (retainedSupplierIds.contains(supplierId)) {
                continue;
            }
            RfqSupplierInvitation invitation = new RfqSupplierInvitation();
            invitation.setCode("RFQ-INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            invitation.setName(BusinessNameGenerator.workflowDocumentName("RfqSupplierInvitation", rfq.getName()));
            invitation.setStatus("INVITED");
            invitation.setRfqId(rfq.getId());
            invitation.setSupplierId(supplierId);
            changed.add(invitation);
        }
        if (!changed.isEmpty()) {
            invitationRepository.saveAll(changed);
        }
    }

    private Set<UUID> validateInvitedSuppliers(List<UUID> requestedSupplierIds, RFQ rfq) {
        if (requestedSupplierIds == null || requestedSupplierIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<UUID> supplierIds = new LinkedHashSet<>(requestedSupplierIds);
        if (supplierIds.contains(null)) {
            throw invalidInvitedSupplier();
        }
        Map<UUID, Supplier> suppliers = supplierRepository.findAllById(supplierIds).stream()
            .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        boolean valid = suppliers.size() == supplierIds.size() && supplierIds.stream().allMatch(supplierId -> {
            Supplier supplier = suppliers.get(supplierId);
            return supplier != null
                && !supplier.isDeleted()
                && TenantContext.currentTenant().equals(supplier.getTenantId())
                && CompanyContext.currentCompany().equals(supplier.getCompanyId())
                && List.of("ACTIVE", "LIMITED").contains(supplier.getStatus());
        });
        if (!valid) {
            throw invalidInvitedSupplier();
        }
        requireSupplierPortalEligibility(supplierIds, suppliers, rfq);
        return supplierIds;
    }

    private void requireSupplierPortalEligibility(Set<UUID> supplierIds, Map<UUID, Supplier> suppliers, RFQ rfq) {
        supplierGovernanceActionPolicy.requireSupplierActionAllowed(isTenderLike(rfq) ? "ADD_TO_TENDER" : "ADD_TO_RFQ");
        var rfqItems = itemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            rfq.getId()
        );
        var demand = accreditationDemandResolver.forRfqItems(rfqItems);
        for (UUID supplierId : supplierIds) {
            Supplier supplier = suppliers.get(supplierId);
            var eligibility = supplierEligibilityService.evaluateNewBusiness(
                supplierId,
                demand,
                "RFQ_INVITATION"
            );
            if (!eligibility.newBusinessAllowed()) {
                throw new BusinessException(
                    "Supplier is not eligible for RFQ invitation: supplierId="
                        + supplierId + ", reason=" + eligibility.summaryReason()
                );
            }
            List<SupplierPortalEligibilityValidator.EligibilityIssue> issues =
                supplierPortalEligibilityValidator.validate(supplier);
            if (!issues.isEmpty()) {
                throw new BusinessException(
                    "RFQ invitations require supplier portal email/account eligibility: supplierId="
                        + supplierId + ", issue=" + issues.get(0).code()
                );
            }
        }
    }

    private boolean isTenderLike(RFQ rfq) {
        if (rfq == null || rfq.getTenderProcedureType() == null || rfq.getTenderProcedureType().isBlank()) {
            return false;
        }
        return Set.of(ProcurementMethodType.OPEN_TENDER.name(), ProcurementMethodType.CLOSED_TENDER.name())
            .contains(rfq.getTenderProcedureType().trim().toUpperCase(Locale.ROOT));
    }

    private BusinessException invalidInvitedSupplier() {
        return new BusinessException("RFQ invitations require an active supplier in the current tenant and company");
    }
}
