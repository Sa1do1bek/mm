package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementLineAllocation;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ProcurementLineAllocationRequest;
import com.enterprise.mm.payload.ProcurementLineAllocationResponse;
import com.enterprise.mm.procurement.lineage.ProcurementAllocationMath;
import com.enterprise.mm.repository.ProcurementLineAllocationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementLineAllocationService {
    private final ProcurementLineAllocationRepository repository;

    @Transactional
    public ProcurementLineAllocationResponse allocate(ProcurementLineAllocationRequest request) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(tenantId, companyId, request.idempotencyKey())
            .map(this::toResponse)
            .orElseGet(() -> create(tenantId, companyId, request));
    }

    @Transactional
    public ProcurementLineAllocationResponse cancel(UUID id, BigDecimal quantity, BigDecimal amount) {
        ProcurementLineAllocation allocation = find(id);
        BigDecimal requestedQuantity = ProcurementAllocationMath.requirePositive(quantity, "cancelled quantity");
        BigDecimal cancelledQuantity = value(allocation.getCancelledQuantity()).add(requestedQuantity);
        if (cancelledQuantity.compareTo(value(allocation.getAllocatedQuantity()).subtract(value(allocation.getReversedQuantity()))) > 0) {
            throw new BusinessException("Cancelled quantity exceeds the remaining allocation");
        }
        BigDecimal requestedAmount = amount == null ? BigDecimal.ZERO : amount;
        if (requestedAmount.signum() < 0 || value(allocation.getCancelledAmount()).add(requestedAmount)
            .compareTo(value(allocation.getAllocatedAmount()).subtract(value(allocation.getReversedAmount()))) > 0) {
            throw new BusinessException("Cancelled amount exceeds the remaining allocation");
        }
        allocation.setCancelledQuantity(cancelledQuantity);
        allocation.setCancelledAmount(value(allocation.getCancelledAmount()).add(requestedAmount));
        return toResponse(repository.save(allocation));
    }

    @Transactional(readOnly = true)
    public List<ProcurementLineAllocationResponse> findByDocument(String documentType, UUID documentId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<ProcurementLineAllocation> source = repository
            .findByTenantIdAndCompanyIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
                tenantId, companyId, normalize(documentType), documentId);
        List<ProcurementLineAllocation> target = repository
            .findByTenantIdAndCompanyIdAndTargetDocumentTypeAndTargetDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
                tenantId, companyId, normalize(documentType), documentId);
        return java.util.stream.Stream.concat(source.stream(), target.stream())
            .distinct()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public ProcurementLineAllocationResponse get(UUID id) {
        return toResponse(find(id));
    }

    private ProcurementLineAllocationResponse create(String tenantId, UUID companyId, ProcurementLineAllocationRequest request) {
        List<ProcurementLineAllocation> existing = repository
            .findByTenantIdAndCompanyIdAndSourceLineIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, companyId, request.sourceLineId());
        String requestedCurrency = request.currencyCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (existing.stream().map(ProcurementLineAllocation::getCurrencyCode).anyMatch(currency ->
            currency != null && !requestedCurrency.equalsIgnoreCase(currency))) {
            throw new BusinessException("Currency cannot change across allocations of the same source line");
        }
        ProcurementAllocationMath.requireAvailable(
            request.allocatedQuantity(),
            request.sourceQuantity(),
            existing.stream().map(item -> new ProcurementAllocationMath.Allocation(
                item.getAllocatedQuantity(), item.getCancelledQuantity(), item.getReversedQuantity())).toList());
        ProcurementLineAllocation entity = new ProcurementLineAllocation();
        entity.setTenantId(tenantId);
        entity.setCompanyId(companyId);
        entity.setName(request.sourceDocumentType() + " to " + request.targetDocumentType() + " allocation");
        entity.setStatus("ACTIVE");
        entity.setSourceDocumentType(normalize(request.sourceDocumentType()));
        entity.setSourceDocumentId(request.sourceDocumentId());
        entity.setSourceLineId(request.sourceLineId());
        entity.setTargetDocumentType(normalize(request.targetDocumentType()));
        entity.setTargetDocumentId(request.targetDocumentId());
        entity.setTargetLineId(request.targetLineId());
        entity.setAllocatedQuantity(request.allocatedQuantity());
        entity.setAllocatedAmount(request.allocatedAmount());
        entity.setCurrencyCode(requestedCurrency);
        entity.setSupplierId(request.supplierId());
        entity.setIdempotencyKey(request.idempotencyKey().trim());
        try {
            return toResponse(repository.save(entity));
        } catch (DataIntegrityViolationException exception) {
            return repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(tenantId, companyId, request.idempotencyKey())
                .map(this::toResponse)
                .orElseThrow(() -> exception);
        }
    }

    private ProcurementLineAllocation find(UUID id) {
        return repository.findById(id)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .filter(item -> !item.isDeleted())
            .orElseThrow(() -> new NotFoundException("Procurement line allocation not found: " + id));
    }

    private ProcurementLineAllocationResponse toResponse(ProcurementLineAllocation item) {
        BigDecimal allocated = value(item.getAllocatedQuantity());
        BigDecimal remaining = allocated.subtract(value(item.getCancelledQuantity())).subtract(value(item.getReversedQuantity()));
        return new ProcurementLineAllocationResponse(
            item.getId(), item.getSourceDocumentType(), item.getSourceDocumentId(), item.getSourceLineId(),
            item.getTargetDocumentType(), item.getTargetDocumentId(), item.getTargetLineId(), allocated,
            value(item.getAllocatedAmount()), value(item.getCancelledQuantity()), value(item.getReversedQuantity()),
            remaining, item.getCurrencyCode(), item.getSupplierId(), item.getIdempotencyKey());
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Document type is required");
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
