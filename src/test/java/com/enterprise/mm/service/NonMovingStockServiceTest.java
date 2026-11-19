package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.enums.NonMovingStockStatus;
import com.enterprise.mm.mapper.NonMovingStockMapper;
import com.enterprise.mm.payload.NonMovingStockResponse;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NonMovingStockServiceTest {

    private static final String TENANT_ID = "tenant-non-moving-test";

    @Mock private NonMovingStockRepository repository;
    @Mock private NonMovingStockMapper mapper;
    @Mock private CompanyRepository companyRepository;
    @Mock private StockBalanceRepository stockBalanceRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private WarehouseService warehouseService;
    @Mock private MaterialCatalogGuard materialCatalogGuard;

    private NonMovingStockService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(companyId);
        service = new NonMovingStockService(
            repository,
            mapper,
            companyRepository,
            stockBalanceRepository,
            stockMovementRepository,
            auditService,
            roleDataScopeService,
            warehouseService,
            materialCatalogGuard
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void analyzePreservesActiveDetectionLifecycleState() {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Company company = new Company();
        company.setNonMovingStockThresholdMonths(12);

        StockBalance balance = new StockBalance();
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("8.0000"));
        balance.setCreatedAt(LocalDateTime.now().minusMonths(18));

        NonMovingStock existing = new NonMovingStock();
        existing.setId(UUID.randomUUID());
        existing.setCompanyId(companyId);
        existing.setMaterialId(materialId);
        existing.setWarehouseId(warehouseId);
        existing.setStatus(NonMovingStockStatus.UNDER_REVIEW.name());
        LocalDateTime firstDetectedAt = LocalDateTime.now().minusMonths(6);
        existing.setDetectedAt(firstDetectedAt);

        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT_ID, companyId))
            .thenReturn(Optional.of(company));
        when(stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TENANT_ID, companyId))
            .thenReturn(List.of(balance));
        when(stockMovementRepository
            .findFirstByTenantIdAndCompanyIdAndMaterial_IdAndWarehouse_IdAndStatusInAndIsDeletedFalseOrderByPostedAtDesc(
                eq(TENANT_ID),
                eq(companyId),
                eq(materialId),
                eq(warehouseId),
                anyList()
            )).thenReturn(Optional.empty());
        when(repository
            .findFirstByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
                eq(TENANT_ID),
                eq(companyId),
                eq(materialId),
                eq(warehouseId),
                anyList()
            )).thenReturn(Optional.of(existing));
        when(repository.save(any(NonMovingStock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any(NonMovingStock.class))).thenReturn(new NonMovingStockResponse());

        service.analyze();

        assertThat(existing.getStatus()).isEqualTo(NonMovingStockStatus.UNDER_REVIEW.name());
        assertThat(existing.getQuantity()).isEqualByComparingTo("8.0000");
        assertThat(existing.getMonthsWithoutMovement()).isGreaterThanOrEqualTo(17);
        assertThat(existing.getDetectedAt()).isEqualTo(firstDetectedAt);
        verify(auditService).log(
            "NonMovingStock",
            existing.getId(),
            "REFRESH_ANALYSIS",
            existing.getMonthsWithoutMovement() + " months",
            null,
            null
        );
    }
}
