package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.ServiceOrderResponse;
import com.enterprise.mm.payload.ServiceOrderUpdateRequest;
import com.enterprise.mm.payload.ServiceOrderCreateRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ServiceOrderMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ServiceOrderSearchRequest;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {"search", "get"})
public class ServiceOrderService {
    private final ServiceOrderRepository repository;
    private final ServiceOrderMapper mapper;
    private final String entityName = "ServiceOrder";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final ServiceRequestRepository serviceRequestRepository;
    private final ServiceWorkflowAccessService accessService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierAccreditationService supplierAccreditationService;
    protected void beforeCreate(ServiceOrder entity, ServiceOrderCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setStatus("DRAFT");
        if (entity.getServiceRequestId() != null) {
            applyServiceRequestDefaults(entity, true);
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), serviceRequestName(entity.getServiceRequestId())
        ));
        assertSupplierAccredited(entity, "service order creation");
    }
    @Transactional(readOnly = true)
    public Page<ServiceOrderResponse> search(ServiceOrderSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        if (accessService.hasFullAccess()) {
            var spec = ProcurementSearchSpecifications.build(
                ServiceOrder.class,
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                filter,
                request
            );
            return repository.findAll(spec, pageable).map(mapper::toResponse);
        }
        var spec = ProcurementSearchSpecifications.build(
            ServiceOrder.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        var records = repository.findAll(spec, Pageable.unpaged());
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> accessService.canAccessServiceOrder(entity, loadRequest(entity.getServiceRequestId())),
            mapper::toResponse
        );
    }
    @Transactional(readOnly = true)
    public ServiceOrderResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ServiceOrderResponse create(ServiceOrderCreateRequest dto) {
        ServiceOrder entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ServiceOrder saved = repository.save(entity);
        updateServiceRequest(saved);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ServiceOrderResponse update(UUID id, ServiceOrderUpdateRequest dto) {
        ServiceOrder entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        if (!"DRAFT".equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT service order can be edited, current status is " + entity.getStatus());
        }
        if (!Objects.equals(entity.getServiceRequestId(), dto.getServiceRequestId())) {
            throw new BusinessException("serviceRequestId cannot be changed after service order creation");
        }
        ServiceOrder accreditationCandidate = mapper.toEntity(dto);
        accreditationCandidate.setTenantId(entity.getTenantId());
        accreditationCandidate.setCompanyId(entity.getCompanyId());
        if (accreditationCandidate.getServiceRequestId() != null) {
            applyServiceRequestDefaults(accreditationCandidate, false);
        }
        assertSupplierAccredited(accreditationCandidate, "service order update");
        String currentStatus = entity.getStatus();
        mapper.updateEntity(entity, dto);
        entity.setStatus(currentStatus);
        beforeUpdate(entity, dto);
        ServiceOrder saved = repository.save(entity);
        updateServiceRequest(saved);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ServiceOrder entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ServiceOrderResponse restore(UUID id) {
        ServiceOrder entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ServiceOrder saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ServiceOrder findEntity(UUID id) {
        ServiceOrder entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (!accessService.canAccessServiceOrder(entity, loadRequest(entity.getServiceRequestId()))) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeUpdate(ServiceOrder entity, ServiceOrderUpdateRequest dto) {
        if (entity.getServiceRequestId() != null) {
            applyServiceRequestDefaults(entity, false);
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), serviceRequestName(entity.getServiceRequestId())
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private void updateServiceRequest(ServiceOrder order) {
        if (order.getServiceRequestId() == null) {
            return;
        }
        serviceRequestRepository.findByIdAndIsDeletedFalse(order.getServiceRequestId())
            .ifPresent(request -> {
                request.setSupplierId(order.getSupplierId());
                request.setContractId(order.getContractId());
                request.setPurchaseOrderId(order.getPurchaseOrderId());
                if (!ServiceRequestStatus.IN_PROGRESS.name().equals(request.getStatus())) {
                    request.setStatus(ServiceRequestStatus.WAITING_EXECUTION.name());
                }
                serviceRequestRepository.save(request);
            });
    }

    private ServiceRequest loadRequest(UUID serviceRequestId) {
        if (serviceRequestId == null) {
            return null;
        }
        return serviceRequestRepository.findByIdAndIsDeletedFalse(serviceRequestId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElse(null);
    }

    private String serviceRequestName(UUID serviceRequestId) {
        ServiceRequest request = loadRequest(serviceRequestId);
        return request == null ? null : request.getName();
    }

    private void applyServiceRequestDefaults(ServiceOrder order, boolean requireApproved) {
        ServiceRequest request = serviceRequestRepository.findByIdAndIsDeletedFalse(order.getServiceRequestId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .filter(accessService::canAccessServiceRequest)
            .orElseThrow(() -> new BusinessException("Service request not found"));
        if (requireApproved
            && !ServiceRequestStatus.APPROVED.name().equals(request.getStatus())
            && !ServiceRequestStatus.WAITING_EXECUTION.name().equals(request.getStatus())
            && !ServiceRequestStatus.IN_PROGRESS.name().equals(request.getStatus())) {
            throw new BusinessException("Service order can be created only after service request approval");
        }
        if (order.getSupplierId() != null && request.getSupplierId() != null
            && !Objects.equals(order.getSupplierId(), request.getSupplierId())) {
            throw new BusinessException("Service order supplier must match service request supplier");
        }
        if (order.getSupplierId() == null) {
            order.setSupplierId(request.getSupplierId());
        }
        if (order.getContractId() == null) {
            order.setContractId(request.getContractId());
        }
        if (order.getPurchaseOrderId() == null) {
            order.setPurchaseOrderId(request.getPurchaseOrderId());
        }
        if (requireApproved && (order.getTotalAmount() == null || order.getTotalAmount().signum() == 0)) {
            order.setTotalAmount(request.getApprovedAmount() == null || request.getApprovedAmount().signum() == 0
                ? request.getEstimatedAmount()
                : request.getApprovedAmount());
        }
    }

    private void assertSupplierAccredited(ServiceOrder order, String source) {
        if (order.getSupplierId() == null) {
            throw new BusinessException("Supplier is required before " + source);
        }
        supplierAccreditationService.assertSupplierEligibleForDemand(
            order.getSupplierId(), accreditationDemand(order), source);
    }

    private AccreditationDemand accreditationDemand(ServiceOrder order) {
        if (order.getPurchaseOrderId() != null) {
            PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(order.getPurchaseOrderId())
                .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
                .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Purchase order not found"));
            if (!Objects.equals(order.getSupplierId(), purchaseOrder.getSupplierId())) {
                throw new BusinessException("Service order supplier must match purchase order supplier");
            }
            if (purchaseOrder.getRequestType() == RequestType.SERVICE) {
                return AccreditationDemand.services();
            }
        }
        String serviceType = order.getServiceType() == null
            ? ""
            : order.getServiceType().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("WORK".equals(serviceType) || "WORKS".equals(serviceType)) {
            return AccreditationDemand.works();
        }
        return AccreditationDemand.services();
    }

}
