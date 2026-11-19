package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.mapper.SupplierMapper;
import com.enterprise.mm.payload.SupplierResponse;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierEligibilityService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RfqSupplierCandidateService {
    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;
    private final SupplierEligibilityService supplierEligibilityService;
    private final SupplierAccreditationService accreditationService;

    @org.springframework.beans.factory.annotation.Autowired
    public RfqSupplierCandidateService(
        SupplierRepository supplierRepository,
        SupplierMapper supplierMapper,
        SupplierEligibilityService supplierEligibilityService
    ) {
        this.supplierRepository = supplierRepository;
        this.supplierMapper = supplierMapper;
        this.supplierEligibilityService = supplierEligibilityService;
        this.accreditationService = null;
    }

    public RfqSupplierCandidateService(
        SupplierRepository supplierRepository,
        SupplierMapper supplierMapper,
        SupplierAccreditationService accreditationService
    ) {
        this.supplierRepository = supplierRepository;
        this.supplierMapper = supplierMapper;
        this.supplierEligibilityService = null;
        this.accreditationService = accreditationService;
    }

    @Transactional(readOnly = true)
    public Page<SupplierResponse> search(String search, Pageable pageable) {
        return search(search, Set.of(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<SupplierResponse> search(String search, Set<UUID> materialGroupIds, Pageable pageable) {
        AccreditationDemand demand = materialGroupIds == null || materialGroupIds.isEmpty()
            ? null
            : AccreditationDemand.materials(materialGroupIds);
        String normalizedSearch = normalize(search);
        List<Supplier> scopedSuppliers = normalizedSearch == null
            ? supplierRepository.findActiveRfqSupplierCandidates(
                TenantContext.currentTenant(), CompanyContext.currentCompany())
            : supplierRepository.findActiveRfqSupplierCandidates(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), normalizedSearch);
        List<SupplierResponse> candidates = scopedSuppliers
            .stream()
            .filter(supplier -> supplierEligibilityService != null || notExpiredOrIneligible(supplier))
            .map(supplier -> candidate(supplier, demand))
            .toList();

        int from = Math.min(Math.toIntExact(pageable.getOffset()), candidates.size());
        int to = Math.min(from + pageable.getPageSize(), candidates.size());
        return new PageImpl<>(candidates.subList(from, to), pageable, candidates.size());
    }

    private SupplierResponse candidate(Supplier supplier, AccreditationDemand demand) {
        SupplierResponse response = supplierMapper.toResponse(supplier);
        if (supplierEligibilityService != null) {
            response.setEligibility(supplierEligibilityService.evaluateNewBusiness(
                supplier.getId(), demand, "RFQ_INVITATION"));
        }
        return response;
    }

    private boolean notExpiredOrIneligible(Supplier supplier) {
        if (supplierEligibilityService != null) {
            return supplierEligibilityService
                .evaluateNewBusiness(supplier.getId(), null, "RFQ_CANDIDATE_DISCOVERY")
                .newBusinessAllowed();
        }
        String overallStatus = accreditationService
            .effectiveEligibility(supplier.getId(), List.of(), LocalDate.now(), "RFQ invitation")
            .overallStatus();
        return !"NOT_ELIGIBLE".equals(overallStatus);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
