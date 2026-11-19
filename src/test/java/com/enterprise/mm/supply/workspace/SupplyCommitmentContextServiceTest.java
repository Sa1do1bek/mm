package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.CustomsDeclaration;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.ImportFreightBooking;
import com.enterprise.mm.entity.ImportInsurancePolicy;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.LandedCostComponent;
import com.enterprise.mm.entity.LandedLogisticsEvent;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.CustomsDeclarationRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.ImportFreightBookingRepository;
import com.enterprise.mm.repository.ImportInsurancePolicyRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.LandedCostComponentRepository;
import com.enterprise.mm.repository.LandedLogisticsEventRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.SupplyCommitmentContextResponse;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import com.enterprise.mm.supply.workspace.service.SupplyCommitmentContextService;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.AccreditationContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.AdvanceShippingNoticeContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.AuditTimelineItem;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EvidenceItem;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeHeader;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeLine;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.IntegrationImpactItem;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.StockMovementContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.WorkspaceAction;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.WorkspaceBlocker;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SupplyCommitmentContextServiceTest {
    private static final String TENANT = "tenant-supply-context";
    private static final UUID COMPANY = UUID.fromString("9117774d-e2a6-42d8-8130-17b8808bf692");
    private static final UUID OTHER_COMPANY = UUID.fromString("2bc7c566-2cd0-48b2-9ef3-3fc0c327ff1d");
    private static final UUID PO_ID = UUID.fromString("4a9ba593-42d0-439f-8106-192bf676d02e");
    private static final UUID SUPPLIER_ID = UUID.fromString("58b07c48-7056-4677-9a99-f82b1fcd4521");
    private static final UUID CONTRACT_ID = UUID.fromString("08a054f8-831d-4d09-bb5c-878b39a08162");
    private static final UUID SCHEDULE_ID = UUID.fromString("f41b66b5-a7ac-4556-8ad0-b9aff43e99dc");
    private static final UUID LINE_ID = UUID.fromString("29c5a5b1-203d-4935-8d67-f4b4adccce6a");
    private static final UUID MATERIAL_ID = UUID.fromString("5848cd08-9b22-4d4e-8550-fe2a51d8a1b7");
    private static final UUID MATERIAL_GROUP_ID = UUID.fromString("11b3098a-350c-46de-8dc6-93504ffdc430");
    private static final UUID WAREHOUSE_ID = UUID.fromString("a44b319b-40de-452e-aab0-85f0659d048d");
    private static final UUID OTHER_WAREHOUSE_ID = UUID.fromString("dc169c2f-87d8-4b42-9cb8-3496218bed06");
    private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.of(2026, 7, 16, 9, 0);

    private final SupplyCommitmentQueryRepository commitmentRepository = mock(SupplyCommitmentQueryRepository.class);
    private final GoodsIncomeWorkspaceService goodsIncomeService = mock(GoodsIncomeWorkspaceService.class);
    private final DeliveryScheduleRepository scheduleRepository = mock(DeliveryScheduleRepository.class);
    private final PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
    private final AdvanceShippingNoticeRepository asnRepository = mock(AdvanceShippingNoticeRepository.class);
    private final ImportDeliveryRepository importRepository = mock(ImportDeliveryRepository.class);
    private final CustomsDeclarationRepository customsRepository = mock(CustomsDeclarationRepository.class);
    private final ImportFreightBookingRepository freightRepository = mock(ImportFreightBookingRepository.class);
    private final ImportInsurancePolicyRepository insuranceRepository = mock(ImportInsurancePolicyRepository.class);
    private final LandedCostComponentRepository landedCostRepository = mock(LandedCostComponentRepository.class);
    private final LandedLogisticsEventRepository logisticsEventRepository = mock(LandedLogisticsEventRepository.class);
    private final ContractRepository contractRepository = mock(ContractRepository.class);
    private final AuditLogRepository auditRepository = mock(AuditLogRepository.class);
    private final IntegrationEventRepository integrationRepository = mock(IntegrationEventRepository.class);
    private final SupplyCommitmentKeyCodec codec = new SupplyCommitmentKeyCodec();
    private final SupplyAccessScope scope = new SupplyAccessScope(TENANT, Set.of(COMPANY), Set.of(), false, false);
    private final SupplyCommitmentContextService service = new SupplyCommitmentContextService(
        commitmentRepository,
        goodsIncomeService,
        scheduleRepository,
        itemRepository,
        asnRepository,
        importRepository,
        customsRepository,
        freightRepository,
        insuranceRepository,
        landedCostRepository,
        logisticsEventRepository,
        contractRepository,
        auditRepository,
        integrationRepository,
        codec,
        Clock.fixed(NOW, ZoneId.of("UTC")),
        "Asia/Tashkent"
    );

    @BeforeEach
    void defaults() {
        authenticate("supply.manager", "ROLE_PROCUREMENT_MANAGER");
        when(goodsIncomeService.context(PO_ID)).thenReturn(goodsIncome(true, true));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TENANT, PO_ID))
            .thenReturn(List.of(purchaseOrderItem()));
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(TENANT, COMPANY, PO_ID))
            .thenReturn(List.of());
        when(importRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByEtaAsc(
            TENANT, COMPANY, PO_ID)).thenReturn(List.of());
        when(contractRepository.findByIdAndIsDeletedFalse(CONTRACT_ID)).thenReturn(Optional.of(contract()));
        when(integrationRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY, PO_ID)).thenReturn(List.of());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scheduledDossierReconcilesPoLinesAndLinksSupplyReceivingAuditAndIntegrationSources() {
        SupplyCommitmentKey key = new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, SCHEDULE_ID);
        when(commitmentRepository.findScoped(key, scope, NOW)).thenReturn(Optional.of(commitment(key, false)));
        when(scheduleRepository.findByIdAndIsDeletedFalse(SCHEDULE_ID)).thenReturn(Optional.of(schedule()));

        AdvanceShippingNotice asn = asn();
        ImportDelivery delivery = importDelivery(asn.getId());
        CustomsDeclaration customs = customs(delivery.getId());
        ImportFreightBooking freight = freight(delivery.getId());
        ImportInsurancePolicy insurance = insurance(delivery.getId());
        LandedCostComponent landedCost = landedCost(delivery.getId());
        LandedLogisticsEvent logisticsEvent = logisticsEvent(delivery.getId());
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(TENANT, COMPANY, PO_ID))
            .thenReturn(List.of(asn));
        when(importRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByEtaAsc(
            TENANT, COMPANY, PO_ID)).thenReturn(List.of(delivery));
        when(importRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(TENANT, COMPANY, asn.getId()))
            .thenReturn(Optional.of(delivery));
        when(customsRepository.findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(
            TENANT, COMPANY, delivery.getId())).thenReturn(List.of(customs));
        when(freightRepository.findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(
            TENANT, COMPANY, delivery.getId())).thenReturn(List.of(freight));
        when(insuranceRepository.findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(
            TENANT, COMPANY, delivery.getId())).thenReturn(List.of(insurance));
        when(landedCostRepository.findByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalse(
            TENANT, COMPANY, delivery.getId())).thenReturn(List.of(landedCost));
        when(logisticsEventRepository.findByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalseOrderByEventDateAsc(
            TENANT, COMPANY, delivery.getId())).thenReturn(List.of(logisticsEvent));

        AuditLog scheduleAudit = audit("DeliverySchedule", SCHEDULE_ID, "CONTROL", NOW_LOCAL.minusHours(3));
        AuditLog importAudit = audit("ImportDelivery", delivery.getId(), "ARRIVE_CUSTOMS", NOW_LOCAL.minusHours(2));
        when(auditRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TENANT, COMPANY, "DeliverySchedule", SCHEDULE_ID)).thenReturn(List.of(scheduleAudit));
        when(auditRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TENANT, COMPANY, "ImportDelivery", delivery.getId())).thenReturn(List.of(importAudit));
        IntegrationEvent importImpact = integrationEvent(delivery.getId(), "IMPORT_ETA_CHANGED", "FAILED");
        when(integrationRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY, delivery.getId())).thenReturn(List.of(importImpact));

        SupplyCommitmentContextResponse result = service.context(codec.encode(key), scope);

        assertThat(result.commitmentKey()).isEqualTo(key.canonical());
        assertThat(result.encodedCommitmentKey()).isEqualTo(codec.encode(key));
        assertThat(result.header().purchaseOrder().id()).isEqualTo(PO_ID);
        assertThat(result.header().contract().id()).isEqualTo(CONTRACT_ID);
        assertThat(result.header().destinationWarehouse().id()).isEqualTo(WAREHOUSE_ID);
        assertThat(result.schedule()).isNotNull();
        assertThat(result.schedule().promisedDate()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(result.schedule().revisions()).isEmpty();

        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.purchaseOrderLineId()).isEqualTo(LINE_ID);
            assertThat(line.planned().value()).isEqualByComparingTo("10");
            assertThat(line.accepted().value()).isEqualByComparingTo("4");
            assertThat(line.open().value()).isEqualByComparingTo("4");
            assertThat(line.openAmount().originalAmount()).isEqualByComparingTo("100");
            assertThat(line.allocationStatus()).isEqualTo("PO_LINE_ONLY");
            assertThat(line.limitations()).extracting("code")
                .contains("HEADER_ONLY_SCHEDULE_ALLOCATION");
        });
        assertThat(result.asns()).extracting("id").containsExactly(asn.getId());
        assertThat(result.imports()).singleElement().satisfies(context -> {
            assertThat(context.id()).isEqualTo(delivery.getId());
            assertThat(context.customsStatus()).isEqualTo("RELEASED");
            assertThat(context.customsClearanceAt()).isEqualTo(customs.getReleasedAt());
        });
        assertThat(result.supportingDocuments()).extracting("documentType")
            .containsExactlyInAnyOrder("CUSTOMS_DECLARATION", "FREIGHT_BOOKING", "INSURANCE_POLICY", "LANDED_COST_COMPONENT");
        assertThat(result.evidence()).extracting("displayName").containsExactly("receipt-photo.jpg");
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.target().moduleKey()).isEqualTo("purchase-orders");
            assertThat(evidence.target().recordId()).isEqualTo(PO_ID);
        });
        assertThat(result.receiving()).singleElement().satisfies(receiving -> {
            assertThat(receiving.id()).isEqualTo(LINE_ID);
            assertThat(receiving.accepted().value()).isEqualByComparingTo("4");
            assertThat(receiving.rejected().value()).isEqualByComparingTo("1");
            assertThat(receiving.damaged().value()).isEqualByComparingTo("1");
            assertThat(receiving.receivedAt()).isNull();
        });
        assertThat(result.accreditation()).isNotNull();
        assertThat(result.accreditation().eligible()).isTrue();
        assertThat(result.accreditation().target().moduleKey()).isEqualTo("suppliers");
        assertThat(result.relatedDocuments()).extracting("documentType")
            .contains("PURCHASE_ORDER", "DELIVERY_SCHEDULE", "ASN", "IMPORT_DELIVERY", "LOGISTICS_EVENT");
        assertThat(result.integrationImpact()).extracting("eventType")
            .contains("PO_RECEIPT_POSTED", "IMPORT_ETA_CHANGED");
        assertThat(result.auditTimeline()).extracting("action")
            .contains("PO_SENT", "CONTROL", "ARRIVE_CUSTOMS");
        assertThat(result.allowedActions()).extracting("key")
            .contains("OPEN_PO", "CONTACT_SUPPLIER", "OPEN_ASN", "OPEN_IMPORT", "OPEN_RECEIVING", "REASSIGN", "ESCALATE")
            .doesNotContain("CLAIM");
        assertThat(result.allowedActions()).filteredOn(action -> "OPEN_RECEIVING".equals(action.key()))
            .singleElement().satisfies(action -> assertThat(action.target().moduleKey()).isEqualTo("goods-receipt"));
        assertThat(result.limitations()).extracting("code")
            .contains(
                "PROMISE_REVISION_HISTORY_UNAVAILABLE",
                "DOCUMENT_CHECKLIST_NOT_MODELED",
                "HEADER_ONLY_SCHEDULE_ALLOCATION",
                "RECEIPT_ALLOCATION_INCOMPLETE");
        assertThat(result.partialErrors()).isEmpty();
        assertThat(result.timeZone()).isEqualTo("Asia/Tashkent");
        assertThat(result.definitionVersion()).isEqualTo("supply-workspace.v1");
    }

    @Test
    void unscheduledDossierExposesGoodsIncomeAccreditationAndSpecialistClaimWithoutInventingSchedule() {
        SupplyCommitmentKey key = new SupplyCommitmentKey(SupplyCommitmentKind.PO, PO_ID);
        when(commitmentRepository.findScoped(key, scope, NOW)).thenReturn(Optional.of(commitment(key, true)));
        when(goodsIncomeService.context(PO_ID)).thenReturn(goodsIncome(false, false));
        authenticate("buyer.one", "ROLE_PROCUREMENT_SPECIALIST");

        SupplyCommitmentContextResponse result = service.context(codec.encode(key), scope);

        assertThat(result.schedule()).isNull();
        assertThat(result.header().code()).isEqualTo("PO-100");
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.allocationStatus()).isEqualTo("PURCHASE_ORDER_LINE");
            assertThat(line.limitations()).extracting("code")
                .doesNotContain("HEADER_ONLY_SCHEDULE_ALLOCATION");
        });
        assertThat(result.accreditation()).isNotNull();
        assertThat(result.accreditation().eligible()).isFalse();
        assertThat(result.accreditation().blockedReason()).contains("accreditation");
        assertThat(result.blockers()).extracting("code")
            .contains("SUPPLIER_ACCREDITATION_BLOCKED", "UNASSIGNED");
        assertThat(result.allowedActions()).extracting("key")
            .contains("CLAIM", "OPEN_PO")
            .doesNotContain("ASSIGN", "REASSIGN", "ESCALATE", "OPEN_RECEIVING");
        assertThat(result.limitations()).extracting("code")
            .contains("PROMISE_REVISION_HISTORY_UNAVAILABLE", "DOCUMENT_CHECKLIST_NOT_MODELED")
            .doesNotContain("HEADER_ONLY_SCHEDULE_ALLOCATION");
    }

    @Test
    void crossScopeCommitmentIsIndistinguishableFromMissingAndDoesNotLoadDownstreamData() {
        SupplyCommitmentKey key = new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, SCHEDULE_ID);
        SupplyAccessScope otherScope = new SupplyAccessScope(TENANT, Set.of(OTHER_COMPANY), Set.of(), false, false);
        when(commitmentRepository.findScoped(key, otherScope, NOW)).thenReturn(Optional.of(commitment(key, false)));

        assertThatThrownBy(() -> service.context(codec.encode(key), otherScope))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("authenticated scope");

        verify(goodsIncomeService, never()).context(PO_ID);
        verify(scheduleRepository, never()).findByIdAndIsDeletedFalse(SCHEDULE_ID);
    }

    @Test
    void poKeyBecomesNotFoundWhenCanonicalQueryReplacesItWithAnActiveSchedule() {
        SupplyCommitmentKey stalePoKey = new SupplyCommitmentKey(SupplyCommitmentKind.PO, PO_ID);
        when(commitmentRepository.findScoped(stalePoKey, scope, NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.context(codec.encode(stalePoKey), scope))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("authenticated scope");

        verify(commitmentRepository).findScoped(stalePoKey, scope, NOW);
        verify(goodsIncomeService, never()).context(PO_ID);
    }

    @Test
    void malformedCommitmentKeyIsRejectedBeforeAnyRepositoryLookup() {
        assertThatThrownBy(() -> service.context("not-base64", scope))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUPPLY_COMMITMENT_KEY_INVALID");
        verify(commitmentRepository, never()).findScoped(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dossierRejectsSameCompanyImportRowsThatAreNotLinkedToItsPoOrScopedAsn() {
        SupplyCommitmentKey key = new SupplyCommitmentKey(SupplyCommitmentKind.PO, PO_ID);
        when(commitmentRepository.findScoped(key, scope, NOW)).thenReturn(Optional.of(commitment(key, true)));
        ImportDelivery unrelated = importDelivery(UUID.randomUUID());
        unrelated.setPurchaseOrderId(UUID.randomUUID());
        when(importRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByEtaAsc(
            TENANT, COMPANY, PO_ID)).thenReturn(List.of(unrelated));

        SupplyCommitmentContextResponse result = service.context(codec.encode(key), scope);

        assertThat(result.imports()).isEmpty();
        assertThat(result.relatedDocuments()).extracting("documentType")
            .doesNotContain("IMPORT_DELIVERY");
    }

    @Test
    void warehouseScopeDoesNotExposeOtherWarehouseLinesAccreditationGroupsOrMovements() {
        SupplyCommitmentKey key = new SupplyCommitmentKey(SupplyCommitmentKind.PO, PO_ID);
        SupplyAccessScope warehouseScope = new SupplyAccessScope(
            TENANT, Set.of(COMPANY), Set.of(WAREHOUSE_ID), false, true);
        when(commitmentRepository.findScoped(key, warehouseScope, NOW))
            .thenReturn(Optional.of(commitment(key, true)));
        GoodsIncomeContext base = goodsIncome(true, true);
        var otherGroup = new com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EntityRef(
            UUID.fromString("d14bc89d-bec5-47fe-bca6-5a0bdf9b9230"), "GRP-OTHER", "Other group", "ACTIVE");
        var otherMaterial = new com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EntityRef(
            UUID.fromString("ae839c33-6e31-4f5f-906f-893b847740bc"), "MAT-OTHER", "Other material", "ACTIVE");
        GoodsIncomeLine otherLine = new GoodsIncomeLine(
            UUID.fromString("117fe9af-f298-4610-86db-bffbeeb7a0ba"), "20", "Other material", otherMaterial,
            otherGroup, "EA", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ONE, null, null, OTHER_WAREHOUSE_ID, null, null, "OPEN", true, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        AccreditationContext otherAccreditation = new AccreditationContext(
            true, "APPROVED", null, base.accreditation().applicationId(), base.accreditation().applicationCode(),
            base.accreditation().approvedScope(),
            List.of(base.accreditation().materialGroups().getFirst(), otherGroup),
            base.accreditation().validTo(), base.accreditation().conditions(), null);
        StockMovementContext otherMovement = new StockMovementContext(
            UUID.fromString("6b9769f4-8cbb-4bcb-b6e5-f6ef60cd1ee5"), "SM-OTHER", "GOODS_RECEIPT", "POSTED",
            otherMaterial.id(), OTHER_WAREHOUSE_ID, null, null, BigDecimal.ONE, null,
            LocalDate.of(2026, 7, 16), NOW_LOCAL);
        GoodsIncomeContext mixed = new GoodsIncomeContext(
            base.header(), List.of(base.lines().getFirst(), otherLine), base.advanceShippingNotices(),
            otherAccreditation, base.blockers(), base.allowedActions(), base.evidence(),
            base.receipts(), List.of(base.stockMovements().getFirst(), otherMovement), base.auditTimeline(),
            base.integrationImpact(), base.generatedAt(), base.dataScope());
        when(goodsIncomeService.context(PO_ID)).thenReturn(mixed);

        SupplyCommitmentContextResponse result = service.context(codec.encode(key), warehouseScope);

        assertThat(result.lines()).extracting("id").containsExactly(LINE_ID);
        assertThat(result.accreditation().materialGroups()).extracting("id")
            .containsExactly(MATERIAL_GROUP_ID);
        assertThat(result.relatedDocuments())
            .filteredOn(document -> "STOCK_MOVEMENT".equals(document.documentType()))
            .extracting("id")
            .containsExactly(UUID.fromString("3c5dbaaf-19b0-42e5-9648-ac9b63442851"));
    }

    private SupplyCommitmentRecord commitment(SupplyCommitmentKey key, boolean unassigned) {
        return new SupplyCommitmentRecord(
            key, COMPANY, PO_ID, "PO-100", "Compressor package", SUPPLIER_ID, "SUP-10", "Industrial Supplier",
            CONTRACT_ID, LocalDate.of(2026, 7, 19), key.kind() == SupplyCommitmentKind.SCHEDULE ? "IN_TRANSIT" : "SENT",
            "USD", new BigDecimal("100"), new BigDecimal("4"), "EA", SupplyDataQuality.AUTHORITATIVE,
            SupplySeverity.WARNING, false, false, false, false, false, unassigned,
            unassigned ? null : UUID.fromString("111e09bc-6556-4227-a64b-d87b922ce5c9"),
            unassigned ? null : "buyer.assigned", unassigned ? null : "PROCUREMENT_SPECIALIST",
            LocalDate.of(2026, 7, 18), unassigned ? null : 3L, SupplyDueDateSource.POLICY,
            SupplySeverity.WARNING, SupplyEscalationState.NONE, 0, "watch delivery", new BigDecimal("10"),
            new BigDecimal("4"), true
        );
    }

    private GoodsIncomeContext goodsIncome(boolean eligible, boolean receiveEnabled) {
        var supplier = new com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EntityRef(
            SUPPLIER_ID, "SUP-10", "Industrial Supplier", "ACTIVE");
        var material = new com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EntityRef(
            MATERIAL_ID, "MAT-10", "Compressor", "ACTIVE");
        var group = new com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EntityRef(
            MATERIAL_GROUP_ID, "GRP-1", "Rotating equipment", "ACTIVE");
        GoodsIncomeHeader header = new GoodsIncomeHeader(
            PO_ID, "PO-100", "Compressor package", "SENT", 8L, supplier,
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 19), "GOODS", new BigDecimal("250"), "USD");
        GoodsIncomeLine line = new GoodsIncomeLine(
            LINE_ID, "10", "Compressor", material, group, "EA", new BigDecimal("10"), new BigDecimal("4"),
            BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("6"), new BigDecimal("4"), null, "LOT-1",
            WAREHOUSE_ID, null, "ACCEPTED", "OPEN", eligible,
            eligible ? null : "Supplier accreditation is required for goods receipt",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        AccreditationContext accreditation = new AccreditationContext(
            eligible, eligible ? "APPROVED" : "MISSING",
            eligible ? null : "Supplier accreditation is required for goods receipt",
            eligible ? UUID.fromString("35068ae7-0ce5-470b-9748-9cb840c2375d") : null,
            eligible ? "ACC-10" : null, eligible ? "MATERIAL_GROUP" : null, List.of(group),
            eligible ? LocalDate.of(2027, 1, 1) : null, null, null);
        List<WorkspaceBlocker> blockers = eligible ? List.of() : List.of(new WorkspaceBlocker(
            "SUPPLIER_ACCREDITATION_BLOCKED", "ERROR", "Supplier accreditation is required",
            "SUPPLIER_MANAGER", "OPEN_SUPPLIER_GOVERNANCE"));
        WorkspaceAction receive = new WorkspaceAction(
            "RECEIVE", "goodsIncome.action.receive", receiveEnabled, receiveEnabled ? null : "Supplier accreditation is required",
            List.of("WAREHOUSE_OPERATOR"), List.of("lines"), List.of(), "Post receipt", true, true);
        EvidenceItem evidence = new EvidenceItem(
            UUID.fromString("1e4dcc2c-7850-4868-9fe8-d731f2fd23aa"), "receipt-photo.jpg", "RECEIPT_EVIDENCE",
            "image/jpeg", NOW_LOCAL.minusHours(1), true, true);
        StockMovementContext movement = new StockMovementContext(
            UUID.fromString("3c5dbaaf-19b0-42e5-9648-ac9b63442851"), "SM-10", "GOODS_RECEIPT", "POSTED",
            MATERIAL_ID, WAREHOUSE_ID, null, null, new BigDecimal("4"), "LOT-1", LocalDate.of(2026, 7, 16), NOW_LOCAL);
        AuditTimelineItem audit = new AuditTimelineItem(
            UUID.fromString("763d7340-537f-46d0-bc4f-ebf37ae388c4"), "PO_SENT", "buyer", "sent to supplier", NOW_LOCAL.minusDays(1));
        IntegrationImpactItem impact = new IntegrationImpactItem(
            UUID.fromString("9f4ec15f-025f-47dc-b76a-6d38f1507390"), "PO_RECEIPT_POSTED", "PO_RECEIPT_POSTED",
            "PUBLISHED", 0, null, NOW_LOCAL, null, null, true, null);
        return new GoodsIncomeContext(
            header, List.of(line), List.<AdvanceShippingNoticeContext>of(), accreditation, blockers, List.of(receive),
            List.of(evidence), List.of(), List.of(movement), List.of(audit), List.of(impact), NOW_LOCAL,
            "TENANT_COMPANY");
    }

    private PurchaseOrderItem purchaseOrderItem() {
        PurchaseOrderItem item = entity(new PurchaseOrderItem(), LINE_ID, "10", "Compressor", "OPEN");
        item.setPurchaseOrderId(PO_ID);
        item.setMaterialId(MATERIAL_ID);
        item.setUnit("EA");
        item.setQuantity(new BigDecimal("10"));
        item.setReceivedQuantity(new BigDecimal("4"));
        item.setRejectedQuantity(BigDecimal.ONE);
        item.setDamagedQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("25"));
        return item;
    }

    private DeliverySchedule schedule() {
        DeliverySchedule schedule = entity(new DeliverySchedule(), SCHEDULE_ID, "DS-10", "July delivery", "IN_TRANSIT");
        schedule.setPurchaseOrderId(PO_ID);
        schedule.setContractId(CONTRACT_ID);
        schedule.setScheduleDate(LocalDate.of(2026, 7, 19));
        schedule.setPlannedQuantity(new BigDecimal("10"));
        schedule.setReceivedQuantity(new BigDecimal("4"));
        return schedule;
    }

    private AdvanceShippingNotice asn() {
        AdvanceShippingNotice asn = entity(new AdvanceShippingNotice(), UUID.fromString("5a58d9c4-650b-483c-9284-9d5067ca6e73"), "ASN-10", "Shipment notice", "CONFIRMED");
        asn.setAsnNumber("ASN-10");
        asn.setPurchaseOrderId(PO_ID);
        asn.setSupplierId(SUPPLIER_ID);
        asn.setSentAt(NOW_LOCAL.minusDays(3));
        asn.setConfirmedAt(NOW_LOCAL.minusDays(2));
        asn.setExpectedArrivalDate(LocalDate.of(2026, 7, 19));
        asn.setCarrierName("Rail Carrier");
        asn.setTrackingNumber("TRK-10");
        return asn;
    }

    private ImportDelivery importDelivery(UUID asnId) {
        ImportDelivery delivery = entity(new ImportDelivery(), UUID.fromString("cf395201-eea5-4a8c-8bea-573a01a42e52"), "IMP-10", "Import shipment", "AT_CUSTOMS");
        delivery.setShipmentNumber("IMP-10");
        delivery.setPurchaseOrderId(PO_ID);
        delivery.setSupplierId(SUPPLIER_ID);
        delivery.setAsnId(asnId);
        delivery.setTransportMode("RAIL");
        delivery.setEta(LocalDate.of(2026, 7, 19));
        delivery.setDestinationWarehouseId(WAREHOUSE_ID);
        delivery.setCurrentMilestone("AT_CUSTOMS");
        delivery.setRiskFlag(true);
        delivery.setRiskReason("Customs inspection");
        return delivery;
    }

    private CustomsDeclaration customs(UUID deliveryId) {
        CustomsDeclaration customs = entity(new CustomsDeclaration(), UUID.fromString("2da2d95c-b2f5-4145-a496-fbb8da9ec374"), "CD-10", "Customs declaration", "RELEASED");
        customs.setImportDeliveryId(deliveryId);
        customs.setPurchaseOrderId(PO_ID);
        customs.setSupplierId(SUPPLIER_ID);
        customs.setDeclarationNumber("DEC-10");
        customs.setDeclarationDate(LocalDate.of(2026, 7, 15));
        customs.setReleasedAt(NOW_LOCAL.minusHours(1));
        customs.setTotalCustomsAmount(new BigDecimal("500"));
        customs.setCurrencyCode("USD");
        return customs;
    }

    private ImportFreightBooking freight(UUID deliveryId) {
        ImportFreightBooking freight = entity(new ImportFreightBooking(), UUID.fromString("150a3478-1936-4450-80ab-b53e79d59715"), "FB-10", "Rail freight", "IN_TRANSIT");
        freight.setImportDeliveryId(deliveryId);
        freight.setBookingNumber("FB-10");
        freight.setTotalFreightAmount(new BigDecimal("700"));
        freight.setCurrencyCode("USD");
        freight.setInvoiceDate(LocalDate.of(2026, 7, 10));
        return freight;
    }

    private ImportInsurancePolicy insurance(UUID deliveryId) {
        ImportInsurancePolicy insurance = entity(new ImportInsurancePolicy(), UUID.fromString("0b077160-feb8-4e8a-9f86-1863f59a2474"), "IP-10", "Cargo policy", "ACTIVE");
        insurance.setImportDeliveryId(deliveryId);
        insurance.setPolicyNumber("IP-10");
        insurance.setPremiumAmount(new BigDecimal("40"));
        insurance.setCurrencyCode("USD");
        insurance.setValidFrom(LocalDate.of(2026, 7, 1));
        return insurance;
    }

    private LandedCostComponent landedCost(UUID deliveryId) {
        LandedCostComponent component = entity(new LandedCostComponent(), UUID.fromString("e1ea36f8-a894-4e92-9aeb-8148bd76ad83"), "LC-10", "Broker fee", "POSTED");
        component.setImportDeliveryId(deliveryId);
        component.setComponentType("BROKER_FEE");
        component.setAmount(new BigDecimal("60"));
        component.setCurrencyCode("USD");
        component.setInvoiceDate(LocalDate.of(2026, 7, 15));
        return component;
    }

    private LandedLogisticsEvent logisticsEvent(UUID deliveryId) {
        LandedLogisticsEvent event = entity(new LandedLogisticsEvent(), UUID.fromString("38f08768-f0c1-4b82-88e8-4206aaf242fd"), "LE-10", "Customs arrival", "RECORDED");
        event.setImportDeliveryId(deliveryId);
        event.setEventType("ARRIVE_CUSTOMS");
        event.setEventDate(NOW_LOCAL.minusHours(2));
        return event;
    }

    private Contract contract() {
        Contract contract = entity(new Contract(), CONTRACT_ID, "CTR-10", "Supply contract", "ACTIVE");
        contract.setContractNumber("C-2026-10");
        contract.setSupplierId(SUPPLIER_ID);
        return contract;
    }

    private AuditLog audit(String entityType, UUID entityId, String action, LocalDateTime occurredAt) {
        AuditLog log = entity(new AuditLog(), UUID.randomUUID(), "AUD", action, "RECORDED");
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setAction(action);
        log.setUsername("operator");
        log.setOccurredAt(occurredAt);
        return log;
    }

    private IntegrationEvent integrationEvent(UUID aggregateId, String eventType, String status) {
        IntegrationEvent event = entity(new IntegrationEvent(), UUID.randomUUID(), "INT", eventType, status);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setRetryCount(2);
        event.setLastError("remote timeout");
        event.setCreatedAt(NOW_LOCAL.minusMinutes(30));
        return event;
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T entity(
        T entity,
        UUID id,
        String code,
        String name,
        String status
    ) {
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setCompanyId(COMPANY);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus(status);
        entity.setVersion(0L);
        entity.setCreatedAt(NOW_LOCAL.minusDays(2));
        return entity;
    }

    private void authenticate(String username, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username,
            "n/a",
            java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()
        ));
    }
}
