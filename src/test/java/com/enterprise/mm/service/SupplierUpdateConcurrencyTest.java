package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.SupplierMapper;
import com.enterprise.mm.payload.SupplierUpdateRequest;
import com.enterprise.mm.repository.SupplierRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierUpdateConcurrencyTest {

    @Mock SupplierRepository repository;
    @Mock SupplierMapper mapper;
    @Mock AuditService auditService;
    @Mock EntityManager entityManager;
    @Mock com.enterprise.mm.repository.EmployeeRepository employeeRepository;
    @Mock IdempotencyService idempotencyService;

    private SupplierService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        service = new SupplierService(repository, mapper, auditService, entityManager, employeeRepository, idempotencyService);
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-supplier-version");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void rejectsStaleSupplierMasterUpdateBeforeMappingOrSaving() {
        UUID supplierId = UUID.randomUUID();
        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId("tenant-supplier-version");
        supplier.setCompanyId(companyId);
        supplier.setVersion(7L);
        when(repository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(supplier));

        SupplierUpdateRequest request = new SupplierUpdateRequest();
        request.setExpectedVersion(6L);
        request.setLegalName("Stale legal name");

        assertThatThrownBy(() -> service.update(supplierId, request))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(mapper, never()).updateEntity(supplier, request);
        verify(repository, never()).save(supplier);
    }
}
