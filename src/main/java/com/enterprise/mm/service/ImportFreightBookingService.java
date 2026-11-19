package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ImportFreightBookingResponse;
import com.enterprise.mm.payload.ImportFreightBookingUpdateRequest;
import com.enterprise.mm.payload.ImportFreightBookingCreateRequest;
import com.enterprise.mm.payload.LandedCostComponentCreateRequest;
import com.enterprise.mm.payload.LandedCostComponentResponse;
import com.enterprise.mm.payload.ImportFreightBookingSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ImportFreightBookingMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ImportFreightStatus;
import com.enterprise.mm.enums.LandedCostAllocationMethod;
import com.enterprise.mm.enums.TransportMode;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.ImportFreightBooking;
import com.enterprise.mm.repository.ImportFreightBookingRepository;
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
public class ImportFreightBookingService {
    private final String entityName = "ImportFreightBooking";
    private final ImportFreightBookingRepository repository;
    private final ImportFreightBookingMapper mapper;
    private final SupplyLogisticsGuard guard;
    private final LandedCostComponentService landedCostComponentService;
    private final AuditService auditService;

    protected void beforeCreate(ImportFreightBooking entity, ImportFreightBookingCreateRequest dto) {
        hydrateFromDelivery(entity);
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

        if (entity.getBookingNumber() == null || entity.getBookingNumber().isBlank()) {
            entity.setBookingNumber(entity.getCode());
        }
        ensureUnique(entity);
        entity.setStatus(ImportFreightStatus.DRAFT.name());
    }
    protected void beforeUpdate(ImportFreightBooking entity, ImportFreightBookingUpdateRequest dto) {
        requireEditable(entity);
        hydrateFromDelivery(entity);
        normalize(entity);
        validate(entity);
        ensureUnique(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void requireEditable(ImportFreightBooking entity) {
        if (!ImportFreightStatus.DRAFT.name().equals(entity.getStatus()) && !ImportFreightStatus.BOOKED.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT or BOOKED freight booking can be edited");
        }
    }
    @Transactional
    public ImportFreightBookingResponse book(UUID id) {
        ImportFreightBooking booking = findEntity(id);
        requireStatus(booking, ImportFreightStatus.DRAFT);
        booking.setStatus(ImportFreightStatus.BOOKED.name());
        auditService.log("ImportFreightBooking", id, "BOOK", booking.getBookingNumber(), null, null);
        return mapper.toResponse(repository.save(booking));
    }
    @Transactional
    public ImportFreightBookingResponse startTransit(UUID id) {
        ImportFreightBooking booking = findEntity(id);
        if (!ImportFreightStatus.DRAFT.name().equals(booking.getStatus()) && !ImportFreightStatus.BOOKED.name().equals(booking.getStatus())) {
            throw new BusinessException("Only DRAFT or BOOKED freight booking can start transit");
        }
        booking.setStatus(ImportFreightStatus.IN_TRANSIT.name());
        booking.setActualPickupDate(booking.getActualPickupDate() == null ? LocalDate.now() : booking.getActualPickupDate());
        auditService.log("ImportFreightBooking", id, "START_TRANSIT", booking.getBookingNumber(), null, null);
        return mapper.toResponse(repository.save(booking));
    }
    @Transactional
    public ImportFreightBookingResponse deliver(UUID id) {
        ImportFreightBooking booking = findEntity(id);
        if (!ImportFreightStatus.BOOKED.name().equals(booking.getStatus()) && !ImportFreightStatus.IN_TRANSIT.name().equals(booking.getStatus())) {
            throw new BusinessException("Only BOOKED or IN_TRANSIT freight booking can be delivered");
        }
        booking.setStatus(ImportFreightStatus.DELIVERED.name());
        booking.setActualDeliveryDate(booking.getActualDeliveryDate() == null ? LocalDate.now() : booking.getActualDeliveryDate());
        auditService.log("ImportFreightBooking", id, "DELIVER", booking.getBookingNumber(), null, null);
        return mapper.toResponse(repository.save(booking));
    }
    @Transactional
    public ImportFreightBookingResponse postCost(UUID id) {
        ImportFreightBooking booking = findEntity(id);
        if (ImportFreightStatus.CANCELLED.name().equals(booking.getStatus())) {
            throw new BusinessException("Cancelled freight booking cannot be posted");
        }
        if (booking.getLandedCostComponentId() != null) {
            throw new BusinessException("Freight cost is already posted to landed cost");
        }
        if (booking.getTotalFreightAmount().signum() <= 0) {
            throw new BusinessException("Total freight amount must be greater than zero");
        }
        LandedCostComponentCreateRequest componentRequest = new LandedCostComponentCreateRequest();
        componentRequest.setName("Freight " + booking.getBookingNumber());
        componentRequest.setImportDeliveryId(booking.getImportDeliveryId());
        componentRequest.setComponentType("FREIGHT");
        componentRequest.setAmount(booking.getTotalFreightAmount());
        componentRequest.setCurrencyCode(booking.getCurrencyCode());
        componentRequest.setAllocationMethod(booking.getAllocationMethod());
        componentRequest.setSupplierId(booking.getCarrierSupplierId());
        componentRequest.setInvoiceNumber(booking.getInvoiceNumber() == null || booking.getInvoiceNumber().isBlank() ? booking.getBookingNumber() : booking.getInvoiceNumber());
        componentRequest.setInvoiceDate(booking.getInvoiceDate() == null ? LocalDate.now() : booking.getInvoiceDate());
        componentRequest.setIncludedInInventoryCost(booking.isIncludedInInventoryCost());
        LandedCostComponentResponse component = landedCostComponentService.post(landedCostComponentService.create(componentRequest).getId());
        if (component.getId() != null) {
            booking.setLandedCostComponent(null);
            booking.setLandedCostComponentId(component.getId());
        }
        booking.setPostedAt(LocalDateTime.now());
        booking.setStatus(ImportFreightStatus.POSTED.name());
        auditService.log("ImportFreightBooking", id, "POST_COST", booking.getTotalFreightAmount().toPlainString(), null, null);
        return mapper.toResponse(repository.save(booking));
    }
    @Transactional
    public ImportFreightBookingResponse cancel(UUID id) {
        ImportFreightBooking booking = findEntity(id);
        if (ImportFreightStatus.POSTED.name().equals(booking.getStatus())) {
            throw new BusinessException("Posted freight booking cannot be cancelled");
        }
        booking.setStatus(ImportFreightStatus.CANCELLED.name());
        auditService.log("ImportFreightBooking", id, "CANCEL", booking.getBookingNumber(), null, null);
        return mapper.toResponse(repository.save(booking));
    }
    private void hydrateFromDelivery(ImportFreightBooking entity) {
        ImportDelivery delivery = entity.getImportDelivery() != null ? entity.getImportDelivery() : guard.importDelivery(entity.getImportDeliveryId());
        entity.setImportDelivery(delivery);
        if (entity.getTransportMode() == null || entity.getTransportMode().isBlank()) {
            entity.setTransportMode(delivery.getTransportMode());
        }
        if (entity.getCarrierName() == null || entity.getCarrierName().isBlank()) {
            entity.setCarrierName(delivery.getCarrierName());
        }
        if (entity.getForwarderName() == null || entity.getForwarderName().isBlank()) {
            entity.setForwarderName(delivery.getForwarderName());
        }
        if (entity.getOriginCountry() == null || entity.getOriginCountry().isBlank()) {
            entity.setOriginCountry(delivery.getOriginCountry());
        }
        if (entity.getOriginPort() == null || entity.getOriginPort().isBlank()) {
            entity.setOriginPort(delivery.getOriginPort());
        }
        if (entity.getDestinationCountry() == null || entity.getDestinationCountry().isBlank()) {
            entity.setDestinationCountry(delivery.getDestinationCountry());
        }
        if (entity.getDestinationPlace() == null || entity.getDestinationPlace().isBlank()) {
            entity.setDestinationPlace(delivery.getDestinationPlace());
        }
    }
    private void normalize(ImportFreightBooking entity) {
        if (entity.getBookingNumber() != null) {
            entity.setBookingNumber(entity.getBookingNumber().trim());
        }
        if (entity.getTransportMode() != null && !entity.getTransportMode().isBlank()) {
            entity.setTransportMode(normalizeEnum(entity.getTransportMode(), null, enumNames(TransportMode.values()), "transportMode"));
        }
        entity.setCurrencyCode(normalizeToken(entity.getCurrencyCode(), "UZS"));
        entity.setAllocationMethod(normalizeEnum(entity.getAllocationMethod(), "VALUE", enumNames(LandedCostAllocationMethod.values()), "allocationMethod"));
        entity.setFreightAmount(nvl(entity.getFreightAmount()));
        entity.setFuelSurchargeAmount(nvl(entity.getFuelSurchargeAmount()));
        entity.setOtherChargesAmount(nvl(entity.getOtherChargesAmount()));
        entity.setTotalFreightAmount(entity.getFreightAmount().add(entity.getFuelSurchargeAmount()).add(entity.getOtherChargesAmount()));
    }
    private void validate(ImportFreightBooking entity) {
        if (entity.getCarrierSupplierId() != null || entity.getCarrierSupplier() != null) {
            entity.setCarrierSupplier(entity.getCarrierSupplier() != null ? entity.getCarrierSupplier() : guard.supplier(entity.getCarrierSupplierId()));
        }
        if (entity.getForwarderSupplierId() != null || entity.getForwarderSupplier() != null) {
            entity.setForwarderSupplier(entity.getForwarderSupplier() != null ? entity.getForwarderSupplier() : guard.supplier(entity.getForwarderSupplierId()));
        }
        if (entity.getFreightAmount().signum() < 0 || entity.getFuelSurchargeAmount().signum() < 0 || entity.getOtherChargesAmount().signum() < 0) {
            throw new BusinessException("Freight amounts cannot be negative");
        }
        if (entity.getPlannedPickupDate() != null && entity.getPlannedDeliveryDate() != null && entity.getPlannedDeliveryDate().isBefore(entity.getPlannedPickupDate())) {
            throw new BusinessException("plannedDeliveryDate must be greater than or equal to plannedPickupDate");
        }
    }
    private void ensureUnique(ImportFreightBooking entity) {
        if (entity.getBookingNumber() == null || entity.getBookingNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndBookingNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getBookingNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Freight booking number already exists");
            });
    }
    private void requireStatus(ImportFreightBooking booking, ImportFreightStatus status) {
        if (!status.name().equals(booking.getStatus())) {
            throw new BusinessException("Only " + status.name() + " freight booking can be processed");
        }
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
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<ImportFreightBookingResponse> search(ImportFreightBookingSearchRequest request, Pageable pageable) {
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
    public ImportFreightBookingResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ImportFreightBookingResponse create(ImportFreightBookingCreateRequest dto) {
        ImportFreightBooking entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ImportFreightBooking saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ImportFreightBookingResponse update(UUID id, ImportFreightBookingUpdateRequest dto) {
        ImportFreightBooking entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireEditable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ImportFreightBooking saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ImportFreightBooking entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ImportFreightBookingResponse restore(UUID id) {
        ImportFreightBooking entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ImportFreightBooking saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ImportFreightBooking findEntity(UUID id) {
        ImportFreightBooking entity = repository.findByIdAndIsDeletedFalse(id)
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
