package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ImportDeliveryResponse;
import com.enterprise.mm.payload.ImportDeliveryUpdateRequest;
import com.enterprise.mm.payload.ImportDeliveryCreateRequest;
import com.enterprise.mm.payload.ImportDeliverySearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ImportDeliveryMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.CustomsDeclarationStatus;
import com.enterprise.mm.enums.ImportDeliveryStatus;
import com.enterprise.mm.enums.LandedCostComponentType;
import com.enterprise.mm.enums.TransportMode;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.repository.CustomsDeclarationRepository;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.LandedCostComponentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImportDeliveryService {
    private final String entityName = "ImportDelivery";
    private static final Set<String> CUSTOMS_COMPONENT_TYPES = Set.of(
        LandedCostComponentType.CUSTOMS_DUTY.name(),
        LandedCostComponentType.VAT.name(),
        LandedCostComponentType.EXCISE.name()
    );
    private final ImportDeliveryRepository repository;
    private final ImportDeliveryMapper mapper;
    private final SupplyLogisticsGuard guard;
    private final LandedCostComponentRepository landedCostComponentRepository;
    private final CustomsDeclarationRepository customsDeclarationRepository;
    private final AuditService auditService;

    protected void beforeCreate(ImportDelivery entity, ImportDeliveryCreateRequest dto) {
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

        if (entity.getShipmentNumber() == null || entity.getShipmentNumber().isBlank()) {
            entity.setShipmentNumber(entity.getCode());
        }
        ensureUniqueShipment(entity);
        entity.setStatus(ImportDeliveryStatus.PLANNED.name());
        recalculateEntity(entity);
    }
    protected void beforeUpdate(ImportDelivery entity, ImportDeliveryUpdateRequest dto) {
        requireEditable(entity);
        normalize(entity);
        validateBusinessLinks(entity);
        ensureUniqueShipment(entity);
        recalculateEntity(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void requireEditable(ImportDelivery entity) {
        if (ImportDeliveryStatus.CLOSED.name().equals(entity.getStatus()) || ImportDeliveryStatus.CANCELLED.name().equals(entity.getStatus())) {
            throw new BusinessException("Closed or cancelled import delivery cannot be edited");
        }
    }
    @Transactional
    public ImportDeliveryResponse book(UUID id) {
        return transition(id, ImportDeliveryStatus.BOOKED.name(), ImportDeliveryStatus.PLANNED.name());
    }
    @Transactional
    public ImportDeliveryResponse startTransit(UUID id) {
        ImportDelivery delivery = findEntity(id);
        if (!ImportDeliveryStatus.PLANNED.name().equals(delivery.getStatus()) && !ImportDeliveryStatus.BOOKED.name().equals(delivery.getStatus())) {
            throw new BusinessException("Only PLANNED or BOOKED import delivery can start transit");
        }
        delivery.setStatus(ImportDeliveryStatus.IN_TRANSIT.name());
        delivery.setCurrentMilestone("IN_TRANSIT");
        delivery.setLastEventAt(LocalDateTime.now());
        auditService.log("ImportDelivery", id, "START_TRANSIT", delivery.getShipmentNumber(), null, null);
        return mapper.toResponse(repository.save(delivery));
    }
    @Transactional
    public ImportDeliveryResponse arriveCustoms(UUID id) {
        ImportDelivery delivery = findEntity(id);
        if (!ImportDeliveryStatus.IN_TRANSIT.name().equals(delivery.getStatus()) && !ImportDeliveryStatus.BOOKED.name().equals(delivery.getStatus())) {
            throw new BusinessException("Only IN_TRANSIT or BOOKED import delivery can arrive to customs");
        }
        delivery.setStatus(ImportDeliveryStatus.AT_CUSTOMS.name());
        delivery.setCurrentMilestone("AT_CUSTOMS");
        delivery.setLastEventAt(LocalDateTime.now());
        auditService.log("ImportDelivery", id, "ARRIVE_CUSTOMS", delivery.getBorderPost(), null, null);
        return mapper.toResponse(repository.save(delivery));
    }
    @Transactional
    public ImportDeliveryResponse clearCustoms(UUID id) {
        ImportDelivery delivery = findEntity(id);
        if (!ImportDeliveryStatus.AT_CUSTOMS.name().equals(delivery.getStatus()) && !ImportDeliveryStatus.IN_TRANSIT.name().equals(delivery.getStatus())) {
            throw new BusinessException("Only AT_CUSTOMS or IN_TRANSIT import delivery can be customs-cleared");
        }
        boolean hasReleasedDeclaration = customsDeclarationRepository
            .findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .stream()
            .anyMatch(declaration -> CustomsDeclarationStatus.RELEASED.name().equals(declaration.getStatus()));
        if (!hasReleasedDeclaration) {
            throw new BusinessException("Customs clearance requires at least one RELEASED customs declaration");
        }
        delivery.setStatus(ImportDeliveryStatus.CLEARED.name());
        delivery.setCurrentMilestone("CUSTOMS_RELEASED");
        delivery.setLastEventAt(LocalDateTime.now());
        recalculateEntity(delivery);
        auditService.log("ImportDelivery", id, "CLEAR_CUSTOMS", delivery.getShipmentNumber(), null, null);
        return mapper.toResponse(repository.save(delivery));
    }
    @Transactional
    public ImportDeliveryResponse deliver(UUID id) {
        ImportDelivery delivery = findEntity(id);
        if (!ImportDeliveryStatus.CLEARED.name().equals(delivery.getStatus()) && !ImportDeliveryStatus.IN_TRANSIT.name().equals(delivery.getStatus())) {
            throw new BusinessException("Only CLEARED or IN_TRANSIT import delivery can be delivered");
        }
        delivery.setStatus(ImportDeliveryStatus.DELIVERED.name());
        delivery.setCurrentMilestone("DELIVERED");
        delivery.setActualArrivalDate(LocalDate.now());
        delivery.setLastEventAt(LocalDateTime.now());
        recalculateEntity(delivery);
        auditService.log("ImportDelivery", id, "DELIVER", delivery.getDestinationPlace(), null, null);
        return mapper.toResponse(repository.save(delivery));
    }
    @Transactional
    public ImportDeliveryResponse close(UUID id) {
        ImportDelivery delivery = findEntity(id);
        if (!ImportDeliveryStatus.DELIVERED.name().equals(delivery.getStatus())) {
            throw new BusinessException("Only DELIVERED import delivery can be closed");
        }
        delivery.setStatus(ImportDeliveryStatus.CLOSED.name());
        recalculateEntity(delivery);
        auditService.log("ImportDelivery", id, "CLOSE", delivery.getShipmentNumber(), null, null);
        return mapper.toResponse(repository.save(delivery));
    }
    @Transactional
    public ImportDeliveryResponse cancel(UUID id) {
        ImportDelivery delivery = findEntity(id);
        if (ImportDeliveryStatus.DELIVERED.name().equals(delivery.getStatus()) || ImportDeliveryStatus.CLOSED.name().equals(delivery.getStatus())) {
            throw new BusinessException("Delivered or closed import delivery cannot be cancelled");
        }
        delivery.setStatus(ImportDeliveryStatus.CANCELLED.name());
        auditService.log("ImportDelivery", id, "CANCEL", delivery.getShipmentNumber(), null, null);
        return mapper.toResponse(repository.save(delivery));
    }
    @Transactional
    public ImportDeliveryResponse recalculate(UUID id) {
        ImportDelivery delivery = findEntity(id);
        recalculateEntity(delivery);
        auditService.log("ImportDelivery", id, "RECALCULATE_LANDED_COST", delivery.getLandedCostAmount().toPlainString(), null, null);
        return mapper.toResponse(repository.save(delivery));
    }
    @Transactional
    public void recalculateForImportDelivery(UUID id) {
        ImportDelivery delivery = findEntity(id);
        recalculateEntity(delivery);
        repository.save(delivery);
    }
    public void recalculateEntity(ImportDelivery delivery) {
        BigDecimal logistics = landedCostComponentRepository
            .findByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), delivery.getId())
            .stream()
            .filter(component -> component.isIncludedInInventoryCost())
            .filter(component -> !CUSTOMS_COMPONENT_TYPES.contains(component.getComponentType()))
            .map(component -> nvl(component.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal customsFromComponents = landedCostComponentRepository
            .findByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), delivery.getId())
            .stream()
            .filter(component -> component.isIncludedInInventoryCost())
            .filter(component -> CUSTOMS_COMPONENT_TYPES.contains(component.getComponentType()))
            .map(component -> nvl(component.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal customs = customsDeclarationRepository
            .findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), delivery.getId())
            .stream()
            .filter(declaration -> CustomsDeclarationStatus.RELEASED.name().equals(declaration.getStatus()))
            .map(declaration -> nvl(declaration.getTotalCustomsAmount()))
            .reduce(customsFromComponents, BigDecimal::add);
        delivery.setLogisticsCostAmount(logistics);
        delivery.setCustomsCostAmount(customs);
        delivery.setLandedCostAmount(nvl(delivery.getBaseGoodsAmount()).add(logistics).add(customs));
        if (delivery.getEta() != null && delivery.getActualArrivalDate() == null && delivery.getEta().isBefore(LocalDate.now())
            && !ImportDeliveryStatus.DELIVERED.name().equals(delivery.getStatus()) && !ImportDeliveryStatus.CLOSED.name().equals(delivery.getStatus())) {
            delivery.setRiskFlag(true);
            if (delivery.getRiskReason() == null || delivery.getRiskReason().isBlank()) {
                delivery.setRiskReason("ETA is overdue");
            }
        }
    }
    private ImportDeliveryResponse transition(UUID id, String targetStatus, String requiredStatus) {
        ImportDelivery delivery = findEntity(id);
        if (!requiredStatus.equals(delivery.getStatus())) {
            throw new BusinessException("Only " + requiredStatus + " import delivery can transition to " + targetStatus);
        }
        delivery.setStatus(targetStatus);
        delivery.setCurrentMilestone(targetStatus);
        delivery.setLastEventAt(LocalDateTime.now());
        auditService.log("ImportDelivery", id, targetStatus, delivery.getShipmentNumber(), null, null);
        return mapper.toResponse(repository.save(delivery));
    }
    private void normalize(ImportDelivery entity) {
        if (entity.getShipmentNumber() == null || entity.getShipmentNumber().isBlank()) {
            entity.setShipmentNumber(entity.getCode());
        } else {
            entity.setShipmentNumber(entity.getShipmentNumber().trim());
        }
        if (entity.getTransportMode() != null && !entity.getTransportMode().isBlank()) {
            entity.setTransportMode(normalizeTransportMode(entity.getTransportMode()));
        }
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode("UZS");
        } else {
            entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }
        if (entity.getEtd() != null && entity.getEta() != null && entity.getEta().isBefore(entity.getEtd())) {
            throw new BusinessException("eta must be greater than or equal to etd");
        }
        entity.setBaseGoodsAmount(nvl(entity.getBaseGoodsAmount()));
        entity.setLogisticsCostAmount(nvl(entity.getLogisticsCostAmount()));
        entity.setCustomsCostAmount(nvl(entity.getCustomsCostAmount()));
        entity.setLandedCostAmount(nvl(entity.getLandedCostAmount()));
    }
    private void validateBusinessLinks(ImportDelivery entity) {
        UUID supplierId = entity.getSupplier() != null ? entity.getSupplier().getId() : entity.getSupplierId();
        guard.supplier(supplierId);
        PurchaseOrder po = guard.purchaseOrder(entity.getPurchaseOrder() != null ? entity.getPurchaseOrder().getId() : entity.getPurchaseOrderId());
        AdvanceShippingNotice asn = guard.asn(entity.getAsn() != null ? entity.getAsn().getId() : entity.getAsnId());
        guard.incoterm(entity.getIncoterm() != null ? entity.getIncoterm().getId() : entity.getIncotermId());
        guard.warehouse(entity.getDestinationWarehouse() != null ? entity.getDestinationWarehouse().getId() : entity.getDestinationWarehouseId());
        if (po != null && !po.getSupplierId().equals(supplierId)) {
            throw new BusinessException("Import delivery supplier must match purchase order supplier");
        }
        if (asn != null) {
            if (!asn.getSupplierId().equals(supplierId)) {
                throw new BusinessException("Import delivery supplier must match ASN supplier");
            }
            if (entity.getPurchaseOrderId() == null) {
                entity.setPurchaseOrder(asn.getPurchaseOrder() != null ? asn.getPurchaseOrder() : po);
            }
            if (entity.getIncotermId() == null) {
                entity.setIncoterm(asn.getIncoterm());
            }
            if (entity.getBaseGoodsAmount().signum() == 0) {
                entity.setBaseGoodsAmount(nvl(asn.getTotalAmount()));
            }
        }
    }
    private void ensureUniqueShipment(ImportDelivery entity) {
        if (entity.getShipmentNumber() == null || entity.getShipmentNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndShipmentNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getShipmentNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Shipment number already exists");
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
    public Page<ImportDeliveryResponse> search(ImportDeliverySearchRequest request, Pageable pageable) {
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
    public ImportDeliveryResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ImportDeliveryResponse create(ImportDeliveryCreateRequest dto) {
        ImportDelivery entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ImportDelivery saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ImportDeliveryResponse update(UUID id, ImportDeliveryUpdateRequest dto) {
        ImportDelivery entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireEditable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ImportDelivery saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ImportDelivery entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ImportDeliveryResponse restore(UUID id) {
        ImportDelivery entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ImportDelivery saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ImportDelivery findEntity(UUID id) {
        ImportDelivery entity = repository.findByIdAndIsDeletedFalse(id)
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
