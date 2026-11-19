package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationWriter;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.payload.StockMovementCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.StockMovementSearchRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementAllocationCostLayerRepository;
import com.enterprise.mm.repository.StockMovementAllocationRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.StockMovementApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class StockMovementApprovalWorkflowTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MOVEMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private final StockMovementRepository repository = mock(StockMovementRepository.class);
    private final ApprovalEngineService approvalEngineService = mock(ApprovalEngineService.class);
    private final MaterialCatalogGuard materialCatalogGuard = mock(MaterialCatalogGuard.class);
    private final RoleDataScopeService roleDataScopeService = mock(RoleDataScopeService.class);
    private final StockMovementService service = new StockMovementService(
        repository,
        new StockMovementMapper(),
        mock(StockBalanceRepository.class),
        mock(StockBatchRepository.class),
        mock(MaterialRepository.class),
        materialCatalogGuard,
        mock(WarehouseRepository.class),
        new StockBalanceMutationService(),
        mock(InventoryPeriodGuardService.class),
        mock(InventoryValuationPolicyService.class),
        mock(StockValuationService.class),
        mock(InventoryCostingService.class),
        mock(StockMovementAllocationWriter.class),
        mock(StockMovementAllocationRepository.class),
        mock(StockMovementAllocationCostLayerRepository.class),
        mock(AuditService.class),
        roleDataScopeService,
        mock(InventoryFreezeGuardService.class),
        mock(MaterialCostObservationService.class),
        approvalEngineService,
        false
    );

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        when(repository.save(any(StockMovement.class))).thenAnswer(invocation -> {
            StockMovement movement = invocation.getArgument(0);
            movement.setId(MOVEMENT_ID);
            return movement;
        });
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setId(UUID.fromString("60000000-0000-0000-0000-000000000001"));
        uom.setCode("EA");
        Material material = new Material();
        material.setId(MATERIAL_ID);
        material.setUnitOfMeasureId(uom.getId());
        material.setUnitOfMeasure(uom);
        when(materialCatalogGuard.requireActiveTmbMaterial(MATERIAL_ID, "materialId")).thenReturn(material);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createDraftDoesNotRequireAnApprovalFlowBeforeSubmission() {
        doThrow(new com.enterprise.mm.error.BusinessException("No active approval flow configured"))
            .when(approvalEngineService).linkItem(any(), any(), any(), any());
        StockMovementResponse response = service.create(createRequest());

        assertThat(response.getId()).isEqualTo(MOVEMENT_ID);
        assertThat(response.getStatus()).isEqualTo("DRAFT");
        verifyNoInteractions(approvalEngineService);
    }

    @Test
    void createAcceptsLegacySingleItemPayloadWithoutTopLevelMaterialId() {
        StockMovementCreateRequest request = createRequest();
        request.setMaterialId(null);
        request.setQuantity(null);
        request.setUnitCost(null);
        StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
        item.setMaterialId(MATERIAL_ID);
        item.setQuantity(BigDecimal.ONE);
        item.setUnitCost(BigDecimal.ZERO);
        request.setItems(java.util.List.of(item));

        StockMovementResponse response = service.create(request);

        assertThat(response.getId()).isEqualTo(MOVEMENT_ID);
        assertThat(response.getMaterialId()).isEqualTo(MATERIAL_ID);
        assertThat(response.getQuantity()).isEqualByComparingTo(BigDecimal.ONE);
        verifyNoInteractions(approvalEngineService);
    }

    @Test
    void submitForApprovalStartsApprovalForStockMovementItself() {
        StockMovement movement = draftMovement();
        when(repository.findByIdAndIsDeletedFalse(MOVEMENT_ID)).thenReturn(Optional.of(movement));

        StockMovementResponse response = service.submitForApproval(MOVEMENT_ID);

        assertThat(response.getId()).isEqualTo(MOVEMENT_ID);
        verify(approvalEngineService).start(StockMovementApprovalAdapter.MODULE_KEY, MOVEMENT_ID);
    }

    @Test
    void searchPassesInventoryIdToRepositorySourceInventoryItemFilter() {
        UUID inventoryId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        StockMovementSearchRequest request = new StockMovementSearchRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            inventoryId,
            null,
            null,
            null
        );
        Pageable pageable = PageRequest.of(0, 20);
        when(roleDataScopeService.applyWarehouseScope(any(SearchCriteria.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.search(
            eq(TENANT),
            eq(COMPANY),
            eq(false),
            eq(""),
            isNull(),
            isNull(),
            isNull(),
            eq(true),
            isNull(),
            isNull(),
            eq(true),
            eq(inventoryId),
            isNull(),
            isNull(),
            isNull(),
            eq(pageable)
        )).thenReturn(new PageImpl<>(java.util.List.of(), pageable, 0));

        service.search(request, pageable);

        verify(repository).search(
            eq(TENANT),
            eq(COMPANY),
            eq(false),
            eq(""),
            isNull(),
            isNull(),
            isNull(),
            eq(true),
            isNull(),
            isNull(),
            eq(true),
            eq(inventoryId),
            isNull(),
            isNull(),
            isNull(),
            eq(pageable)
        );
    }

    private StockMovementCreateRequest createRequest() {
        StockMovementCreateRequest request = new StockMovementCreateRequest();
        request.setCode("SMV-1");
        request.setMaterialId(MATERIAL_ID);
        request.setWarehouseId(WAREHOUSE_ID);
        request.setMovementType(MovementType.RECEIPT);
        request.setQuantity(BigDecimal.ONE);
        request.setUnitCost(BigDecimal.ZERO);
        request.setMovementDate(LocalDate.now());
        return request;
    }

    private StockMovement draftMovement() {
        StockMovement movement = new StockMovement();
        movement.setId(MOVEMENT_ID);
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setCode("SMV-1");
        movement.setName("Stock movement");
        movement.setStatus(StockMovementStatus.DRAFT.name());
        movement.setMaterialId(MATERIAL_ID);
        movement.setWarehouseId(WAREHOUSE_ID);
        movement.setMovementType(MovementType.RECEIPT);
        movement.setQuantity(BigDecimal.ONE);
        movement.setUnitCost(BigDecimal.ZERO);
        movement.setMovementDate(LocalDate.now());
        return movement;
    }
}
