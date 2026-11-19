package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReceiptEvent;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReceiptResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionReceiptAdapterService {

    private final WarehouseProductionReceiptRepository receiptRepository;
    private final AccountAwareStockPostingService postingService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProductionReceiptAdapterService(
        WarehouseProductionReceiptRepository receiptRepository,
        AccountAwareStockPostingService postingService
    ) {
        this.receiptRepository = receiptRepository;
        this.postingService = postingService;
    }

    @Transactional
    public ProductionReceiptResult processProductionReceipt(ProductionReceiptEvent event) {
        if (event == null) {
            throw new BadRequestException("Production receipt event cannot be null");
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new BadRequestException("Event ID is required");
        }
        if (event.materialId() == null) {
            throw new BadRequestException("Material ID is required");
        }
        if (event.warehouseId() == null) {
            throw new BadRequestException("Warehouse ID is required");
        }
        if (event.quantity() == null || event.quantity().signum() <= 0) {
            throw new BadRequestException("Production receipt quantity must be strictly positive");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        String contentHash = computeHash(event);

        // 1. Deduplication and Conflict Guard
        var existingOpt = receiptRepository.findByTenantIdAndCompanyIdAndEventId(
            tenantId, companyId.toString(), event.eventId()
        );
        if (existingOpt.isPresent()) {
            WarehouseProductionReceipt existing = existingOpt.get();
            if (contentHash.equals(existing.getContentHash())) {
                // Idempotent duplicate delivery: return existing receipt without reposting
                return new ProductionReceiptResult(
                    existing.getId(),
                    existing.getEventId(),
                    existing.getPostingReceiptId(),
                    existing.getQuantity(),
                    existing.getTotalCost(),
                    true
                );
            } else {
                throw new ConflictException(
                    "Conflict: Production receipt event ID '" + event.eventId()
                    + "' already exists with different payload or parameters"
                );
            }
        }

        // 2. Calculations & context resolution
        String effectiveAccount = event.accountCode() != null ? event.accountCode() : "2810";
        String quality = event.qualityDisposition() != null ? event.qualityDisposition() : "APPROVED";
        String disposition = "QUARANTINE".equalsIgnoreCase(quality) ? "QUARANTINE" : "AVAILABLE";

        BigDecimal unitCost = event.unitCost();
        BigDecimal totalCost = event.totalCost();
        if (unitCost == null && totalCost != null && event.quantity().signum() > 0) {
            unitCost = totalCost.divide(event.quantity(), 4, RoundingMode.HALF_UP);
        } else if (unitCost == null) {
            unitCost = BigDecimal.ZERO;
        }

        if (totalCost == null) {
            totalCost = unitCost.multiply(event.quantity()).setScale(4, RoundingMode.HALF_UP);
        }

        LocalDate date = event.receiptDate() != null ? event.receiptDate() : LocalDate.now();

        // 3. Post receipt to shared warehouse posting coordinator
        var postingCmd = new PostingCommand(
            "PRODUCTION", "RECEIPT", "PRODUCTION_FINISHED_GOODS", UUID.randomUUID(),
            event.eventId(), date, 1L, mapper.createObjectNode().put("qty", event.quantity().toPlainString())
        );

        var request = new AccountAwareStockPostingService.ReceiptRequest(
            postingCmd,
            event.materialId(),
            event.warehouseId(),
            null, null, null,
            null,
            "OWNED",
            disposition,
            effectiveAccount,
            event.baseUnitCode() != null ? event.baseUnitCode() : "PCS",
            event.quantity(),
            unitCost,
            "UZS",
            event.productionOrderRef() != null ? event.productionOrderRef() : "PROD_RECEIPT:" + event.eventId()
        );

        PostingReceipt postingReceipt = postingService.postReceipt(request);
        String postingReceiptId = postingReceipt != null && postingReceipt.batchId() != null
            ? postingReceipt.batchId().toString()
            : UUID.randomUUID().toString();

        // 4. Save production receipt record
        WarehouseProductionReceipt record = new WarehouseProductionReceipt();
        record.setId(UUID.randomUUID());
        record.setTenantId(tenantId);
        record.setCompanyId(companyId.toString());
        record.setEventId(event.eventId());
        record.setEventVersion(event.eventVersion() > 0 ? event.eventVersion() : 1);
        record.setContentHash(contentHash);
        record.setMaterialId(event.materialId());
        record.setWarehouseId(event.warehouseId());
        record.setAccountCode(effectiveAccount);
        record.setQuantity(event.quantity());
        record.setUnitCost(unitCost);
        record.setTotalCost(totalCost);
        record.setQualityDisposition(quality);
        record.setReceiptDate(date);
        record.setProductionOrderRef(event.productionOrderRef());
        record.setPostingReceiptId(postingReceiptId);
        record.setCreatedAt(Instant.now());

        WarehouseProductionReceipt saved = receiptRepository.save(record);

        return new ProductionReceiptResult(
            saved.getId(),
            saved.getEventId(),
            saved.getPostingReceiptId(),
            saved.getQuantity(),
            saved.getTotalCost(),
            false
        );
    }

    private String computeHash(ProductionReceiptEvent event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = event.eventId() + "|"
                + event.materialId() + "|"
                + event.warehouseId() + "|"
                + event.quantity().stripTrailingZeros().toPlainString() + "|"
                + (event.totalCost() != null ? event.totalCost().stripTrailingZeros().toPlainString() : "0") + "|"
                + (event.qualityDisposition() != null ? event.qualityDisposition() : "APPROVED") + "|"
                + (event.accountCode() != null ? event.accountCode() : "2810");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
