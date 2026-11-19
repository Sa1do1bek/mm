package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceClaimOriginType;
import com.enterprise.mm.enums.ServiceClaimStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ServiceClaimMapper;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.ServiceClaimCreateRequest;
import com.enterprise.mm.payload.ServiceClaimResponse;
import com.enterprise.mm.payload.ServiceClaimSearchRequest;
import com.enterprise.mm.payload.ServiceClaimUpdateRequest;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {"search", "get"})
public class ServiceClaimService {
    private final ServiceClaimRepository repository;
    private final ServiceRequestRepository requestRepository;
    private final ServiceClaimMapper mapper;
    private final AuditService auditService;
    private final ServiceWorkflowNotificationService notificationService;
    private final ServiceReviewHistoryService historyService;
    private final RoleDataScopeService roleDataScopeService;
    private final ServiceWorkflowAccessService accessService;
    private final AttachmentService attachmentService;
    private final ServiceAcceptanceActRepository acceptanceActRepository;
    private final ServiceOrderRepository serviceOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public ServiceClaimService(
        ServiceClaimRepository repository,
        ServiceRequestRepository requestRepository,
        ServiceClaimMapper mapper,
        AuditService auditService,
        ServiceWorkflowNotificationService notificationService,
        ServiceReviewHistoryService historyService,
        RoleDataScopeService roleDataScopeService,
        ServiceWorkflowAccessService accessService,
        AttachmentService attachmentService,
        ServiceAcceptanceActRepository acceptanceActRepository,
        ServiceOrderRepository serviceOrderRepository,
        PurchaseOrderRepository purchaseOrderRepository,
        PurchaseOrderItemRepository purchaseOrderItemRepository,
        WorkCompletionActRepository workCompletionActRepository,
        UserRepository userRepository
    ) {
        this.repository = repository;
        this.requestRepository = requestRepository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.historyService = historyService;
        this.roleDataScopeService = roleDataScopeService;
        this.accessService = accessService;
        this.attachmentService = attachmentService;
        this.acceptanceActRepository = acceptanceActRepository;
        this.serviceOrderRepository = serviceOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.workCompletionActRepository = workCompletionActRepository;
        this.userRepository = userRepository;
    }

    public ServiceClaimService(
        ServiceClaimRepository repository,
        ServiceRequestRepository requestRepository,
        ServiceClaimMapper mapper,
        AuditService auditService,
        ServiceWorkflowNotificationService notificationService,
        ServiceReviewHistoryService historyService,
        RoleDataScopeService roleDataScopeService,
        ServiceWorkflowAccessService accessService,
        AttachmentService attachmentService,
        ServiceAcceptanceActRepository acceptanceActRepository,
        ServiceOrderRepository serviceOrderRepository
    ) {
        this(repository, requestRepository, mapper, auditService, notificationService, historyService,
            roleDataScopeService, accessService, attachmentService, acceptanceActRepository, serviceOrderRepository,
            null, null, null, null);
    }

    public ServiceClaimService(
        ServiceClaimRepository repository,
        ServiceRequestRepository requestRepository,
        ServiceClaimMapper mapper,
        AuditService auditService,
        ServiceWorkflowNotificationService notificationService,
        ServiceReviewHistoryService historyService,
        RoleDataScopeService roleDataScopeService,
        ServiceWorkflowAccessService accessService,
        AttachmentService attachmentService
    ) {
        this(repository, requestRepository, mapper, auditService, notificationService, historyService,
            roleDataScopeService, accessService, attachmentService, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public Page<ServiceClaimResponse> search(ServiceClaimSearchRequest request, Pageable pageable) {
        if (accessService.hasFullAccess()) {
            return repository.findAll(spec(request), pageable).map(mapper::toResponse);
        }
        Page<ServiceClaim> records = repository.findAll(spec(request), Pageable.unpaged());
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            this::canAccessClaim,
            mapper::toResponse
        );
    }

    @Transactional(readOnly = true)
    public ServiceClaimResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public ServiceClaimResponse create(ServiceClaimCreateRequest request) {
        ServiceClaim entity = mapper.toEntity(request);
        beforeCreate(entity);
        ServiceClaim saved = repository.save(entity);
        notificationService.claimRaised("SUPPLIER_MANAGER", saved.getCode());
        historyService.recordClaim(saved, "CREATED", saved.getDescription());
        auditService.log("ServiceClaim", saved.getId(), "CREATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceClaimResponse update(UUID id, ServiceClaimUpdateRequest request) {
        ServiceClaim entity = findEntity(id);
        requireDraftClaim(entity, "edited");
        mapper.updateEntity(entity, request);
        beforeUpdate(entity);
        ServiceClaim saved = repository.save(entity);
        historyService.recordClaim(saved, "UPDATED", saved.getDescription());
        auditService.log("ServiceClaim", saved.getId(), "UPDATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceClaimResponse send(UUID id, ServiceActionRequest action) {
        ServiceClaim claim = findEntity(id);
        requireStatus(claim, ServiceClaimStatus.CREATED);
        validateClaimAttachments(claim);
        claim.setStatus(ServiceClaimStatus.SENT.name());
        claim.setSentAt(LocalDateTime.now());
        ServiceClaim saved = repository.save(claim);
        historyService.recordClaim(saved, "SENT", action == null ? null : action.comment());
        auditService.log("ServiceClaim", saved.getId(), "SEND", action == null ? null : action.comment(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceClaimResponse startResolution(UUID id, ServiceActionRequest action) {
        ServiceClaim claim = findEntity(id);
        requireStatus(claim, ServiceClaimStatus.SENT);
        claim.setStatus(ServiceClaimStatus.UNDER_RESOLUTION.name());
        claim.setSupplierResponse(action == null ? claim.getSupplierResponse() : action.comment());
        ServiceClaim saved = repository.save(claim);
        historyService.recordClaim(saved, "UNDER_RESOLUTION", action == null ? null : action.comment());
        auditService.log("ServiceClaim", saved.getId(), "START_RESOLUTION", action == null ? null : action.comment(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceClaimResponse resolve(UUID id, ServiceActionRequest action) {
        ServiceClaim claim = findEntity(id);
        requireStatus(claim, ServiceClaimStatus.SENT, ServiceClaimStatus.UNDER_RESOLUTION);
        claim.setStatus(ServiceClaimStatus.RESOLVED.name());
        claim.setResolvedAt(LocalDateTime.now());
        claim.setSupplierResponse(action == null ? claim.getSupplierResponse() : action.comment());
        ServiceClaim saved = repository.save(claim);
        historyService.recordClaim(saved, "RESOLVED", action == null ? null : action.comment());
        auditService.log("ServiceClaim", saved.getId(), "RESOLVE", action == null ? null : action.comment(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceClaimResponse reject(UUID id, ServiceActionRequest action) {
        ServiceClaim claim = findEntity(id);
        requireStatus(claim, ServiceClaimStatus.SENT, ServiceClaimStatus.UNDER_RESOLUTION);
        claim.setStatus(ServiceClaimStatus.REJECTED.name());
        claim.setSupplierResponse(action == null ? claim.getSupplierResponse() : action.comment());
        ServiceClaim saved = repository.save(claim);
        historyService.recordClaim(saved, "REJECTED", action == null ? null : action.comment());
        auditService.log("ServiceClaim", saved.getId(), "REJECT", action == null ? null : action.comment(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceClaimResponse close(UUID id, ServiceActionRequest action) {
        ServiceClaim claim = findEntity(id);
        requireStatus(claim, ServiceClaimStatus.RESOLVED, ServiceClaimStatus.REJECTED);
        claim.setStatus(ServiceClaimStatus.CLOSED.name());
        claim.setClosedAt(LocalDateTime.now());
        ServiceClaim saved = repository.save(claim);
        historyService.recordClaim(saved, "CLOSED", action == null ? null : action.comment());
        auditService.log("ServiceClaim", saved.getId(), "CLOSE", action == null ? null : action.comment(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        ServiceClaim claim = findEntity(id);
        requireDraftClaim(claim, "deleted");
        claim.softDelete();
        repository.save(claim);
        auditService.log("ServiceClaim", id, "SOFT_DELETE", claim.getCode(), null, null);
    }

    @Transactional
    public ServiceClaimResponse restore(UUID id) {
        ServiceClaim claim = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException("ServiceClaim not found: " + id));
        if (!TenantContext.currentTenant().equals(claim.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(claim.getCompanyId())) {
            throw new NotFoundException("ServiceClaim not found: " + id);
        }
        if (!canAccessClaim(claim)) {
            throw new NotFoundException("ServiceClaim not found: " + id);
        }
        requireDraftClaim(claim, "restored");
        claim.restore();
        return mapper.toResponse(repository.save(claim));
    }

    protected ServiceClaim findEntity(UUID id) {
        ServiceClaim claim = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("ServiceClaim not found: " + id));
        if (!TenantContext.currentTenant().equals(claim.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(claim.getCompanyId())) {
            throw new NotFoundException("ServiceClaim not found: " + id);
        }
        if (!canAccessClaim(claim)) {
            throw new NotFoundException("ServiceClaim not found: " + id);
        }
        return claim;
    }

    private void beforeCreate(ServiceClaim entity) {
        OriginContext origin = resolveOrigin(entity, true);
        UUID submittedSupplierId = entity.getSupplierId();
        if (origin.supplierId() == null) {
            throw new BusinessException("Claim origin does not have a supplier");
        }
        if (submittedSupplierId != null && !submittedSupplierId.equals(origin.supplierId())) {
            throw new BusinessException("Claim supplier must match the server-owned origin supplier");
        }
        entity.setOriginType(origin.type());
        entity.setSupplierId(origin.supplierId());
        entity.setCompanyId(origin.companyId());
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("SCL-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(ServiceClaimStatus.CREATED.name());
        }
        entity.setClaimType(normalize(entity.getClaimType(), "OTHER"));
        entity.setSeverity(normalize(entity.getSeverity(), "MEDIUM"));
        entity.setPenaltyAmount(entity.getPenaltyAmount() == null ? BigDecimal.ZERO : entity.getPenaltyAmount());
        if (entity.getPenaltyAmount().signum() < 0) {
            throw new BusinessException("penaltyAmount cannot be negative");
        }
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode(origin.currencyCode() == null ? "UZS" : origin.currencyCode());
        }
        entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        if (entity.getCurrencyCode().length() != 3) {
            throw new BusinessException("currencyCode must contain three characters");
        }
        validateAssignee(entity);
    }

    private void beforeUpdate(ServiceClaim entity) {
        OriginContext origin = resolveOrigin(entity, true);
        if (!origin.supplierId().equals(entity.getSupplierId())) {
            throw new BusinessException("Claim supplier must match the server-owned origin supplier");
        }
        entity.setOriginType(origin.type());
        entity.setCompanyId(origin.companyId());
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        entity.setClaimType(normalize(entity.getClaimType(), "OTHER"));
        entity.setSeverity(normalize(entity.getSeverity(), "MEDIUM"));
        entity.setPenaltyAmount(entity.getPenaltyAmount() == null ? BigDecimal.ZERO : entity.getPenaltyAmount());
        if (entity.getPenaltyAmount().signum() < 0) throw new BusinessException("penaltyAmount cannot be negative");
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode(origin.currencyCode() == null ? "UZS" : origin.currencyCode());
        }
        entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        if (entity.getCurrencyCode().length() != 3) throw new BusinessException("currencyCode must contain three characters");
        validateAssignee(entity);
    }

    private OriginContext resolveOrigin(ServiceClaim claim, boolean enforceAccess) {
        int origins = (claim.getServiceRequestId() == null ? 0 : 1)
            + (claim.getServiceOrderId() == null ? 0 : 1)
            + (claim.getPurchaseOrderItemId() == null ? 0 : 1);
        if (origins != 1) {
            throw new BusinessException("A service claim must reference exactly one primary origin");
        }
        if (claim.getServiceRequestId() != null) {
            if (claim.getWorkCompletionActId() != null) {
                throw new BusinessException("SERVICE_REQUEST claims cannot link a work completion act");
            }
            ServiceRequest request = loadRequest(claim.getServiceRequestId());
            ServiceAcceptanceAct act = validateServiceAct(claim.getServiceActId(), request, null);
            if (enforceAccess && !accessService.canAccessServiceRequest(request)) {
                throw new BusinessException("Service request not found");
            }
            return new OriginContext(
                ServiceClaimOriginType.SERVICE_REQUEST, request, null, null, null, act,
                request.getSupplierId(), request.getCompanyId(), request.getCurrencyCode()
            );
        }
        if (claim.getServiceOrderId() != null) {
            if (claim.getWorkCompletionActId() != null) {
                throw new BusinessException("PO_SERVICE claims cannot link a work completion act");
            }
            ServiceOrder order = loadServiceOrder(claim.getServiceOrderId());
            if (order.getServiceRequestId() != null) {
                throw new BusinessException("PO_SERVICE claim requires a request-less service order");
            }
            PurchaseOrder purchaseOrder = loadPurchaseOrder(order.getPurchaseOrderId());
            if (purchaseOrder.getRequestType() != RequestType.SERVICE
                || !sameScope(order, purchaseOrder)
                || !java.util.Objects.equals(order.getSupplierId(), purchaseOrder.getSupplierId())) {
                throw new BusinessException("Service order does not belong to a coherent PO_SERVICE origin");
            }
            ServiceAcceptanceAct act = validateServiceAct(claim.getServiceActId(), null, order);
            if (enforceAccess && !accessService.canAccessServiceOrder(order, null)) {
                throw new BusinessException("Service order not found");
            }
            return new OriginContext(
                ServiceClaimOriginType.PO_SERVICE, null, order, null, purchaseOrder, act,
                order.getSupplierId(), order.getCompanyId(), firstNonBlank(order.getCurrencyCode(), purchaseOrder.getCurrencyCode())
            );
        }
        if (claim.getServiceActId() != null) {
            throw new BusinessException("PO_WORKS claims cannot link a service acceptance act");
        }
        PurchaseOrderItem item = loadPurchaseOrderItem(claim.getPurchaseOrderItemId());
        PurchaseOrder purchaseOrder = loadPurchaseOrder(item.getPurchaseOrderId());
        if (item.getLineType() != ProcurementLineType.WORK
            || !sameScope(item, purchaseOrder)) {

            throw new BusinessException("Purchase order item does not belong to a coherent PO_WORKS origin");
        }
        WorkCompletionAct workAct = validateWorkAct(claim.getWorkCompletionActId(), item, purchaseOrder);
        if (enforceAccess && !accessService.canAccessPurchaseOrder(purchaseOrder)) {
            throw new BusinessException("Purchase order works line not found");
        }
        return new OriginContext(
            ServiceClaimOriginType.PO_WORKS, null, null, item, purchaseOrder, null,
            purchaseOrder.getSupplierId(), item.getCompanyId(), purchaseOrder.getCurrencyCode()
        );
    }

    private ServiceAcceptanceAct validateServiceAct(UUID actId, ServiceRequest request, ServiceOrder expectedOrder) {
        if (actId == null) return null;
        if (acceptanceActRepository == null) throw new BusinessException("Service act validation is unavailable");
        ServiceAcceptanceAct act = acceptanceActRepository.findByIdAndIsDeletedFalse(actId)
            .filter(this::inScope)
            .orElseThrow(() -> new BusinessException("Service act not found"));
        if (request != null && !java.util.Objects.equals(request.getId(), act.getServiceRequestId())) {
            throw new BusinessException("Claim service act does not belong to service request");
        }
        if (expectedOrder != null && !java.util.Objects.equals(expectedOrder.getId(), act.getServiceOrderId())) {
            throw new BusinessException("Claim service act does not belong to service order");
        }
        ServiceOrder actualOrder = act.getServiceOrderId() == null ? null : loadServiceOrder(act.getServiceOrderId());
        if (request != null && actualOrder != null
            && (!java.util.Objects.equals(request.getId(), actualOrder.getServiceRequestId())
                || !java.util.Objects.equals(request.getSupplierId(), actualOrder.getSupplierId()))) {
            throw new BusinessException("Claim service act order does not match request supplier and request");
        }
        if (!accessService.canAccessServiceAcceptanceAct(act, request, actualOrder)) {
            throw new BusinessException("Service act not found");
        }
        return act;
    }

    private WorkCompletionAct validateWorkAct(UUID actId, PurchaseOrderItem item, PurchaseOrder order) {
        if (actId == null) return null;
        if (workCompletionActRepository == null) throw new BusinessException("Work completion act validation is unavailable");
        WorkCompletionAct act = workCompletionActRepository.findByIdAndIsDeletedFalse(actId)
            .filter(this::inScope)
            .orElseThrow(() -> new BusinessException("Work completion act not found"));
        if (!java.util.Objects.equals(item.getId(), act.getPurchaseOrderItemId())
            || !java.util.Objects.equals(order.getId(), act.getPurchaseOrderId())) {
            throw new BusinessException("Claim work completion act does not belong to purchase order works line");
        }
        return act;
    }

    private boolean canAccessClaim(ServiceClaim claim) {
        try {
            OriginContext origin = resolveOrigin(claim, false);
            return accessService.canAccessServiceClaim(
                claim, origin.request(), origin.serviceOrder(), origin.purchaseOrder()
            );
        } catch (BusinessException exception) {
            return false;
        }
    }

    private ServiceRequest loadRequest(UUID id) {
        if (id == null || requestRepository == null) throw new BusinessException("Service request not found");
        return requestRepository.findByIdAndIsDeletedFalse(id).filter(this::inScope)
            .orElseThrow(() -> new BusinessException("Service request not found"));
    }

    private ServiceOrder loadServiceOrder(UUID id) {
        if (id == null || serviceOrderRepository == null) throw new BusinessException("Service order not found");
        return serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(this::inScope)
            .orElseThrow(() -> new BusinessException("Service order not found"));
    }

    private PurchaseOrder loadPurchaseOrder(UUID id) {
        if (id == null || purchaseOrderRepository == null) throw new BusinessException("Purchase order not found");
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(id).filter(this::inScope)
            .orElseThrow(() -> new BusinessException("Purchase order not found"));
    }

    private PurchaseOrderItem loadPurchaseOrderItem(UUID id) {
        if (id == null || purchaseOrderItemRepository == null) throw new BusinessException("Purchase order item not found");
        return purchaseOrderItemRepository.findByIdAndIsDeletedFalse(id).filter(this::inScope)
            .orElseThrow(() -> new BusinessException("Purchase order item not found"));
    }

    private boolean inScope(com.enterprise.mm.common.BusinessEntity entity) {
        return TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    private boolean sameScope(com.enterprise.mm.common.BusinessEntity left, com.enterprise.mm.common.BusinessEntity right) {
        return java.util.Objects.equals(left.getTenantId(), right.getTenantId())
            && java.util.Objects.equals(left.getCompanyId(), right.getCompanyId());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private void validateAssignee(ServiceClaim claim) {
        if (claim.getAssignedTo() == null) {
            throw new BusinessException("assignedTo is required for a service claim");
        }
        if (userRepository == null) {
            throw new BusinessException("assignedTo validation is unavailable");
        }
        userRepository.findByIdAndIsDeletedFalse(claim.getAssignedTo())
            .filter(user -> TenantContext.currentTenant().equals(user.getTenantId()))
            .filter(user -> CompanyContext.currentCompany().equals(user.getCompanyId()))
            .filter(com.enterprise.mm.entity.User::isEnabled)
            .filter(user -> "ACTIVE".equalsIgnoreCase(user.getStatus()))
            .orElseThrow(() -> new BusinessException("assignedTo must reference an active user in the current company"));
    }

    private void requireStatus(ServiceClaim claim, ServiceClaimStatus... statuses) {
        for (ServiceClaimStatus status : statuses) {
            if (status.name().equals(claim.getStatus())) {
                return;
            }
        }
        throw new BusinessException("Invalid claim status: " + claim.getStatus());
    }

    private void requireDraftClaim(ServiceClaim claim, String action) {
        if (!ServiceClaimStatus.CREATED.name().equals(claim.getStatus())) {
            throw new BusinessException("Only CREATED service claim can be " + action);
        }
    }

    private String normalize(String value, String fallback) {
        String effective = value == null || value.isBlank() ? fallback : value;
        return effective.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private void validateClaimAttachments(ServiceClaim claim) {
        boolean required = List.of("HIGH", "CRITICAL").contains(normalize(claim.getSeverity(), "MEDIUM"))
            || List.of("DOCUMENTS", "SAFETY").contains(normalize(claim.getClaimType(), "OTHER"))
            || entityAmount(claim).signum() > 0;
        if (!required) {
            return;
        }
        if (attachmentService.list("SERVICE_CLAIM", claim.getId()).stream().noneMatch(item ->
            "CLAIM_DOCUMENT".equals(item.getAttachmentType()) || "PHOTO".equals(item.getAttachmentType()))) {
            throw new BusinessException("Claim attachment is required before sending high-risk or document-based claims");
        }
    }

    private BigDecimal entityAmount(ServiceClaim claim) {
        return claim.getPenaltyAmount() == null ? BigDecimal.ZERO : claim.getPenaltyAmount();
    }

    private Specification<ServiceClaim> spec(ServiceClaimSearchRequest request) {
        return (root, query, cb) -> {
            ServiceClaimSearchRequest r = request == null
                ? new ServiceClaimSearchRequest(null, null, null, null, null, null, null, null, null, null, null, false)
                : request;
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            if (!r.includeDeleted()) predicates.add(cb.isFalse(root.get("isDeleted")));
            if (r.search() != null && !r.search().isBlank()) {
                String pattern = "%" + r.search().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("code")), pattern), cb.like(cb.lower(root.get("name")), pattern)));
            }
            if (r.status() != null && !r.status().isEmpty()) predicates.add(root.get("status").in(r.status()));
            if (r.serviceRequestId() != null) predicates.add(cb.equal(root.get("serviceRequestId"), r.serviceRequestId()));
            if (r.serviceActId() != null) predicates.add(cb.equal(root.get("serviceActId"), r.serviceActId()));
            if (r.serviceOrderId() != null) predicates.add(cb.equal(root.get("serviceOrderId"), r.serviceOrderId()));
            if (r.purchaseOrderItemId() != null) predicates.add(cb.equal(root.get("purchaseOrderItemId"), r.purchaseOrderItemId()));
            if (r.workCompletionActId() != null) predicates.add(cb.equal(root.get("workCompletionActId"), r.workCompletionActId()));
            if (r.originType() != null && !r.originType().isBlank()) {
                predicates.add(cb.equal(root.get("originType"), ServiceClaimOriginType.valueOf(normalize(r.originType(), null))));
            }
            if (r.supplierId() != null) predicates.add(cb.equal(root.get("supplierId"), r.supplierId()));
            if (r.claimType() != null && !r.claimType().isEmpty()) predicates.add(root.get("claimType").in(r.claimType()));
            if (r.severity() != null && !r.severity().isEmpty()) predicates.add(root.get("severity").in(r.severity()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private record OriginContext(
        ServiceClaimOriginType type,
        ServiceRequest request,
        ServiceOrder serviceOrder,
        PurchaseOrderItem purchaseOrderItem,
        PurchaseOrder purchaseOrder,
        ServiceAcceptanceAct serviceAct,
        UUID supplierId,
        UUID companyId,
        String currencyCode
    ) {
    }
}
