package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionCorrectionCommand;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionCorrectionResult;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReconciliationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionReconciliationService {

    private final WarehouseProductionReceiptRepository receiptRepository;
    private final WarehouseProductionCorrectionRepository correctionRepository;
    private final AccountAwareStockPostingService postingService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProductionReconciliationService(
        WarehouseProductionReceiptRepository receiptRepository,
        WarehouseProductionCorrectionRepository correctionRepository,
        AccountAwareStockPostingService postingService
    ) {
        this.receiptRepository = receiptRepository;
        this.correctionRepository = correctionRepository;
        this.postingService = postingService;
    }

    @Transactional
    public ProductionCorrectionResult applyProductionCorrection(ProductionCorrectionCommand command) {
        if (command == null) {
            throw new BadRequestException("Correction command cannot be null");
        }
        if (command.parentEventId() == null || command.parentEventId().isBlank()) {
            throw new BadRequestException("Parent event ID is required");
        }
        if (command.correctionEventId() == null || command.correctionEventId().isBlank()) {
            throw new BadRequestException("Correction event ID is required");
        }
        if (command.deltaQuantity() == null || command.deltaQuantity().signum() == 0) {
            throw new BadRequestException("Delta quantity must be non-zero");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        // 1. Check duplicate correction with idempotent replay support
        var existingCorrection = correctionRepository.findByTenantIdAndCompanyIdAndCorrectionEventId(
            tenantId, companyId.toString(), command.correctionEventId()
        );
        if (existingCorrection.isPresent()) {
            var existing = existingCorrection.get();
            if (existing.getDeltaQuantity().compareTo(command.deltaQuantity()) == 0
                && Objects.equals(existing.getParentEventId(), command.parentEventId())) {
                return new ProductionCorrectionResult(
                    existing.getId(),
                    existing.getParentEventId(),
                    existing.getCorrectionEventId(),
                    existing.getDeltaQuantity(),
                    existing.getDeltaCost(),
                    existing.getPostingReceiptId()
                );
            }
            throw new ConflictException(
                "Correction event ID already processed with different parameters: " + command.correctionEventId()
            );
        }

        // 2. Find parent production receipt
        var parentOpt = receiptRepository.findByTenantIdAndCompanyIdAndEventId(
            tenantId, companyId.toString(), command.parentEventId()
        );
        if (parentOpt.isEmpty()) {
            throw new BadRequestException("Parent production event not found: " + command.parentEventId());
        }
        WarehouseProductionReceipt parent = parentOpt.get();

        if (command.eventVersion() <= parent.getEventVersion()) {
            throw new BadRequestException(
                "Correction event version (" + command.eventVersion()
                + ") must be strictly greater than parent version (" + parent.getEventVersion() + ")"
            );
        }

        // 3. Post linked compensating / adjustment effect (never edit parent posted receipt)
        PostingReceipt postingReceipt;
        BigDecimal deltaCost = command.deltaCost() != null ? command.deltaCost() : BigDecimal.ZERO;

        if (command.deltaQuantity().signum() > 0) {
            // Positive adjustment = additional receipt
            var postingCmd = new PostingCommand(
                "PRODUCTION", "RECEIPT", "PRODUCTION_CORRECTION_ADD", UUID.randomUUID(),
                command.correctionEventId(), LocalDate.now(), (long) command.eventVersion(),
                mapper.createObjectNode().put("deltaQty", command.deltaQuantity().toPlainString())
            );
            BigDecimal unitCost = deltaCost.signum() > 0
                ? deltaCost.divide(command.deltaQuantity(), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            var req = new AccountAwareStockPostingService.ReceiptRequest(
                postingCmd,
                parent.getMaterialId(),
                parent.getWarehouseId(),
                null, null, null, null,
                "OWNED",
                "AVAILABLE",
                parent.getAccountCode(),
                "PCS",
                command.deltaQuantity(),
                unitCost,
                "UZS",
                "CORRECTION_OF:" + parent.getEventId() + ":" + (command.reason() != null ? command.reason() : "")
            );
            postingReceipt = postingService.postReceipt(req);
        } else {
            // Negative adjustment = reduction issue
            BigDecimal absQty = command.deltaQuantity().abs();
            var postingCmd = new PostingCommand(
                "PRODUCTION", "ISSUE", "PRODUCTION_CORRECTION_REDUCE", UUID.randomUUID(),
                command.correctionEventId(), LocalDate.now(), (long) command.eventVersion(),
                mapper.createObjectNode().put("deltaQty", command.deltaQuantity().toPlainString())
            );
            var req = new AccountAwareStockPostingService.IssueRequest(
                postingCmd,
                parent.getMaterialId(),
                parent.getWarehouseId(),
                null, null, null, null,
                "OWNED",
                "AVAILABLE",
                parent.getAccountCode(),
                "PCS",
                absQty,
                BigDecimal.ZERO,
                "UZS",
                "CORRECTION_OF:" + parent.getEventId() + ":" + (command.reason() != null ? command.reason() : "")
            );
            postingReceipt = postingService.postIssue(req);
        }

        String postingReceiptId = postingReceipt != null && postingReceipt.batchId() != null
            ? postingReceipt.batchId().toString()
            : UUID.randomUUID().toString();

        // 4. Save linked correction record
        var correction = new WarehouseProductionCorrection();
        correction.setId(UUID.randomUUID());
        correction.setTenantId(tenantId);
        correction.setCompanyId(companyId.toString());
        correction.setParentEventId(command.parentEventId());
        correction.setCorrectionEventId(command.correctionEventId());
        correction.setEventVersion(command.eventVersion());
        correction.setMaterialId(parent.getMaterialId());
        correction.setWarehouseId(parent.getWarehouseId());
        correction.setAccountCode(parent.getAccountCode());
        correction.setDeltaQuantity(command.deltaQuantity());
        correction.setDeltaCost(deltaCost);
        correction.setReason(command.reason());
        correction.setPostingReceiptId(postingReceiptId);
        correction.setCreatedAt(Instant.now());

        WarehouseProductionCorrection saved = correctionRepository.save(correction);

        return new ProductionCorrectionResult(
            saved.getId(),
            saved.getParentEventId(),
            saved.getCorrectionEventId(),
            saved.getDeltaQuantity(),
            saved.getDeltaCost(),
            saved.getPostingReceiptId()
        );
    }

    @Transactional(readOnly = true)
    public ProductionReconciliationReport reconstructProductionReceiptTotals(UUID materialId, UUID warehouseId) {
        if (materialId == null) {
            throw new BadRequestException("Material ID is required for reconciliation");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        List<WarehouseProductionReceipt> receipts = receiptRepository.findByTenantIdAndCompanyIdAndMaterialId(
            tenantId, companyId.toString(), materialId
        );
        List<WarehouseProductionCorrection> corrections = correctionRepository.findByTenantIdAndCompanyIdAndMaterialId(
            tenantId, companyId.toString(), materialId
        );

        BigDecimal totalOrigQty = BigDecimal.ZERO;
        BigDecimal totalOrigCost = BigDecimal.ZERO;

        for (var r : receipts) {
            if (warehouseId == null || warehouseId.equals(r.getWarehouseId())) {
                totalOrigQty = totalOrigQty.add(r.getQuantity());
                totalOrigCost = totalOrigCost.add(r.getTotalCost());
            }
        }

        BigDecimal totalDeltaQty = BigDecimal.ZERO;
        BigDecimal totalDeltaCost = BigDecimal.ZERO;

        for (var c : corrections) {
            if (warehouseId == null || warehouseId.equals(c.getWarehouseId())) {
                totalDeltaQty = totalDeltaQty.add(c.getDeltaQuantity());
                totalDeltaCost = totalDeltaCost.add(c.getDeltaCost());
            }
        }

        BigDecimal netQty = totalOrigQty.add(totalDeltaQty);
        BigDecimal netCost = totalOrigCost.add(totalDeltaCost);

        return new ProductionReconciliationReport(
            materialId,
            warehouseId,
            totalOrigQty,
            totalDeltaQty,
            netQty,
            totalOrigCost,
            totalDeltaCost,
            netCost
        );
    }
}
