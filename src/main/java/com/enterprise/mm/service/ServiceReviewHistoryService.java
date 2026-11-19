package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceReviewHistory;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ServiceReviewHistoryMapper;
import com.enterprise.mm.payload.ServiceReviewHistoryResponse;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.ServiceReviewHistoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServiceReviewHistoryService {
    private final ServiceReviewHistoryRepository repository;
    private final ServiceReviewHistoryMapper mapper;
    private final ServiceRequestRepository serviceRequestRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final ServiceWorkflowAccessService accessService;



    @Transactional
    public void record(UUID serviceRequestId, UUID serviceActId, String reviewType, String decision, String comment) {
        ServiceReviewHistory history = new ServiceReviewHistory();
        history.setCompanyId(CompanyContext.currentCompany());
        history.setCode("SRH-" + UUID.randomUUID().toString().substring(0, 8));
        history.setName(reviewType + " " + decision);
        history.setStatus(decision);
        history.setServiceRequestId(serviceRequestId);
        history.setServiceActId(serviceActId);
        history.setReviewType(normalize(reviewType));
        history.setDecision(normalize(decision));
        history.setActor(currentActor());
        history.setActedAt(LocalDateTime.now());
        history.setCommentText(comment);
        repository.save(history);
    }

    @Transactional
    public void recordClaim(ServiceClaim claim, String decision, String comment) {
        if (claim == null) return;
        ServiceReviewHistory history = new ServiceReviewHistory();
        history.setCompanyId(claim.getCompanyId() == null ? CompanyContext.currentCompany() : claim.getCompanyId());
        history.setCode("SRH-" + UUID.randomUUID().toString().substring(0, 8));
        history.setName("CLAIM " + decision);
        history.setStatus(normalize(decision));
        history.setServiceRequestId(claim.getServiceRequestId());
        history.setServiceActId(claim.getServiceActId());
        history.setServiceClaimId(claim.getId());
        history.setClaimOriginType(claim.getOriginType() == null ? null : claim.getOriginType().name());
        history.setClaimOriginId(claimOriginId(claim));
        history.setReviewType("CLAIM");
        history.setDecision(normalize(decision));
        history.setActor(currentActor());
        history.setActedAt(LocalDateTime.now());
        history.setCommentText(comment);
        repository.save(history);
    }

    @Transactional(readOnly = true)
    public List<ServiceReviewHistoryResponse> forRequest(UUID serviceRequestId) {
        var request = serviceRequestRepository.findByIdAndIsDeletedFalse(serviceRequestId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ServiceRequest not found: " + serviceRequestId));
        if (!accessService.canAccessServiceRequest(request)) {
            throw new NotFoundException("ServiceRequest not found: " + serviceRequestId);
        }
        return repository.findByTenantIdAndServiceRequestIdAndIsDeletedFalseOrderByActedAtAsc(
            TenantContext.currentTenant(),
            serviceRequestId
        ).stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceReviewHistoryResponse> forAct(UUID serviceActId) {
        var act = serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(serviceActId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ServiceAcceptanceAct not found: " + serviceActId));
        var request = act.getServiceRequestId() == null ? null : serviceRequestRepository.findByIdAndIsDeletedFalse(act.getServiceRequestId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElse(null);
        if (!accessService.canAccessServiceAcceptanceAct(act, request, act.getServiceOrder())) {
            throw new NotFoundException("ServiceAcceptanceAct not found: " + serviceActId);
        }
        return repository.findByTenantIdAndServiceActIdAndIsDeletedFalseOrderByActedAtAsc(
            TenantContext.currentTenant(),
            serviceActId
        ).stream().map(mapper::toResponse).toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private UUID claimOriginId(ServiceClaim claim) {
        if (claim.getServiceRequestId() != null) return claim.getServiceRequestId();
        if (claim.getServiceOrderId() != null) return claim.getServiceOrderId();
        return claim.getPurchaseOrderItemId();
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }
}
