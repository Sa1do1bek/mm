package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.repository.SupplierRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierEligibilityServiceTest {
    private static final String TENANT = "supplier-eligibility-test";

    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierAccreditationService accreditationService;

    private UUID companyId;
    private SupplierEligibilityService service;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        service = new SupplierEligibilityService(supplierRepository, accreditationService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void rfqInvitationAccreditationReasonUsesHumanOperationLabel() {
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(companyId);
        supplier.setStatus("ACTIVE");
        supplier.setCategory("A");
        AccreditationDemand demand = AccreditationDemand.materials(Set.of(materialGroupId));

        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplierId))
            .thenReturn(Optional.of(supplier));
        when(accreditationService.supplierEligibilityBlockedReasonForDemand(
            supplierId,
            demand,
            "RFQ invitation",
            LocalDate.now()
        )).thenReturn("Supplier accreditation does not cover the material group for RFQ invitation");

        var eligibility = service.evaluateNewBusiness(supplierId, demand, "RFQ_INVITATION");

        assertThat(eligibility.newBusinessAllowed()).isFalse();
        assertThat(eligibility.summaryReason())
            .isEqualTo("Supplier accreditation does not cover the material group for RFQ invitation");
    }
}
