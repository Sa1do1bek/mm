package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.payload.SupplierWorkspacePreviewRequest;
import com.enterprise.mm.repository.SupplierRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierWorkspacePreviewServiceTest {
    @Mock SupplierRepository supplierRepository;
    SupplierWorkspacePreviewService service;
    UUID companyId;

    @BeforeEach
    void setUp() {
        service = new SupplierWorkspacePreviewService(supplierRepository);
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void previewReturnsExactTaxMatchAndDoesNotMutate() {
        when(supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse("tenant-a", companyId))
            .thenReturn(List.of(existingSupplier("302000111", "Industrial Pumps LLC")));

        var result = service.preview(new SupplierWorkspacePreviewRequest(
            null, "Industrial Pumps", null, "302 000 111", null, null,
            "EXTERNAL", "HEAD_OFFICE", List.of("GOODS")));

        assertThat(result.exactDuplicate()).isTrue();
        assertThat(result.duplicates()).singleElement()
            .satisfies(row -> {
                assertThat(row.matchReasons()).contains("TAX_ID");
                assertThat(row.supplier().displayName()).isEqualTo("Industrial Pumps LLC");
            });
        verify(supplierRepository, never()).save(any());
    }

    @Test
    void internalPreviewDoesNotClaimReadinessBypass() {
        when(supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse("tenant-a", companyId))
            .thenReturn(List.of());

        var result = service.preview(new SupplierWorkspacePreviewRequest(
            null, "Internal Repairs", null, null, null, null,
            "INTERNAL", "HEAD_OFFICE", List.of("SERVICES")));

        assertThat(result.policyImpact().readinessBypass()).isFalse();
    }

    private Supplier existingSupplier(String taxId, String name) {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId("tenant-a");
        supplier.setCompanyId(companyId);
        supplier.setCode("SUP-1");
        supplier.setName(name);
        supplier.setLegalName(name);
        supplier.setTaxId(taxId);
        supplier.setStatus("ACTIVE");
        return supplier;
    }
}
