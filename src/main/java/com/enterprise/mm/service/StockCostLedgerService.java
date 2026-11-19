package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.enums.StockCostMovementType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.StockCostMovementRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockCostLedgerService {
    private static final int MONEY_SCALE = 4;
    private static final int UNIT_SCALE = 6;

    private final StockCostMovementRepository repository;
    private final FinancialAuthorizationService financialAuthorizationService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ExactOpeningImportGuard openingImportGuard;

    @Transactional
    public StockCostMovement record(RecordCommand command) {
        if (command == null) {
            throw new BusinessException("Cost ledger command is required");
        }
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(
            command.companyId() == null ? CompanyContext.currentCompany() : command.companyId());
        if (command.materialId() == null || command.warehouseId() == null) {
            throw new BusinessException("Cost ledger material and warehouse are required");
        }
        if (command.movementType() == null) {
            throw new BusinessException("Cost ledger movement type is required");
        }
        if (blank(command.sourceDocumentType()) || command.sourceDocumentId() == null) {
            throw new BusinessException("Cost ledger source document is required");
        }

        StockCostMovement entry = new StockCostMovement();
        entry.setCode("COST-MOV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        entry.setName(command.movementType().name() + " cost movement");
        entry.setStatus("POSTED");
        entry.setCompanyId(command.companyId() == null ? CompanyContext.currentCompany() : command.companyId());
        entry.setMaterialId(command.materialId());
        entry.setWarehouseId(command.warehouseId());
        entry.setStockMovementId(command.stockMovementId());
        entry.setCostLayerId(command.costLayerId());
        entry.setMovementType(command.movementType().name());
        entry.setQuantity(nvl(command.quantity()));
        entry.setUnitCost(nvl(command.unitCost()).setScale(UNIT_SCALE, RoundingMode.HALF_UP));
        entry.setTotalCost(nvl(command.totalCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        entry.setCostCenterId(command.costCenterId());
        entry.setSourceDocumentType(command.sourceDocumentType());
        entry.setSourceDocumentId(command.sourceDocumentId());
        entry.setTransferTransactionId(command.transferTransactionId());
        entry.setReversal(command.reversal());
        entry.setReversalOfId(command.reversalOfId());
        entry.setReversalReason(command.reversalReason());
        entry.setReversedAt(command.reversedAt());
        entry.setReversedBy(command.reversedBy());
        return repository.save(entry);
    }

    @Transactional
    public StockCostMovement reverse(UUID id, String reason) {
        financialAuthorizationService.requireFinance("reverse cost ledger movement");
        StockCostMovement original = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Cost ledger entry not found: " + id));
        if (!TenantContext.currentTenant().equals(original.getTenantId()) || !CompanyContext.currentCompany().equals(original.getCompanyId())) {
            throw new NotFoundException("Cost ledger entry not found: " + id);
        }
        StockCostMovementType type = StockCostMovementType.REVERSAL;
        RecordCommand command = new RecordCommand(
            original.getCompanyId(),
            original.getMaterialId(),
            original.getWarehouseId(),
            original.getStockMovementId(),
            original.getCostLayerId(),
            type,
            nvl(original.getQuantity()).negate(),
            nvl(original.getUnitCost()),
            nvl(original.getTotalCost()).negate(),
            original.getCostCenterId(),
            original.getSourceDocumentType(),
            original.getSourceDocumentId(),
            original.getTransferTransactionId(),
            true,
            original.getId(),
            reason,
            LocalDateTime.now(),
            currentUsername()
        );
        return record(command);
    }

    @Transactional(readOnly = true)
    public List<StockCostMovement> findByMovement(UUID movementId) {
        return repository.findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), movementId
        );
    }

    @Transactional(readOnly = true)
    public List<StockCostMovement> findByDocument(String sourceDocumentType, UUID sourceDocumentId) {
        return repository.findByTenantIdAndCompanyIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceDocumentType, sourceDocumentId
        );
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String currentUsername() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    public record RecordCommand(
        UUID companyId,
        UUID materialId,
        UUID warehouseId,
        UUID stockMovementId,
        UUID costLayerId,
        StockCostMovementType movementType,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        UUID costCenterId,
        String sourceDocumentType,
        UUID sourceDocumentId,
        UUID transferTransactionId,
        boolean reversal,
        UUID reversalOfId,
        String reversalReason,
        LocalDateTime reversedAt,
        String reversedBy
    ) {
    }
}
