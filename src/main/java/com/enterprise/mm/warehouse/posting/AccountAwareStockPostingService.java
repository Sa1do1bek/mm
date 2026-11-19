package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.service.StockBalanceMutationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single transactional coordinator connecting physical stock balances,
 * cost valuation layers, and account-aware posting evidence.
 * Enforces deterministic locking, expected versions, and atomic rollbacks.
 */
@Service
public class AccountAwareStockPostingService {
    private final PostingJournalService postingJournalService;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockBalanceMutationService stockBalanceMutationService;
    private final PostingEvidenceStore postingEvidenceStore;
    private final com.enterprise.mm.warehouse.custody.OwnerEligibilityService ownerEligibilityService;

    @org.springframework.beans.factory.annotation.Autowired
    public AccountAwareStockPostingService(
        PostingJournalService postingJournalService,
        StockBalanceRepository stockBalanceRepository,
        StockBalanceMutationService stockBalanceMutationService,
        PostingEvidenceStore postingEvidenceStore,
        com.enterprise.mm.warehouse.custody.OwnerEligibilityService ownerEligibilityService
    ) {
        this.postingJournalService = postingJournalService;
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockBalanceMutationService = stockBalanceMutationService;
        this.postingEvidenceStore = postingEvidenceStore;
        this.ownerEligibilityService = ownerEligibilityService != null
            ? ownerEligibilityService
            : new com.enterprise.mm.warehouse.custody.OwnerEligibilityService();
    }

    public AccountAwareStockPostingService(
        PostingJournalService postingJournalService,
        StockBalanceRepository stockBalanceRepository,
        StockBalanceMutationService stockBalanceMutationService,
        PostingEvidenceStore postingEvidenceStore
    ) {
        this(postingJournalService, stockBalanceRepository, stockBalanceMutationService, postingEvidenceStore, new com.enterprise.mm.warehouse.custody.OwnerEligibilityService());
    }

    public AccountAwareStockPostingService(
        PostingJournalService postingJournalService,
        StockBalanceRepository stockBalanceRepository,
        StockBalanceMutationService stockBalanceMutationService
    ) {
        this(postingJournalService, stockBalanceRepository, stockBalanceMutationService, null, new com.enterprise.mm.warehouse.custody.OwnerEligibilityService());
    }

    public record IssueRequest(
        PostingCommand command,
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        UUID stockBatchId,
        UUID ownerId,
        String stockCategory,
        String disposition,
        String accountCode,
        String baseUnitCode,
        BigDecimal quantity,
        BigDecimal unitCost,
        String currencyCode,
        String sourceReference,
        BigDecimal totalAmount
    ) {
        public IssueRequest(
            PostingCommand command,
            UUID materialId,
            UUID warehouseId,
            UUID storageLocationId,
            UUID storageBinId,
            UUID stockBatchId,
            UUID ownerId,
            String stockCategory,
            String disposition,
            String accountCode,
            String baseUnitCode,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currencyCode,
            String sourceReference
        ) {
            this(command, materialId, warehouseId, storageLocationId, storageBinId, stockBatchId,
                 ownerId, stockCategory, disposition, accountCode, baseUnitCode, quantity, unitCost,
                 currencyCode, sourceReference, null);
        }
    }

    public record ReceiptRequest(
        PostingCommand command,
        UUID materialId,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        UUID stockBatchId,
        UUID ownerId,
        String stockCategory,
        String disposition,
        String accountCode,
        String baseUnitCode,
        BigDecimal quantity,
        BigDecimal unitCost,
        String currencyCode,
        String sourceReference,
        BigDecimal totalAmount
    ) {
        public ReceiptRequest(
            PostingCommand command,
            UUID materialId,
            UUID warehouseId,
            UUID storageLocationId,
            UUID storageBinId,
            UUID stockBatchId,
            UUID ownerId,
            String stockCategory,
            String disposition,
            String accountCode,
            String baseUnitCode,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currencyCode,
            String sourceReference
        ) {
            this(command, materialId, warehouseId, storageLocationId, storageBinId, stockBatchId,
                 ownerId, stockCategory, disposition, accountCode, baseUnitCode, quantity, unitCost,
                 currencyCode, sourceReference, null);
        }
    }

    @Transactional
    public PostingReceipt postIssue(IssueRequest request) {
        validateRequest(request.command(), request.materialId(), request.warehouseId(),
            request.baseUnitCode(), request.quantity());

        if (request.quantity().signum() <= 0) {
            throw new BadRequestException("Issue quantity must be strictly positive");
        }
        if (request.unitCost() != null && request.unitCost().stripTrailingZeros().scale() > 4) {
            throw new BadRequestException("Unit cost exceeds maximum supported precision of 4 decimal places: " + request.unitCost());
        }
        if (request.totalAmount() != null && request.totalAmount().stripTrailingZeros().scale() > 4) {
            throw new BadRequestException("Total amount exceeds maximum supported precision of 4 decimal places: " + request.totalAmount());
        }

        String effectiveCategory = request.stockCategory() != null ? request.stockCategory() : "OWNED";
        ownerEligibilityService.validateEligibility(request.ownerId(), effectiveCategory, request.accountCode());

        return postingJournalService.execute(request.command(), rule -> {
            String resolvedAccount = resolveAccount(rule, request.accountCode());
            ownerEligibilityService.validateEligibility(request.ownerId(), effectiveCategory, resolvedAccount);
            String resolvedCurrency = resolveCurrency(rule, request.currencyCode());

            BigDecimal quantity = request.quantity().setScale(7, RoundingMode.HALF_UP);

            if (postingEvidenceStore != null) {
                postingEvidenceStore.lockAccountPosition(TenantContext.currentTenant(), CompanyContext.currentCompany(),
                    request.materialId(), request.warehouseId(), resolvedAccount, request.ownerId(), effectiveCategory);
            }

            // Lock the account scope before its physical partition and availability check.
            StockBalance balance = lockStockBalance(request.materialId(), request.warehouseId(),
                request.storageLocationId(), request.storageBinId(), request.stockBatchId(),
                request.ownerId(), effectiveCategory);

            // Account and owner level stock isolation check under partition lock (Blocker 6)
            if (postingEvidenceStore != null) {
                String tenantId = TenantContext.currentTenant();
                UUID companyId = CompanyContext.currentCompany();
                BigDecimal available = postingEvidenceStore.getAccountAndOwnerAvailableQuantityForUnit(
                    tenantId, companyId, request.materialId(), request.warehouseId(),
                    resolvedAccount, request.ownerId(), effectiveCategory,
                    request.baseUnitCode(), resolvedCurrency
                );
                if (available == null) {
                    available = BigDecimal.ZERO;
                }
                if (available.compareTo(quantity) < 0) {
                    throw new InsufficientStockException(
                        "Insufficient stock for owner and account: requested " + quantity + " but only " + available
                        + " available on account " + resolvedAccount + " for owner " + (request.ownerId() != null ? request.ownerId() : companyId)
                        + " (" + effectiveCategory + ")",
                        request.materialId(), null, request.warehouseId(), null, quantity, available, request.baseUnitCode()
                    );
                }
            }

            BigDecimal unitCost = request.unitCost() == null
                ? calculateAverageUnitCost(balance)
                : request.unitCost().setScale(4, RoundingMode.HALF_UP);
            BigDecimal consumedCost = request.totalAmount() != null
                ? request.totalAmount().setScale(4, RoundingMode.HALF_UP)
                : quantity.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);

            // The last unit consumes the exact remaining value, including earlier rounding residuals.
            if (request.totalAmount() == null && balance.getQuantity() != null
                && quantity.compareTo(balance.getQuantity()) == 0 && balance.getTotalValue() != null) {
                consumedCost = balance.getTotalValue().setScale(4, RoundingMode.UNNECESSARY);
            }

            // Execute in-memory mutation on physical stock balance
            stockBalanceMutationService.decreaseOnHand(balance, quantity, consumedCost, false);
            stockBalanceRepository.save(balance);

            // Plan atomic effects: STOCK, ACCOUNT, COST
            List<PostingEffect> effects = new ArrayList<>();

            effects.add(new PostingEffect(
                PostingEffectType.STOCK,
                request.materialId(),
                request.warehouseId(),
                resolvedAccount,
                request.baseUnitCode(),
                quantity.negate(),
                BigDecimal.ZERO,
                null,
                request.storageLocationId(),
                request.storageBinId(),
                request.stockBatchId(),
                request.ownerId(),
                request.stockCategory() != null ? request.stockCategory() : "OWNED",
                request.disposition() != null ? request.disposition() : "AVAILABLE",
                request.sourceReference()
            ));

            effects.add(new PostingEffect(
                PostingEffectType.ACCOUNT,
                request.materialId(),
                request.warehouseId(),
                resolvedAccount,
                request.baseUnitCode(),
                quantity.negate(),
                consumedCost.negate(),
                resolvedCurrency,
                request.storageLocationId(),
                request.storageBinId(),
                request.stockBatchId(),
                request.ownerId(),
                request.stockCategory() != null ? request.stockCategory() : "OWNED",
                request.disposition() != null ? request.disposition() : "AVAILABLE",
                request.sourceReference()
            ));

            if (consumedCost.signum() != 0) {
                effects.add(new PostingEffect(
                    PostingEffectType.COST,
                    request.materialId(),
                    request.warehouseId(),
                    resolvedAccount,
                    null,
                    BigDecimal.ZERO,
                    consumedCost.negate(),
                    resolvedCurrency,
                    request.storageLocationId(),
                    request.storageBinId(),
                    request.stockBatchId(),
                    request.ownerId(),
                    request.stockCategory() != null ? request.stockCategory() : "OWNED",
                    request.disposition() != null ? request.disposition() : "AVAILABLE",
                    request.sourceReference()
                ));
            }

            return effects;
        });
    }

    @Transactional
    public PostingReceipt postReceipt(ReceiptRequest request) {
        validateRequest(request.command(), request.materialId(), request.warehouseId(),
            request.baseUnitCode(), request.quantity());

        if (request.quantity().signum() <= 0) {
            throw new BadRequestException("Receipt quantity must be strictly positive");
        }
        if (request.unitCost() != null && request.unitCost().stripTrailingZeros().scale() > 4) {
            throw new BadRequestException("Unit cost exceeds maximum supported precision of 4 decimal places: " + request.unitCost());
        }
        if (request.totalAmount() != null && request.totalAmount().stripTrailingZeros().scale() > 4) {
            throw new BadRequestException("Total amount exceeds maximum supported precision of 4 decimal places: " + request.totalAmount());
        }

        String effectiveCategory = request.stockCategory() != null ? request.stockCategory() : "OWNED";
        ownerEligibilityService.validateEligibility(request.ownerId(), effectiveCategory, request.accountCode());

        return postingJournalService.execute(request.command(), rule -> {
            String resolvedAccount = resolveAccount(rule, request.accountCode());
            ownerEligibilityService.validateEligibility(request.ownerId(), effectiveCategory, resolvedAccount);
            String resolvedCurrency = resolveCurrency(rule, request.currencyCode());

            if (postingEvidenceStore != null) {
                postingEvidenceStore.lockAccountPosition(TenantContext.currentTenant(), CompanyContext.currentCompany(),
                    request.materialId(), request.warehouseId(), resolvedAccount, request.ownerId(), effectiveCategory);
            }

            StockBalance balance = getOrCreateStockBalance(request.materialId(), request.warehouseId(),
                request.storageLocationId(), request.storageBinId(), request.stockBatchId(),
                request.ownerId(), effectiveCategory);

            BigDecimal quantity = request.quantity().setScale(7, RoundingMode.HALF_UP);
            BigDecimal unitCost = request.unitCost() == null
                ? BigDecimal.ZERO
                : request.unitCost().setScale(4, RoundingMode.HALF_UP);
            BigDecimal totalValue = request.totalAmount() != null
                ? request.totalAmount().setScale(4, RoundingMode.HALF_UP)
                : quantity.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);

            stockBalanceMutationService.increaseOnHand(balance, quantity, unitCost, totalValue);
            stockBalanceRepository.save(balance);

            List<PostingEffect> effects = new ArrayList<>();

            effects.add(new PostingEffect(
                PostingEffectType.STOCK,
                request.materialId(),
                request.warehouseId(),
                resolvedAccount,
                request.baseUnitCode(),
                quantity,
                BigDecimal.ZERO,
                null,
                request.storageLocationId(),
                request.storageBinId(),
                request.stockBatchId(),
                request.ownerId(),
                request.stockCategory() != null ? request.stockCategory() : "OWNED",
                request.disposition() != null ? request.disposition() : "AVAILABLE",
                request.sourceReference()
            ));

            effects.add(new PostingEffect(
                PostingEffectType.ACCOUNT,
                request.materialId(),
                request.warehouseId(),
                resolvedAccount,
                request.baseUnitCode(),
                quantity,
                totalValue,
                resolvedCurrency,
                request.storageLocationId(),
                request.storageBinId(),
                request.stockBatchId(),
                request.ownerId(),
                request.stockCategory() != null ? request.stockCategory() : "OWNED",
                request.disposition() != null ? request.disposition() : "AVAILABLE",
                request.sourceReference()
            ));

            if (totalValue.signum() != 0) {
                effects.add(new PostingEffect(
                    PostingEffectType.COST,
                    request.materialId(),
                    request.warehouseId(),
                    resolvedAccount,
                    null,
                    BigDecimal.ZERO,
                    totalValue,
                    resolvedCurrency,
                    request.storageLocationId(),
                    request.storageBinId(),
                    request.stockBatchId(),
                    request.ownerId(),
                    request.stockCategory() != null ? request.stockCategory() : "OWNED",
                    request.disposition() != null ? request.disposition() : "AVAILABLE",
                    request.sourceReference()
                ));
            }

            return effects;
        });
    }

    private StockBalance lockStockBalance(UUID materialId, UUID warehouseId,
                                          UUID storageLocationId, UUID storageBinId, UUID stockBatchId,
                                          UUID ownerId, String stockCategory) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        String effectiveCategory = stockCategory != null ? stockCategory : "OWNED";

        boolean locationEmpty = storageLocationId == null;
        boolean binEmpty = storageBinId == null;
        boolean batchEmpty = stockBatchId == null;

        return stockBalanceRepository.findPartitionedBalanceForUpdate(
            tenantId, companyId, materialId, warehouseId,
            storageLocationId, storageBinId, stockBatchId,
            ownerId, effectiveCategory
        ).or(() -> stockBalanceRepository.findBalanceForUpdate(
            tenantId, materialId, warehouseId,
            locationEmpty, storageLocationId,
            binEmpty, storageBinId,
            batchEmpty, stockBatchId
        )).orElseThrow(() -> new InsufficientStockException("No stock balance found to issue from",
            materialId, null, warehouseId, null, BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    private StockBalance getOrCreateStockBalance(UUID materialId, UUID warehouseId,
                                                UUID storageLocationId, UUID storageBinId, UUID stockBatchId,
                                                UUID ownerId, String stockCategory) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        String effectiveCategory = stockCategory != null ? stockCategory : "OWNED";

        boolean locationEmpty = storageLocationId == null;
        boolean binEmpty = storageBinId == null;
        boolean batchEmpty = stockBatchId == null;

        return stockBalanceRepository.findPartitionedBalanceForUpdate(
            tenantId, companyId, materialId, warehouseId,
            storageLocationId, storageBinId, stockBatchId,
            ownerId, effectiveCategory
        ).or(() -> stockBalanceRepository.findBalanceForUpdate(
            tenantId, materialId, warehouseId,
            locationEmpty, storageLocationId,
            binEmpty, storageBinId,
            batchEmpty, stockBatchId
        )).orElseGet(() -> {
            var b = new StockBalance();
            b.setTenantId(tenantId);
            b.setCompanyId(companyId);
            b.setMaterialId(materialId);
            b.setWarehouseId(warehouseId);
            b.setStorageLocationId(storageLocationId);
            b.setStorageBinId(storageBinId);
            b.setStockBatchId(stockBatchId);
            b.setOwnerPartyId(ownerId);
            b.setStockCategory(effectiveCategory);
            b.setQuantity(BigDecimal.ZERO);
            b.setAvailableQuantity(BigDecimal.ZERO);
            b.setReservedQuantity(BigDecimal.ZERO);
            b.setQuarantineQuantity(BigDecimal.ZERO);
            b.setTotalValue(BigDecimal.ZERO);
            return b;
        });
    }

    private BigDecimal calculateAverageUnitCost(StockBalance balance) {
        if (balance.getQuantity() != null && balance.getQuantity().signum() > 0
            && balance.getTotalValue() != null && balance.getTotalValue().signum() > 0) {
            return balance.getTotalValue().divide(balance.getQuantity(), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private String resolveAccount(PostingRule rule, String requestedAccount) {
        String req = requestedAccount != null && !requestedAccount.isBlank() ? requestedAccount.trim() : null;
        String ruleAcct = rule != null && rule.accountCode() != null && !rule.accountCode().isBlank() ? rule.accountCode().trim() : null;

        if (req != null && ruleAcct != null) {
            if (!req.equalsIgnoreCase(ruleAcct)) {
                throw new BadRequestException("Posting rule account mismatch: rule specifies account " + ruleAcct + " but requested account is " + req);
            }
            return req;
        }
        if (req != null) {
            return req;
        }
        if (ruleAcct != null) {
            return ruleAcct;
        }
        throw new BadRequestException("Posting rule has no account code and no account was provided");
    }

    private String resolveCurrency(PostingRule rule, String requestedCurrency) {
        if (rule != null && rule.currencyCode() != null && !rule.currencyCode().isBlank()) {
            if (requestedCurrency != null && !requestedCurrency.isBlank()
                && !rule.currencyCode().equalsIgnoreCase(requestedCurrency)) {
                throw new BadRequestException("Posting rule currency does not match requested currency");
            }
            return rule.currencyCode();
        }
        if (requestedCurrency != null && !requestedCurrency.isBlank()) {
            return requestedCurrency;
        }
        return "UZS";
    }

    private void validateRequest(PostingCommand command, UUID materialId, UUID warehouseId,
                                 String baseUnitCode, BigDecimal quantity) {
        if (command == null) {
            throw new BadRequestException("Posting command is required");
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        if (tenantId == null || tenantId.isBlank() || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required for warehouse posting");
        }
        if (materialId == null || warehouseId == null) {
            throw new BadRequestException("Material ID and Warehouse ID are required");
        }
        if (baseUnitCode == null || baseUnitCode.isBlank()) {
            throw new BadRequestException("Base unit code is required");
        }
        if (quantity == null) {
            throw new BadRequestException("Quantity is required");
        }
        if (quantity.stripTrailingZeros().scale() > 7) {
            throw new BadRequestException("Quantity exceeds maximum supported precision of 7 decimal places: " + quantity);
        }
    }
}
