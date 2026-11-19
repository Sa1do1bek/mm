package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.DeliveryScheduleResponse;
import com.enterprise.mm.payload.DeliveryScheduleUpdateRequest;
import com.enterprise.mm.payload.DeliveryScheduleCreateRequest;
import com.enterprise.mm.payload.DeliveryScheduleSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DeliveryScheduleMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeliveryScheduleService {
    private final String entityName = "DeliverySchedule";
    private final DeliveryScheduleRepository repository;
    private final DeliveryScheduleMapper mapper;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ContractRepository contractRepository;
    protected void beforeCreate(DeliverySchedule entity, DeliveryScheduleCreateRequest dto) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeOpportunityAssumptions(entity);
        validateQuantities(entity.getPlannedQuantity(), dto.getReceivedQuantity());
        entity.setPlannedQuantity(amount(entity.getPlannedQuantity()));
        entity.setStatus("PLANNED");
        entity.setReceivedQuantity(BigDecimal.ZERO);
        entity.setReceivedAmount(BigDecimal.ZERO);
        entity.setOverdueFlag(false);
        entity.setControlledAt(null);
        control(entity);
    }
    protected void beforeUpdate(DeliverySchedule entity, DeliveryScheduleUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeOpportunityAssumptions(entity);
    }
    @Transactional
    public DeliveryScheduleResponse control(UUID id) {
        DeliverySchedule schedule = findEntity(id);
        control(schedule);
        DeliverySchedule saved = repository.save(schedule);
        auditService.log("DeliverySchedule", saved.getId(), "CONTROL", saved.getStatus(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public int controlOverdue() {
        List<DeliverySchedule> schedules = repository.findByTenantIdAndCompanyIdAndScheduleDateBeforeAndStatusInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            LocalDate.now(),
            List.of("PLANNED", "PARTIALLY_RECEIVED")
        );
        int changed = 0;
        for (DeliverySchedule schedule : schedules) {
            control(schedule);
            if (schedule.isOverdueFlag()) {
                repository.save(schedule);
                auditService.log("DeliverySchedule", schedule.getId(), "OVERDUE", schedule.getCode(), null, null);
                changed++;
            }
        }
        return changed;
    }
    private void control(DeliverySchedule schedule) {
        boolean fullyReceived = schedule.getPlannedQuantity() != null
            && schedule.getReceivedQuantity() != null
            && schedule.getReceivedQuantity().compareTo(schedule.getPlannedQuantity()) >= 0;
        if (fullyReceived) {
            schedule.setStatus("RECEIVED");
            schedule.setOverdueFlag(false);
        } else if (schedule.getScheduleDate() != null && schedule.getScheduleDate().isBefore(LocalDate.now())) {
            schedule.setStatus(schedule.getReceivedQuantity() != null && schedule.getReceivedQuantity().signum() > 0 ? "PARTIALLY_RECEIVED" : "OVERDUE");
            schedule.setOverdueFlag(true);
        }
        schedule.setControlledAt(LocalDateTime.now());
    }

    private void normalizeOpportunityAssumptions(DeliverySchedule entity) {
        entity.setBusinessImpactReference(trim(entity.getBusinessImpactReference()));
        entity.setBusinessImpactComment(trim(entity.getBusinessImpactComment()));
        if (entity.getImpactCurrencyCode() != null && !entity.getImpactCurrencyCode().isBlank()) {
            entity.setImpactCurrencyCode(entity.getImpactCurrencyCode().trim().toUpperCase(Locale.ROOT));
            if (entity.getImpactCurrencyCode().length() != 3) {
                throw new BusinessException("impactCurrencyCode must be a 3-letter ISO code");
            }
        } else {
            entity.setImpactCurrencyCode(null);
        }
        BigDecimal attributableShare = entity.getAttributableShare();
        if (attributableShare != null && (attributableShare.signum() <= 0 || attributableShare.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException("Delivery attributable share must be in (0, 1]");
        }
        boolean hasImpactMetadata = entity.getAttributableShare() != null
            || entity.getImpactCurrencyCode() != null
            || entity.getBusinessImpactReference() != null
            || entity.getBusinessImpactComment() != null;
        if (hasImpactMetadata && entity.getBusinessImpactReference() == null) {
            throw new BusinessException("businessImpactReference is required when delivery impact metadata is provided");
        }
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    @Transactional(readOnly = true)
    public Page<DeliveryScheduleResponse> search(DeliveryScheduleSearchRequest request, Pageable pageable) {
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
    public DeliveryScheduleResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public DeliveryScheduleResponse create(DeliveryScheduleCreateRequest dto) {
        DeliverySchedule entity = mapper.toEntity(dto);
        requireLinkedParents(entity.getPurchaseOrderId(), entity.getContractId());
        beforeCreate(entity, dto);
        DeliverySchedule saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public DeliveryScheduleResponse update(UUID id, DeliveryScheduleUpdateRequest dto) {
        DeliverySchedule entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        if ("RECEIVED".equals(entity.getStatus()) || "CANCELLED".equals(entity.getStatus())) {
            throw new BusinessException("Delivery schedule in terminal status cannot be updated: " + entity.getStatus());
        }
        UUID purchaseOrderId = entity.getPurchaseOrderId();
        UUID contractId = entity.getContractId();
        requireLinkedParents(purchaseOrderId, contractId);
        if (!Objects.equals(purchaseOrderId, dto.getPurchaseOrderId())) {
            throw new BusinessException("Delivery schedule cannot be moved to another purchase order");
        }
        if (!Objects.equals(contractId, dto.getContractId())) {
            throw new BusinessException("Delivery schedule cannot be moved to another contract");
        }
        String status = entity.getStatus();
        BigDecimal receivedQuantity = entity.getReceivedQuantity();
        BigDecimal receivedAmount = entity.getReceivedAmount();
        boolean overdueFlag = entity.isOverdueFlag();
        LocalDateTime controlledAt = entity.getControlledAt();
        mapper.updateEntity(entity, dto);
        validateQuantities(entity.getPlannedQuantity(), entity.getReceivedQuantity());
        entity.setPurchaseOrderId(purchaseOrderId);
        entity.setContractId(contractId);
        entity.setStatus(status);
        entity.setReceivedQuantity(amount(receivedQuantity));
        entity.setReceivedAmount(amount(receivedAmount));
        entity.setOverdueFlag(overdueFlag);
        entity.setControlledAt(controlledAt);
        validateQuantities(entity.getPlannedQuantity(), entity.getReceivedQuantity());
        beforeUpdate(entity, dto);
        DeliverySchedule saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        DeliverySchedule entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public DeliveryScheduleResponse restore(UUID id) {
        DeliverySchedule entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireLinkedParents(entity.getPurchaseOrderId(), entity.getContractId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        DeliverySchedule saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected DeliverySchedule findEntity(UUID id) {
        DeliverySchedule entity = repository.findByIdAndIsDeletedFalse(id)
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

    private void requireLinkedParents(UUID purchaseOrderId, UUID contractId) {
        if (purchaseOrderId == null) {
            throw new BusinessException("purchaseOrderId is required");
        }
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                || com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + purchaseOrderId));
        var purchaseRequest = purchaseOrder.getPurchaseRequest();
        if (purchaseRequest != null && !roleDataScopeService.canAccessDocument(
                purchaseRequest.getCreatedBy(),
                purchaseRequest.getRequestedBy(),
                purchaseRequest.getDepartmentId()
            )) {
            throw new NotFoundException("PurchaseOrder not found: " + purchaseOrderId);
        }
        Contract contract = contractId == null ? null : contractRepository.findByIdAndIsDeletedFalse(contractId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                || com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        if (!Objects.equals(purchaseOrder.getContractId(), contractId)) {
            throw new BusinessException("Delivery schedule contract must match purchase order contract");
        }
        if (contract != null && !Objects.equals(purchaseOrder.getSupplierId(), contract.getSupplierId())) {
            throw new BusinessException("Delivery schedule contract supplier must match purchase order supplier");
        }
    }

    private void validateQuantities(BigDecimal plannedQuantity, BigDecimal receivedQuantity) {
        if (plannedQuantity != null && plannedQuantity.signum() < 0) {
            throw new BusinessException("plannedQuantity cannot be negative");
        }
        if (receivedQuantity != null && receivedQuantity.signum() < 0) {
            throw new BusinessException("receivedQuantity cannot be negative");
        }
        if (amount(receivedQuantity).compareTo(amount(plannedQuantity)) > 0) {
            throw new BusinessException("receivedQuantity cannot exceed plannedQuantity");
        }
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    protected boolean companyScoped() {
        return true;
    }
}
