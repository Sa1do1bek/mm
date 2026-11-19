package com.enterprise.mm.warehouse.custody;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.enterprise.mm.warehouse.posting.PostingEvidenceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles operations on third-party custody stock (Schet 002):
 * - Receipt into 002 with 0 company monetary asset value.
 * - Dispatch / Return back to owner or approved destination.
 * - Ownership transfer / buyout into company ownership (002 -> 1010) without physical stock duplication.
 */
@Service
public class CustodyOperationService {
    private final AccountAwareStockPostingService postingService;
    private final CustodyAgreementService agreementService;
    private final PostingEvidenceStore postingEvidenceStore;
    private final WarehouseCustodyOwnershipTransferRepository transferRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final WarehouseCustodyActivationRepository activationRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public CustodyOperationService(
        AccountAwareStockPostingService postingService,
        CustodyAgreementService agreementService,
        PostingEvidenceStore postingEvidenceStore,
        WarehouseCustodyOwnershipTransferRepository transferRepository,
        StockBalanceRepository stockBalanceRepository,
        WarehouseCustodyActivationRepository activationRepository,
        IdempotencyService idempotencyService
    ) {
        this.postingService = postingService;
        this.agreementService = agreementService;
        this.postingEvidenceStore = postingEvidenceStore;
        this.transferRepository = transferRepository;
        this.stockBalanceRepository = stockBalanceRepository;
        this.activationRepository = activationRepository;
        this.idempotencyService = idempotencyService;
    }

    public CustodyOperationService(
        AccountAwareStockPostingService postingService,
        CustodyAgreementService agreementService,
        PostingEvidenceStore postingEvidenceStore,
        WarehouseCustodyOwnershipTransferRepository transferRepository,
        StockBalanceRepository stockBalanceRepository,
        IdempotencyService idempotencyService
    ) {
        this(postingService, agreementService, postingEvidenceStore, transferRepository, stockBalanceRepository, null, idempotencyService);
    }

    @Transactional
    public CustodyCommands.CustodyReceiptResult receiveCustodyStock(CustodyCommands.CustodyReceiptCommand command) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }
        if (command.ownerId() == null) {
            throw new BadRequestException("Custody owner ID is required");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Receipt quantity must be strictly positive");
        }

        LocalDate date = command.receiptDate() != null ? command.receiptDate() : LocalDate.now();

        // Validate custody agreement
        agreementService.validateAgreementForCustodyOperation(tenantId, companyId, command.ownerId(), date);

        var postingCmd = new PostingCommand(
            "WAREHOUSE", "RECEIPT", "CUSTODY", UUID.randomUUID(),
            command.commandId(), date, 1L, mapper.createObjectNode().put("qty", command.quantity().toPlainString())
        );

        var request = new AccountAwareStockPostingService.ReceiptRequest(
            postingCmd,
            command.materialId(),
            command.warehouseId(),
            null, null, null,
            command.ownerId(),
            "CUSTODY",
            "AVAILABLE",
            "002",
            command.baseUnitCode() != null ? command.baseUnitCode() : "PCS",
            command.quantity(),
            BigDecimal.ZERO, // Non-owned stock carries 0 company asset cost
            "UZS",
            command.sourceReference()
        );

        postingService.postReceipt(request);

        return new CustodyCommands.CustodyReceiptResult(
            command.commandId(),
            command.materialId(),
            command.warehouseId(),
            command.ownerId(),
            command.quantity(),
            "002",
            "CUSTODY",
            BigDecimal.ZERO,
            date
        );
    }

    @Transactional
    public CustodyCommands.CustodyDispatchResult dispatchCustodyStock(CustodyCommands.CustodyDispatchCommand command) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }
        if (command.ownerId() == null) {
            throw new BadRequestException("Custody owner ID is required");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Dispatch quantity must be strictly positive");
        }

        LocalDate date = command.dispatchDate() != null ? command.dispatchDate() : LocalDate.now();

        // Validate custody agreement
        agreementService.validateAgreementForCustodyOperation(tenantId, companyId, command.ownerId(), date);

        var postingCmd = new PostingCommand(
            "WAREHOUSE", "ISSUE", "CUSTODY", UUID.randomUUID(),
            command.commandId(), date, 1L, mapper.createObjectNode().put("qty", command.quantity().toPlainString())
        );

        var request = new AccountAwareStockPostingService.IssueRequest(
            postingCmd,
            command.materialId(),
            command.warehouseId(),
            null, null, null,
            command.ownerId(),
            "CUSTODY",
            "AVAILABLE",
            "002",
            command.baseUnitCode() != null ? command.baseUnitCode() : "PCS",
            command.quantity(),
            BigDecimal.ZERO,
            "UZS",
            command.sourceReference()
        );

        postingService.postIssue(request);

        return new CustodyCommands.CustodyDispatchResult(
            command.commandId(),
            command.materialId(),
            command.warehouseId(),
            command.ownerId(),
            command.quantity(),
            "002",
            "CUSTODY",
            date
        );
    }

    @Transactional
    public CustodyCommands.CustodyOwnershipTransferResult transferOwnershipToCompany(
        CustodyCommands.CustodyOwnershipTransferCommand command
    ) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }
        if (command.sourceOwnerId() == null) {
            throw new BadRequestException("Source owner ID is required");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Transfer quantity must be strictly positive");
        }
        if (command.unitSettlementPrice() == null || command.unitSettlementPrice().signum() < 0) {
            throw new BadRequestException("Settlement price must be non-negative");
        }

        LocalDate date = command.settlementDate() != null ? command.settlementDate() : LocalDate.now();

        // Validate agreement
        agreementService.validateAgreementForCustodyOperation(tenantId, companyId, command.sourceOwnerId(), date);

        BigDecimal quantity = command.quantity().setScale(7, RoundingMode.HALF_UP);
        BigDecimal price = command.unitSettlementPrice().setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalValue = quantity.multiply(price).setScale(4, RoundingMode.HALF_UP);
        String targetAccount = command.targetAccountCode() != null ? command.targetAccountCode() : "1010";
        String uom = command.baseUnitCode() != null ? command.baseUnitCode() : "PCS";

        // Check availability on 002
        if (postingEvidenceStore != null) {
            BigDecimal available = postingEvidenceStore.getAccountAndOwnerAvailableQuantity(
                tenantId, companyId, command.materialId(), command.warehouseId(), "002", command.sourceOwnerId(), "CUSTODY"
            );
            if (available != null && available.compareTo(quantity) < 0) {
                throw new InsufficientStockException(
                    "Insufficient stock for owner and account: requested " + quantity + " but only " + available
                    + " available on account 002 for owner " + command.sourceOwnerId() + " (CUSTODY)",
                    command.materialId(), null, command.warehouseId(), null, quantity, available, uom
                );
            }
        }

        // 1. Issue/consume custody stock from 002
        var issuePostingCmd = new PostingCommand(
            "WAREHOUSE", "ISSUE", "CUSTODY", UUID.randomUUID(),
            command.commandId() + "-out", date, 1L, mapper.createObjectNode().put("qty", quantity.toPlainString())
        );

        var issueRequest = new AccountAwareStockPostingService.IssueRequest(
            issuePostingCmd,
            command.materialId(),
            command.warehouseId(),
            null, null, null,
            command.sourceOwnerId(),
            "CUSTODY",
            "AVAILABLE",
            "002",
            uom,
            quantity,
            BigDecimal.ZERO,
            "UZS",
            command.sourceReference()
        );
        postingService.postIssue(issueRequest);

        // 2. Receive owned stock on 1010 with valuation layer
        var receiptPostingCmd = new PostingCommand(
            "WAREHOUSE", "RECEIPT", "OWNED", UUID.randomUUID(),
            command.commandId() + "-in", date, 1L, mapper.createObjectNode().put("qty", quantity.toPlainString())
        );

        var receiptRequest = new AccountAwareStockPostingService.ReceiptRequest(
            receiptPostingCmd,
            command.materialId(),
            command.warehouseId(),
            null, null, null,
            companyId,
            "OWNED",
            "AVAILABLE",
            targetAccount,
            uom,
            quantity,
            price,
            "UZS",
            command.sourceReference()
        );
        postingService.postReceipt(receiptRequest);

        // 3. Save transfer evidence record
        if (transferRepository != null) {
            var transfer = new WarehouseCustodyOwnershipTransfer();
            transfer.setTenantId(tenantId);
            transfer.setCompanyId(companyId);
            transfer.setCommandId(command.commandId());
            transfer.setMaterialId(command.materialId());
            transfer.setWarehouseId(command.warehouseId());
            transfer.setSourceOwnerId(command.sourceOwnerId());
            transfer.setSourceAccountCode("002");
            transfer.setTargetAccountCode(targetAccount);
            transfer.setQuantity(quantity);
            transfer.setUnitSettlementPrice(price);
            transfer.setTotalSettlementValue(totalValue);
            transfer.setSettlementDate(date);
            transfer.setSettlementReference(command.sourceReference());
            transferRepository.save(transfer);
        }

        return new CustodyCommands.CustodyOwnershipTransferResult(
            command.commandId(),
            command.materialId(),
            command.warehouseId(),
            command.sourceOwnerId(),
            quantity,
            price,
            totalValue,
            "002",
            targetAccount,
            date
        );
    }

    @Transactional
    public CustodyCommands.CustodyActivationResult activateHistoricalCustody(CustodyCommands.ActivateCustodyCommand command) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }
        if (command.sourceReferenceId() == null || command.sourceReferenceId().isBlank()) {
            throw new BadRequestException("Source reference ID is required");
        }
        if (command.sourceHash() == null || command.sourceHash().isBlank()) {
            throw new BadRequestException("Source hash is required");
        }
        if (command.ownerId() == null) {
            throw new BadRequestException("Custody owner ID is required");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Activation quantity must be strictly positive");
        }

        LocalDate date = command.activationDate() != null ? command.activationDate() : LocalDate.now();

        // 1. Idempotency check: see if already activated
        if (activationRepository != null) {
            var existing = activationRepository.findByTenantIdAndCompanyIdAndSourceReferenceId(
                tenantId, companyId, command.sourceReferenceId()
            );
            if (existing.isPresent()) {
                var act = existing.get();
                if (!act.getSourceHash().equals(command.sourceHash())) {
                    throw new ConflictException("Source content hash mismatch for custody row: " + command.sourceReferenceId());
                }
                return new CustodyCommands.CustodyActivationResult(
                    act.getSourceReferenceId(),
                    act.getSourceHash(),
                    act.getMaterialId(),
                    act.getWarehouseId(),
                    act.getOwnerId(),
                    act.getQuantity(),
                    act.getReferenceAmount(),
                    act.getAccountCode(),
                    "CUSTODY",
                    true,
                    act.getActivationDate()
                );
            }
        }

        // 2. Validate custody agreement
        agreementService.validateAgreementForCustodyOperation(tenantId, companyId, command.ownerId(), date);

        BigDecimal quantity = command.quantity().setScale(7, RoundingMode.HALF_UP);
        BigDecimal referenceAmount = command.referenceAmount() != null
            ? command.referenceAmount().setScale(4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        String currency = command.currencyCode() != null ? command.currencyCode().trim() : "UZS";
        String uom = command.baseUnitCode() != null ? command.baseUnitCode().trim() : "PCS";

        // 3. Post into 002 custody subledger
        var postingCmd = new PostingCommand(
            "WAREHOUSE", "RECEIPT", "CUSTODY", UUID.randomUUID(),
            "act-custody-" + command.sourceReferenceId(), date, 1L,
            mapper.createObjectNode().put("qty", quantity.toPlainString()).put("src", command.sourceReferenceId())
        );

        var request = new AccountAwareStockPostingService.ReceiptRequest(
            postingCmd,
            command.materialId(),
            command.warehouseId(),
            null, null, null,
            command.ownerId(),
            "CUSTODY",
            "AVAILABLE",
            "002",
            uom,
            quantity,
            BigDecimal.ZERO, // 0 company asset cost
            currency,
            command.sourceReferenceId()
        );

        postingService.postReceipt(request);

        // 4. Save activation record
        if (activationRepository != null) {
            var act = new WarehouseCustodyActivation();
            act.setTenantId(tenantId);
            act.setCompanyId(companyId);
            act.setSourceReferenceId(command.sourceReferenceId());
            act.setSourceHash(command.sourceHash());
            act.setMaterialId(command.materialId());
            act.setWarehouseId(command.warehouseId());
            act.setOwnerId(command.ownerId());
            act.setAccountCode("002");
            act.setQuantity(quantity);
            act.setReferenceAmount(referenceAmount);
            act.setCurrencyCode(currency);
            act.setActivationDate(date);
            activationRepository.save(act);
        }

        return new CustodyCommands.CustodyActivationResult(
            command.sourceReferenceId(),
            command.sourceHash(),
            command.materialId(),
            command.warehouseId(),
            command.ownerId(),
            quantity,
            referenceAmount,
            "002",
            "CUSTODY",
            false,
            date
        );
    }

    @Transactional(readOnly = true)
    public CustodyCommands.CustodyStatementReport generateCustodyStatement(CustodyCommands.CustodyStatementQuery query) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }
        if (query.ownerId() == null) {
            throw new BadRequestException("Owner ID is required");
        }

        var counterpartyReport = agreementService.getCounterpartyStatement(
            new CustodyAgreementService.CounterpartyStatementQuery(
                query.ownerId(), query.fromDate(), query.toDate(), query.warehouseId(), query.materialId()
            )
        );

        List<CustodyCommands.CustodyStatementPosition> positions = new ArrayList<>();
        for (var p : counterpartyReport.positions()) {
            positions.add(new CustodyCommands.CustodyStatementPosition(
                p.materialId(),
                p.warehouseId(),
                p.accountCode(),
                p.stockCategory(),
                p.closingQuantity(),
                BigDecimal.ZERO, // Separate non-owned value base: 0 company asset value
                BigDecimal.ZERO, // Reference amount
                p.reconciledDelta()
            ));
        }

        return new CustodyCommands.CustodyStatementReport(
            counterpartyReport.agreementNumber(),
            query.ownerId(),
            counterpartyReport.counterpartyName(),
            positions
        );
    }
}
