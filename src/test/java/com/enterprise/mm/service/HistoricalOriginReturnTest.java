package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialReturnDocument;
import com.enterprise.mm.entity.MaterialReturnLine;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MaterialReturnCondition;
import com.enterprise.mm.enums.MaterialReturnStatus;
import com.enterprise.mm.enums.ReturnStockStatus;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.MaterialReturnMapper;
import com.enterprise.mm.payload.MaterialReturnAcceptRequest;
import com.enterprise.mm.payload.MaterialReturnCreateRequest;
import com.enterprise.mm.payload.MaterialReturnResponse;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.repository.IssueRequestLineRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialReturnDocumentRepository;
import com.enterprise.mm.repository.MaterialReturnLineRepository;
import com.enterprise.mm.repository.ReturnOriginLinkRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HistoricalOriginReturnTest {

    private static final String TENANT = "test-tenant";
    private static final UUID COMPANY = UUID.randomUUID();

    private MaterialReturnDocumentRepository repository;
    private MaterialReturnLineRepository lineRepository;
    private IssueRequestLineRepository issueLineRepository;
    private IssueRequestService issueRequestService;
    private WarehouseRepository warehouseRepository;
    private MaterialReturnMapper mapper;
    private StockMovementDocumentService stockMovementDocumentService;
    private StockBalanceRepository stockBalanceRepository;
    private StockBalanceMutationService stockBalanceMutationService;
    private MaterialCatalogGuard materialCatalogGuard;
    private MaterialRepository materialRepository;
    private IdempotencyService idempotencyService;
    private AuditService auditService;
    private AuditSnapshotSerializer snapshotSerializer;
    private RoleDataScopeService roleDataScopeService;
    private MaterialUnitEligibilityService materialUnitEligibilityService;
    private InventoryPeriodGuardService periodGuard;
    private ReturnOriginLinkRepository returnOriginLinkRepository;
    private MaterialReturnService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        repository = mock(MaterialReturnDocumentRepository.class);
        lineRepository = mock(MaterialReturnLineRepository.class);
        issueLineRepository = mock(IssueRequestLineRepository.class);
        issueRequestService = mock(IssueRequestService.class);
        warehouseRepository = mock(WarehouseRepository.class);
        mapper = new MaterialReturnMapper();
        stockMovementDocumentService = mock(StockMovementDocumentService.class);
        stockBalanceRepository = mock(StockBalanceRepository.class);
        stockBalanceMutationService = mock(StockBalanceMutationService.class);
        materialCatalogGuard = mock(MaterialCatalogGuard.class);
        materialRepository = mock(MaterialRepository.class);
        idempotencyService = mock(IdempotencyService.class);
        auditService = mock(AuditService.class);
        snapshotSerializer = mock(AuditSnapshotSerializer.class);
        roleDataScopeService = mock(RoleDataScopeService.class);
        materialUnitEligibilityService = mock(MaterialUnitEligibilityService.class);
        periodGuard = mock(InventoryPeriodGuardService.class);
        returnOriginLinkRepository = mock(ReturnOriginLinkRepository.class);

        when(roleDataScopeService.canAccessWarehouse(any())).thenReturn(true);
        when(materialRepository.findByIdAndIsDeletedFalse(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Material m = new Material();
            m.setId(id);
            m.setTenantId(TENANT);
            m.setCompanyId(COMPANY);
            m.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
            return Optional.of(m);
        });
        when(warehouseRepository.findByIdAndIsDeletedFalse(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Warehouse w = new Warehouse();
            w.setId(id);
            w.setTenantId(TENANT);
            w.setCompanyId(COMPANY);
            return Optional.of(w);
        });
        when(idempotencyService.execute(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Object arg = inv.getArgument(5);
                if (arg instanceof java.util.function.Supplier<?> sup) {
                    return sup.get();
                }
                return null;
            });

        service = new MaterialReturnService(
            mock(InventoryFreezeGuardService.class),
            repository,
            lineRepository,
            issueLineRepository,
            issueRequestService,
            warehouseRepository,
            mapper,
            stockMovementDocumentService,
            stockBalanceRepository,
            stockBalanceMutationService,
            materialCatalogGuard,
            materialRepository,
            idempotencyService,
            auditService,
            snapshotSerializer,
            roleDataScopeService,
            materialUnitEligibilityService,
            periodGuard,
            returnOriginLinkRepository
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * P2 AT05: 1C tarixiy hujjati bo'yicha tovar omborga qaytariladi (soxta MM buyurtmasi yasalmaydi).
     * Muqobil tasdiqlangan omborga qaytarish qabul qilinadi.
     */
    @Test
    @DisplayName("P2 AT05: Historical 1C return without fake MM issue succeeds and records origin link")
    void testHistorical1CReturnWithoutFakeMMIssue() {
        UUID warehouseId = UUID.randomUUID();
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId(TENANT);
        warehouse.setCompanyId(COMPANY);

        UUID materialId = UUID.randomUUID();
        Material material = new Material();
        material.setId(materialId);
        material.setTenantId(TENANT);
        material.setCompanyId(COMPANY);
        material.setName("Historical Valve D-50");
        material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);

        when(warehouseRepository.findByIdAndIsDeletedFalse(warehouseId)).thenReturn(Optional.of(warehouse));
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(materialRepository.findByIdAndIsDeletedFalse(materialId)).thenReturn(Optional.of(material));
        when(repository.save(any(MaterialReturnDocument.class))).thenAnswer(inv -> {
            MaterialReturnDocument doc = inv.getArgument(0);
            doc.setId(UUID.randomUUID());
            return doc;
        });

        List<MaterialReturnLine> savedLines = new java.util.ArrayList<>();
        when(lineRepository.save(any(MaterialReturnLine.class))).thenAnswer(inv -> {
            MaterialReturnLine l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId(UUID.randomUUID());
            }
            savedLines.add(l);
            return l;
        });
        when(lineRepository.findByTenantIdAndReturnDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(eq(TENANT), any()))
            .thenAnswer(inv -> new java.util.ArrayList<>(savedLines));

        // Sum returned quantity from origin link repository is 0 (first return)
        when(returnOriginLinkRepository.sumReturnedQuantity(eq(TENANT), eq(COMPANY), eq("1C-DOC-998822"), eq(materialId)))
            .thenReturn(BigDecimal.ZERO);

        MaterialReturnCreateRequest request = new MaterialReturnCreateRequest();
        request.setOriginType("HISTORICAL_1C");
        request.setHistoricalDocumentNumber("1C-DOC-998822");
        request.setHistoricalDocumentDate(LocalDate.of(2025, 11, 20));
        request.setHistoricalSourceSystem("1C_ENTERPRISE");
        request.setWarehouseId(warehouseId);
        request.setReason("Returned from workshop after overhaul");

        MaterialReturnCreateRequest.Line line = new MaterialReturnCreateRequest.Line();
        line.setMaterialId(materialId);
        line.setIssuedQuantity(new BigDecimal("20.0000"));
        line.setReturnQuantity(new BigDecimal("5.0000"));
        line.setOriginalUnitCost(new BigDecimal("125000.00"));
        line.setCondition(MaterialReturnCondition.USABLE);
        request.setLines(List.of(line));

        MaterialReturnResponse response = service.create(request);

        assertThat(response).isNotNull();
        assertThat(response.getOriginType()).isEqualTo("HISTORICAL_1C");
        assertThat(response.getHistoricalDocumentNumber()).isEqualTo("1C-DOC-998822");
        assertThat(response.getIssueRequestId()).isNull(); // NO fake MM issue!
        assertThat(response.getLines()).hasSize(1);
        assertThat(response.getLines().getFirst().getReturnQuantity()).isEqualByComparingTo("5.0000");
    }

    /**
     * P2 AT05: Over-return (attempting to return more than originally issued minus previously returned) is rejected.
     */
    @Test
    @DisplayName("P2 AT05: Over-return beyond historical issued limit is rejected")
    void testOverReturnRejected() {
        UUID warehouseId = UUID.randomUUID();
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId(TENANT);
        warehouse.setCompanyId(COMPANY);

        UUID materialId = UUID.randomUUID();
        Material material = new Material();
        material.setId(materialId);
        material.setTenantId(TENANT);
        material.setCompanyId(COMPANY);
        material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);

        when(warehouseRepository.findByIdAndIsDeletedFalse(warehouseId)).thenReturn(Optional.of(warehouse));
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(materialRepository.findByIdAndIsDeletedFalse(materialId)).thenReturn(Optional.of(material));

        // 15 units were already returned out of 20
        when(returnOriginLinkRepository.sumReturnedQuantity(eq(TENANT), eq(COMPANY), eq("1C-DOC-998822"), eq(materialId)))
            .thenReturn(new BigDecimal("15.0000"));

        MaterialReturnCreateRequest request = new MaterialReturnCreateRequest();
        request.setOriginType("HISTORICAL_1C");
        request.setHistoricalDocumentNumber("1C-DOC-998822");
        request.setWarehouseId(warehouseId);

        MaterialReturnCreateRequest.Line line = new MaterialReturnCreateRequest.Line();
        line.setMaterialId(materialId);
        line.setIssuedQuantity(new BigDecimal("20.0000"));
        line.setReturnQuantity(new BigDecimal("10.0000")); // Only 5 available to return!
        line.setCondition(MaterialReturnCondition.USABLE);
        request.setLines(List.of(line));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("returnQuantity exceeds returnableQuantity");
    }

    /**
     * P2 AT05: Accepting a return in a closed accounting period is strictly rejected.
     */
    @Test
    @DisplayName("P2 AT05: Accepting return in closed accounting period is rejected")
    void testAcceptReturnInClosedPeriodRejected() {
        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        MaterialReturnDocument doc = new MaterialReturnDocument();
        doc.setId(docId);
        doc.setWarehouseId(warehouseId);
        doc.setStatus(MaterialReturnStatus.SUBMITTED.name());
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);

        UUID lineId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        MaterialReturnLine line = new MaterialReturnLine();
        line.setId(lineId);
        line.setReturnDocumentId(docId);
        line.setMaterialId(materialId);
        line.setReturnQuantity(new BigDecimal("5.0000"));
        line.setCondition(MaterialReturnCondition.USABLE);

        when(repository.findByIdAndIsDeletedFalse(docId)).thenReturn(Optional.of(doc));
        when(lineRepository.findByTenantIdAndReturnDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, docId))
            .thenReturn(List.of(line));
        // Period is closed!
        when(periodGuard.isClosed(eq(warehouseId), any(LocalDate.class))).thenReturn(true);

        MaterialReturnAcceptRequest acceptReq = new MaterialReturnAcceptRequest();
        MaterialReturnAcceptRequest.Line acceptLine = new MaterialReturnAcceptRequest.Line();
        acceptLine.setReturnLineId(lineId);
        acceptLine.setAcceptedQuantity(new BigDecimal("5.0000"));
        acceptLine.setRejectedQuantity(BigDecimal.ZERO);
        acceptReq.setLines(List.of(acceptLine));

        assertThatThrownBy(() -> service.accept(docId, acceptReq, "key-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Inventory period is closed");
    }

    /**
     * P2 AT05: Damaged return condition is routed to QUARANTINE stock status.
     */
    @Test
    @DisplayName("P2 AT05: Damaged return is placed into QUARANTINE stock status")
    void testDamagedReturnRoutedToQuarantine() {
        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        MaterialReturnDocument doc = new MaterialReturnDocument();
        doc.setId(docId);
        doc.setWarehouseId(warehouseId);
        doc.setStatus(MaterialReturnStatus.SUBMITTED.name());
        doc.setCode("RET-DMG-01");
        doc.setReason("Damaged valve overhaul return");
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);
        doc.setOriginType("HISTORICAL_1C");
        doc.setHistoricalDocumentNumber("1C-DOC-DAMAGED");

        UUID lineId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        MaterialReturnLine line = new MaterialReturnLine();
        line.setId(lineId);
        line.setReturnDocumentId(docId);
        line.setMaterialId(materialId);
        line.setReturnQuantity(new BigDecimal("3.0000"));
        line.setCondition(MaterialReturnCondition.DAMAGED);

        when(repository.findByIdAndIsDeletedFalse(docId)).thenReturn(Optional.of(doc));
        when(lineRepository.findByTenantIdAndReturnDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, docId))
            .thenReturn(List.of(line));
        when(periodGuard.isClosed(eq(warehouseId), any(LocalDate.class))).thenReturn(false);

        Material material = new Material();
        material.setId(materialId);
        material.setTenantId(TENANT);
        material.setCompanyId(COMPANY);
        material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
        when(materialRepository.findByIdAndIsDeletedFalse(materialId)).thenReturn(Optional.of(material));

        StockMovementDocumentResponse movementResponse = new StockMovementDocumentResponse();
        movementResponse.setId(UUID.randomUUID());
        movementResponse.setVersion(1L);
        StockMovementResponse itemResp = new StockMovementResponse();
        itemResp.setMaterialId(materialId);
        movementResponse.setItems(List.of(itemResp));
        when(stockMovementDocumentService.create(any())).thenReturn(movementResponse);
        when(stockMovementDocumentService.changeStatus(any(), any())).thenReturn(movementResponse);
        when(stockMovementDocumentService.postManagedReturn(any(), any(), any())).thenReturn(movementResponse);

        StockBalance balance = new StockBalance();
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal("3.0000"));
        balance.setAvailableQuantity(new BigDecimal("3.0000"));
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        when(stockBalanceRepository.findCompanyBalanceForUpdate(any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any()))
            .thenReturn(Optional.of(balance));

        when(repository.save(any(MaterialReturnDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        MaterialReturnAcceptRequest acceptReq = new MaterialReturnAcceptRequest();
        MaterialReturnAcceptRequest.Line acceptLine = new MaterialReturnAcceptRequest.Line();
        acceptLine.setReturnLineId(lineId);
        acceptLine.setAcceptedQuantity(new BigDecimal("3.0000"));
        acceptLine.setRejectedQuantity(BigDecimal.ZERO);
        acceptReq.setLines(List.of(acceptLine));

        service.accept(docId, acceptReq, "key-2");

        // Verify line target status became QUALITY_INSPECTION (quarantine)!
        assertThat(line.getTargetStockStatus()).isEqualTo(ReturnStockStatus.QUALITY_INSPECTION);
    }
}
