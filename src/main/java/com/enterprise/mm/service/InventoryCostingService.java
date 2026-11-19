package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.InventoryCostLayerType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockCostMovementType;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.service.transfer.TransferValueSplit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryCostingService {
    private static final int MONEY_SCALE = 4;
    private static final int UNIT_SCALE = 6;
    private static final int QUANTITY_ALLOCATION_SCALE = 7;

    private final InventoryCostLayerRepository costLayerRepository;
    private final StockCostLedgerService stockCostLedgerService;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockValuationService stockValuationService;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;

    @Transactional
    public InboundLayerResult recordInboundLayer(
        StockMovement movement,
        UUID warehouseId,
        UUID stockBatchId,
        BigDecimal quantity,
        BigDecimal unitCost,
        InventoryCostLayerType layerType
    ) {
        BigDecimal normalizedQuantity = nvl(quantity);
        BigDecimal normalizedUnitCost = nvl(unitCost).setScale(UNIT_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalCost = normalizedQuantity.multiply(normalizedUnitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return saveInboundLayer(movement, warehouseId, stockBatchId, normalizedQuantity,
            normalizedUnitCost, totalCost, layerType, false);
    }

    /** A production allocation is an exact book value; division into a rounded unit price must not lose money. */
    @Transactional
    public InboundLayerResult recordProductionLayer(StockMovement movement, UUID warehouseId, UUID stockBatchId) {
        if (!"PRODUCTION_OPERATION".equals(movement.getReferenceType()) || movement.getReferenceId() == null
            || movement.getMovementType() != MovementType.RECEIPT || movement.getTotalCost() == null
            || movement.getTotalCost().signum() < 0 || movement.getTotalCost().stripTrailingZeros().scale() > MONEY_SCALE)
            throw new BusinessException("Invalid production cost allocation");
        return saveInboundLayer(movement, warehouseId, stockBatchId, movement.getQuantity(),
            movement.getTotalCost().divide(movement.getQuantity(), UNIT_SCALE, RoundingMode.HALF_UP),
            movement.getTotalCost(), InventoryCostLayerType.RECEIPT, true);
    }

    /** Import book value is authoritative; the unit cost is only a rounded snapshot. */
    @Transactional
    public InboundLayerResult recordExactOpeningLayer(StockMovement movement, BigDecimal totalValue) {
        if (movement == null || movement.getId() == null || movement.getCompanyId() == null
            || movement.getMaterialId() == null || movement.getWarehouseId() == null
            || movement.getReferenceId() == null || movement.getMovementType() != MovementType.RECEIPT
            || !"OPENING_STOCK_EXACT".equals(movement.getReferenceType())) {
            throw new BusinessException("Exact opening cost requires a persisted managed opening movement");
        }
        BigDecimal quantity = movement.getQuantity();
        if (quantity == null || quantity.signum() <= 0
            || quantity.stripTrailingZeros().scale() > QUANTITY_ALLOCATION_SCALE) {
            throw new BusinessException("Exact opening quantity must be positive with at most seven decimal places");
        }
        if (totalValue == null || totalValue.signum() < 0) {
            throw new BusinessException("Exact opening book value must be nonnegative");
        }
        BigDecimal bookValue;
        try {
            bookValue = totalValue.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new BusinessException("Exact opening UZS book value permits two decimal places; value is not rounded");
        }
        if (movement.getTotalCost() == null || movement.getTotalCost().compareTo(bookValue) != 0) {
            throw new BusinessException("Exact opening movement value must match its authoritative book value");
        }
        BigDecimal unitCost = bookValue.divide(quantity, UNIT_SCALE, RoundingMode.HALF_UP);
        return saveInboundLayer(movement, movement.getWarehouseId(), movement.getStockBatchId(), quantity,
            unitCost, bookValue, InventoryCostLayerType.OPENING_BALANCE, true);
    }

    private InboundLayerResult saveInboundLayer(
        StockMovement movement, UUID warehouseId, UUID stockBatchId, BigDecimal normalizedQuantity,
        BigDecimal normalizedUnitCost, BigDecimal totalCost, InventoryCostLayerType layerType,
        boolean authoritativeValue
    ) {

        InventoryCostLayer layer = new InventoryCostLayer();
        layer.setCode("COST-LAYER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        layer.setName(layerType.name() + " layer");
        layer.setStatus("ACTIVE");
        layer.setCompanyId(companyId(movement));
        layer.setMaterialId(movement.getMaterialId());
        layer.setWarehouseId(warehouseId);
        layer.setStockBatchId(stockBatchId);
        layer.setSourceMovementId(movement.getId());
        if (layerType == InventoryCostLayerType.TRANSFER_IN) {
            layer.setTransferTransactionId(movement.getId());
            layer.setSourceWarehouseId(movement.getWarehouseId());
            layer.setTargetWarehouseId(warehouseId);
            layer.setSourceStockMovementId(movement.getId());
            layer.setTargetStockMovementId(movement.getId());
        }
        layer.setSourceDocumentType(movement.getReferenceType());
        layer.setSourceDocumentId(movement.getReferenceId());
        layer.setLayerType(layerType.name());
        layer.setReceivedQuantity(normalizedQuantity);
        layer.setRemainingQuantity(normalizedQuantity);
        layer.setUnitCost(normalizedUnitCost);
        layer.setTotalCost(totalCost);
        layer.setAuthoritativeValue(authoritativeValue);
        layer.setCurrencyCode("UZS");
        layer.setExchangeRate(BigDecimal.ONE);
        layer.setReceivedAt(movement.getPostedAt() == null ? LocalDateTime.now() : movement.getPostedAt());
        InventoryCostLayer saved = costLayerRepository.save(layer);
        if (saved == null || saved.getId() == null) {
            throw new BusinessException("Inbound cost layer id was not assigned");
        }

        StockCostMovement costMovement = recordCostMovement(
            movement,
            warehouseId,
            saved.getId(),
            movementTypeForInbound(layerType, movement.getMovementType()),
            normalizedQuantity,
            normalizedUnitCost,
            totalCost
        );
        if (costMovement == null || costMovement.getId() == null) {
            throw new BusinessException("Inbound cost movement id was not assigned");
        }
        return new InboundLayerResult(
            saved.getId(),
            saved.getParentCostLayerId(),
            costMovement.getId(),
            nvl(costMovement.getQuantity()),
            nvl(costMovement.getTotalCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            nvl(costMovement.getUnitCost()).setScale(UNIT_SCALE, RoundingMode.HALF_UP),
            saved.getCurrencyCode(),
            saved.getExchangeRate()
        );
    }

    @Transactional
    public InboundLayerResult recordTransferInboundLayer(StockMovement movement,
        com.enterprise.mm.entity.TransferShipmentAllocation origin, BigDecimal bookValue) {
        if (origin.getConsumedCostLayerIds().size() != 1)
            throw new BusinessException("Transfer receipt requires one exact source cost layer");
        UUID parentId = origin.getConsumedCostLayerIds().getFirst();
        var parent = costLayerRepository.findByIdAndIsDeletedFalse(parentId)
            .orElseThrow(() -> new BusinessException("Transfer source cost layer is missing"));
        if (!parent.getTenantId().equals(movement.getTenantId()) || !parent.getCompanyId().equals(companyId(movement))
            || !parent.getMaterialId().equals(movement.getMaterialId()))
            throw new BusinessException("Transfer cost lineage is outside movement scope");
        var layer = new InventoryCostLayer();
        layer.setName("Transfer receipt cost layer"); layer.setCode("XFER-COST-" + UUID.randomUUID().toString().substring(0, 8));
        layer.setStatus("ACTIVE"); layer.setCompanyId(companyId(movement)); layer.setMaterialId(movement.getMaterialId());
        layer.setWarehouseId(movement.getWarehouseId()); layer.setStockBatchId(movement.getStockBatchId());
        layer.setSourceMovementId(movement.getId()); layer.setParentCostLayerId(parentId);
        layer.setTransferTransactionId(origin.getTransferDocumentId()); layer.setSourceWarehouseId(origin.getSourceWarehouseId());
        layer.setTargetWarehouseId(movement.getWarehouseId()); layer.setSourceStockMovementId(origin.getSourceStockMovementId());
        layer.setTargetStockMovementId(movement.getId()); layer.setSourceDocumentType(movement.getReferenceType());
        layer.setSourceDocumentId(movement.getReferenceId()); layer.setLayerType(InventoryCostLayerType.TRANSFER_IN.name());
        layer.setReceivedQuantity(movement.getQuantity()); layer.setRemainingQuantity(movement.getQuantity());
        layer.setUnitCost(bookValue.divide(movement.getQuantity(), UNIT_SCALE, RoundingMode.HALF_UP));
        layer.setTotalCost(bookValue); layer.setCurrencyCode(origin.getCurrencyCode()); layer.setExchangeRate(origin.getExchangeRate());
        layer.setAuthoritativeValue(parent.isAuthoritativeValue());
        layer.setReceivedAt(movement.getMovementDate().atStartOfDay()); layer = costLayerRepository.save(layer);
        var cost = stockCostLedgerService.record(new StockCostLedgerService.RecordCommand(companyId(movement), movement.getMaterialId(),
            movement.getWarehouseId(), movement.getId(), layer.getId(), StockCostMovementType.TRANSFER_IN, movement.getQuantity(),
            layer.getUnitCost(), bookValue, null, movement.getReferenceType(), movement.getReferenceId(),
            origin.getTransferDocumentId(), false, null, null, null, null));
        return new InboundLayerResult(layer.getId(), parentId, cost.getId(), movement.getQuantity(), bookValue,
            layer.getUnitCost(), origin.getCurrencyCode(), origin.getExchangeRate());
    }

    @Transactional
    public CostResult consumeOutboundLayers(StockMovement movement, UUID warehouseId, BigDecimal quantity, ValuationMethod method) {
        return consumeOutboundLayers(movement, warehouseId, quantity, method, false);
    }

    /** Quarantined stock belongs to one exact batch, including an unbatched position. */
    @Transactional
    public CostResult consumeOutboundLayersForStockBatch(StockMovement movement, UUID warehouseId, BigDecimal quantity, ValuationMethod method) {
        return consumeOutboundLayers(movement, warehouseId, quantity, method, true);
    }

    private CostResult consumeOutboundLayers(StockMovement movement, UUID warehouseId, BigDecimal quantity, ValuationMethod method, boolean exactBatch) {
        ensureOpeningLayers(movement.getMaterialId(), warehouseId);
        BigDecimal normalizedQuantity = nvl(quantity);
        List<InventoryCostLayer> layers = orderedLayers(movement, warehouseId, method);
        if (exactBatch) layers = layers.stream().filter(layer -> java.util.Objects.equals(layer.getStockBatchId(), movement.getStockBatchId())).toList();
        BigDecimal availableQuantity = layers.stream()
            .map(InventoryCostLayer::getRemainingQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (availableQuantity.compareTo(normalizedQuantity) < 0) {
            throw insufficientStock(movement, warehouseId, normalizedQuantity, availableQuantity);
        }
        if ((method == ValuationMethod.MARKET_PRICE || method == ValuationMethod.LOWER_OF_COST_OR_MARKET
            || method == ValuationMethod.NET_REALIZABLE_VALUE)
            && layers.stream().anyMatch(InventoryCostLayer::isAuthoritativeValue)) {
            throw new BusinessException("Analytical price consumption of authoritative opening layers requires explicit book-value revaluation");
        }

        List<LayerConsumption> consumptions = switch (method) {
            case WEIGHTED_AVERAGE, MOVING_AVERAGE, ACTUAL_COST -> consumeWeighted(movement, warehouseId, layers, normalizedQuantity);
            case SPECIFIC_BATCH -> consumeSpecificBatch(movement, warehouseId, layers, normalizedQuantity);
            case FIFO -> consumeOrdered(movement, warehouseId, layers, normalizedQuantity);
            case LIFO -> consumeOrdered(movement, warehouseId, layers, normalizedQuantity);
            case MARKET_PRICE, LOWER_OF_COST_OR_MARKET, NET_REALIZABLE_VALUE -> consumeWeightedAtUnitCost(
                movement,
                warehouseId,
                layers,
                normalizedQuantity,
                analyticalUnitCost(movement, warehouseId, normalizedQuantity, method)
            );
        };
        BigDecimal totalCost = consumptions.stream()
            .map(LayerConsumption::totalCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal unitCost = normalizedQuantity.signum() == 0
            ? BigDecimal.ZERO
            : totalCost.divide(normalizedQuantity, UNIT_SCALE, RoundingMode.HALF_UP);
        return new CostResult(unitCost, totalCost, consumptions);
    }

    @Transactional
    public void recordManualCostMovement(StockMovement movement, UUID warehouseId, BigDecimal quantity, BigDecimal unitCost) {
        BigDecimal normalizedUnitCost = nvl(unitCost).setScale(UNIT_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalCost = nvl(quantity).multiply(normalizedUnitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        recordCostMovement(movement, warehouseId, null, movementTypeForOutbound(movement.getMovementType()), nvl(quantity), normalizedUnitCost, totalCost);
    }

    @Transactional
    public List<LandedCostAdjustment> capitalizeLandedCost(
        UUID materialId,
        UUID warehouseId,
        UUID stockBatchId,
        BigDecimal amount,
        String sourceDocumentType,
        UUID sourceDocumentId
    ) {
        ensureOpeningLayers(materialId, warehouseId);
        BigDecimal normalizedAmount = nvl(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (normalizedAmount.signum() <= 0) {
            return List.of();
        }
        List<InventoryCostLayer> layers = costLayerRepository.findCapitalizableLayersForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            materialId,
            warehouseId,
            stockBatchId,
            BigDecimal.ZERO
        );
        if (layers.isEmpty()) {
            throw new BusinessException("No active cost layer found for landed cost capitalization");
        }
        BigDecimal totalRemaining = layers.stream()
            .map(InventoryCostLayer::getRemainingQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalRemaining.signum() <= 0) {
            throw new BusinessException("Cost layers have no remaining quantity for landed cost capitalization");
        }

        BigDecimal remainder = normalizedAmount;
        java.util.ArrayList<LandedCostAdjustment> adjustments = new java.util.ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            InventoryCostLayer layer = layers.get(i);
            BigDecimal layerAmount = i == layers.size() - 1
                ? remainder
                : normalizedAmount.multiply(nvl(layer.getRemainingQuantity())).divide(totalRemaining, MONEY_SCALE, RoundingMode.HALF_UP);
            if (layerAmount.signum() <= 0) {
                continue;
            }
            BigDecimal nextTotalCost = nvl(layer.getTotalCost()).add(layerAmount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            layer.setTotalCost(nextTotalCost);
            layer.setUnitCost(nextTotalCost.divide(nvl(layer.getRemainingQuantity()), UNIT_SCALE, RoundingMode.HALF_UP));
            costLayerRepository.save(layer);
            recordFinancialCostMovement(
                materialId,
                warehouseId,
                layer.getId(),
                StockCostMovementType.LANDED_COST_ADJUSTMENT,
                nvl(layer.getRemainingQuantity()),
                layerAmount.divide(nvl(layer.getRemainingQuantity()), UNIT_SCALE, RoundingMode.HALF_UP),
                layerAmount,
                sourceDocumentType,
                sourceDocumentId
            );
            adjustments.add(new LandedCostAdjustment(
                layer.getId(),
                layer.getStockBatchId(),
                nvl(layer.getRemainingQuantity()),
                layerAmount.divide(nvl(layer.getRemainingQuantity()), UNIT_SCALE, RoundingMode.HALF_UP),
                layerAmount
            ));
            remainder = remainder.subtract(layerAmount);
        }
        return adjustments;
    }

    @Transactional
    public List<ExactReversalResult> reverseExactLineages(
        StockMovement reversalMovement,
        UUID warehouseId,
        List<ExactReversalLineage> originalLineages,
        String reason
    ) {
        if (reversalMovement == null || reversalMovement.getId() == null) {
            throw new BusinessException("Persisted reversal movement is required");
        }
        if (originalLineages == null || originalLineages.isEmpty()) {
            return List.of();
        }
        List<ExactReversalResult> results = new ArrayList<>();
        for (ExactReversalLineage original : originalLineages) {
            BigDecimal quantityDelta = nvl(original.quantityDelta()).negate();
            BigDecimal valueDelta = nvl(original.valueDelta()).negate();
            if (quantityDelta.signum() == 0 && valueDelta.signum() == 0) {
                throw new BusinessException("Exact cost layer reversal cannot contain only zero deltas");
            }

            if (original.costLayerId() != null) {
                InventoryCostLayer layer = costLayerRepository
                    .findByIdAndIsDeletedFalseForUpdate(original.costLayerId())
                    .orElseThrow(() -> new BusinessException(
                        "Exact cost layer reversal source is missing: " + original.costLayerId()));
                validateExactReversalLayerScope(layer, reversalMovement, warehouseId, original);
                BigDecimal nextQuantity = nvl(layer.getRemainingQuantity()).add(quantityDelta);
                BigDecimal nextValue = nvl(layer.getTotalCost()).add(valueDelta);
                if (nextQuantity.signum() < 0 || nextValue.signum() < 0) {
                    throw new BusinessException(
                        "Exact cost layer reversal would produce a negative quantity or value");
                }
                if (nextQuantity.compareTo(nvl(layer.getReceivedQuantity())) > 0) {
                    throw new BusinessException(
                        "Exact cost layer reversal would exceed the persisted received quantity");
                }
                layer.setRemainingQuantity(nextQuantity);
                layer.setTotalCost(nextValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                layer.setUnitCost(nextQuantity.signum() == 0
                    ? BigDecimal.ZERO.setScale(UNIT_SCALE, RoundingMode.HALF_UP)
                    : nextValue.divide(nextQuantity, UNIT_SCALE, RoundingMode.HALF_UP));
                layer.setStatus(nextQuantity.signum() == 0 ? "CONSUMED" : "ACTIVE");
                costLayerRepository.save(layer);
            }

            StockCostMovement costMovement = stockCostLedgerService.record(
                exactReversalCostCommand(
                    reversalMovement,
                    warehouseId,
                    original,
                    quantityDelta,
                    valueDelta,
                    reason
                )
            );
            if (costMovement == null || costMovement.getId() == null) {
                throw new BusinessException("Exact reversal cost movement id was not assigned");
            }
            results.add(new ExactReversalResult(
                original.costLayerId(),
                original.parentCostLayerIdSnapshot(),
                costMovement.getId(),
                quantityDelta,
                valueDelta,
                original.unitCostSnapshot(),
                original.currencyCodeSnapshot(),
                original.exchangeRateSnapshot()
            ));
        }
        return List.copyOf(results);
    }

    private void validateExactReversalLayerScope(
        InventoryCostLayer layer,
        StockMovement reversalMovement,
        UUID warehouseId,
        ExactReversalLineage original
    ) {
        if (!TenantContext.currentTenant().equals(layer.getTenantId())
            || !companyId(reversalMovement).equals(layer.getCompanyId())
            || !reversalMovement.getMaterialId().equals(layer.getMaterialId())
            || !warehouseId.equals(layer.getWarehouseId())) {
            throw new BusinessException("Exact cost layer reversal source is outside movement scope");
        }
        if (!java.util.Objects.equals(
            layer.getParentCostLayerId(), original.parentCostLayerIdSnapshot())) {
            throw new BusinessException("Exact cost layer reversal parent lineage does not match");
        }
    }

    private StockCostLedgerService.RecordCommand exactReversalCostCommand(
        StockMovement reversalMovement,
        UUID warehouseId,
        ExactReversalLineage original,
        BigDecimal quantityDelta,
        BigDecimal valueDelta,
        String reason
    ) {
        return new StockCostLedgerService.RecordCommand(
            companyId(reversalMovement),
            reversalMovement.getMaterialId(),
            warehouseId,
            reversalMovement.getId(),
            original.costLayerId(),
            StockCostMovementType.REVERSAL,
            quantityDelta,
            nvl(original.unitCostSnapshot()).setScale(UNIT_SCALE, RoundingMode.HALF_UP),
            valueDelta.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            null,
            sourceType(reversalMovement.getReferenceType()),
            sourceId(reversalMovement.getReferenceId(), reversalMovement.getId()),
            reversalMovement.getMovementType() == MovementType.TRANSFER
                ? reversalMovement.getId()
                : null,
            true,
            original.sourceCostMovementId(),
            reason,
            LocalDateTime.now(),
            currentUsername()
        );
    }

    private String currentUsername() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    @Transactional
    public void postImpairmentReserve(
        UUID materialId,
        UUID warehouseId,
        BigDecimal reserveAmount,
        boolean reduceLayerCarryingCost,
        String sourceDocumentType,
        UUID sourceDocumentId
    ) {
        ensureOpeningLayers(materialId, warehouseId);
        BigDecimal normalizedAmount = nvl(reserveAmount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (normalizedAmount.signum() <= 0) {
            return;
        }
        List<InventoryCostLayer> layers = costLayerRepository.findCapitalizableLayersForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            materialId,
            warehouseId,
            null,
            BigDecimal.ZERO
        );
        if (layers.isEmpty()) {
            throw new BusinessException("No active cost layer found for impairment posting");
        }
        BigDecimal totalCost = layers.stream()
            .map(InventoryCostLayer::getTotalCost)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalCost.signum() <= 0) {
            throw new BusinessException("Cost layers have no value for impairment posting");
        }
        BigDecimal amountToPost = normalizedAmount.min(totalCost);
        BigDecimal remainder = amountToPost;
        for (int i = 0; i < layers.size(); i++) {
            InventoryCostLayer layer = layers.get(i);
            BigDecimal layerAmount = i == layers.size() - 1
                ? remainder
                : amountToPost.multiply(nvl(layer.getTotalCost())).divide(totalCost, MONEY_SCALE, RoundingMode.HALF_UP);
            if (layerAmount.signum() <= 0) {
                continue;
            }
            if (reduceLayerCarryingCost) {
                BigDecimal nextTotalCost = nvl(layer.getTotalCost()).subtract(layerAmount).max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                layer.setTotalCost(nextTotalCost);
                layer.setUnitCost(nvl(layer.getRemainingQuantity()).signum() == 0
                    ? BigDecimal.ZERO
                    : nextTotalCost.divide(nvl(layer.getRemainingQuantity()), UNIT_SCALE, RoundingMode.HALF_UP));
                if (nextTotalCost.signum() == 0) {
                    layer.setStatus("IMPAIRED");
                }
                costLayerRepository.save(layer);
            }
            BigDecimal unitReserve = nvl(layer.getRemainingQuantity()).signum() == 0
                ? BigDecimal.ZERO
                : layerAmount.divide(nvl(layer.getRemainingQuantity()), UNIT_SCALE, RoundingMode.HALF_UP);
            recordFinancialCostMovement(
                materialId,
                warehouseId,
                layer.getId(),
                StockCostMovementType.IMPAIRMENT,
                nvl(layer.getRemainingQuantity()),
                unitReserve,
                layerAmount,
                sourceDocumentType,
                sourceDocumentId
            );
            remainder = remainder.subtract(layerAmount);
        }
    }

    private List<LayerConsumption> consumeWeighted(StockMovement movement, UUID warehouseId, List<InventoryCostLayer> layers, BigDecimal quantity) {
        // Preserve pooled book value for legacy layers too. Subtracting one pooled
        // unit cost from differently priced layers and clamping negatives creates value.
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (InventoryCostLayer layer : layers) {
            if (nvl(layer.getRemainingQuantity()).signum() <= 0 || nvl(layer.getTotalCost()).signum() < 0) {
                throw new BusinessException("Authoritative weighted layers require positive quantities and nonnegative values");
            }
            totalQuantity = totalQuantity.add(layer.getRemainingQuantity());
            totalValue = totalValue.add(nvl(layer.getTotalCost()));
        }
        BigDecimal targetValue = bookValueForQuantity(totalQuantity, totalValue, quantity);
        BigDecimal[] quantities = new BigDecimal[layers.size()];
        BigDecimal[] values = new BigDecimal[layers.size()];
        BigDecimal cumulativeQuantity = BigDecimal.ZERO;
        BigDecimal allocatedQuantity = BigDecimal.ZERO;
        BigDecimal allocatedValue = BigDecimal.ZERO;
        for (int i = 0; i < layers.size(); i++) {
            InventoryCostLayer layer = layers.get(i);
            cumulativeQuantity = cumulativeQuantity.add(layer.getRemainingQuantity());
            BigDecimal cumulativeAllocation = i == layers.size() - 1 ? quantity
                : quantity.multiply(cumulativeQuantity).divide(totalQuantity, QUANTITY_ALLOCATION_SCALE, RoundingMode.HALF_UP);
            BigDecimal consume = cumulativeAllocation.subtract(allocatedQuantity);
            if (consume.signum() < 0 || consume.compareTo(layer.getRemainingQuantity()) > 0) {
                throw new BusinessException("Weighted quantity allocation exceeds a source layer");
            }
            quantities[i] = consume;
            values[i] = consume.signum() == 0 ? BigDecimal.ZERO
                : bookValueForQuantity(layer.getRemainingQuantity(), nvl(layer.getTotalCost()), consume);
            allocatedQuantity = allocatedQuantity.add(consume);
            allocatedValue = allocatedValue.add(values[i]);
        }
        // Rounded quantity allocations may shift a money quantum. Keep the pooled
        // weighted value exact, adjusting only partial layers with actual quantity.
        BigDecimal residual = targetValue.subtract(allocatedValue);
        for (int i = layers.size() - 1; i >= 0 && residual.signum() != 0; i--) {
            if (quantities[i].signum() == 0
                || quantities[i].compareTo(layers.get(i).getRemainingQuantity()) == 0) {
                continue;
            }
            BigDecimal capacity = residual.signum() > 0
                ? nvl(layers.get(i).getTotalCost()).subtract(values[i]) : values[i];
            BigDecimal adjustment = residual.abs().min(capacity);
            if (residual.signum() < 0) adjustment = adjustment.negate();
            values[i] = values[i].add(adjustment);
            residual = residual.subtract(adjustment);
        }
        if (residual.signum() != 0 || allocatedQuantity.compareTo(quantity) != 0) {
            throw new BusinessException("Authoritative weighted value cannot be allocated at quantity precision without revaluation");
        }
        List<LayerConsumption> consumptions = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            if (quantities[i].signum() == 0) continue;
            InventoryCostLayer layer = layers.get(i);
            BigDecimal unitCost = values[i].divide(quantities[i], UNIT_SCALE, RoundingMode.HALF_UP);
            applyBookValueConsumption(layer, quantities[i], values[i]);
            consumptions.add(recordOutboundConsumption(movement, warehouseId, layer, quantities[i], unitCost, values[i]));
        }
        return consumptions;
    }

    private List<LayerConsumption> consumeOrdered(StockMovement movement, UUID warehouseId, List<InventoryCostLayer> layers, BigDecimal quantity) {
        BigDecimal remaining = quantity;
        java.util.ArrayList<LayerConsumption> consumptions = new java.util.ArrayList<>();
        for (InventoryCostLayer layer : layers) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal consume = nvl(layer.getRemainingQuantity()).min(remaining);
            if (consume.signum() <= 0) {
                continue;
            }
            // A rounded unit-price snapshot is not the remaining book value. In
            // particular, final depletion must consume the last monetary quantum.
            BigDecimal totalCost = bookValueForQuantity(layer.getRemainingQuantity(), nvl(layer.getTotalCost()), consume);
            BigDecimal unitCost = totalCost.divide(consume, UNIT_SCALE, RoundingMode.HALF_UP);
            applyBookValueConsumption(layer, consume, totalCost);
            consumptions.add(recordOutboundConsumption(
                movement, warehouseId, layer, consume, unitCost, totalCost));
            remaining = remaining.subtract(consume);
        }
        if (remaining.signum() > 0) {
            throw new BusinessException("Unable to consume cost layers");
        }
        return consumptions;
    }

    private List<LayerConsumption> consumeSpecificBatch(StockMovement movement, UUID warehouseId, List<InventoryCostLayer> layers, BigDecimal quantity) {
        if (movement.getStockBatchId() == null) {
            throw new BusinessException("SPECIFIC_BATCH requires stockBatchId");
        }
        return consumeOrdered(movement, warehouseId, layers, quantity);
    }

    private List<LayerConsumption> consumeWeightedAtUnitCost(
        StockMovement movement,
        UUID warehouseId,
        List<InventoryCostLayer> layers,
        BigDecimal quantity,
        BigDecimal unitCost
    ) {
        if (layers.stream().anyMatch(InventoryCostLayer::isAuthoritativeValue)) {
            throw new BusinessException("Analytical price consumption of authoritative opening layers requires explicit book-value revaluation");
        }
        BigDecimal totalQuantity = layers.stream()
            .map(InventoryCostLayer::getRemainingQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = quantity;
        java.util.ArrayList<LayerConsumption> consumptions = new java.util.ArrayList<>();

        for (int i = 0; i < layers.size() && remaining.signum() > 0; i++) {
            InventoryCostLayer layer = layers.get(i);
            BigDecimal consume = i == layers.size() - 1
                ? remaining
                : quantity.multiply(nvl(layer.getRemainingQuantity())).divide(totalQuantity, QUANTITY_ALLOCATION_SCALE, RoundingMode.HALF_UP);
            consume = consume.min(nvl(layer.getRemainingQuantity())).min(remaining);
            if (consume.signum() <= 0) {
                continue;
            }
            BigDecimal totalCost = consume.multiply(unitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            applyConsumption(layer, consume, totalCost);
            consumptions.add(recordOutboundConsumption(
                movement, warehouseId, layer, consume, unitCost, totalCost));
            remaining = remaining.subtract(consume);
        }
        if (remaining.signum() > 0) {
            throw new BusinessException("Unable to consume analytical cost layers");
        }
        return consumptions;
    }

    private void applyConsumption(InventoryCostLayer layer, BigDecimal quantity, BigDecimal consumedCost) {
        if (layer.isAuthoritativeValue()) {
            applyBookValueConsumption(layer, quantity, consumedCost);
            return;
        }
        BigDecimal remainingQuantity = nvl(layer.getRemainingQuantity()).subtract(quantity).max(BigDecimal.ZERO);
        BigDecimal remainingCost = nvl(layer.getTotalCost()).subtract(nvl(consumedCost)).max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        layer.setRemainingQuantity(remainingQuantity);
        layer.setTotalCost(remainingCost);
        layer.setUnitCost(remainingQuantity.signum() == 0 ? BigDecimal.ZERO : remainingCost.divide(remainingQuantity, UNIT_SCALE, RoundingMode.HALF_UP));
        if (remainingQuantity.signum() == 0) {
            layer.setStatus("CONSUMED");
        }
        costLayerRepository.save(layer);
    }

    private BigDecimal bookValueForQuantity(BigDecimal remainingQuantity, BigDecimal remainingValue, BigDecimal quantity) {
        try {
            return TransferValueSplit.take(remainingQuantity, remainingValue, quantity);
        } catch (IllegalArgumentException error) {
            throw new BusinessException("Invalid authoritative layer quantity or remaining book value");
        }
    }

    private void applyBookValueConsumption(InventoryCostLayer layer, BigDecimal quantity, BigDecimal consumedCost) {
        BigDecimal remainingQuantity = nvl(layer.getRemainingQuantity()).subtract(quantity);
        BigDecimal remainingCost = nvl(layer.getTotalCost()).subtract(consumedCost);
        if (quantity.signum() <= 0 || consumedCost.signum() < 0 || remainingQuantity.signum() < 0
            || remainingCost.signum() < 0 || (remainingQuantity.signum() == 0 && remainingCost.signum() != 0)) {
            throw new BusinessException("Authoritative layer consumption must conserve remaining quantity and book value");
        }
        layer.setRemainingQuantity(remainingQuantity);
        layer.setTotalCost(remainingCost.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY));
        layer.setUnitCost(remainingQuantity.signum() == 0 ? BigDecimal.ZERO.setScale(UNIT_SCALE)
            : remainingCost.divide(remainingQuantity, UNIT_SCALE, RoundingMode.HALF_UP));
        if (remainingQuantity.signum() == 0) layer.setStatus("CONSUMED");
        costLayerRepository.save(layer);
    }

    private LayerConsumption recordOutboundConsumption(
        StockMovement movement,
        UUID warehouseId,
        InventoryCostLayer layer,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost
    ) {
        StockCostMovement costMovement = recordCostMovement(
            movement,
            warehouseId,
            layer.getId(),
            movementTypeForOutbound(movement.getMovementType()),
            quantity,
            unitCost,
            totalCost
        );
        if (costMovement == null || costMovement.getId() == null) {
            throw new BusinessException("Outbound cost movement id was not assigned");
        }
        return new LayerConsumption(
            layer.getId(),
            layer.getParentCostLayerId(),
            layer.getStockBatchId(),
            costMovement.getId(),
            quantity,
            unitCost,
            totalCost,
            layer.getCurrencyCode(),
            layer.getExchangeRate()
        );
    }

    private BigDecimal analyticalUnitCost(StockMovement movement, UUID warehouseId, BigDecimal quantity, ValuationMethod method) {
        BigDecimal totalCost = switch (method) {
            case MARKET_PRICE -> stockValuationService.calculateMarketPriceCost(movement.getMaterialId(), java.util.List.of(warehouseId), quantity);
            case LOWER_OF_COST_OR_MARKET -> stockValuationService.calculateLowerOfCostOrMarketCost(movement.getMaterialId(), java.util.List.of(warehouseId), quantity);
            case NET_REALIZABLE_VALUE -> stockValuationService.calculateNetRealizableValueCost(movement.getMaterialId(), java.util.List.of(warehouseId), quantity);
            default -> BigDecimal.ZERO;
        };
        return quantity.signum() == 0 ? BigDecimal.ZERO : totalCost.divide(quantity, UNIT_SCALE, RoundingMode.HALF_UP);
    }

    private List<InventoryCostLayer> orderedLayers(StockMovement movement, UUID warehouseId, ValuationMethod method) {
        UUID stockBatchId = movement.getStockBatchId();
        if (method == ValuationMethod.SPECIFIC_BATCH && stockBatchId == null) {
            throw new BusinessException("SPECIFIC_BATCH requires stockBatchId");
        }
        // Always acquire row locks in the same order, even for LIFO. Reversing the
        // SELECT lock order allows concurrent FIFO/LIFO commands to deadlock.
        var layers = new ArrayList<>(costLayerRepository.findActiveLayersForUpdateAsc(
            TenantContext.currentTenant(), companyId(movement), movement.getMaterialId(), warehouseId, stockBatchId, BigDecimal.ZERO));
        layers.sort(StockPickingOrder.costLayers(method == ValuationMethod.LIFO
            ? com.enterprise.mm.enums.PickingMethod.LIFO : com.enterprise.mm.enums.PickingMethod.FIFO));
        return layers;
    }

    /** Called before transfer balance locks; uses the same lock order as ordinary outbound costing. */
    @Transactional
    public void lockPickingSources(UUID warehouseId, List<UUID> materialIds) {
        for (UUID materialId : materialIds.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
            ensureOpeningLayers(materialId, warehouseId);
            costLayerRepository.findActiveLayersForUpdateAsc(TenantContext.currentTenant(), CompanyContext.currentCompany(),
                materialId, warehouseId, null, BigDecimal.ZERO);
        }
    }

    private void ensureOpeningLayers(UUID materialId, UUID warehouseId) {
        if (costLayerRepository.existsByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), materialId, warehouseId
        )) {
            return;
        }
        for (StockBalance balance : stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), materialId, warehouseId
        )) {
            if (!CompanyContext.currentCompany().equals(balance.getCompanyId()) || nvl(balance.getQuantity()).signum() <= 0) {
                continue;
            }
            BigDecimal quantity = nvl(balance.getQuantity());
            BigDecimal totalValue = nvl(balance.getTotalValue()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal unitCost = quantity.signum() == 0 ? BigDecimal.ZERO : totalValue.divide(quantity, UNIT_SCALE, RoundingMode.HALF_UP);
            InventoryCostLayer layer = new InventoryCostLayer();
            layer.setCode("OPENING-LAYER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            layer.setName("Opening balance cost layer");
            layer.setStatus(unitCost.signum() > 0 ? "ACTIVE" : "COST_MISSING");
            layer.setCompanyId(balance.getCompanyId());
            layer.setMaterialId(balance.getMaterialId());
            layer.setWarehouseId(balance.getWarehouseId());
            layer.setStockBatchId(balance.getStockBatchId());
            layer.setSourceMovementId(balance.getId());
            layer.setSourceDocumentType("OPENING_BALANCE");
            layer.setSourceDocumentId(balance.getId());
            layer.setLayerType(InventoryCostLayerType.OPENING_BALANCE.name());
            layer.setReceivedQuantity(quantity);
            layer.setRemainingQuantity(quantity);
            layer.setUnitCost(unitCost);
            layer.setTotalCost(totalValue);
            layer.setCurrencyCode("UZS");
            layer.setExchangeRate(BigDecimal.ONE);
            layer.setReceivedAt(balance.getBalanceAt() == null ? LocalDateTime.now() : balance.getBalanceAt());
            costLayerRepository.save(layer);
        }
    }

    private StockCostMovement recordCostMovement(
        StockMovement movement,
        UUID warehouseId,
        UUID costLayerId,
        StockCostMovementType movementType,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost
    ) {
        StockCostMovement costMovement = new StockCostMovement();
        costMovement.setCode("COST-MOV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        costMovement.setName(movementType.name() + " cost movement");
        costMovement.setStatus("POSTED");
        costMovement.setCompanyId(companyId(movement));
        costMovement.setMaterialId(movement.getMaterialId());
        costMovement.setWarehouseId(warehouseId);
        costMovement.setStockMovementId(movement.getId());
        costMovement.setCostLayerId(costLayerId);
        costMovement.setMovementType(movementType.name());
        costMovement.setQuantity(nvl(quantity));
        costMovement.setUnitCost(nvl(unitCost).setScale(UNIT_SCALE, RoundingMode.HALF_UP));
        costMovement.setTotalCost(nvl(totalCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        costMovement.setSourceDocumentType(movement.getReferenceType());
        costMovement.setSourceDocumentId(movement.getReferenceId());
        return stockCostLedgerService.record(new StockCostLedgerService.RecordCommand(
            costMovement.getCompanyId(),
            costMovement.getMaterialId(),
            costMovement.getWarehouseId(),
            costMovement.getStockMovementId(),
            costMovement.getCostLayerId(),
            movementType,
            costMovement.getQuantity(),
            costMovement.getUnitCost(),
            costMovement.getTotalCost(),
            costMovement.getCostCenterId(),
            sourceType(movement.getReferenceType()),
            sourceId(movement.getReferenceId(), movement.getId()),
            movement.getMovementType() == MovementType.TRANSFER ? movement.getId() : null,
            false,
            null,
            null,
            null,
            null
        ));
    }

    private StockCostMovement recordFinancialCostMovement(
        UUID materialId,
        UUID warehouseId,
        UUID costLayerId,
        StockCostMovementType movementType,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        String sourceDocumentType,
        UUID sourceDocumentId
    ) {
        StockCostMovement costMovement = new StockCostMovement();
        costMovement.setCode("COST-MOV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        costMovement.setName(movementType.name() + " cost movement");
        costMovement.setStatus("POSTED");
        costMovement.setCompanyId(CompanyContext.currentCompany());
        costMovement.setMaterialId(materialId);
        costMovement.setWarehouseId(warehouseId);
        costMovement.setCostLayerId(costLayerId);
        costMovement.setMovementType(movementType.name());
        costMovement.setQuantity(nvl(quantity));
        costMovement.setUnitCost(nvl(unitCost).setScale(UNIT_SCALE, RoundingMode.HALF_UP));
        costMovement.setTotalCost(nvl(totalCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        costMovement.setSourceDocumentType(sourceDocumentType);
        costMovement.setSourceDocumentId(sourceDocumentId);
        return stockCostLedgerService.record(new StockCostLedgerService.RecordCommand(
            costMovement.getCompanyId(),
            costMovement.getMaterialId(),
            costMovement.getWarehouseId(),
            null,
            costMovement.getCostLayerId(),
            movementType,
            costMovement.getQuantity(),
            costMovement.getUnitCost(),
            costMovement.getTotalCost(),
            costMovement.getCostCenterId(),
            sourceType(sourceDocumentType),
            sourceDocumentId,
            null,
            false,
            null,
            null,
            null,
            null
        ));
    }

    private StockCostMovementType movementTypeForInbound(InventoryCostLayerType layerType, MovementType movementType) {
        if (layerType == InventoryCostLayerType.TRANSFER_IN) {
            return StockCostMovementType.TRANSFER_IN;
        }
        if (layerType == InventoryCostLayerType.OPENING_BALANCE) {
            return StockCostMovementType.OPENING_BALANCE;
        }
        if (movementType == MovementType.ADJUSTMENT) {
            return StockCostMovementType.ADJUSTMENT;
        }
        return StockCostMovementType.RECEIPT;
    }

    private StockCostMovementType movementTypeForOutbound(MovementType movementType) {
        return switch (movementType) {
            case ISSUE -> StockCostMovementType.ISSUE;
            case WRITE_OFF -> StockCostMovementType.WRITE_OFF;
            case TRANSFER -> StockCostMovementType.TRANSFER_OUT;
            case REVERSAL -> StockCostMovementType.REVERSAL;
            case ADJUSTMENT -> StockCostMovementType.ADJUSTMENT;
            case RECEIPT, RETURN -> StockCostMovementType.RECEIPT;
        };
    }

    private UUID companyId(StockMovement movement) {
        return movement.getCompanyId() == null ? CompanyContext.currentCompany() : movement.getCompanyId();
    }

    private String sourceType(String referenceType) {
        return referenceType == null || referenceType.isBlank() ? "STOCK_MOVEMENT" : referenceType;
    }

    private UUID sourceId(UUID referenceId, UUID movementId) {
        return referenceId == null ? movementId : referenceId;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private InsufficientStockException insufficientStock(
        StockMovement movement,
        UUID warehouseId,
        BigDecimal requested,
        BigDecimal available
    ) {
        var material = materialRepository.findByIdAndIsDeletedFalse(movement.getMaterialId()).orElse(null);
        var warehouse = warehouseRepository.findByIdAndIsDeletedFalse(warehouseId).orElse(null);
        String unit = material == null || material.getUnitOfMeasure() == null
            ? null
            : firstNonBlank(
                material.getUnitOfMeasure().getMeasurementNameShort(),
                material.getUnitOfMeasure().getMeasurementNameShortRu(),
                material.getUnitOfMeasure().getName()
            );
        return new InsufficientStockException(
            movement.getMaterialId(),
            material == null ? null : material.getName(),
            warehouseId,
            warehouse == null ? null : warehouse.getName(),
            requested,
            available,
            unit
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record CostResult(BigDecimal unitCost, BigDecimal totalCost, List<LayerConsumption> consumptions) {
    }

    public record InboundLayerResult(
        UUID costLayerId,
        UUID parentCostLayerId,
        UUID costMovementId,
        BigDecimal quantityDelta,
        BigDecimal valueDelta,
        BigDecimal unitCost,
        String currencyCode,
        BigDecimal exchangeRate
    ) {
    }

    public record LayerConsumption(
        UUID costLayerId,
        UUID parentCostLayerId,
        UUID stockBatchId,
        UUID costMovementId,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        String currencyCode,
        BigDecimal exchangeRate
    ) {
    }

    public record ExactReversalLineage(
        UUID costLayerId,
        UUID parentCostLayerIdSnapshot,
        UUID sourceCostMovementId,
        BigDecimal quantityDelta,
        BigDecimal valueDelta,
        BigDecimal unitCostSnapshot,
        String currencyCodeSnapshot,
        BigDecimal exchangeRateSnapshot
    ) {
    }

    public record ExactReversalResult(
        UUID costLayerId,
        UUID parentCostLayerIdSnapshot,
        UUID costMovementId,
        BigDecimal quantityDelta,
        BigDecimal valueDelta,
        BigDecimal unitCostSnapshot,
        String currencyCodeSnapshot,
        BigDecimal exchangeRateSnapshot
    ) {
    }

    public record LandedCostAdjustment(UUID costLayerId, UUID stockBatchId, BigDecimal quantity, BigDecimal unitCostIncrease, BigDecimal totalCostIncrease) {
    }
}
