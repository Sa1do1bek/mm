package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.AdvanceShippingNoticeResponse;
import com.enterprise.mm.payload.AdvanceShippingNoticeUpdateRequest;
import com.enterprise.mm.payload.AdvanceShippingNoticeCreateRequest;
import com.enterprise.mm.payload.AdvanceShippingNoticeSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.AdvanceShippingNoticeItemCreateRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AdvanceShippingNoticeMapper;
import com.enterprise.mm.mapper.AdvanceShippingNoticeItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.TransportMode;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.fulfillment.FulfillmentQuantities;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
public class AdvanceShippingNoticeService {
    private final AdvanceShippingNoticeRepository repository;
    private final AdvanceShippingNoticeMapper mapper;
    private final String entityName = "AdvanceShippingNotice";
    private final AuditService auditService;
    private final AdvanceShippingNoticeItemRepository itemRepository;
    private final AdvanceShippingNoticeItemMapper itemMapper;
    private final SupplyLogisticsGuard guard;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final IntegrationEventService integrationEventService;
    private final RoleDataScopeService roleDataScopeService;
    private final SupplierAccreditationService supplierAccreditationService;
    private final SupplierEligibilityService supplierEligibilityService;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final MaterialRepository materialRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public AdvanceShippingNoticeService(
        AdvanceShippingNoticeRepository repository,
        AdvanceShippingNoticeMapper mapper,
        AuditService auditService,
        AdvanceShippingNoticeItemRepository itemRepository,
        AdvanceShippingNoticeItemMapper itemMapper,
        SupplyLogisticsGuard guard,
        MaterialCatalogGuard materialCatalogGuard,
        IntegrationEventService integrationEventService,
        RoleDataScopeService roleDataScopeService,
        SupplierAccreditationService supplierAccreditationService,
        SupplierEligibilityService supplierEligibilityService,
        PurchaseOrderItemRepository purchaseOrderItemRepository,
        MaterialRepository materialRepository
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.itemRepository = itemRepository;
        this.itemMapper = itemMapper;
        this.guard = guard;
        this.materialCatalogGuard = materialCatalogGuard;
        this.integrationEventService = integrationEventService;
        this.roleDataScopeService = roleDataScopeService;
        this.supplierAccreditationService = supplierAccreditationService;
        this.supplierEligibilityService = supplierEligibilityService;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.materialRepository = materialRepository;
    }

    public AdvanceShippingNoticeService(
        AdvanceShippingNoticeRepository repository,
        AdvanceShippingNoticeMapper mapper,
        AuditService auditService,
        AdvanceShippingNoticeItemRepository itemRepository,
        AdvanceShippingNoticeItemMapper itemMapper,
        SupplyLogisticsGuard guard,
        MaterialCatalogGuard materialCatalogGuard,
        IntegrationEventService integrationEventService,
        RoleDataScopeService roleDataScopeService,
        SupplierAccreditationService supplierAccreditationService,
        PurchaseOrderItemRepository purchaseOrderItemRepository,
        MaterialRepository materialRepository
    ) {
        this(repository, mapper, auditService, itemRepository, itemMapper, guard, materialCatalogGuard,
            integrationEventService, roleDataScopeService, supplierAccreditationService, null,
            purchaseOrderItemRepository, materialRepository);
    }
    protected void beforeCreate(AdvanceShippingNotice entity, AdvanceShippingNoticeCreateRequest dto) {
        normalize(entity);
        validateBusinessLinks(entity);
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

        if (entity.getAsnNumber() == null || entity.getAsnNumber().isBlank()) {
            entity.setAsnNumber(entity.getCode());
        }
        ensureUniqueAsn(entity);
        entity.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
    }
    protected void beforeUpdate(AdvanceShippingNotice entity, AdvanceShippingNoticeUpdateRequest dto) {
        if (!AdvanceShippingNoticeStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT ASN can be edited");
        }
        normalize(entity);
        validateBusinessLinks(entity);
        ensureUniqueAsn(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    public AdvanceShippingNoticeResponse submit(UUID id) {
        AdvanceShippingNotice asn = findEntity(id);
        if (!AdvanceShippingNoticeStatus.DRAFT.name().equals(asn.getStatus())) {
            throw new BusinessException("Only DRAFT ASN can be submitted");
        }
        List<AdvanceShippingNoticeItem> items = itemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id
        );
        if (items.isEmpty()) {
            throw new BusinessException("ASN must have at least one item");
        }
        validateSubmissionReadiness(asn, items);
        requireAsnSupplierEligible(asn.getSupplierId());
        asn.setStatus(AdvanceShippingNoticeStatus.SENT.name());
        asn.setSentAt(LocalDateTime.now());
        integrationEventService.emit("ETTN.ASN_SENT", "ETTN", asn.getId(), Map.of(
            "asnId", asn.getId(),
            "asnNumber", asn.getAsnNumber() == null ? "" : asn.getAsnNumber(),
            "purchaseOrderId", asn.getPurchaseOrderId() == null ? "" : asn.getPurchaseOrderId().toString()
        ));
        auditService.log("AdvanceShippingNotice", id, "SUBMIT", asn.getAsnNumber(), null, null);
        return mapper.toResponse(repository.save(asn));
    }

    private void requireAsnSupplierEligible(UUID supplierId) {
        if (supplierEligibilityService != null) {
            supplierEligibilityService.requireExistingObligationAllowed(
                supplierId,
                "ADVANCE_SHIPPING_NOTICE_SUBMISSION"
            );
            return;
        }
        supplierAccreditationService.assertSupplierEligible(supplierId, List.of(), "advance shipping notice submission");
    }
    @Transactional
    public AdvanceShippingNoticeResponse confirm(UUID id) {
        AdvanceShippingNotice asn = findEntity(id);
        if (!AdvanceShippingNoticeStatus.SENT.name().equals(asn.getStatus())) {
            throw new BusinessException("Only SENT ASN can be confirmed");
        }
        asn.setStatus(AdvanceShippingNoticeStatus.CONFIRMED.name());
        asn.setConfirmedAt(LocalDateTime.now());
        auditService.log("AdvanceShippingNotice", id, "CONFIRM", asn.getAsnNumber(), null, null);
        return mapper.toResponse(repository.save(asn));
    }
    @Transactional
    public AdvanceShippingNoticeResponse markReceived(UUID id) {
        findEntity(id);
        throw new BusinessException("ASN receipt must be posted through purchase order receipt orchestration");
    }
    @Transactional
    public AdvanceShippingNoticeResponse cancel(UUID id) {
        AdvanceShippingNotice asn = findEntity(id);
        if (AdvanceShippingNoticeStatus.RECEIVED.name().equals(asn.getStatus())) {
            throw new BusinessException("Received ASN cannot be cancelled");
        }
        asn.setStatus(AdvanceShippingNoticeStatus.CANCELLED.name());
        auditService.log("AdvanceShippingNotice", id, "CANCEL", asn.getAsnNumber(), null, null);
        return mapper.toResponse(repository.save(asn));
    }
    @Transactional
    public void recalculateTotals(UUID asnId) {
        AdvanceShippingNotice asn = findEntity(asnId);
        BigDecimal totalQuantity = itemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), asnId)
            .stream()
            .map(item -> nvl(item.getQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        asn.setTotalQuantity(totalQuantity);
        repository.save(asn);
    }
    private void normalize(AdvanceShippingNotice entity) {
        if (entity.getNoticeDate() == null) {
            entity.setNoticeDate(LocalDate.now());
        }
        if (entity.getAsnNumber() == null || entity.getAsnNumber().isBlank()) {
            entity.setAsnNumber(entity.getCode());
        } else {
            entity.setAsnNumber(entity.getAsnNumber().trim());
        }
        if (entity.getTransportMode() != null && !entity.getTransportMode().isBlank()) {
            entity.setTransportMode(normalizeTransportMode(entity.getTransportMode()));
        }
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode("UZS");
        } else {
            entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }
        if (entity.getExpectedShipDate() != null && entity.getExpectedArrivalDate() != null && entity.getExpectedArrivalDate().isBefore(entity.getExpectedShipDate())) {
            throw new BusinessException("expectedArrivalDate must be greater than or equal to expectedShipDate");
        }
        entity.setTotalQuantity(nvl(entity.getTotalQuantity()));
        entity.setTotalAmount(nvl(entity.getTotalAmount()));
    }
    private void validateBusinessLinks(AdvanceShippingNotice entity) {
        guard.supplier(entity.getSupplierId());
        PurchaseOrder po = guard.purchaseOrder(entity.getPurchaseOrderId());
        guard.incoterm(entity.getIncotermId());
        if (po != null && !po.getSupplierId().equals(entity.getSupplierId())) {
            throw new BusinessException("ASN supplier must match purchase order supplier");
        }
    }
    private void ensureUniqueAsn(AdvanceShippingNotice entity) {
        if (entity.getAsnNumber() == null || entity.getAsnNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndAsnNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getAsnNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("ASN number already exists");
            });
    }
    private String normalizeTransportMode(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (TransportMode mode : TransportMode.values()) {
            if (mode.name().equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException("Unsupported transportMode: " + value);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<AdvanceShippingNoticeResponse> search(AdvanceShippingNoticeSearchRequest request, Pageable pageable) {
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
            filter.supplierId(),
            filter.contractId(),
            filter.purchaseOrderId(),
            pageRequest
        );
        if (!dataScope.hasDocumentScope()) {
            return records.map(mapper::toResponse);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> entity.getPurchaseOrder() != null
                && entity.getPurchaseOrder().getPurchaseRequest() != null
                && roleDataScopeService.canAccessDocument(
                    entity.getPurchaseOrder().getPurchaseRequest().getCreatedBy(),
                    entity.getPurchaseOrder().getPurchaseRequest().getRequestedBy(),
                    entity.getPurchaseOrder().getPurchaseRequest().getDepartmentId()
                ),
            mapper::toResponse
        );
    }
    @Transactional(readOnly = true)
    public AdvanceShippingNoticeResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public AdvanceShippingNoticeResponse create(AdvanceShippingNoticeCreateRequest dto) {
        AdvanceShippingNotice entity = mapper.toEntity(dto);
        recalculateTotals(entity, dto);
        beforeCreate(entity, dto);
        AdvanceShippingNotice saved = repository.save(entity);
        saveSubmittedItems(saved.getId(), dto.getItems(), false);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public AdvanceShippingNoticeResponse update(UUID id, AdvanceShippingNoticeUpdateRequest dto) {
        AdvanceShippingNotice entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        if (!AdvanceShippingNoticeStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT ASN can be edited");
        }
        UUID supplierId = entity.getSupplierId();
        UUID purchaseOrderId = entity.getPurchaseOrderId();
        UUID contractId = entity.getContractId();
        if (!Objects.equals(supplierId, dto.getSupplierId())) {
            throw new BusinessException("ASN supplier cannot be changed");
        }
        if (!Objects.equals(purchaseOrderId, dto.getPurchaseOrderId())) {
            throw new BusinessException("ASN cannot be moved to another purchase order");
        }
        if (!Objects.equals(contractId, dto.getContractId())) {
            throw new BusinessException("ASN cannot be moved to another contract");
        }
        String status = entity.getStatus();
        LocalDateTime sentAt = entity.getSentAt();
        LocalDateTime confirmedAt = entity.getConfirmedAt();
        LocalDateTime receivedAt = entity.getReceivedAt();
        BigDecimal totalQuantity = entity.getTotalQuantity();
        BigDecimal totalAmount = entity.getTotalAmount();
        mapper.updateEntity(entity, dto);
        entity.setSupplierId(supplierId);
        entity.setPurchaseOrderId(purchaseOrderId);
        entity.setContractId(contractId);
        entity.setStatus(status);
        entity.setSentAt(sentAt);
        entity.setConfirmedAt(confirmedAt);
        entity.setReceivedAt(receivedAt);
        entity.setTotalQuantity(totalQuantity);
        entity.setTotalAmount(totalAmount);
        recalculateTotals(entity, dto);
        beforeUpdate(entity, dto);
        AdvanceShippingNotice saved = repository.save(entity);
        saveSubmittedItems(saved.getId(), dto.getItems(), true);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        AdvanceShippingNotice entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public AdvanceShippingNoticeResponse restore(UUID id) {
        AdvanceShippingNotice entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        AdvanceShippingNotice saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected AdvanceShippingNotice findEntity(UUID id) {
        AdvanceShippingNotice entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (entity.getPurchaseOrder() != null
            && entity.getPurchaseOrder().getPurchaseRequest() != null
            && !roleDataScopeService.canAccessDocument(
                entity.getPurchaseOrder().getPurchaseRequest().getCreatedBy(),
                entity.getPurchaseOrder().getPurchaseRequest().getRequestedBy(),
                entity.getPurchaseOrder().getPurchaseRequest().getDepartmentId()
            )) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private void saveSubmittedItems(UUID asnId, List<AdvanceShippingNoticeItemCreateRequest> items, boolean replaceExisting) {
        if (items == null) {
            return;
        }
        if (replaceExisting) {
            itemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), asnId)
                .forEach(item -> {
                    item.softDelete();
                    itemRepository.save(item);
                });
        }
        items.forEach(item -> {
            item.setAsnId(asnId);
            AdvanceShippingNoticeItem entity = itemMapper.toEntity(item);
            validateInlineItem(entity);
            if (entity.getStatus() == null || entity.getStatus().isBlank()) {
                entity.setStatus("ACTIVE");
            }
            if (entity.getCompanyId() == null) {
                entity.setCompanyId(CompanyContext.currentCompany());
            }
            if (entity.getCode() == null || entity.getCode().isBlank()) {
                entity.setCode("ADVANCE_SHIPPING_NOTICE_ITEM-" + UUID.randomUUID().toString().substring(0, 8));
            }
            entity.setName(BusinessDisplayNamePolicy.requireHumanName(
                "Shipping notice item", entity.getName(), entity.getCode()
            ));
            itemRepository.save(entity);
        });
        recalculateTotals(asnId);
    }

    private void validateInlineItem(AdvanceShippingNoticeItem entity) {
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
        validateItemReadiness(entity);
    }

    void validateItemReadiness(AdvanceShippingNoticeItem entity) {
        AdvanceShippingNotice asn = guard.asn(entity.getAsnId());
        if (asn == null) {
            throw new BusinessException("asnId is required");
        }
        PurchaseOrder po = requireLinkedPurchaseOrder(asn);
        PurchaseOrderItem poItem = validatePurchaseOrderItem(asn, entity);
        BigDecimal currentQuantity = positiveQuantity(entity);
        BigDecimal otherQuantity = itemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), asn.getId()
            ).stream()
            .filter(item -> !Objects.equals(item.getId(), entity.getId()))
            .filter(item -> Objects.equals(item.getPurchaseOrderItemId(), poItem.getId()))
            .map(item -> nvl(item.getQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (otherQuantity.add(currentQuantity).compareTo(openQuantity(poItem)) > 0) {
            throw new BusinessException("ASN item quantity exceeds open purchase order line quantity");
        }
        if (!Objects.equals(po.getSupplierId(), asn.getSupplierId())) {
            throw new BusinessException("ASN supplier must match purchase order supplier");
        }
    }

    private List<UUID> validateSubmissionReadiness(AdvanceShippingNotice asn, List<AdvanceShippingNoticeItem> items) {
        PurchaseOrder po = requireLinkedPurchaseOrder(asn);
        if (!List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name()).contains(po.getStatus())) {
            throw new BusinessException("ASN purchase order must be SENT or PARTIALLY_RECEIVED");
        }
        if (!Objects.equals(po.getSupplierId(), asn.getSupplierId())) {
            throw new BusinessException("ASN supplier must match purchase order supplier");
        }
        Map<UUID, BigDecimal> quantitiesByPoItem = new LinkedHashMap<>();
        Map<UUID, PurchaseOrderItem> poItemsById = new LinkedHashMap<>();
        LinkedHashSet<UUID> materialGroupIds = new LinkedHashSet<>();
        for (AdvanceShippingNoticeItem item : items) {
            materialCatalogGuard.requireUsableMaterial(item.getMaterialId(), "materialId");
            PurchaseOrderItem poItem = validatePurchaseOrderItem(asn, item);
            quantitiesByPoItem.merge(poItem.getId(), positiveQuantity(item), BigDecimal::add);
            poItemsById.put(poItem.getId(), poItem);
            Material material = materialRepository
                .findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), item.getMaterialId())
                .filter(candidate -> Objects.equals(asn.getCompanyId(), candidate.getCompanyId()))
                .orElseThrow(() -> new BusinessException("ASN material is unavailable in the current company"));
            if (material.getMaterialGroupId() == null) {
                throw new BusinessException("Material group is required for every material line in ASN submission");
            }
            materialGroupIds.add(material.getMaterialGroupId());
        }
        quantitiesByPoItem.forEach((poItemId, quantity) -> {
            if (quantity.compareTo(openQuantity(poItemsById.get(poItemId))) > 0) {
                throw new BusinessException("ASN item quantity exceeds open purchase order line quantity");
            }
        });
        return List.copyOf(materialGroupIds);
    }

    private PurchaseOrder requireLinkedPurchaseOrder(AdvanceShippingNotice asn) {
        if (asn.getPurchaseOrderId() == null) {
            throw new BusinessException("ASN must be linked to a purchase order before submission");
        }
        PurchaseOrder po = guard.purchaseOrder(asn.getPurchaseOrderId());
        if (po == null) {
            throw new BusinessException("Purchase order not found");
        }
        return po;
    }

    private PurchaseOrderItem validatePurchaseOrderItem(AdvanceShippingNotice asn, AdvanceShippingNoticeItem item) {
        if (item.getPurchaseOrderItemId() == null) {
            throw new BusinessException("purchaseOrderItemId is required for each ASN item");
        }
        PurchaseOrderItem poItem = purchaseOrderItemRepository.findByIdAndIsDeletedFalse(item.getPurchaseOrderItemId())
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(asn.getCompanyId(), candidate.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Purchase order item not found"));
        if (!Objects.equals(asn.getPurchaseOrderId(), poItem.getPurchaseOrderId())) {
            throw new BusinessException("ASN item purchase order line does not belong to ASN purchase order");
        }
        if (poItem.getLineType() != ProcurementLineType.MATERIAL) {
            throw new BusinessException("ASN item must reference a MATERIAL purchase order line");
        }
        if (!Objects.equals(item.getMaterialId(), poItem.getMaterialId())) {
            throw new BusinessException("ASN item material must match purchase order line material");
        }
        StockUomPolicy.purchasing(materialCatalogGuard.requireActiveMaterial(poItem.getMaterialId(), "materialId"), poItem.getUnit(), item.getQuantity());
        return poItem;
    }

    private BigDecimal positiveQuantity(AdvanceShippingNoticeItem item) {
        BigDecimal quantity = nvl(item.getQuantity());
        if (quantity.signum() <= 0) {
            throw new BusinessException("ASN item quantity must be greater than zero");
        }
        return quantity;
    }

    private BigDecimal openQuantity(PurchaseOrderItem item) {
        if (item != null && item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET) {
            return FulfillmentQuantities.acceptedFulfillmentRemaining(item);
        }
        return FulfillmentQuantities.physicalReceiptRemaining(item);
    }

    private void recalculateTotals(AdvanceShippingNotice entity, AdvanceShippingNoticeCreateRequest dto) {
        if (dto.getItems() == null) {
            return;
        }
        BigDecimal totalQuantity = dto.getItems().stream()
            .map(AdvanceShippingNoticeItemCreateRequest::getQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        entity.setTotalQuantity(totalQuantity);
    }
}
