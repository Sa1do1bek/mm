package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.mapper.SupplierMapper;
import com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility;
import com.enterprise.mm.payload.SupplierResponse;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.SupplierAccreditationService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class RfqSupplierCandidateServiceTest {
    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierAccreditationService accreditationService;

    private RfqSupplierCandidateService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("rfq-supplier-candidates-test");
        CompanyContext.setCompany(companyId);
        service = new RfqSupplierCandidateService(
            supplierRepository,
            new SupplierMapper(),
            accreditationService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void excludesNotEligibleSuppliersFromRfqInvitationCandidates() {
        Supplier eligible = supplier("supplier-1", "ACTIVE");
        Supplier expired = supplier("supplier-2", "ACTIVE");
        when(supplierRepository.findActiveRfqSupplierCandidates(
            eq("rfq-supplier-candidates-test"),
            eq(companyId),
            eq("pump")
        )).thenReturn(List.of(eligible, expired));
        when(accreditationService.effectiveEligibility(eq(eligible.getId()), any(), any(LocalDate.class), eq("RFQ invitation")))
            .thenReturn(eligibility(eligible.getId(), "ELIGIBLE", true));
        when(accreditationService.effectiveEligibility(eq(expired.getId()), any(), any(LocalDate.class), eq("RFQ invitation")))
            .thenReturn(eligibility(expired.getId(), "NOT_ELIGIBLE", false));

        Page<SupplierResponse> result = service.search(" pump ", PageRequest.of(0, 50));

        assertThat(result.getContent())
            .extracting(SupplierResponse::getId)
            .containsExactly(eligible.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void keepsUnknownEligibilityActiveSuppliersVisibleForTheRfqSelector() {
        Supplier supplier = supplier("supplier-1", "ACTIVE");
        when(supplierRepository.findActiveRfqSupplierCandidates(
            eq("rfq-supplier-candidates-test"),
            eq(companyId)
        )).thenReturn(List.of(supplier));
        when(accreditationService.effectiveEligibility(eq(supplier.getId()), any(), any(LocalDate.class), eq("RFQ invitation")))
            .thenReturn(eligibility(supplier.getId(), "UNKNOWN", false));

        Page<SupplierResponse> result = service.search(null, PageRequest.of(0, 50));

        assertThat(result.getContent())
            .extracting(SupplierResponse::getId)
            .containsExactly(supplier.getId());
    }

    private Supplier supplier(String code, String status) {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId("rfq-supplier-candidates-test");
        supplier.setCompanyId(companyId);
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus(status);
        return supplier;
    }

    private EffectiveSupplierEligibility eligibility(UUID supplierId, String status, boolean eligible) {
        return new EffectiveSupplierEligibility(
            supplierId,
            LocalDate.of(2026, 8, 11),
            status,
            eligible,
            List.of(),
            List.of(),
            null
        );
    }
}
