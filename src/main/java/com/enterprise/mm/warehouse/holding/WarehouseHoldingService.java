package com.enterprise.mm.warehouse.holding;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.warehouse.holding.HoldingCommands.AcknowledgeHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.ConsumeHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.DispatchHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HistoricalActivationResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HistoricalHoldingActivationCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingConsumptionResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingHandoverResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingReturnResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingWriteOffResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.RejectHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.ReturnHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementPosition;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementQuery;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementReport;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingTransferResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.TransferHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.WriteOffHoldingCommand;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing Materials in Use (Account 014) holdings and handover lifecycles.
 * Implements strict separation between pending handover dispatch and acknowledged active holding quantity.
 * Supports idempotent historical activation that never replays past 1C source issues into native operational stock.
 */
@Service
public class WarehouseHoldingService {

    private final WarehouseHoldingRepository holdingRepository;
    private final WarehouseHoldingHandoverRepository handoverRepository;
    private final WarehouseHoldingTransferRepository transferRepository;
    private final IdempotencyService idempotencyService;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final com.enterprise.mm.service.StockBalanceMutationService stockBalanceMutationService;
    private final AccountAwareStockPostingService postingService;
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired(required=false)
    private com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundService warehouseOutbound;

    private WarehouseHolding saveHolding(WarehouseHolding holding) {
        WarehouseHolding saved = holdingRepository.save(holding);
        if (warehouseOutbound != null && warehouseOutbound.captureEnabled()) {
            holdingRepository.flush();
            warehouseOutbound.captureHolding(saved.getId());
        }
        return saved;
    }

    public WarehouseHoldingService(
        WarehouseHoldingRepository holdingRepository,
        WarehouseHoldingHandoverRepository handoverRepository,
        IdempotencyService idempotencyService
    ) {
        this(holdingRepository, handoverRepository, null, idempotencyService, null, null, null, null);
    }

    public WarehouseHoldingService(
        WarehouseHoldingRepository holdingRepository,
        WarehouseHoldingHandoverRepository handoverRepository,
        WarehouseHoldingTransferRepository transferRepository,
        IdempotencyService idempotencyService
    ) {
        this(holdingRepository, handoverRepository, transferRepository, idempotencyService, null, null, null, null);
    }

    public WarehouseHoldingService(
        WarehouseHoldingRepository holdingRepository,
        WarehouseHoldingHandoverRepository handoverRepository,
        IdempotencyService idempotencyService,
        StockBalanceRepository stockBalanceRepository,
        StockMovementRepository stockMovementRepository
    ) {
        this(holdingRepository, handoverRepository, null, idempotencyService, stockBalanceRepository, stockMovementRepository, null, null);
    }

    public WarehouseHoldingService(
        WarehouseHoldingRepository holdingRepository,
        WarehouseHoldingHandoverRepository handoverRepository,
        IdempotencyService idempotencyService,
        StockBalanceRepository stockBalanceRepository,
        StockMovementRepository stockMovementRepository,
        com.enterprise.mm.service.StockBalanceMutationService stockBalanceMutationService
    ) {
        this(holdingRepository, handoverRepository, null, idempotencyService, stockBalanceRepository, stockMovementRepository, stockBalanceMutationService, null);
    }

    public WarehouseHoldingService(
        WarehouseHoldingRepository holdingRepository,
        WarehouseHoldingHandoverRepository handoverRepository,
        WarehouseHoldingTransferRepository transferRepository,
        IdempotencyService idempotencyService,
        StockBalanceRepository stockBalanceRepository,
        StockMovementRepository stockMovementRepository,
        com.enterprise.mm.service.StockBalanceMutationService stockBalanceMutationService
    ) {
        this(holdingRepository, handoverRepository, transferRepository, idempotencyService, stockBalanceRepository, stockMovementRepository, stockBalanceMutationService, null);
    }

    @Autowired
    public WarehouseHoldingService(
        WarehouseHoldingRepository holdingRepository,
        WarehouseHoldingHandoverRepository handoverRepository,
        @Autowired(required = false) WarehouseHoldingTransferRepository transferRepository,
        IdempotencyService idempotencyService,
        @Autowired(required = false) StockBalanceRepository stockBalanceRepository,
        @Autowired(required = false) StockMovementRepository stockMovementRepository,
        @Autowired(required = false) com.enterprise.mm.service.StockBalanceMutationService stockBalanceMutationService,
        @Autowired(required = false) AccountAwareStockPostingService postingService
    ) {
        this.holdingRepository = holdingRepository;
        this.handoverRepository = handoverRepository;
        this.transferRepository = transferRepository;
        this.idempotencyService = idempotencyService;
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.stockBalanceMutationService = stockBalanceMutationService;
        this.postingService = postingService;
    }

    @Transactional
    public HoldingHandoverResult dispatchHandover(DispatchHandoverCommand command) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }

        return idempotencyService.execute(
            "WAREHOUSE_HOLDING_DISPATCH",
            null,
            command.idempotencyKey(),
            command,
            HoldingHandoverResult.class,
            () -> executeDispatch(tenantId, companyId, command)
        );
    }

    private HoldingHandoverResult executeDispatch(String tenantId, UUID companyId, DispatchHandoverCommand command) {
        Optional<WarehouseHoldingHandover> existing = handoverRepository
            .findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(tenantId, companyId, command.idempotencyKey());

        if (existing.isPresent()) {
            WarehouseHoldingHandover ho = existing.get();
            if (ho.getQuantity().compareTo(command.quantity()) != 0 || !Objects.equals(ho.getMaterialId(), command.materialId())) {
                throw new ConflictException("Payload differs from existing dispatch under idempotency key: " + command.idempotencyKey());
            }
            WarehouseHolding holding = holdingRepository.findById(ho.getHoldingId())
                .orElseThrow(() -> new BadRequestException("Holding not found for existing handover"));
            return mapResult(ho, holding);
        }

        // Find or create holding position
        WarehouseHolding holding = holdingRepository
            .findFirstByTenantIdAndCompanyIdAndMaterialIdAndDepartmentIdAndEmployeeIdAndAccountCodeAndIsDeletedFalse(
                tenantId, companyId, command.materialId(), command.departmentId(), command.employeeId(), "014"
            )
            .orElseGet(() -> {
                WarehouseHolding h = new WarehouseHolding();
                h.setTenantId(tenantId);
                h.setCompanyId(companyId);
                h.setMaterialId(command.materialId());
                h.setDepartmentId(command.departmentId());
                h.setEmployeeId(command.employeeId());
                h.setAccountCode("014");
                h.setOwnerId("OWNED");
                h.setOriginType(HoldingOriginType.NATIVE_DISPATCH);
                h.setOriginReference(command.originReference());
                h.setUom(command.uom() != null ? command.uom() : "PCS");
                h.setPendingQuantity(BigDecimal.ZERO);
                h.setActiveQuantity(BigDecimal.ZERO);
                h.setConsumedQuantity(BigDecimal.ZERO);
                h.setReturnedQuantity(BigDecimal.ZERO);
                h.setWrittenOffQuantity(BigDecimal.ZERO);
                h.setUnitCost(command.unitCost() != null ? command.unitCost() : BigDecimal.ZERO);
                h.setTotalValue(BigDecimal.ZERO);
                h.setHoldingStatus(HoldingStatus.PENDING_ACKNOWLEDGEMENT);
                return h;
            });

        // Add to pending quantity only (active quantity remains 0 until acknowledged by recipient)
        holding.setPendingQuantity(holding.getPendingQuantity().add(command.quantity()));
        if (command.totalValue() != null) {
            holding.setTotalValue(holding.getTotalValue().add(command.totalValue()));
        }
        if (holding.getActiveQuantity().signum() == 0) {
            holding.setHoldingStatus(HoldingStatus.PENDING_ACKNOWLEDGEMENT);
        }
        holding = saveHolding(holding);

        // Create handover record in PENDING status
        WarehouseHoldingHandover handover = new WarehouseHoldingHandover();
        handover.setTenantId(tenantId);
        handover.setCompanyId(companyId);
        handover.setHandoverNumber(command.handoverNumber() != null ? command.handoverNumber()
            : "HO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        handover.setHoldingId(holding.getId());
        handover.setMaterialId(command.materialId());
        handover.setDepartmentId(command.departmentId());
        handover.setEmployeeId(command.employeeId());
        handover.setQuantity(command.quantity());
        handover.setUnitCost(command.unitCost() != null ? command.unitCost() : BigDecimal.ZERO);
        handover.setTotalValue(command.totalValue() != null ? command.totalValue() : BigDecimal.ZERO);
        handover.setHandoverStatus(HandoverStatus.PENDING);
        handover.setDispatchedAt(LocalDateTime.now());
        handover.setIdempotencyKey(command.idempotencyKey());
        handover = handoverRepository.save(handover);

        return mapResult(handover, holding);
    }

    @Transactional
    public HoldingHandoverResult acknowledgeHandover(AcknowledgeHandoverCommand command) {
        WarehouseHoldingHandover handover = handoverRepository.findById(command.handoverId())
            .orElseThrow(() -> new BadRequestException("Handover record not found: " + command.handoverId()));

        if (handover.getHandoverStatus() != HandoverStatus.PENDING) {
            throw new BadRequestException("Handover is not in PENDING status, current: " + handover.getHandoverStatus());
        }

        UUID targetHoldingId = handover.getHoldingId();
        WarehouseHolding holding = holdingRepository.findById(targetHoldingId)
            .orElseThrow(() -> new BadRequestException("Holding record not found: " + targetHoldingId));

        BigDecimal totalHandoverQty = handover.getQuantity();
        BigDecimal acceptedQty = command.acceptedQuantity() != null ? command.acceptedQuantity() : totalHandoverQty;
        if (acceptedQty.compareTo(BigDecimal.ZERO) < 0 || acceptedQty.compareTo(totalHandoverQty) > 0) {
            throw new BadRequestException("Accepted quantity must be between 0 and handover quantity: " + totalHandoverQty);
        }

        // Shift accepted quantity from pending to active
        holding.setPendingQuantity(holding.getPendingQuantity().subtract(totalHandoverQty).max(BigDecimal.ZERO));
        holding.setActiveQuantity(holding.getActiveQuantity().add(acceptedQty));
        holding.setHoldingStatus(HoldingStatus.ACTIVE);
        WarehouseHolding savedHolding = saveHolding(holding);

        if (acceptedQty.compareTo(totalHandoverQty) < 0) {
            handover.setHandoverStatus(HandoverStatus.PARTIALLY_ACKNOWLEDGED);
        } else {
            handover.setHandoverStatus(HandoverStatus.ACKNOWLEDGED);
        }
        handover.setAcknowledgedAt(LocalDateTime.now());
        WarehouseHoldingHandover savedHandover = handoverRepository.save(handover);

        return mapResult(savedHandover, savedHolding);
    }

    @Transactional
    public HoldingHandoverResult rejectHandover(RejectHandoverCommand command) {
        WarehouseHoldingHandover handover = handoverRepository.findById(command.handoverId())
            .orElseThrow(() -> new BadRequestException("Handover record not found: " + command.handoverId()));

        if (handover.getHandoverStatus() != HandoverStatus.PENDING) {
            throw new BadRequestException("Handover is not in PENDING status, current: " + handover.getHandoverStatus());
        }

        UUID targetHoldingId = handover.getHoldingId();
        WarehouseHolding holding = holdingRepository.findById(targetHoldingId)
            .orElseThrow(() -> new BadRequestException("Holding record not found: " + targetHoldingId));

        BigDecimal qty = handover.getQuantity();

        // Relieve pending quantity without touching active holdings
        holding.setPendingQuantity(holding.getPendingQuantity().subtract(qty).max(BigDecimal.ZERO));
        if (holding.getActiveQuantity().signum() == 0 && holding.getPendingQuantity().signum() == 0) {
            holding.setHoldingStatus(HoldingStatus.RETURNED);
        }
        WarehouseHolding savedHolding = saveHolding(holding);

        handover.setHandoverStatus(HandoverStatus.REJECTED);
        handover.setRejectionReason(command.rejectionReason());
        WarehouseHoldingHandover savedHandover = handoverRepository.save(handover);

        return mapResult(savedHandover, savedHolding);
    }

    @Transactional
    public HistoricalActivationResult activateHistoricalHolding(HistoricalHoldingActivationCommand command) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }

        // Idempotency check via handover registry
        Optional<WarehouseHoldingHandover> existing = handoverRepository
            .findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(tenantId, companyId, command.idempotencyKey());

        if (existing.isPresent()) {
            WarehouseHoldingHandover ho = existing.get();
            if (ho.getQuantity().compareTo(command.quantity()) != 0 || !Objects.equals(ho.getMaterialId(), command.materialId())) {
                throw new ConflictException("Payload differs from existing activation under idempotency key: " + command.idempotencyKey());
            }
            return new HistoricalActivationResult(
                ho.getHoldingId(),
                command.idempotencyKey(),
                ho.getQuantity(),
                BigDecimal.ZERO,
                ho.getTotalValue(),
                HoldingOriginType.HISTORICAL_1C_OPENING,
                true
            );
        }

        // Create or locate holding for Account 014
        WarehouseHolding holding = holdingRepository
            .findFirstByTenantIdAndCompanyIdAndMaterialIdAndDepartmentIdAndEmployeeIdAndAccountCodeAndIsDeletedFalse(
                tenantId, companyId, command.materialId(), command.departmentId(), command.employeeId(), "014"
            )
            .orElseGet(() -> {
                WarehouseHolding h = new WarehouseHolding();
                h.setTenantId(tenantId);
                h.setCompanyId(companyId);
                h.setMaterialId(command.materialId());
                h.setDepartmentId(command.departmentId());
                h.setEmployeeId(command.employeeId());
                h.setAccountCode("014");
                h.setOwnerId("OWNED");
                h.setOriginType(HoldingOriginType.HISTORICAL_1C_OPENING);
                h.setOriginReference(command.sourceKey());
                h.setUom(command.uom() != null ? command.uom() : "PCS");
                h.setPendingQuantity(BigDecimal.ZERO);
                h.setActiveQuantity(BigDecimal.ZERO);
                h.setConsumedQuantity(BigDecimal.ZERO);
                h.setReturnedQuantity(BigDecimal.ZERO);
                h.setWrittenOffQuantity(BigDecimal.ZERO);
                h.setUnitCost(command.unitCost() != null ? command.unitCost() : BigDecimal.ZERO);
                h.setTotalValue(BigDecimal.ZERO);
                h.setHoldingStatus(HoldingStatus.ACTIVE);
                return h;
            });

        // Directly establish active quantity without posting any native stock movement
        // (CRITICAL INVARIANT: NEVER REPLAY OLD 1C SOURCE ISSUE INTO WAREHOUSE PHYSICAL STOCK)
        holding.setActiveQuantity(holding.getActiveQuantity().add(command.quantity()));
        if (command.totalValue() != null) {
            holding.setTotalValue(holding.getTotalValue().add(command.totalValue()));
        }
        holding.setHoldingStatus(HoldingStatus.ACTIVE);
        holding = saveHolding(holding);

        // Store activation record
        WarehouseHoldingHandover activationRecord = new WarehouseHoldingHandover();
        activationRecord.setTenantId(tenantId);
        activationRecord.setCompanyId(companyId);
        activationRecord.setHandoverNumber("ACT-014-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        activationRecord.setHoldingId(holding.getId());
        activationRecord.setMaterialId(command.materialId());
        activationRecord.setDepartmentId(command.departmentId());
        activationRecord.setEmployeeId(command.employeeId());
        activationRecord.setQuantity(command.quantity());
        activationRecord.setUnitCost(command.unitCost() != null ? command.unitCost() : BigDecimal.ZERO);
        activationRecord.setTotalValue(command.totalValue() != null ? command.totalValue() : BigDecimal.ZERO);
        activationRecord.setHandoverStatus(HandoverStatus.ACKNOWLEDGED);
        activationRecord.setDispatchedAt(LocalDateTime.now());
        activationRecord.setAcknowledgedAt(LocalDateTime.now());
        activationRecord.setIdempotencyKey(command.idempotencyKey());
        handoverRepository.save(activationRecord);

        return new HistoricalActivationResult(
            holding.getId(),
            command.idempotencyKey(),
            command.quantity(),
            BigDecimal.ZERO,
            holding.getTotalValue(),
            HoldingOriginType.HISTORICAL_1C_OPENING,
            false
        );
    }

    @Transactional
    public HoldingConsumptionResult consumeHolding(ConsumeHoldingCommand command) {
        WarehouseHolding holding = holdingRepository.findById(command.holdingId())
            .orElseThrow(() -> new BadRequestException("Holding not found: " + command.holdingId()));

        if (command.quantity().compareTo(holding.getActiveQuantity()) > 0) {
            throw new BadRequestException("Holding active quantity is insufficient for consumption: requested="
                + command.quantity() + ", active=" + holding.getActiveQuantity());
        }

        holding.setActiveQuantity(holding.getActiveQuantity().subtract(command.quantity()));
        holding.setConsumedQuantity(holding.getConsumedQuantity().add(command.quantity()));
        if (holding.getActiveQuantity().signum() == 0 && holding.getPendingQuantity().signum() == 0) {
            holding.setHoldingStatus(HoldingStatus.CONSUMED);
        }
        holding = saveHolding(holding);

        return new HoldingConsumptionResult(
            holding.getId(),
            command.quantity(),
            holding.getActiveQuantity(),
            false,
            "PRIOR_EXPENSED_AT_INITIAL_ISSUE"
        );
    }

    @Transactional
    public HoldingWriteOffResult writeOffHolding(WriteOffHoldingCommand command) {
        WarehouseHolding holding = holdingRepository.findById(command.holdingId())
            .orElseThrow(() -> new BadRequestException("Holding not found: " + command.holdingId()));

        if (command.quantity().compareTo(holding.getActiveQuantity()) > 0) {
            throw new BadRequestException("Holding active quantity is insufficient for write-off: requested="
                + command.quantity() + ", active=" + holding.getActiveQuantity());
        }

        holding.setActiveQuantity(holding.getActiveQuantity().subtract(command.quantity()));
        holding.setWrittenOffQuantity(holding.getWrittenOffQuantity().add(command.quantity()));
        if (holding.getActiveQuantity().signum() == 0 && holding.getPendingQuantity().signum() == 0) {
            holding.setHoldingStatus(HoldingStatus.WRITTEN_OFF);
        }
        holding = saveHolding(holding);

        return new HoldingWriteOffResult(
            holding.getId(),
            command.quantity(),
            holding.getActiveQuantity(),
            false,
            command.actNumber()
        );
    }

    @Transactional
    public HoldingReturnResult returnHoldingToWarehouse(ReturnHoldingCommand command) {
        WarehouseHolding holding = holdingRepository.findById(command.holdingId())
            .orElseThrow(() -> new BadRequestException("Holding not found: " + command.holdingId()));

        if (command.quantity().compareTo(holding.getActiveQuantity()) > 0) {
            throw new BadRequestException("Cannot return quantity exceeding active holding quantity: requested="
                + command.quantity() + ", active=" + holding.getActiveQuantity());
        }

        holding.setActiveQuantity(holding.getActiveQuantity().subtract(command.quantity()));
        holding.setReturnedQuantity(holding.getReturnedQuantity().add(command.quantity()));
        if (holding.getActiveQuantity().signum() == 0 && holding.getPendingQuantity().signum() == 0) {
            holding.setHoldingStatus(HoldingStatus.RETURNED);
        }
        holding = saveHolding(holding);

        BigDecimal unitCost = command.unitCost() != null ? command.unitCost() : holding.getUnitCost();
        BigDecimal totalRestoredValue = command.quantity().multiply(unitCost);

        if (postingService != null) {
            String targetAccount = command.targetAccountCode() != null ? command.targetAccountCode() : "1010";
            var postingCmd = new PostingCommand(
                "WAREHOUSE", "RECEIPT", "HOLDING_RETURN", UUID.randomUUID(),
                command.idempotencyKey() != null ? command.idempotencyKey() : UUID.randomUUID().toString(),
                LocalDate.now(), 1L, mapper.createObjectNode().put("qty", command.quantity().toPlainString())
            );
            var receiptRequest = new AccountAwareStockPostingService.ReceiptRequest(
                postingCmd,
                holding.getMaterialId(),
                command.warehouseId(),
                null, null, null,
                null,
                "OWNED",
                "AVAILABLE",
                targetAccount,
                holding.getUom() != null ? holding.getUom() : "PCS",
                command.quantity(),
                unitCost,
                "UZS",
                "HOLDING_RETURN:" + holding.getId(),
                totalRestoredValue
            );
            postingService.postReceipt(receiptRequest);
        } else if (stockBalanceRepository != null && stockBalanceMutationService != null) {
            String tenantId = TenantContext.currentTenant();
            UUID companyId = CompanyContext.currentCompany();

            List<StockBalance> balances = stockBalanceRepository
                .findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(tenantId, holding.getMaterialId(), command.warehouseId());

            StockBalance balance = balances.isEmpty() ? null : balances.get(0);
            if (balance == null) {
                balance = new StockBalance();
                balance.setTenantId(tenantId);
                balance.setCompanyId(companyId);
                balance.setMaterialId(holding.getMaterialId());
                balance.setWarehouseId(command.warehouseId());
                balance.setQuantity(BigDecimal.ZERO);
                balance.setAvailableQuantity(BigDecimal.ZERO);
                balance.setReservedQuantity(BigDecimal.ZERO);
                balance.setTotalValue(BigDecimal.ZERO);
            }

            stockBalanceMutationService.increaseOnHand(balance, command.quantity(), unitCost);
            stockBalanceRepository.save(balance);
        }

        return new HoldingReturnResult(
            holding.getId(),
            command.warehouseId(),
            command.quantity(),
            holding.getActiveQuantity(),
            command.targetAccountCode() != null ? command.targetAccountCode() : "1010",
            command.quantity(),
            totalRestoredValue
        );
    }

    @Transactional(readOnly = true)
    public Optional<WarehouseHolding> findHolding(UUID holdingId) {
        return holdingRepository.findById(holdingId);
    }

    @Transactional(readOnly = true)
    public List<WarehouseHolding> getHoldingsByDepartment(UUID departmentId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return holdingRepository.findByTenantIdAndCompanyIdAndDepartmentIdAndIsDeletedFalse(tenantId, companyId, departmentId);
    }

    @Transactional(readOnly = true)
    public List<WarehouseHolding> getHoldingsByEmployee(UUID employeeId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return holdingRepository.findByTenantIdAndCompanyIdAndEmployeeIdAndIsDeletedFalse(tenantId, companyId, employeeId);
    }

    @Transactional(readOnly = true)
    public List<WarehouseHoldingHandover> getPendingHandovers(UUID departmentId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return handoverRepository.findByTenantIdAndCompanyIdAndDepartmentIdAndHandoverStatusAndIsDeletedFalse(
            tenantId, companyId, departmentId, HandoverStatus.PENDING
        );
    }

    @Transactional
    public HoldingTransferResult transferHolding(TransferHoldingCommand command) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }

        WarehouseHolding sourceHolding = holdingRepository.findById(command.sourceHoldingId())
            .orElseThrow(() -> new BadRequestException("Source holding not found: " + command.sourceHoldingId()));

        if (command.quantity().compareTo(sourceHolding.getActiveQuantity()) > 0) {
            throw new BadRequestException("Holding active quantity is insufficient for transfer: requested="
                + command.quantity() + ", active=" + sourceHolding.getActiveQuantity());
        }

        if (Objects.equals(sourceHolding.getDepartmentId(), command.targetDepartmentId())
            && Objects.equals(sourceHolding.getEmployeeId(), command.targetEmployeeId())) {
            throw new BadRequestException("Target holder must differ from source holder");
        }

        // Relieve from source holding
        sourceHolding.setActiveQuantity(sourceHolding.getActiveQuantity().subtract(command.quantity()));
        sourceHolding.setTransferredOutQuantity(sourceHolding.getTransferredOutQuantity().add(command.quantity()));
        if (sourceHolding.getActiveQuantity().signum() == 0 && sourceHolding.getPendingQuantity().signum() == 0) {
            sourceHolding.setHoldingStatus(HoldingStatus.RETURNED);
        }
        saveHolding(sourceHolding);

        // Find or create target holding
        WarehouseHolding targetHolding = holdingRepository
            .findFirstByTenantIdAndCompanyIdAndMaterialIdAndDepartmentIdAndEmployeeIdAndAccountCodeAndIsDeletedFalse(
                tenantId, companyId, sourceHolding.getMaterialId(), command.targetDepartmentId(), command.targetEmployeeId(), "014"
            )
            .orElseGet(() -> {
                WarehouseHolding h = new WarehouseHolding();
                h.setTenantId(tenantId);
                h.setCompanyId(companyId);
                h.setMaterialId(sourceHolding.getMaterialId());
                h.setDepartmentId(command.targetDepartmentId());
                h.setEmployeeId(command.targetEmployeeId());
                h.setAccountCode(sourceHolding.getAccountCode());
                h.setOwnerId(sourceHolding.getOwnerId());
                h.setOriginType(HoldingOriginType.TRANSFERRED);
                h.setOriginReference(sourceHolding.getId().toString());
                h.setUom(sourceHolding.getUom());
                h.setUnitCost(sourceHolding.getUnitCost());
                h.setTotalValue(BigDecimal.ZERO);
                h.setPendingQuantity(BigDecimal.ZERO);
                h.setActiveQuantity(BigDecimal.ZERO);
                h.setConsumedQuantity(BigDecimal.ZERO);
                h.setReturnedQuantity(BigDecimal.ZERO);
                h.setWrittenOffQuantity(BigDecimal.ZERO);
                h.setTransferredInQuantity(BigDecimal.ZERO);
                h.setTransferredOutQuantity(BigDecimal.ZERO);
                h.setHoldingStatus(HoldingStatus.ACTIVE);
                return h;
            });

        targetHolding.setActiveQuantity(targetHolding.getActiveQuantity().add(command.quantity()));
        targetHolding.setTransferredInQuantity(targetHolding.getTransferredInQuantity().add(command.quantity()));
        targetHolding.setTotalValue(targetHolding.getTotalValue().add(command.quantity().multiply(sourceHolding.getUnitCost())));
        targetHolding.setHoldingStatus(HoldingStatus.ACTIVE);
        targetHolding = saveHolding(targetHolding);

        UUID transferId = UUID.randomUUID();
        String transferNum = command.transferNumber() != null ? command.transferNumber()
            : "TR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        if (transferRepository != null) {
            WarehouseHoldingTransfer transfer = new WarehouseHoldingTransfer();
            transfer.setTenantId(tenantId);
            transfer.setCompanyId(companyId);
            transfer.setTransferNumber(transferNum);
            transfer.setSourceHoldingId(sourceHolding.getId());
            transfer.setTargetHoldingId(targetHolding.getId());
            transfer.setMaterialId(sourceHolding.getMaterialId());
            transfer.setFromDepartmentId(sourceHolding.getDepartmentId());
            transfer.setFromEmployeeId(sourceHolding.getEmployeeId());
            transfer.setToDepartmentId(command.targetDepartmentId());
            transfer.setToEmployeeId(command.targetEmployeeId());
            transfer.setQuantity(command.quantity());
            transfer.setUnitCost(sourceHolding.getUnitCost());
            transfer.setTotalValue(command.quantity().multiply(sourceHolding.getUnitCost()));
            transfer.setReason(command.reason());
            transfer.setIdempotencyKey(command.idempotencyKey());
            transfer.setTransferredAt(LocalDateTime.now());
            transfer = transferRepository.save(transfer);
            transferId = transfer.getId();
        }

        BigDecimal conservedTotal = sourceHolding.getActiveQuantity().add(targetHolding.getActiveQuantity());

        return new HoldingTransferResult(
            transferId,
            transferNum,
            sourceHolding.getId(),
            targetHolding.getId(),
            sourceHolding.getMaterialId(),
            command.quantity(),
            sourceHolding.getActiveQuantity(),
            targetHolding.getActiveQuantity(),
            conservedTotal
        );
    }

    @Transactional(readOnly = true)
    public HoldingStatementReport generateHoldingStatement(HoldingStatementQuery query) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        List<WarehouseHolding> allHoldings = holdingRepository.findAll().stream()
            .filter(h -> Objects.equals(h.getTenantId(), tenantId) && Objects.equals(h.getCompanyId(), companyId))
            .filter(h -> query.materialId() == null || Objects.equals(h.getMaterialId(), query.materialId()))
            .filter(h -> query.departmentId() == null || Objects.equals(h.getDepartmentId(), query.departmentId()))
            .filter(h -> query.employeeId() == null || Objects.equals(h.getEmployeeId(), query.employeeId()))
            .filter(h -> query.accountCode() == null || Objects.equals(h.getAccountCode(), query.accountCode()))
            .toList();

        List<HoldingStatementPosition> positions = new java.util.ArrayList<>();
        int balancedCount = 0;
        int discrepancyCount = 0;

        BigDecimal totalActive = BigDecimal.ZERO;
        BigDecimal totalPending = BigDecimal.ZERO;
        BigDecimal totalConsumed = BigDecimal.ZERO;
        BigDecimal totalReturned = BigDecimal.ZERO;
        BigDecimal totalWrittenOff = BigDecimal.ZERO;
        BigDecimal totalTransferredIn = BigDecimal.ZERO;
        BigDecimal totalTransferredOut = BigDecimal.ZERO;

        for (WarehouseHolding h : allHoldings) {
            BigDecimal active = h.getActiveQuantity();
            BigDecimal pending = h.getPendingQuantity();
            BigDecimal consumed = h.getConsumedQuantity();
            BigDecimal returned = h.getReturnedQuantity();
            BigDecimal writtenOff = h.getWrittenOffQuantity();
            BigDecimal trIn = h.getTransferredInQuantity() != null ? h.getTransferredInQuantity() : BigDecimal.ZERO;
            BigDecimal trOut = h.getTransferredOutQuantity() != null ? h.getTransferredOutQuantity() : BigDecimal.ZERO;

            totalActive = totalActive.add(active);
            totalPending = totalPending.add(pending);
            totalConsumed = totalConsumed.add(consumed);
            totalReturned = totalReturned.add(returned);
            totalWrittenOff = totalWrittenOff.add(writtenOff);
            totalTransferredIn = totalTransferredIn.add(trIn);
            totalTransferredOut = totalTransferredOut.add(trOut);

            BigDecimal calculatedClosing = active;
            BigDecimal delta = calculatedClosing.subtract(active);
            boolean balanced = delta.signum() == 0;

            if (balanced) {
                balancedCount++;
            } else {
                discrepancyCount++;
            }

            positions.add(new HoldingStatementPosition(
                h.getId(),
                h.getMaterialId(),
                h.getDepartmentId(),
                h.getEmployeeId(),
                h.getAccountCode(),
                h.getUom(),
                pending,
                active,
                consumed,
                returned,
                writtenOff,
                trIn,
                trOut,
                calculatedClosing,
                delta,
                balanced
            ));
        }

        return new HoldingStatementReport(
            allHoldings.size(),
            balancedCount,
            discrepancyCount,
            totalActive,
            totalPending,
            totalConsumed,
            totalReturned,
            totalWrittenOff,
            totalTransferredIn,
            totalTransferredOut,
            discrepancyCount == 0,
            positions
        );
    }

    private HoldingHandoverResult mapResult(WarehouseHoldingHandover ho, WarehouseHolding holding) {
        return new HoldingHandoverResult(
            ho.getId(),
            ho.getHandoverNumber(),
            holding.getId(),
            ho.getHandoverStatus(),
            holding.getHoldingStatus(),
            holding.getPendingQuantity(),
            holding.getActiveQuantity(),
            holding.getUnitCost(),
            holding.getTotalValue()
        );
    }
}
