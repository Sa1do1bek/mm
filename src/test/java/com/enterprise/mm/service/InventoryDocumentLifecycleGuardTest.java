package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.InventoryDocumentMapper;
import com.enterprise.mm.mapper.InventoryItemMapper;
import com.enterprise.mm.mapper.WarehouseMapper;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.InventoryDocumentMaterialGroupRepository;
import com.enterprise.mm.repository.InventoryDocumentMaterialRepository;
import com.enterprise.mm.repository.InventoryDocumentRepository;
import com.enterprise.mm.repository.InventoryItemRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryDocumentLifecycleGuardTest {
    private static final String TENANT = "inventory-lifecycle-guard";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Mock private InventoryDocumentRepository repository;
    @Mock private AuditService auditService;

    private InventoryDocumentService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        service = new InventoryDocumentService(
            repository,
            mock(InventoryDocumentMapper.class),
            mock(InventoryItemMapper.class),
            mock(WarehouseMapper.class),
            auditService,
            mock(RoleDataScopeService.class),
            mock(StockBalanceRepository.class),
            mock(InventoryItemRepository.class),
            mock(CommissionRepository.class),
            mock(CommissionMemberRepository.class),
            mock(InventoryCommissionService.class),
            mock(InventoryDocumentMaterialRepository.class),
            mock(InventoryDocumentMaterialGroupRepository.class),
            mock(MaterialRepository.class),
            mock(MaterialGroupRepository.class),
            mock(WarehouseRepository.class),
            mock(AttachmentService.class),
            mock(ApprovalEngineService.class),
            mock(MaterialCatalogGuard.class), mock(com.enterprise.mm.service.inventory.InventoryAccessService.class), mock(InventoryScopeService.class), mock(jakarta.persistence.EntityManager.class),
            mock(InventoryAiDraftService.class), mock(IdempotencyService.class), mock(InventoryStockAsOfQuery.class)
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void deleteRejectsStartedInventorySoFreezeCannotBeReleasedBySoftDelete() {
        InventoryDocument document = document(InventoryStatus.IN_PROGRESS);
        when(repository.findByIdAndIsDeletedFalse(document.getId())).thenReturn(Optional.of(document));
        org.mockito.Mockito.lenient().when(repository.findScopedForUpdate(document.getId(),TENANT,COMPANY)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.softDelete(document.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("before counting starts");

        verify(repository, never()).save(any());
    }

    @Test
    void restoreRejectsPreviouslyDeletedStartedInventory() {
        InventoryDocument document = document(InventoryStatus.IN_PROGRESS);
        document.softDelete();
        when(repository.findAnyById(document.getId())).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.restore(document.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("before counting starts");

        verify(repository, never()).save(any());
    }

    @Test
    void startingAnUnfrozenCountIsRejectedBeforeTakingItsSnapshot() {
        InventoryDocument document = document(InventoryStatus.PLANNED);
        document.setFreezeOperations(false);
        when(repository.findByIdAndIsDeletedFalse(document.getId())).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.start(document.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Enable the stock freeze");

        verify(repository, never()).save(any());
    }

    @Test
    void planningRejectsAnEndDateBeforeTheStartDateForBothCreateAndUpdate() {
        InventoryDocument document = document(InventoryStatus.PLANNED);
        document.setPlannedStartDate(java.time.LocalDate.of(2026, 11, 6));
        document.setPlannedEndDate(java.time.LocalDate.of(2026, 11, 5));

        assertThatThrownBy(() -> service.beforeCreate(document, new com.enterprise.mm.payload.InventoryDocumentCreateRequest()))
            .isInstanceOf(com.enterprise.mm.error.BadRequestException.class)
            .hasMessageContaining("end date must be on or after");
        assertThatThrownBy(() -> service.beforeUpdate(document, new com.enterprise.mm.payload.InventoryDocumentUpdateRequest()))
            .isInstanceOf(com.enterprise.mm.error.BadRequestException.class)
            .hasMessageContaining("end date must be on or after");

        verify(repository, never()).save(any());
    }

    private InventoryDocument document(InventoryStatus status) {
        InventoryDocument document = new InventoryDocument();
        document.setId(UUID.randomUUID());
        document.setTenantId(TENANT);
        document.setCompanyId(COMPANY);
        document.setCode("INV-GUARD");
        document.setName("Inventory guard");
        document.setStatus(status.name());
        return document;
    }
}
