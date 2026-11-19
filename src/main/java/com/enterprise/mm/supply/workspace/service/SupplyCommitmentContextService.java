package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.common.BusinessEntity;
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
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.enums.SupplySourceState;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.AccreditationContext;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.AsnContext;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.AuditTimelineItem;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.CommitmentHeader;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.CommitmentLine;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.EvidenceItem;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.ImportContext;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.IntegrationImpact;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.ReceivingContext;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.RelatedDocument;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.ScheduleContext;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.SupportingDocument;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.SupplyCommitmentContextResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.WorkspaceBlocker;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AllowedAction;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AssignmentSummary;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DeepLinkTarget;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.EntityRef;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.QuantityValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SourceFreshness;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.UnavailableReason;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeLine;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplyCommitmentContextService {
    private static final Set<String> MANAGER_AUTHORITIES = Set.of(
        "ROLE_ADMIN", "ROLE_MM_MANAGER", "ROLE_PROCUREMENT_MANAGER");
    private static final String SPECIALIST_AUTHORITY = "ROLE_PROCUREMENT_SPECIALIST";

    private final SupplyCommitmentQueryRepository commitmentRepository;
    private final GoodsIncomeWorkspaceService goodsIncomeService;
    private final DeliveryScheduleRepository scheduleRepository;
    private final PurchaseOrderItemRepository itemRepository;
    private final AdvanceShippingNoticeRepository asnRepository;
    private final ImportDeliveryRepository importRepository;
    private final CustomsDeclarationRepository customsRepository;
    private final ImportFreightBookingRepository freightRepository;
    private final ImportInsurancePolicyRepository insuranceRepository;
    private final LandedCostComponentRepository landedCostRepository;
    private final LandedLogisticsEventRepository logisticsEventRepository;
    private final ContractRepository contractRepository;
    private final AuditLogRepository auditRepository;
    private final IntegrationEventRepository integrationRepository;
    private final SupplyCommitmentKeyCodec keyCodec;
    private final Clock clock;
    private final ZoneId timeZone;

    public SupplyCommitmentContextService(
        SupplyCommitmentQueryRepository commitmentRepository,
        GoodsIncomeWorkspaceService goodsIncomeService,
        DeliveryScheduleRepository scheduleRepository,
        PurchaseOrderItemRepository itemRepository,
        AdvanceShippingNoticeRepository asnRepository,
        ImportDeliveryRepository importRepository,
        CustomsDeclarationRepository customsRepository,
        ImportFreightBookingRepository freightRepository,
        ImportInsurancePolicyRepository insuranceRepository,
        LandedCostComponentRepository landedCostRepository,
        LandedLogisticsEventRepository logisticsEventRepository,
        ContractRepository contractRepository,
        AuditLogRepository auditRepository,
        IntegrationEventRepository integrationRepository,
        SupplyCommitmentKeyCodec keyCodec,
        Clock clock,
        @Value("${app.supply-workspace.time-zone:Asia/Tashkent}") String timeZone
    ) {
        this.commitmentRepository = commitmentRepository;
        this.goodsIncomeService = goodsIncomeService;
        this.scheduleRepository = scheduleRepository;
        this.itemRepository = itemRepository;
        this.asnRepository = asnRepository;
        this.importRepository = importRepository;
        this.customsRepository = customsRepository;
        this.freightRepository = freightRepository;
        this.insuranceRepository = insuranceRepository;
        this.landedCostRepository = landedCostRepository;
        this.logisticsEventRepository = logisticsEventRepository;
        this.contractRepository = contractRepository;
        this.auditRepository = auditRepository;
        this.integrationRepository = integrationRepository;
        this.keyCodec = keyCodec;
        this.clock = clock;
        this.timeZone = ZoneId.of(timeZone == null || timeZone.isBlank() ? "Asia/Tashkent" : timeZone.trim());
    }

    @Transactional(readOnly = true)
    public SupplyCommitmentContextResponse context(
        String encodedCommitmentKey,
        SupplyAccessScope scope
    ) {
        if (scope == null) {
            throw new AccessDeniedException("Authenticated supply scope is required");
        }
        SupplyCommitmentKey key = decode(encodedCommitmentKey);
        Instant generatedAt = clock.instant();
        SupplyCommitmentRecord commitment = commitmentRepository.findScoped(key, scope, generatedAt)
            .filter(record -> scope.allowsCompany(record.companyId()))
            .orElseThrow(() -> notFound(key));

        GoodsIncomeContext goodsIncome = goodsIncomeService.context(commitment.purchaseOrderId());
        if (goodsIncome == null || goodsIncome.header() == null
            || !Objects.equals(goodsIncome.header().id(), commitment.purchaseOrderId())) {
            throw notFound(key);
        }

        DeliverySchedule schedule = loadSchedule(key, commitment, scope);
        List<GoodsIncomeLine> visibleGoodsLines = visibleGoodsLines(goodsIncome.lines(), scope);
        Map<UUID, PurchaseOrderItem> poItems = poItems(commitment, scope);
        List<AdvanceShippingNotice> asns = scopedAsns(commitment, scope);
        List<ImportBundle> importBundles = importBundles(commitment, asns, scope);
        EntityRef contract = contract(commitment, scope);
        List<UnavailableReason> limitations = limitations(key, commitment, importBundles, contract);
        EntityRef destinationWarehouse = destinationWarehouse(importBundles, visibleGoodsLines, limitations);
        AssignmentSummary assignment = assignment(commitment);

        List<CommitmentLine> lines = lines(key, commitment, visibleGoodsLines, poItems);
        List<AsnContext> asnContexts = asns.stream().map(asn -> asnContext(asn, key)).toList();
        List<ImportContext> importContexts = importBundles.stream().map(bundle -> importContext(bundle, key)).toList();
        List<SupportingDocument> supportingDocuments = supportingDocuments(importBundles, key);
        List<EvidenceItem> evidence = evidence(goodsIncome, key);
        List<ReceivingContext> receiving = receiving(goodsIncome, visibleGoodsLines, key);
        AccreditationContext accreditation = accreditation(goodsIncome, visibleGoodsLines, commitment, key);
        List<WorkspaceBlocker> blockers = blockers(goodsIncome, commitment, key);
        List<RelatedDocument> relatedDocuments = relatedDocuments(
            goodsIncome, schedule, asns, importBundles, supportingDocuments, scope, key);
        List<IntegrationImpact> integrationImpact = integrationImpact(
            goodsIncome, schedule, asns, importBundles, commitment, scope, key);
        List<AuditTimelineItem> auditTimeline = auditTimeline(goodsIncome, schedule, asns, importBundles);
        List<AllowedAction> allowedActions = actions(
            commitment, goodsIncome, asns, importBundles, integrationImpact, encodedCommitmentKey);
        String currentMilestone = currentMilestone(importBundles, commitment.status());

        CommitmentHeader header = new CommitmentHeader(
            key.canonical(),
            encodedCommitmentKey,
            schedule == null ? goodsIncome.header().code() : code(schedule),
            schedule == null ? goodsIncome.header().status() : schedule.getStatus(),
            currentMilestone,
            commitment.operationalDate(),
            commitment.severity() == null ? SupplySeverity.WARNING : commitment.severity(),
            commitment.dataQuality() == null ? SupplyDataQuality.PARTIAL : commitment.dataQuality(),
            new EntityRef(
                goodsIncome.header().id(), goodsIncome.header().code(),
                goodsIncome.header().displayName(), goodsIncome.header().status()),
            entityRef(goodsIncome.header().supplier()),
            contract,
            destinationWarehouse,
            assignment
        );

        List<SourceFreshness> freshness = List.of(
            freshness("CANONICAL_SUPPLY_COMMITMENT", generatedAt),
            freshness("GOODS_INCOME_CONTEXT", generatedAt),
            freshness("SUPPLY_LOGISTICS", generatedAt),
            freshness("AUDIT_LOG", generatedAt),
            freshness("INTEGRATION_EVENT", generatedAt)
        );
        return new SupplyCommitmentContextResponse(
            key.canonical(),
            encodedCommitmentKey,
            header,
            lines,
            scheduleContext(schedule),
            asnContexts,
            importContexts,
            supportingDocuments,
            evidence,
            receiving,
            accreditation,
            blockers,
            relatedDocuments,
            integrationImpact,
            auditTimeline,
            allowedActions,
            limitations,
            freshness,
            List.of(),
            generatedAt,
            timeZone.getId(),
            SupplyWorkspaceQueryService.DEFINITION_VERSION
        );
    }

    private SupplyCommitmentKey decode(String encodedCommitmentKey) {
        try {
            return keyCodec.decode(encodedCommitmentKey);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(
                "SUPPLY_COMMITMENT_KEY_INVALID: " + exception.getMessage(),
                "supply.workspace.commitment.keyInvalid");
        }
    }

    private NotFoundException notFound(SupplyCommitmentKey key) {
        return new NotFoundException(
            "Supply commitment was not found in authenticated scope",
            "supply.workspace.commitment.notFound",
            key.canonical());
    }

    private DeliverySchedule loadSchedule(
        SupplyCommitmentKey key,
        SupplyCommitmentRecord commitment,
        SupplyAccessScope scope
    ) {
        if (key.kind() != SupplyCommitmentKind.SCHEDULE) {
            return null;
        }
        return scheduleRepository.findByIdAndIsDeletedFalse(key.id())
            .filter(schedule -> scoped(schedule, scope.tenantId(), commitment.companyId()))
            .filter(schedule -> Objects.equals(schedule.getPurchaseOrderId(), commitment.purchaseOrderId()))
            .orElseThrow(() -> notFound(key));
    }

    private List<GoodsIncomeLine> visibleGoodsLines(List<GoodsIncomeLine> lines, SupplyAccessScope scope) {
        return safe(lines).stream()
            .filter(Objects::nonNull)
            .filter(line -> !scope.warehouseScoped()
                || (line.receiptWarehouseId() != null && scope.allowsWarehouse(line.receiptWarehouseId())))
            .toList();
    }

    private Map<UUID, PurchaseOrderItem> poItems(
        SupplyCommitmentRecord commitment,
        SupplyAccessScope scope
    ) {
        return safe(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            scope.tenantId(),
            commitment.purchaseOrderId())).stream()
            .filter(item -> scoped(item, scope.tenantId(), commitment.companyId()))
            .filter(item -> Objects.equals(item.getPurchaseOrderId(), commitment.purchaseOrderId()))
            .filter(item -> !scope.warehouseScoped()
                || (item.getReceiptWarehouseId() != null && scope.allowsWarehouse(item.getReceiptWarehouseId())))
            .collect(Collectors.toMap(
                BusinessEntity::getId,
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new));
    }

    private List<AdvanceShippingNotice> scopedAsns(
        SupplyCommitmentRecord commitment,
        SupplyAccessScope scope
    ) {
        String tenantId = scope.tenantId();
        return safe(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            tenantId, commitment.companyId(), commitment.purchaseOrderId())).stream()
            .filter(asn -> scoped(asn, tenantId, commitment.companyId()))
            .filter(asn -> Objects.equals(asn.getPurchaseOrderId(), commitment.purchaseOrderId()))
            .sorted(Comparator.comparing(
                AdvanceShippingNotice::getExpectedArrivalDate,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AdvanceShippingNotice::getId))
            .toList();
    }

    private List<ImportBundle> importBundles(
        SupplyCommitmentRecord commitment,
        List<AdvanceShippingNotice> asns,
        SupplyAccessScope scope
    ) {
        String tenantId = scope.tenantId();
        Set<UUID> scopedAsnIds = asns.stream()
            .map(AdvanceShippingNotice::getId)
            .collect(Collectors.toUnmodifiableSet());
        LinkedHashMap<UUID, ImportDelivery> imports = new LinkedHashMap<>();
        safe(importRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByEtaAsc(
            tenantId, commitment.companyId(), commitment.purchaseOrderId())).stream()
            .filter(delivery -> scopedImport(delivery, commitment, scope, scopedAsnIds))
            .forEach(delivery -> imports.putIfAbsent(delivery.getId(), delivery));
        for (AdvanceShippingNotice asn : asns) {
            importRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
                    tenantId, commitment.companyId(), asn.getId())
                .filter(delivery -> scopedImport(delivery, commitment, scope, scopedAsnIds))
                .ifPresent(delivery -> imports.putIfAbsent(delivery.getId(), delivery));
        }
        return imports.values().stream()
            .sorted(Comparator.comparing(ImportDelivery::getEta, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ImportDelivery::getId))
            .map(delivery -> new ImportBundle(
                delivery,
                safe(customsRepository.findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(
                    tenantId, commitment.companyId(), delivery.getId())).stream()
                    .filter(value -> scoped(value, tenantId, commitment.companyId()))
                    .filter(value -> Objects.equals(value.getImportDeliveryId(), delivery.getId()))
                    .sorted(Comparator.comparing(CustomsDeclaration::getDeclarationDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CustomsDeclaration::getId))
                    .toList(),
                safe(freightRepository.findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(
                    tenantId, commitment.companyId(), delivery.getId())).stream()
                    .filter(value -> scoped(value, tenantId, commitment.companyId()))
                    .filter(value -> Objects.equals(value.getImportDeliveryId(), delivery.getId()))
                    .toList(),
                safe(insuranceRepository.findByTenantIdAndCompanyIdAndImportDelivery_IdAndIsDeletedFalse(
                    tenantId, commitment.companyId(), delivery.getId())).stream()
                    .filter(value -> scoped(value, tenantId, commitment.companyId()))
                    .filter(value -> Objects.equals(value.getImportDeliveryId(), delivery.getId()))
                    .toList(),
                safe(landedCostRepository.findByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalse(
                    tenantId, commitment.companyId(), delivery.getId())).stream()
                    .filter(value -> scoped(value, tenantId, commitment.companyId()))
                    .filter(value -> Objects.equals(value.getImportDeliveryId(), delivery.getId()))
                    .toList(),
                safe(logisticsEventRepository.findByTenantIdAndCompanyIdAndImportDeliveryIdAndIsDeletedFalseOrderByEventDateAsc(
                    tenantId, commitment.companyId(), delivery.getId())).stream()
                    .filter(value -> scoped(value, tenantId, commitment.companyId()))
                    .filter(value -> Objects.equals(value.getImportDeliveryId(), delivery.getId()))
                    .toList()))
            .toList();
    }

    private boolean scopedImport(
        ImportDelivery delivery,
        SupplyCommitmentRecord commitment,
        SupplyAccessScope scope,
        Set<UUID> scopedAsnIds
    ) {
        if (!scoped(delivery, scope.tenantId(), commitment.companyId())) return false;
        boolean linkedToPo = Objects.equals(delivery.getPurchaseOrderId(), commitment.purchaseOrderId());
        boolean linkedToScopedAsn = delivery.getAsnId() != null && scopedAsnIds.contains(delivery.getAsnId());
        if (!linkedToPo && !linkedToScopedAsn) return false;
        return !scope.warehouseScoped()
            || (delivery.getDestinationWarehouseId() != null
                && scope.allowsWarehouse(delivery.getDestinationWarehouseId()));
    }

    private EntityRef contract(SupplyCommitmentRecord commitment, SupplyAccessScope scope) {
        if (commitment.contractId() == null) return null;
        return contractRepository.findByIdAndIsDeletedFalse(commitment.contractId())
            .filter(value -> scoped(value, scope.tenantId(), commitment.companyId()))
            .map(value -> new EntityRef(
                value.getId(), code(value, value.getContractNumber()), displayName(value), value.getStatus()))
            .orElse(null);
    }

    private List<CommitmentLine> lines(
        SupplyCommitmentKey key,
        SupplyCommitmentRecord commitment,
        List<GoodsIncomeLine> goodsLines,
        Map<UUID, PurchaseOrderItem> poItems
    ) {
        return goodsLines.stream().map(line -> {
            PurchaseOrderItem poItem = poItems.get(line.id());
            String uom = normalize(line.unit());
            List<UnavailableReason> limitations = new ArrayList<>();
            if (key.kind() == SupplyCommitmentKind.SCHEDULE) {
                limitations.add(reason(
                    "HEADER_ONLY_SCHEDULE_ALLOCATION",
                    "supply.workspace.context.schedule.headerOnlyAllocation"));
            }
            if (uom == null) {
                limitations.add(reason("LINE_UOM_UNAVAILABLE", "supply.workspace.context.line.uomUnavailable"));
            }
            BigDecimal unitPrice = poItem == null ? null : poItem.getUnitPrice();
            if (unitPrice == null) {
                limitations.add(reason(
                    "LINE_UNIT_PRICE_UNAVAILABLE",
                    "supply.workspace.context.line.unitPriceUnavailable"));
            }
            BigDecimal openQuantity = line.openQuantity();
            BigDecimal openAmount = openQuantity == null || unitPrice == null
                ? null
                : openQuantity.multiply(unitPrice);
            SupplyDataQuality dataQuality = uom != null
                && line.orderedQuantity() != null
                && line.acceptedQuantity() != null
                && openQuantity != null
                && unitPrice != null
                ? SupplyDataQuality.AUTHORITATIVE
                : SupplyDataQuality.PARTIAL;
            return new CommitmentLine(
                line.id(),
                line.id(),
                null,
                entityRef(line.material()),
                quantity(line.orderedQuantity(), uom),
                quantity(openQuantity, uom),
                quantity(line.acceptedQuantity(), uom),
                money(unitPrice, commitment.currencyCode()),
                money(openAmount, commitment.currencyCode()),
                key.kind() == SupplyCommitmentKind.SCHEDULE ? "PO_LINE_ONLY" : "PURCHASE_ORDER_LINE",
                dataQuality,
                limitations);
        }).toList();
    }

    private ScheduleContext scheduleContext(DeliverySchedule schedule) {
        if (schedule == null) return null;
        return new ScheduleContext(
            schedule.getId(), code(schedule), schedule.getStatus(), schedule.getScheduleDate(), null, List.of());
    }

    private AsnContext asnContext(AdvanceShippingNotice asn, SupplyCommitmentKey key) {
        return new AsnContext(
            asn.getId(), code(asn, asn.getAsnNumber()), asn.getStatus(), asn.getSentAt(),
            asn.getConfirmedAt(), asn.getExpectedArrivalDate(), asn.getCarrierName(), asn.getTrackingNumber(),
            target("asn", asn.getId(), key));
    }

    private ImportContext importContext(ImportBundle bundle, SupplyCommitmentKey key) {
        CustomsDeclaration customs = currentCustoms(bundle.customs());
        return new ImportContext(
            bundle.delivery().getId(),
            code(bundle.delivery(), bundle.delivery().getShipmentNumber()),
            bundle.delivery().getStatus(),
            bundle.delivery().getTransportMode(),
            bundle.delivery().getEta(),
            bundle.delivery().getActualArrivalDate(),
            customs == null ? null : customs.getStatus(),
            customs == null ? null : customs.getReleasedAt(),
            bundle.delivery().getRiskReason(),
            target("import-deliveries", bundle.delivery().getId(), key));
    }

    private CustomsDeclaration currentCustoms(List<CustomsDeclaration> declarations) {
        return declarations.stream().max(Comparator
            .comparing(CustomsDeclaration::getReleasedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(CustomsDeclaration::getDeclarationDate, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(CustomsDeclaration::getId)).orElse(null);
    }

    private List<SupportingDocument> supportingDocuments(
        List<ImportBundle> bundles,
        SupplyCommitmentKey key
    ) {
        List<SupportingDocument> documents = new ArrayList<>();
        for (ImportBundle bundle : bundles) {
            bundle.customs().forEach(value -> documents.add(new SupportingDocument(
                value.getId(), "CUSTOMS_DECLARATION", code(value, value.getDeclarationNumber()),
                displayName(value), value.getStatus(), value.getDeclarationDate(),
                money(value.getTotalCustomsAmount(), value.getCurrencyCode()),
                target("customs-declarations", value.getId(), key))));
            bundle.freight().forEach(value -> documents.add(new SupportingDocument(
                value.getId(), "FREIGHT_BOOKING", code(value, value.getBookingNumber()),
                displayName(value), value.getStatus(), value.getInvoiceDate(),
                money(value.getTotalFreightAmount(), value.getCurrencyCode()),
                target("import-freight-bookings", value.getId(), key))));
            bundle.insurance().forEach(value -> documents.add(new SupportingDocument(
                value.getId(), "INSURANCE_POLICY", code(value, value.getPolicyNumber()),
                displayName(value), value.getStatus(), value.getValidFrom(),
                money(value.getPremiumAmount(), value.getCurrencyCode()),
                target("import-insurance-policies", value.getId(), key))));
            bundle.landedCosts().forEach(value -> documents.add(new SupportingDocument(
                value.getId(), "LANDED_COST_COMPONENT", code(value), displayName(value), value.getStatus(),
                value.getInvoiceDate(), money(value.getAmount(), value.getCurrencyCode()),
                target("landed-cost-components", value.getId(), key))));
        }
        return documents.stream()
            .sorted(Comparator.comparing(SupportingDocument::documentDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SupportingDocument::documentType)
                .thenComparing(SupportingDocument::id))
            .toList();
    }

    private List<EvidenceItem> evidence(GoodsIncomeContext goodsIncome, SupplyCommitmentKey key) {
        return safe(goodsIncome.evidence()).stream().map(value -> new EvidenceItem(
            value.id(), value.attachmentType(), value.displayName(), "PURCHASE_ORDER",
            value.uploadedAt(), null, null,
            new DeepLinkTarget(
                "purchase-orders",
                goodsIncome.header().id(),
                key.canonical(),
                Map.of("tab", "evidence")))).toList();
    }

    private List<ReceivingContext> receiving(
        GoodsIncomeContext goodsIncome,
        List<GoodsIncomeLine> lines,
        SupplyCommitmentKey key
    ) {
        return lines.stream().map(line -> {
            String uom = normalize(line.unit());
            return new ReceivingContext(
                line.id(), line.code(), line.lineStatus(),
                line.receiptWarehouseId() == null
                    ? null
                    : new EntityRef(line.receiptWarehouseId(), null, null, null),
                goodsIncome.header().expectedDeliveryDate(),
                null,
                quantity(line.acceptedQuantity(), uom),
                quantity(line.rejectedQuantity(), uom),
                quantity(line.damagedQuantity(), uom),
                target("goods-receipt", goodsIncome.header().id(), key));
        }).toList();
    }

    private AccreditationContext accreditation(
        GoodsIncomeContext goodsIncome,
        List<GoodsIncomeLine> visibleGoodsLines,
        SupplyCommitmentRecord commitment,
        SupplyCommitmentKey key
    ) {
        var value = goodsIncome.accreditation();
        if (value == null) return null;
        EntityRef application = value.applicationId() == null
            ? null
            : new EntityRef(value.applicationId(), value.applicationCode(), value.applicationCode(), value.status());
        Set<UUID> visibleMaterialGroupIds = visibleGoodsLines.stream()
            .map(GoodsIncomeLine::materialGroup)
            .filter(Objects::nonNull)
            .map(com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EntityRef::id)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
        return new AccreditationContext(
            value.eligible(), value.status(), value.blockedReason(), application, value.approvedScope(),
            safe(value.materialGroups()).stream()
                .filter(group -> group != null && visibleMaterialGroupIds.contains(group.id()))
                .map(this::entityRef)
                .toList(),
            value.validTo(), value.conditions(),
            target("suppliers", commitment.supplierId(), key));
    }

    private List<WorkspaceBlocker> blockers(
        GoodsIncomeContext goodsIncome,
        SupplyCommitmentRecord commitment,
        SupplyCommitmentKey key
    ) {
        LinkedHashMap<String, WorkspaceBlocker> blockers = new LinkedHashMap<>();
        safe(goodsIncome.blockers()).forEach(blocker -> blockers.putIfAbsent(blocker.code(), new WorkspaceBlocker(
            blocker.code(), severity(blocker.severity()), blockerMessageKey(blocker.code()), blocker.message(),
            blocker.ownerRole(), blocker.resolutionAction(), blockerTarget(blocker.resolutionAction(), commitment, key))));
        addRiskBlocker(blockers, commitment.isIntegrationFailed(), "INTEGRATION_FAILED", SupplySeverity.BLOCKING, key);
        addRiskBlocker(blockers, commitment.isCustomsDocumentBlocked(), "CUSTOMS_DOCUMENT_BLOCKED", SupplySeverity.BLOCKING, key);
        addRiskBlocker(blockers, commitment.isActiveImportEtaRisk(), "ACTIVE_IMPORT_ETA_RISK", SupplySeverity.BLOCKING, key);
        addRiskBlocker(blockers, commitment.isOverdueCommitment(), "OVERDUE_COMMITMENT", SupplySeverity.BLOCKING, key);
        addRiskBlocker(blockers, commitment.isArriving7dWithoutConfirmedAsn(), "ASN_CONFIRMATION_MISSING", SupplySeverity.WARNING, key);
        addRiskBlocker(blockers, commitment.isUnassigned(), "UNASSIGNED", SupplySeverity.WARNING, key);
        return List.copyOf(blockers.values());
    }

    private void addRiskBlocker(
        Map<String, WorkspaceBlocker> blockers,
        boolean applies,
        String code,
        SupplySeverity severity,
        SupplyCommitmentKey key
    ) {
        if (!applies) return;
        blockers.putIfAbsent(code, new WorkspaceBlocker(
            code, severity, blockerMessageKey(code), null, null, null,
            target("supply-control", key.id(), key)));
    }

    private List<RelatedDocument> relatedDocuments(
        GoodsIncomeContext goodsIncome,
        DeliverySchedule schedule,
        List<AdvanceShippingNotice> asns,
        List<ImportBundle> imports,
        List<SupportingDocument> documents,
        SupplyAccessScope scope,
        SupplyCommitmentKey key
    ) {
        List<RelatedDocument> result = new ArrayList<>();
        result.add(new RelatedDocument(
            "PURCHASE_ORDER", goodsIncome.header().id(), goodsIncome.header().code(),
            goodsIncome.header().displayName(), goodsIncome.header().status(),
            target("purchase-orders", goodsIncome.header().id(), key)));
        if (schedule != null) {
            result.add(related("DELIVERY_SCHEDULE", schedule, "delivery-schedules", key));
        }
        asns.forEach(value -> result.add(new RelatedDocument(
            "ASN", value.getId(), code(value, value.getAsnNumber()), displayName(value), value.getStatus(),
            target("asn", value.getId(), key))));
        imports.forEach(bundle -> {
            result.add(new RelatedDocument(
                "IMPORT_DELIVERY", bundle.delivery().getId(),
                code(bundle.delivery(), bundle.delivery().getShipmentNumber()), displayName(bundle.delivery()),
                bundle.delivery().getStatus(), target("import-deliveries", bundle.delivery().getId(), key)));
            bundle.logisticsEvents().forEach(event -> result.add(new RelatedDocument(
                "LOGISTICS_EVENT", event.getId(), code(event), displayName(event), event.getStatus(),
                target("landed-logistics-events", event.getId(), key))));
        });
        documents.forEach(document -> result.add(new RelatedDocument(
            document.documentType(), document.id(), document.code(), document.displayName(), document.status(),
            document.target())));
        safe(goodsIncome.stockMovements()).stream()
            .filter(movement -> !scope.warehouseScoped()
                || (movement.warehouseId() != null && scope.allowsWarehouse(movement.warehouseId())))
            .forEach(movement -> result.add(new RelatedDocument(
                "STOCK_MOVEMENT", movement.id(), movement.code(), movement.code(), movement.status(),
                target("stock-movements", movement.id(), key))));
        return result;
    }

    private List<IntegrationImpact> integrationImpact(
        GoodsIncomeContext goodsIncome,
        DeliverySchedule schedule,
        List<AdvanceShippingNotice> asns,
        List<ImportBundle> imports,
        SupplyCommitmentRecord commitment,
        SupplyAccessScope scope,
        SupplyCommitmentKey key
    ) {
        LinkedHashMap<UUID, IntegrationImpact> result = new LinkedHashMap<>();
        safe(goodsIncome.integrationImpact()).forEach(value -> result.putIfAbsent(value.id(), new IntegrationImpact(
            value.id(), value.eventType(), value.status(), value.retryCount(), value.lastError(), value.createdAt(),
            target("integration-events", value.id(), key))));
        LinkedHashSet<UUID> relatedIds = new LinkedHashSet<>();
        if (schedule != null) relatedIds.add(schedule.getId());
        asns.forEach(value -> relatedIds.add(value.getId()));
        imports.forEach(bundle -> {
            relatedIds.add(bundle.delivery().getId());
            bundle.customs().forEach(value -> relatedIds.add(value.getId()));
            bundle.freight().forEach(value -> relatedIds.add(value.getId()));
            bundle.insurance().forEach(value -> relatedIds.add(value.getId()));
            bundle.landedCosts().forEach(value -> relatedIds.add(value.getId()));
            bundle.logisticsEvents().forEach(value -> relatedIds.add(value.getId()));
        });
        String tenantId = scope.tenantId();
        UUID companyId = commitment.companyId();
        for (UUID id : relatedIds) {
            safe(integrationRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId, companyId, id)).stream()
                .filter(event -> event.getId() != null)
                .map(event -> integration(event, key))
                .forEach(value -> result.putIfAbsent(value.id(), value));
        }
        return result.values().stream()
            .sorted(Comparator.comparing(IntegrationImpact::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(IntegrationImpact::id))
            .toList();
    }

    private IntegrationImpact integration(IntegrationEvent event, SupplyCommitmentKey key) {
        return new IntegrationImpact(
            event.getId(), event.getEventType(), event.getStatus(), event.getRetryCount(), event.getLastError(),
            event.getCreatedAt(), target("integration-events", event.getId(), key));
    }

    private List<AuditTimelineItem> auditTimeline(
        GoodsIncomeContext goodsIncome,
        DeliverySchedule schedule,
        List<AdvanceShippingNotice> asns,
        List<ImportBundle> imports
    ) {
        LinkedHashMap<UUID, AuditTimelineItem> result = new LinkedHashMap<>();
        safe(goodsIncome.auditTimeline()).forEach(value -> result.putIfAbsent(value.id(), new AuditTimelineItem(
            value.id(), value.action(), value.actor(), value.details(), value.occurredAt())));
        if (schedule != null) addAudit(result, "DeliverySchedule", schedule.getId(), schedule.getTenantId(), schedule.getCompanyId());
        asns.forEach(value -> addAudit(result, "AdvanceShippingNotice", value.getId(), value.getTenantId(), value.getCompanyId()));
        imports.forEach(bundle -> {
            addAudit(result, "ImportDelivery", bundle.delivery().getId(), bundle.delivery().getTenantId(), bundle.delivery().getCompanyId());
            bundle.customs().forEach(value -> addAudit(result, "CustomsDeclaration", value.getId(), value.getTenantId(), value.getCompanyId()));
            bundle.freight().forEach(value -> addAudit(result, "ImportFreightBooking", value.getId(), value.getTenantId(), value.getCompanyId()));
            bundle.insurance().forEach(value -> addAudit(result, "ImportInsurancePolicy", value.getId(), value.getTenantId(), value.getCompanyId()));
            bundle.landedCosts().forEach(value -> addAudit(result, "LandedCostComponent", value.getId(), value.getTenantId(), value.getCompanyId()));
            bundle.logisticsEvents().forEach(value -> addAudit(result, "LandedLogisticsEvent", value.getId(), value.getTenantId(), value.getCompanyId()));
        });
        return result.values().stream()
            .sorted(Comparator.comparing(AuditTimelineItem::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AuditTimelineItem::id))
            .toList();
    }

    private void addAudit(
        Map<UUID, AuditTimelineItem> result,
        String entityType,
        UUID entityId,
        String tenantId,
        UUID companyId
    ) {
        safe(auditRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            tenantId, companyId, entityType, entityId)).stream()
            .map(this::audit)
            .forEach(value -> result.putIfAbsent(value.id(), value));
    }

    private AuditTimelineItem audit(AuditLog log) {
        return new AuditTimelineItem(
            log.getId(), log.getAction(), log.getFullName(), log.getDetails(), log.getOccurredAt());
    }

    private List<AllowedAction> actions(
        SupplyCommitmentRecord commitment,
        GoodsIncomeContext goodsIncome,
        List<AdvanceShippingNotice> asns,
        List<ImportBundle> imports,
        List<IntegrationImpact> integrationImpact,
        String encodedKey
    ) {
        SupplyCommitmentKey key = commitment.key();
        List<AllowedAction> actions = new ArrayList<>();
        actions.add(action("OPEN_COMMITMENT", false, commitment.assignmentVersion(),
            target("supply-control", key.id(), key)));
        actions.add(action("OPEN_PO", false, commitment.assignmentVersion(),
            target("purchase-orders", commitment.purchaseOrderId(), key)));
        actions.add(action("CONTACT_SUPPLIER", false, commitment.assignmentVersion(),
            target("suppliers", commitment.supplierId(), key)));
        if (!asns.isEmpty()) {
            actions.add(action("OPEN_ASN", false, commitment.assignmentVersion(),
                target("asn", asns.get(0).getId(), key)));
        }
        if (!imports.isEmpty()) {
            actions.add(action("OPEN_IMPORT", false, commitment.assignmentVersion(),
                target("import-deliveries", imports.get(0).delivery().getId(), key)));
        }
        if (safe(goodsIncome.allowedActions()).stream()
            .anyMatch(value -> "RECEIVE".equals(value.code()) && value.enabled())) {
            actions.add(action("OPEN_RECEIVING", false, commitment.assignmentVersion(),
                target("goods-receipt", commitment.purchaseOrderId(), key)));
        }
        if (!integrationImpact.isEmpty()) {
            actions.add(action("OPEN_INTEGRATION", false, commitment.assignmentVersion(),
                target("integration-events", integrationImpact.get(0).id(), key)));
        }

        Set<String> authorities = authorities();
        boolean manager = authorities.stream().anyMatch(MANAGER_AUTHORITIES::contains);
        boolean specialist = authorities.contains(SPECIALIST_AUTHORITY);
        if (commitment.isUnassigned()) {
            if (specialist) {
                actions.add(action("CLAIM", true, commitment.assignmentVersion(),
                    mutationTarget(encodedKey, key)));
            }
            if (manager) {
                actions.add(action("ASSIGN", true, commitment.assignmentVersion(),
                    mutationTarget(encodedKey, key)));
            }
        } else if (manager) {
            actions.add(action("REASSIGN", true, commitment.assignmentVersion(),
                mutationTarget(encodedKey, key)));
            actions.add(action("ESCALATE", true, commitment.assignmentVersion(),
                mutationTarget(encodedKey, key)));
        }
        return actions;
    }

    private AllowedAction action(String key, boolean reason, Long version, DeepLinkTarget target) {
        return new AllowedAction(
            key,
            "supply.workspace.context.action." + key.toLowerCase(Locale.ROOT),
            "ESCALATE".equals(key) ? "danger" : "default",
            reason,
            false,
            version,
            target);
    }

    private DeepLinkTarget mutationTarget(String encodedKey, SupplyCommitmentKey key) {
        return new DeepLinkTarget(
            "supply-control", key.id(), key.canonical(), Map.of("commitment", encodedKey));
    }

    private Set<String> authorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return Set.of();
        return authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(Collectors.toUnmodifiableSet());
    }

    private List<UnavailableReason> limitations(
        SupplyCommitmentKey key,
        SupplyCommitmentRecord commitment,
        List<ImportBundle> imports,
        EntityRef contract
    ) {
        List<UnavailableReason> result = new ArrayList<>();
        result.add(reason(
            "PROMISE_REVISION_HISTORY_UNAVAILABLE",
            "supply.workspace.context.promiseRevisionHistoryUnavailable"));
        result.add(reason(
            "DOCUMENT_CHECKLIST_NOT_MODELED",
            "supply.workspace.context.documentChecklistNotModeled"));
        result.add(reason(
            "RECEIPT_ALLOCATION_INCOMPLETE",
            "supply.workspace.context.receiptAllocationIncomplete"));
        if (key.kind() == SupplyCommitmentKind.SCHEDULE) {
            result.add(reason(
                "HEADER_ONLY_SCHEDULE_ALLOCATION",
                "supply.workspace.context.schedule.headerOnlyAllocation"));
        }
        if (commitment.contractId() != null && contract == null) {
            result.add(reason(
                "CONTRACT_DETAILS_UNAVAILABLE",
                "supply.workspace.context.contractDetailsUnavailable"));
        }
        if (imports.stream().anyMatch(bundle -> bundle.customs().isEmpty())) {
            result.add(reason(
                "CUSTOMS_DECLARATION_NOT_LINKED",
                "supply.workspace.context.customsDeclarationNotLinked"));
        }
        return result;
    }

    private EntityRef destinationWarehouse(
        List<ImportBundle> imports,
        List<GoodsIncomeLine> goodsLines,
        List<UnavailableReason> limitations
    ) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        imports.stream().map(bundle -> bundle.delivery().getDestinationWarehouseId())
            .filter(Objects::nonNull).forEach(ids::add);
        goodsLines.stream().map(GoodsIncomeLine::receiptWarehouseId)
            .filter(Objects::nonNull).forEach(ids::add);
        if (ids.size() != 1) {
            limitations.add(reason(
                ids.isEmpty() ? "DESTINATION_WAREHOUSE_UNAVAILABLE" : "DESTINATION_WAREHOUSE_NOT_SINGULAR",
                ids.isEmpty()
                    ? "supply.workspace.context.destinationWarehouseUnavailable"
                    : "supply.workspace.context.destinationWarehouseNotSingular"));
            return null;
        }
        UUID id = ids.iterator().next();
        return imports.stream()
            .map(ImportBundle::delivery)
            .filter(value -> Objects.equals(value.getDestinationWarehouseId(), id))
            .map(ImportDelivery::getDestinationWarehouse)
            .filter(Objects::nonNull)
            .map(value -> new EntityRef(value.getId(), value.getCode(), displayName(value), value.getStatus()))
            .findFirst()
            .orElse(new EntityRef(id, null, null, null));
    }

    private AssignmentSummary assignment(SupplyCommitmentRecord record) {
        EntityRef owner = record.isUnassigned()
            ? null
            : new EntityRef(record.ownerUserId(), record.ownerUsername(), record.ownerUsername(), null);
        return new AssignmentSummary(
            owner,
            record.ownerRole(),
            record.assignmentDueDate(),
            record.assignmentDueDateSource(),
            record.assignmentSeverity() == null
                ? record.severity() == null ? SupplySeverity.WARNING : record.severity()
                : record.assignmentSeverity(),
            record.assignmentEscalationState(),
            record.assignmentEscalationLevel(),
            record.assignmentReason(),
            record.assignmentVersion(),
            SupplyDataQuality.AUTHORITATIVE,
            List.of());
    }

    private String currentMilestone(List<ImportBundle> bundles, String fallback) {
        return bundles.stream()
            .map(ImportBundle::delivery)
            .filter(value -> normalize(value.getCurrentMilestone()) != null)
            .max(Comparator.comparing(
                ImportDelivery::getLastEventAt,
                Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ImportDelivery::getId))
            .map(ImportDelivery::getCurrentMilestone)
            .orElse(fallback);
    }

    private SourceFreshness freshness(String source, Instant generatedAt) {
        return new SourceFreshness(
            source, generatedAt, generatedAt, SupplySourceState.FRESH,
            "supply.workspace.context.source.available");
    }

    private DeepLinkTarget blockerTarget(
        String resolutionAction,
        SupplyCommitmentRecord commitment,
        SupplyCommitmentKey key
    ) {
        return switch (resolutionAction == null ? "" : resolutionAction) {
            case "OPEN_SUPPLIER_GOVERNANCE" -> target("suppliers", commitment.supplierId(), key);
            case "OPEN_PURCHASE_ORDER" -> target("purchase-orders", commitment.purchaseOrderId(), key);
            case "REVIEW_PURCHASE_ORDER_LINES" -> target("purchase-order-items", commitment.purchaseOrderId(), key);
            default -> null;
        };
    }

    private RelatedDocument related(
        String documentType,
        BusinessEntity entity,
        String moduleKey,
        SupplyCommitmentKey key
    ) {
        return new RelatedDocument(
            documentType, entity.getId(), code(entity), displayName(entity), entity.getStatus(),
            target(moduleKey, entity.getId(), key));
    }

    private DeepLinkTarget target(String moduleKey, UUID recordId, SupplyCommitmentKey key) {
        return new DeepLinkTarget(moduleKey, recordId, key.canonical(), Map.of());
    }

    private MoneyValue money(BigDecimal amount, String currencyCode) {
        if (amount == null) return null;
        return new MoneyValue(amount, normalizeCurrency(currencyCode), null, null, null, null, null, false);
    }

    private QuantityValue quantity(BigDecimal value, String uom) {
        return value == null && uom == null ? null : new QuantityValue(value, uom);
    }

    private EntityRef entityRef(
        com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EntityRef value
    ) {
        if (value == null) return null;
        return new EntityRef(value.id(), value.code(), value.displayName(), value.status());
    }

    private boolean scoped(BusinessEntity entity, String tenantId, UUID companyId) {
        return entity != null && !entity.isDeleted()
            && Objects.equals(entity.getTenantId(), tenantId)
            && Objects.equals(entity.getCompanyId(), companyId);
    }

    private String code(BusinessEntity entity, String... fallbacks) {
        String code = normalize(entity == null ? null : entity.getCode());
        if (code != null) return code;
        for (String fallback : fallbacks) {
            String normalized = normalize(fallback);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private String displayName(BusinessEntity entity) {
        if (entity == null) return null;
        String name = normalize(entity.getName());
        return name == null ? code(entity) : name;
    }

    private String normalizeCurrency(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SupplySeverity severity(String value) {
        String normalized = normalize(value);
        if (normalized == null) return SupplySeverity.INFO;
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "ERROR", "BLOCKING", "CRITICAL" -> SupplySeverity.BLOCKING;
            case "WARNING", "WARN" -> SupplySeverity.WARNING;
            default -> SupplySeverity.INFO;
        };
    }

    private String blockerMessageKey(String code) {
        return "supply.workspace.context.blocker." + code.toLowerCase(Locale.ROOT);
    }

    private UnavailableReason reason(String code, String messageKey) {
        return new UnavailableReason(code, messageKey);
    }

    private static <T> List<T> safe(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record ImportBundle(
        ImportDelivery delivery,
        List<CustomsDeclaration> customs,
        List<ImportFreightBooking> freight,
        List<ImportInsurancePolicy> insurance,
        List<LandedCostComponent> landedCosts,
        List<LandedLogisticsEvent> logisticsEvents
    ) {
    }
}
