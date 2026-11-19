package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.ExchangeRate;
import com.enterprise.mm.entity.ManagementOpportunityPolicy;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialMarketPrice;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.ManagementOpportunityConfidence;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityEvidenceSourceEntityType;
import com.enterprise.mm.enums.ManagementOpportunityType;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.ExchangeRateRepository;
import com.enterprise.mm.repository.MaterialMarketPriceRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementOpportunityCalculationService {
    private static final String DEFAULT_TARGET_CURRENCY = "UZS";
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    private final ManagementOpportunitySourceReaderService sourceReaderService;
    private final MaterialMarketPriceRepository materialMarketPriceRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final ManagementOpportunityPolicyService policyService;
    private final ObjectMapper objectMapper;

    public ManagementOpportunityCalculationService(
        PurchaseOrderItemRepository purchaseOrderItemRepository,
        MaterialMarketPriceRepository materialMarketPriceRepository,
        MaterialRepository materialRepository,
        NonMovingStockCaseLineRepository nonMovingStockCaseLineRepository,
        NonMovingStockDecisionRepository nonMovingStockDecisionRepository,
        DeliveryScheduleRepository deliveryScheduleRepository,
        CurrencyRepository currencyRepository,
        ExchangeRateRepository exchangeRateRepository,
        ManagementOpportunityPolicyService policyService,
        ObjectMapper objectMapper
    ) {
        this.sourceReaderService = new ManagementOpportunitySourceReaderService(
            purchaseOrderItemRepository,
            materialRepository,
            nonMovingStockCaseLineRepository,
            nonMovingStockDecisionRepository,
            deliveryScheduleRepository
        );
        this.materialMarketPriceRepository = materialMarketPriceRepository;
        this.currencyRepository = currencyRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.policyService = policyService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ManagementOpportunityCalculatedCandidate> calculate(
        LocalDate asOfDate,
        List<ManagementOpportunityType> requestedTypes,
        String targetCurrencyCode
    ) {
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        String targetCurrency = normalizeCurrency(targetCurrencyCode == null ? DEFAULT_TARGET_CURRENCY : targetCurrencyCode);
        List<ManagementOpportunityType> types = requestedTypes == null || requestedTypes.isEmpty()
            ? List.of(ManagementOpportunityType.values())
            : requestedTypes;

        List<ManagementOpportunityCalculatedCandidate> candidates = new ArrayList<>();
        if (types.contains(ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE)) {
            candidates.addAll(procurementPriceVariance(date, targetCurrency));
        }
        if (types.contains(ManagementOpportunityType.EXCESS_CARRYING_COST)) {
            candidates.addAll(excessCarryingCost(date, targetCurrency));
        }
        if (types.contains(ManagementOpportunityType.NON_MOVING_RECOVERY_GAP)) {
            candidates.addAll(nonMovingRecoveryGap(date, targetCurrency));
        }
        if (types.contains(ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE)) {
            candidates.addAll(supplierDelayExposure(date, targetCurrency));
        }
        return candidates;
    }

    private List<ManagementOpportunityCalculatedCandidate> procurementPriceVariance(LocalDate asOfDate, String targetCurrency) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return sourceReaderService.receiptCandidates(tenantId, companyId, asOfDate)
            .stream()
            .map(item -> procurementPriceVariance(item, asOfDate, targetCurrency))
            .toList();
    }

    private ManagementOpportunityCalculatedCandidate procurementPriceVariance(PurchaseOrderItem item, LocalDate asOfDate, String targetCurrency) {
        PurchaseOrder purchaseOrder = item.getPurchaseOrder();
        UUID warehouseId = item.getReceiptWarehouseId();
        UUID materialGroupId = item.getMaterial() == null ? null : item.getMaterial().getMaterialGroupId();
        ManagementOpportunityPolicy policy;
        try {
            policy = policyService.resolveActivePolicy(
                ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE,
                new ManagementOpportunityScope(warehouseId, materialGroupId, purchaseOrder == null ? null : purchaseOrder.getSupplierId()),
                asOfDate
            );
        } catch (RuntimeException exception) {
            return skipped(
                ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE,
                item.getPurchaseOrderId(),
                item.getId(),
                item.getMaterialId(),
                purchaseOrder == null ? null : purchaseOrder.getSupplierId(),
                warehouseId,
                asOfDate,
                currency(purchaseOrder == null ? null : purchaseOrder.getCurrencyCode()),
                "POLICY_MISSING",
                exception.getMessage()
            );
        }

        BigDecimal acceptedQuantity = nvl(item.getReceivedQuantity()).subtract(nvl(item.getRejectedQuantity())).subtract(nvl(item.getDamagedQuantity()));
        if (acceptedQuantity.signum() <= 0 || !acceptedQuality(item.getQualityStatus())) {
            return skipped(ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE, item.getPurchaseOrderId(), item.getId(), item.getMaterialId(),
                purchaseOrder == null ? null : purchaseOrder.getSupplierId(), warehouseId, asOfDate, currency(purchaseOrder == null ? null : purchaseOrder.getCurrencyCode()),
                "ACCEPTED_QUANTITY_MISSING", "Accepted receipt quantity and accepted quality status are required");
        }

        List<MaterialMarketPrice> baselines = materialMarketPriceRepository.findEffectiveApproved(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            item.getMaterialId(),
            warehouseId,
            asOfDate
        );
        if (baselines.isEmpty()) {
            return skipped(ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE, item.getPurchaseOrderId(), item.getId(), item.getMaterialId(),
                purchaseOrder == null ? null : purchaseOrder.getSupplierId(), warehouseId, asOfDate, currency(purchaseOrder == null ? null : purchaseOrder.getCurrencyCode()),
                "APPROVED_MARKET_PRICE_MISSING", "Approved effective market price is required");
        }
        MaterialMarketPrice baseline = baselines.get(0);
        String sourceCurrency = currency(purchaseOrder == null ? null : purchaseOrder.getCurrencyCode());
        if (!sourceCurrency.equals(currency(baseline.getCurrencyCode()))) {
            return skipped(ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE, item.getPurchaseOrderId(), item.getId(), item.getMaterialId(),
                purchaseOrder == null ? null : purchaseOrder.getSupplierId(), warehouseId, asOfDate, sourceCurrency,
                "BASELINE_CURRENCY_MISMATCH", "PO currency and approved market price currency must match in v1");
        }

        BigDecimal unitVariance = nvl(item.getUnitPrice()).subtract(nvl(baseline.getMarketUnitPrice())).max(BigDecimal.ZERO);
        BigDecimal sourceAmount = money(unitVariance.multiply(acceptedQuantity));
        if (sourceAmount.signum() <= 0) {
            return excluded(ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE, item.getPurchaseOrderId(), item.getId(), item.getMaterialId(),
                purchaseOrder == null ? null : purchaseOrder.getSupplierId(), warehouseId, asOfDate, sourceCurrency, "Variance is zero or negative");
        }

        Normalization normalization = normalize(sourceAmount, sourceCurrency, targetCurrency, asOfDate);
        return candidate(
            ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE,
            policy.getId(),
            "PurchaseOrder",
            item.getPurchaseOrderId(),
            item.getId(),
            item.getMaterialId(),
            purchaseOrder == null ? null : purchaseOrder.getSupplierId(),
            warehouseId,
            asOfDate,
            sourceCurrency,
            sourceAmount,
            normalization,
            baseline.getMarketUnitPrice(),
            item.getUnitPrice(),
            acceptedQuantity,
            "PROCUREMENT_PRICE_VARIANCE_V1",
            snapshot(Map.of(
                "formula", "max(actualUnitPrice - approvedMarketUnitPrice, 0) * acceptedQuantity",
                "acceptedQuantity", acceptedQuantity,
                "actualUnitPrice", nvl(item.getUnitPrice()),
                "approvedMarketPriceId", baseline.getId(),
                "approvedMarketUnitPrice", nvl(baseline.getMarketUnitPrice()),
                "policyId", policy.getId()
            )),
            ManagementOpportunityConfidence.HIGH,
            normalization.complete() ? ManagementOpportunityDataCompleteness.COMPLETE : ManagementOpportunityDataCompleteness.DATA_INCOMPLETE,
            normalization.complete() ? null : normalization.reason(),
            List.of(
                evidence("PO_ITEM", ManagementOpportunityEvidenceSourceEntityType.PURCHASE_ORDER_ITEM, item.getId(), Map.of(
                    "purchaseOrderId", item.getPurchaseOrderId(),
                    "materialId", item.getMaterialId(),
                    "unitPrice", nvl(item.getUnitPrice())
                )),
                evidence("RECEIPT_ACCEPTANCE", ManagementOpportunityEvidenceSourceEntityType.PURCHASE_ORDER_ITEM, item.getId(), Map.of(
                    "purchaseOrderId", item.getPurchaseOrderId(),
                    "receiptWarehouseId", Objects.toString(item.getReceiptWarehouseId(), ""),
                    "receiptStorageLocationId", Objects.toString(item.getReceiptStorageLocationId(), ""),
                    "receiptBatchNumber", Objects.toString(item.getReceiptBatchNumber(), ""),
                    "receivedQuantity", nvl(item.getReceivedQuantity()),
                    "rejectedQuantity", nvl(item.getRejectedQuantity()),
                    "damagedQuantity", nvl(item.getDamagedQuantity()),
                    "acceptedQuantity", acceptedQuantity,
                    "qualityStatus", Objects.toString(item.getQualityStatus(), "")
                )),
                evidence("APPROVED_MARKET_PRICE", ManagementOpportunityEvidenceSourceEntityType.MATERIAL_MARKET_PRICE, baseline.getId(), Map.of(
                    "marketUnitPrice", nvl(baseline.getMarketUnitPrice()),
                    "currencyCode", baseline.getCurrencyCode(),
                    "effectiveDate", baseline.getEffectiveDate(),
                    "sourceReference", Objects.toString(baseline.getSourceReference(), "")
                )),
                evidence("POLICY", ManagementOpportunityEvidenceSourceEntityType.MANAGEMENT_OPPORTUNITY_POLICY, policy.getId(), Map.of(
                    "policyId", policy.getId(),
                    "code", Objects.toString(policy.getCode(), ""),
                    "opportunityType", policy.getOpportunityType().name()
                ))
            )
        );
    }

    private List<ManagementOpportunityCalculatedCandidate> excessCarryingCost(LocalDate asOfDate, String targetCurrency) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return sourceReaderService.carryingCostCandidates(tenantId, companyId)
            .stream()
            .map(line -> excessCarryingCost(line, asOfDate, targetCurrency))
            .toList();
    }

    private ManagementOpportunityCalculatedCandidate excessCarryingCost(NonMovingStockCaseLine line, LocalDate asOfDate, String targetCurrency) {
        Material material = sourceReaderService.material(TenantContext.currentTenant(), line.getMaterialId()).orElse(null);
        ManagementOpportunityPolicy policy;
        try {
            policy = policyService.resolveActivePolicy(
                ManagementOpportunityType.EXCESS_CARRYING_COST,
                new ManagementOpportunityScope(line.getWarehouseId(), material == null ? null : material.getMaterialGroupId(), null),
                asOfDate
            );
        } catch (RuntimeException exception) {
            return skipped(ManagementOpportunityType.EXCESS_CARRYING_COST, line.getCaseId(), line.getId(), line.getMaterialId(), null, line.getWarehouseId(),
                asOfDate, targetCurrency, "POLICY_MISSING", exception.getMessage());
        }
        BigDecimal rate = nvl(policy.getAnnualCarryingCostRate());
        long eligibleDays = eligibleDays(line, asOfDate);
        if (nvl(line.getBookValue()).signum() <= 0 || rate.signum() <= 0 || eligibleDays <= 0) {
            return excluded(ManagementOpportunityType.EXCESS_CARRYING_COST, line.getCaseId(), line.getId(), line.getMaterialId(), null, line.getWarehouseId(),
                asOfDate, targetCurrency, "Book value, rate, and eligible days must be positive");
        }
        BigDecimal sourceAmount = money(nvl(line.getBookValue()).multiply(rate).multiply(BigDecimal.valueOf(eligibleDays)).divide(DAYS_IN_YEAR, 4, RoundingMode.HALF_UP));
        Normalization normalization = normalize(sourceAmount, targetCurrency, targetCurrency, asOfDate);
        return candidate(
            ManagementOpportunityType.EXCESS_CARRYING_COST,
            policy.getId(),
            "NonMovingStockCase",
            line.getCaseId(),
            line.getId(),
            line.getMaterialId(),
            null,
            line.getWarehouseId(),
            asOfDate,
            targetCurrency,
            sourceAmount,
            normalization,
            null,
            line.getBookValue(),
            line.getQuantity(),
            "EXCESS_CARRYING_COST_V1",
            snapshot(Map.of(
                "formula", "bookValue * annualCarryingCostRate * eligibleDays / 365",
                "bookValue", nvl(line.getBookValue()),
                "sourceCurrencyAssumption", targetCurrency,
                "annualCarryingCostRate", rate,
                "eligibleDays", eligibleDays,
                "policyId", policy.getId()
            )),
            ManagementOpportunityConfidence.MEDIUM,
            ManagementOpportunityDataCompleteness.COMPLETE,
            null,
            List.of(
                evidence("NON_MOVING_CASE_LINE", ManagementOpportunityEvidenceSourceEntityType.NON_MOVING_STOCK_CASE_LINE, line.getId(), Map.of(
                    "caseId", line.getCaseId(),
                    "bookValue", nvl(line.getBookValue()),
                    "sourceCurrencyAssumption", targetCurrency,
                    "quantity", nvl(line.getQuantity()),
                    "lastMovementAt", Objects.toString(line.getLastMovementAt(), ""),
                    "monthsWithoutMovement", Objects.toString(line.getMonthsWithoutMovement(), "")
                )),
                evidence("POLICY", ManagementOpportunityEvidenceSourceEntityType.MANAGEMENT_OPPORTUNITY_POLICY, policy.getId(), Map.of(
                    "policyId", policy.getId(),
                    "annualCarryingCostRate", rate
                ))
            )
        );
    }

    private List<ManagementOpportunityCalculatedCandidate> nonMovingRecoveryGap(LocalDate asOfDate, String targetCurrency) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<ManagementOpportunityCalculatedCandidate> candidates = new ArrayList<>();
        for (NonMovingStockDecision decision : sourceReaderService.recoveryGapDecisions(tenantId, companyId)) {
            List<NonMovingStockCaseLine> lines = sourceReaderService.recoveryGapLines(tenantId, decision.getCaseId());
            candidates.addAll(recoveryGap(decision, lines, asOfDate, targetCurrency));
        }
        return candidates;
    }

    private List<ManagementOpportunityCalculatedCandidate> recoveryGap(
        NonMovingStockDecision decision,
        List<NonMovingStockCaseLine> lines,
        LocalDate asOfDate,
        String targetCurrency
    ) {
        if (decision.getExpectedRecoveryAmount() == null || isBlank(decision.getRecoveryBasisReference())) {
            return List.of(skipped(ManagementOpportunityType.NON_MOVING_RECOVERY_GAP, decision.getCaseId(), decision.getId(), null, null, null,
                asOfDate, targetCurrency, "RECOVERY_EVIDENCE_MISSING", "Expected recovery amount and recovery basis reference are required"));
        }
        String recoveryCurrency = currency(decision.getExpectedRecoveryCurrencyCode());
        if (!targetCurrency.equals(recoveryCurrency)) {
            return List.of(skipped(ManagementOpportunityType.NON_MOVING_RECOVERY_GAP, decision.getCaseId(), decision.getId(), null, null, null,
                asOfDate, recoveryCurrency, "RECOVERY_CURRENCY_UNSUPPORTED", "Recovery currency must match target currency in v1"));
        }
        BigDecimal totalBasis = totalRecoveryBasis(lines, decision.getRecoveryAllocationBasis());
        if (totalBasis.signum() <= 0) {
            return List.of(excluded(ManagementOpportunityType.NON_MOVING_RECOVERY_GAP, decision.getCaseId(), decision.getId(), null, null, null,
                asOfDate, targetCurrency, "Recovery allocation basis is zero"));
        }

        List<ManagementOpportunityCalculatedCandidate> candidates = new ArrayList<>();
        for (NonMovingStockCaseLine line : lines) {
            Material material = sourceReaderService.material(TenantContext.currentTenant(), line.getMaterialId()).orElse(null);
            ManagementOpportunityPolicy policy;
            try {
                policy = policyService.resolveActivePolicy(
                    ManagementOpportunityType.NON_MOVING_RECOVERY_GAP,
                    new ManagementOpportunityScope(line.getWarehouseId(), material == null ? null : material.getMaterialGroupId(), null),
                    asOfDate
                );
            } catch (RuntimeException exception) {
                candidates.add(skipped(ManagementOpportunityType.NON_MOVING_RECOVERY_GAP, decision.getCaseId(), line.getId(), line.getMaterialId(), null, line.getWarehouseId(),
                    asOfDate, targetCurrency, "POLICY_MISSING", exception.getMessage()));
                continue;
            }
            BigDecimal basis = recoveryBasis(line, decision.getRecoveryAllocationBasis());
            BigDecimal allocatedRecovery = money(nvl(decision.getExpectedRecoveryAmount()).multiply(basis).divide(totalBasis, 4, RoundingMode.HALF_UP));
            BigDecimal sourceAmount = money(nvl(line.getBookValue()).subtract(allocatedRecovery).max(BigDecimal.ZERO));
            if (sourceAmount.signum() <= 0) {
                candidates.add(excluded(ManagementOpportunityType.NON_MOVING_RECOVERY_GAP, decision.getCaseId(), line.getId(), line.getMaterialId(), null, line.getWarehouseId(),
                    asOfDate, targetCurrency, "Expected recovery is greater than or equal to book value"));
                continue;
            }
            Normalization normalization = normalize(sourceAmount, targetCurrency, targetCurrency, asOfDate);
            candidates.add(candidate(
                ManagementOpportunityType.NON_MOVING_RECOVERY_GAP,
                policy.getId(),
                "NonMovingStockDecision",
                decision.getId(),
                line.getId(),
                line.getMaterialId(),
                null,
                line.getWarehouseId(),
                asOfDate,
                targetCurrency,
                sourceAmount,
                normalization,
                allocatedRecovery,
                line.getBookValue(),
                line.getQuantity(),
                "NON_MOVING_RECOVERY_GAP_V1",
                snapshot(Map.of(
                    "formula", "max(allocatedBookValue - allocatedExpectedRecoveryAmount, 0)",
                    "bookValue", nvl(line.getBookValue()),
                    "sourceCurrencyAssumption", targetCurrency,
                    "allocatedExpectedRecoveryAmount", allocatedRecovery,
                    "recoveryBasisReference", decision.getRecoveryBasisReference(),
                    "allocationBasis", Objects.toString(decision.getRecoveryAllocationBasis(), "BOOK_VALUE")
                )),
                ManagementOpportunityConfidence.MEDIUM,
                ManagementOpportunityDataCompleteness.COMPLETE,
                null,
                List.of(
                    evidence("NON_MOVING_DECISION", ManagementOpportunityEvidenceSourceEntityType.NON_MOVING_STOCK_DECISION, decision.getId(), Map.of(
                        "decisionType", decision.getDecisionType(),
                        "expectedRecoveryAmount", nvl(decision.getExpectedRecoveryAmount()),
                        "expectedRecoveryCurrencyCode", recoveryCurrency,
                        "recoveryBasisReference", decision.getRecoveryBasisReference()
                    )),
                    evidence("NON_MOVING_CASE_LINE", ManagementOpportunityEvidenceSourceEntityType.NON_MOVING_STOCK_CASE_LINE, line.getId(), Map.of(
                        "bookValue", nvl(line.getBookValue()),
                        "sourceCurrencyAssumption", targetCurrency,
                        "quantity", nvl(line.getQuantity())
                    )),
                    evidence("POLICY", ManagementOpportunityEvidenceSourceEntityType.MANAGEMENT_OPPORTUNITY_POLICY, policy.getId(), Map.of("policyId", policy.getId()))
                )
            ));
        }
        return candidates;
    }

    private List<ManagementOpportunityCalculatedCandidate> supplierDelayExposure(LocalDate asOfDate, String targetCurrency) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return sourceReaderService.delayCandidates(tenantId, companyId, asOfDate)
            .stream()
            .map(schedule -> supplierDelayExposure(schedule, asOfDate, targetCurrency))
            .toList();
    }

    private ManagementOpportunityCalculatedCandidate supplierDelayExposure(DeliverySchedule schedule, LocalDate asOfDate, String targetCurrency) {
        PurchaseOrder purchaseOrder = schedule.getPurchaseOrder();
        UUID supplierId = purchaseOrder == null ? null : purchaseOrder.getSupplierId();
        ManagementOpportunityPolicy policy;
        try {
            policy = policyService.resolveActivePolicy(
                ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE,
                new ManagementOpportunityScope(null, null, supplierId),
                asOfDate
            );
        } catch (RuntimeException exception) {
            return skipped(ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE, schedule.getId(), schedule.getId(), null, supplierId, null,
                asOfDate, targetCurrency, "POLICY_MISSING", exception.getMessage());
        }
        BigDecimal share = schedule.getAttributableShare() == null ? policy.getAttributableShare() : schedule.getAttributableShare();
        if (isBlank(schedule.getBusinessImpactReference()) || share == null || share.signum() <= 0 || nvl(policy.getDailyImpactRate()).signum() <= 0 || !hasOpenSchedule(schedule)) {
            return skipped(ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE, schedule.getId(), schedule.getId(), null, supplierId, null,
                asOfDate, currency(policy.getCurrencyCode()), "DELAY_EVIDENCE_MISSING", "Overdue schedule requires open quantity or amount, impact reference, daily rate, and share");
        }
        long lateDays = ChronoUnit.DAYS.between(schedule.getScheduleDate(), asOfDate);
        if (lateDays <= 0) {
            return excluded(ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE, schedule.getId(), schedule.getId(), null, supplierId, null,
                asOfDate, currency(policy.getCurrencyCode()), "Schedule is not overdue");
        }
        String sourceCurrency = currency(policy.getCurrencyCode());
        BigDecimal sourceAmount = money(BigDecimal.valueOf(lateDays).multiply(nvl(policy.getDailyImpactRate())).multiply(share));
        Normalization normalization = normalize(sourceAmount, sourceCurrency, targetCurrency, asOfDate);
        return candidate(
            ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE,
            policy.getId(),
            "DeliverySchedule",
            schedule.getId(),
            schedule.getId(),
            null,
            supplierId,
            null,
            asOfDate,
            sourceCurrency,
            sourceAmount,
            normalization,
            null,
            policy.getDailyImpactRate(),
            share,
            "SUPPLIER_DELAY_EXPOSURE_V1",
            snapshot(Map.of(
                "formula", "lateDays * dailyImpactRate * attributableShare",
                "lateDays", lateDays,
                "dailyImpactRate", nvl(policy.getDailyImpactRate()),
                "attributableShare", share,
                "businessImpactReference", schedule.getBusinessImpactReference()
            )),
            ManagementOpportunityConfidence.LOW,
            normalization.complete() ? ManagementOpportunityDataCompleteness.COMPLETE : ManagementOpportunityDataCompleteness.DATA_INCOMPLETE,
            normalization.complete() ? null : normalization.reason(),
            List.of(
                evidence("DELIVERY_SCHEDULE", ManagementOpportunityEvidenceSourceEntityType.DELIVERY_SCHEDULE, schedule.getId(), Map.of(
                    "scheduleDate", schedule.getScheduleDate(),
                    "plannedQuantity", nvl(schedule.getPlannedQuantity()),
                    "receivedQuantity", nvl(schedule.getReceivedQuantity()),
                    "businessImpactReference", schedule.getBusinessImpactReference()
                )),
                evidence("POLICY", ManagementOpportunityEvidenceSourceEntityType.MANAGEMENT_OPPORTUNITY_POLICY, policy.getId(), Map.of(
                    "policyId", policy.getId(),
                    "dailyImpactRate", nvl(policy.getDailyImpactRate()),
                    "currencyCode", sourceCurrency
                ))
            )
        );
    }

    private Normalization normalize(BigDecimal sourceAmount, String sourceCurrency, String targetCurrency, LocalDate asOfDate) {
        String source = currency(sourceCurrency);
        String target = currency(targetCurrency);
        if (source.equals(target)) {
            return new Normalization(target, money(sourceAmount), BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP), null, null, "SAME_CURRENCY", "COMPLETE", true, null);
        }
        Currency currency = currencyRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), source)
            .or(() -> currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(TenantContext.currentTenant(), source))
            .orElse(null);
        if (currency == null) {
            return Normalization.incomplete(target, "Currency master data not found for " + source);
        }
        List<ExchangeRate> rates = exchangeRateRepository.findLatestOnOrBefore(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            true,
            currency.getId(),
            asOfDate,
            PageRequest.of(0, 1)
        );
        if (rates.isEmpty()) {
            return Normalization.incomplete(target, "Exchange-rate snapshot not found for " + source);
        }
        ExchangeRate rate = rates.get(0);
        BigDecimal rateValue = nvl(rate.getRateToBase()).setScale(8, RoundingMode.HALF_UP);
        return new Normalization(
            target,
            money(sourceAmount.multiply(rateValue)),
            rateValue,
            rate.getId(),
            rate.getRateDate(),
            rate.getSource(),
            "COMPLETE",
            true,
            null
        );
    }

    private ManagementOpportunityCalculatedCandidate candidate(
        ManagementOpportunityType type,
        UUID policyId,
        String sourceAggregateType,
        UUID sourceAggregateId,
        UUID sourceLineId,
        UUID materialId,
        UUID supplierId,
        UUID warehouseId,
        LocalDate asOfDate,
        String sourceCurrencyCode,
        BigDecimal sourceAmount,
        Normalization normalization,
        BigDecimal baselineAmount,
        BigDecimal actualAmount,
        BigDecimal quantity,
        String formulaCode,
        String formulaSnapshot,
        ManagementOpportunityConfidence confidence,
        ManagementOpportunityDataCompleteness completeness,
        String skipReason,
        List<ManagementOpportunityEvidenceDescriptor> evidence
    ) {
        return new ManagementOpportunityCalculatedCandidate(
            type,
            policyId,
            sourceAggregateType,
            sourceAggregateId,
            sourceLineId,
            materialId,
            supplierId,
            warehouseId,
            asOfDate,
            currency(sourceCurrencyCode),
            normalization.normalizedCurrencyCode(),
            money(sourceAmount),
            normalization.normalizedAmount(),
            baselineAmount == null ? null : money(baselineAmount),
            actualAmount == null ? null : money(actualAmount),
            quantity == null ? null : money(quantity),
            normalization.exchangeRate(),
            normalization.exchangeRateId(),
            normalization.exchangeRateDate(),
            normalization.exchangeRateSource(),
            normalization.quality(),
            formulaCode,
            formulaSnapshot,
            confidence,
            completeness,
            skipReason,
            evidence
        );
    }

    private ManagementOpportunityCalculatedCandidate skipped(
        ManagementOpportunityType type,
        UUID sourceAggregateId,
        UUID sourceLineId,
        UUID materialId,
        UUID supplierId,
        UUID warehouseId,
        LocalDate asOfDate,
        String sourceCurrency,
        String formulaCode,
        String reason
    ) {
        return new ManagementOpportunityCalculatedCandidate(
            type,
            null,
            "SOURCE",
            sourceAggregateId,
            sourceLineId,
            materialId,
            supplierId,
            warehouseId,
            asOfDate,
            currency(sourceCurrency),
            null,
            BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "DATA_INCOMPLETE",
            formulaCode,
            snapshot(Map.of("skipReason", reason == null ? "Data incomplete" : reason)),
            ManagementOpportunityConfidence.LOW,
            ManagementOpportunityDataCompleteness.DATA_INCOMPLETE,
            reason,
            List.of()
        );
    }

    private ManagementOpportunityCalculatedCandidate excluded(
        ManagementOpportunityType type,
        UUID sourceAggregateId,
        UUID sourceLineId,
        UUID materialId,
        UUID supplierId,
        UUID warehouseId,
        LocalDate asOfDate,
        String sourceCurrency,
        String reason
    ) {
        return new ManagementOpportunityCalculatedCandidate(
            type,
            null,
            "SOURCE",
            sourceAggregateId,
            sourceLineId,
            materialId,
            supplierId,
            warehouseId,
            asOfDate,
            currency(sourceCurrency),
            null,
            BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "EXCLUDED",
            "EXCLUDED",
            snapshot(Map.of("skipReason", reason)),
            ManagementOpportunityConfidence.LOW,
            ManagementOpportunityDataCompleteness.EXCLUDED,
            reason,
            List.of()
        );
    }

    private ManagementOpportunityEvidenceDescriptor evidence(
        String type,
        ManagementOpportunityEvidenceSourceEntityType sourceType,
        UUID sourceId,
        Map<String, ?> snapshot
    ) {
        return new ManagementOpportunityEvidenceDescriptor(type, sourceType, sourceId, snapshot(snapshot));
    }

    private String snapshot(Map<String, ?> values) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize management opportunity snapshot", exception);
        }
    }

    private boolean acceptedQuality(String qualityStatus) {
        if (qualityStatus == null || qualityStatus.isBlank()) {
            return false;
        }
        String status = qualityStatus.trim().toUpperCase(Locale.ROOT);
        return status.equals("ACCEPTED") || status.equals("RELEASED") || status.equals("APPROVED");
    }

    private boolean hasOpenSchedule(DeliverySchedule schedule) {
        BigDecimal openQuantity = nvl(schedule.getPlannedQuantity()).subtract(nvl(schedule.getReceivedQuantity()));
        BigDecimal openAmount = nvl(schedule.getPlannedAmount()).subtract(nvl(schedule.getReceivedAmount()));
        return openQuantity.signum() > 0 || openAmount.signum() > 0;
    }

    private long eligibleDays(NonMovingStockCaseLine line, LocalDate asOfDate) {
        if (line.getLastMovementAt() != null) {
            return Math.max(0, ChronoUnit.DAYS.between(line.getLastMovementAt().toLocalDate(), asOfDate));
        }
        return Math.max(0, line.getMonthsWithoutMovement() == null ? 0 : line.getMonthsWithoutMovement() * 30L);
    }

    private BigDecimal totalRecoveryBasis(List<NonMovingStockCaseLine> lines, String allocationBasis) {
        return lines.stream()
            .map(line -> recoveryBasis(line, allocationBasis))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal recoveryBasis(NonMovingStockCaseLine line, String allocationBasis) {
        if ("QUANTITY".equalsIgnoreCase(Objects.toString(allocationBasis, ""))) {
            return nvl(line.getQuantity());
        }
        return nvl(line.getBookValue());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return nvl(value).setScale(4, RoundingMode.HALF_UP);
    }

    private String currency(String value) {
        return normalizeCurrency(value == null || value.isBlank() ? DEFAULT_TARGET_CURRENCY : value);
    }

    private String normalizeCurrency(String value) {
        return value == null || value.isBlank() ? DEFAULT_TARGET_CURRENCY : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Normalization(
        String normalizedCurrencyCode,
        BigDecimal normalizedAmount,
        BigDecimal exchangeRate,
        UUID exchangeRateId,
        LocalDate exchangeRateDate,
        String exchangeRateSource,
        String quality,
        boolean complete,
        String reason
    ) {
        static Normalization incomplete(String normalizedCurrencyCode, String reason) {
            return new Normalization(normalizedCurrencyCode, null, null, null, null, null, "DATA_INCOMPLETE", false, reason);
        }
    }
}
