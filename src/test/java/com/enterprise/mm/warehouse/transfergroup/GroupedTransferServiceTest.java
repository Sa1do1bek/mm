package com.enterprise.mm.warehouse.transfergroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.TransferCreateRequest;
import com.enterprise.mm.payload.TransferDocumentLineResponse;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.repository.TransferDocumentLineRepository;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.TransferDocumentService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupedTransferServiceTest {

    private static final String TENANT = "test-tenant";
    private static final UUID COMPANY = UUID.randomUUID();

    private WarehouseTransferGroupRepository groupRepository;
    private WarehouseTransferGroupItemRepository itemRepository;
    private TransferDocumentRepository transferRepository;
    private TransferDocumentLineRepository transferLineRepository;
    private TransferDocumentService transferDocumentService;
    private RoleDataScopeService roleDataScopeService;
    private WarehouseRepository warehouseRepository;
    private WarehouseTransferGroupService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        groupRepository = mock(WarehouseTransferGroupRepository.class);
        itemRepository = mock(WarehouseTransferGroupItemRepository.class);
        transferRepository = mock(TransferDocumentRepository.class);
        transferLineRepository = mock(TransferDocumentLineRepository.class);
        transferDocumentService = mock(TransferDocumentService.class);
        roleDataScopeService = mock(RoleDataScopeService.class);
        warehouseRepository = mock(WarehouseRepository.class);

        service = new WarehouseTransferGroupService(
            groupRepository,
            itemRepository,
            transferRepository,
            transferLineRepository,
            transferDocumentService,
            roleDataScopeService,
            warehouseRepository
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * P2 AT02: 1 ta umumiy talabnomadan 3 ta manzil omboriga alohida transferlar ochiladi
     * va umumiy jarayon yagona ota hujjatda kuzatiladi.
     */
    @Test
    @DisplayName("P2 AT02: 1 parent transfer request for 3 destinations generates 3 scoped child transfers with stable mappings and aggregate progress")
    void testSingleParentRequestGeneratesThreeChildTransfersWithAggregateProgress() {
        UUID sourceWarehouseId = UUID.randomUUID();
        UUID dest1 = UUID.randomUUID();
        UUID dest2 = UUID.randomUUID();
        UUID dest3 = UUID.randomUUID();

        UUID matA = UUID.randomUUID();
        UUID matB = UUID.randomUUID();
        UUID matC = UUID.randomUUID();

        when(roleDataScopeService.canAccessWarehouse(sourceWarehouseId)).thenReturn(true);
        when(groupRepository.save(any(WarehouseTransferGroup.class))).thenAnswer(inv -> {
            WarehouseTransferGroup g = inv.getArgument(0);
            g.setId(UUID.randomUUID());
            return g;
        });
        when(itemRepository.save(any(WarehouseTransferGroupItem.class))).thenAnswer(inv -> {
            WarehouseTransferGroupItem i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        // Mock child transfer creations
        UUID childTransferId1 = UUID.randomUUID();
        UUID childLineId1 = UUID.randomUUID();
        TransferDocumentResponse resp1 = new TransferDocumentResponse();
        resp1.setId(childTransferId1);
        TransferDocumentLineResponse lineResp1 = new TransferDocumentLineResponse();
        lineResp1.setId(childLineId1);
        lineResp1.setMaterialId(matA);
        lineResp1.setPlannedQuantity(new BigDecimal("50.0000"));
        resp1.setLines(List.of(lineResp1));

        UUID childTransferId2 = UUID.randomUUID();
        UUID childLineId2 = UUID.randomUUID();
        TransferDocumentResponse resp2 = new TransferDocumentResponse();
        resp2.setId(childTransferId2);
        TransferDocumentLineResponse lineResp2 = new TransferDocumentLineResponse();
        lineResp2.setId(childLineId2);
        lineResp2.setMaterialId(matB);
        lineResp2.setPlannedQuantity(new BigDecimal("40.0000"));
        resp2.setLines(List.of(lineResp2));

        UUID childTransferId3 = UUID.randomUUID();
        UUID childLineId3 = UUID.randomUUID();
        TransferDocumentResponse resp3 = new TransferDocumentResponse();
        resp3.setId(childTransferId3);
        TransferDocumentLineResponse lineResp3 = new TransferDocumentLineResponse();
        lineResp3.setId(childLineId3);
        lineResp3.setMaterialId(matC);
        lineResp3.setPlannedQuantity(new BigDecimal("30.0000"));
        resp3.setLines(List.of(lineResp3));

        when(transferDocumentService.create(any(TransferCreateRequest.class)))
            .thenReturn(resp1, resp2, resp3);

        TransferGroupCreateRequest request = new TransferGroupCreateRequest(
            "WTG-001",
            "Multi-branch dispatch",
            "Central warehouse distribution to 3 branches",
            sourceWarehouseId,
            null,
            LocalDate.of(2026, 10, 15),
            List.of(
                new TransferGroupCreateRequest.DestinationPlan(
                    dest1,
                    null,
                    List.of(new TransferGroupCreateRequest.MaterialLine(matA, new BigDecimal("50.0000"), null, null))
                ),
                new TransferGroupCreateRequest.DestinationPlan(
                    dest2,
                    null,
                    List.of(new TransferGroupCreateRequest.MaterialLine(matB, new BigDecimal("40.0000"), null, null))
                ),
                new TransferGroupCreateRequest.DestinationPlan(
                    dest3,
                    null,
                    List.of(new TransferGroupCreateRequest.MaterialLine(matC, new BigDecimal("30.0000"), null, null))
                )
            )
        );

        TransferGroupResponse response = service.createGroup(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.code()).isEqualTo("WTG-001");
        assertThat(response.childTransferIds()).hasSize(3);
        assertThat(response.childTransferIds()).containsExactlyInAnyOrder(childTransferId1, childTransferId2, childTransferId3);
        assertThat(response.items()).hasSize(3);

        // Verify aggregate progress across all 3 child destinations:
        // Total planned = 50 + 40 + 30 = 120
        TransferGroupAggregateProgress agg = response.aggregateProgress();
        assertThat(agg.plannedQuantity()).isEqualByComparingTo("120.0000");
        assertThat(agg.outstandingQuantity()).isEqualByComparingTo("120.0000");
        assertThat(agg.dispatchedQuantity()).isEqualByComparingTo("0");
        assertThat(agg.receivedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("P2 AT02: Creating a group transfer without access to the source warehouse is rejected")
    void testSourceWarehouseAccessViolationRejected() {
        UUID sourceWarehouseId = UUID.randomUUID();
        when(roleDataScopeService.canAccessWarehouse(sourceWarehouseId)).thenReturn(false);

        TransferGroupCreateRequest request = new TransferGroupCreateRequest(
            "WTG-FAIL",
            "Denied transfer",
            null,
            sourceWarehouseId,
            null,
            LocalDate.now(),
            List.of(
                new TransferGroupCreateRequest.DestinationPlan(
                    UUID.randomUUID(),
                    null,
                    List.of(new TransferGroupCreateRequest.MaterialLine(UUID.randomUUID(), BigDecimal.TEN, null, null))
                )
            )
        );

        assertThatThrownBy(() -> service.createGroup(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("User does not have access to source warehouse");
    }

    @Test
    @DisplayName("P2 AT02: Destination warehouse cannot be identical to source warehouse")
    void testSameDestinationAndSourceWarehouseRejected() {
        UUID sourceWarehouseId = UUID.randomUUID();
        when(roleDataScopeService.canAccessWarehouse(sourceWarehouseId)).thenReturn(true);

        TransferGroupCreateRequest request = new TransferGroupCreateRequest(
            "WTG-SAME",
            "Same warehouse",
            null,
            sourceWarehouseId,
            null,
            LocalDate.now(),
            List.of(
                new TransferGroupCreateRequest.DestinationPlan(
                    sourceWarehouseId, // Same as source!
                    null,
                    List.of(new TransferGroupCreateRequest.MaterialLine(UUID.randomUUID(), BigDecimal.TEN, null, null))
                )
            )
        );

        assertThatThrownBy(() -> service.createGroup(request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Source and destination warehouses must be different");
    }
}
