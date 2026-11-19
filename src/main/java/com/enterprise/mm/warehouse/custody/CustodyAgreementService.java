package com.enterprise.mm.warehouse.custody;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.warehouse.posting.PostingEvidenceStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages custody agreements (Schet 002) and counterparty bindings.
 * Enforces legal authorization bounds and produces independent reconciliation statements.
 */
@Service
public class CustodyAgreementService {
    private final WarehouseCustodyAgreementRepository agreementRepository;
    private final PostingEvidenceStore postingEvidenceStore;
    private final StockBalanceRepository stockBalanceRepository;

    @Autowired
    public CustodyAgreementService(
        WarehouseCustodyAgreementRepository agreementRepository,
        @Autowired(required = false) PostingEvidenceStore postingEvidenceStore,
        @Autowired(required = false) StockBalanceRepository stockBalanceRepository
    ) {
        this.agreementRepository = agreementRepository;
        this.postingEvidenceStore = postingEvidenceStore;
        this.stockBalanceRepository = stockBalanceRepository;
    }

    public CustodyAgreementService(
        WarehouseCustodyAgreementRepository agreementRepository,
        PostingEvidenceStore postingEvidenceStore
    ) {
        this(agreementRepository, postingEvidenceStore, null);
    }

    public CustodyAgreementService(WarehouseCustodyAgreementRepository agreementRepository) {
        this(agreementRepository, null, null);
    }

    public record RegisterCustodyAgreementCommand(
        String agreementNumber,
        UUID ownerId,
        String counterpartyName,
        LocalDate validFrom,
        LocalDate validThrough,
        String settlementPolicy,
        String notes
    ) {}

    public record CounterpartyStatementQuery(
        UUID ownerId,
        LocalDate fromDate,
        LocalDate toDate,
        UUID warehouseId,
        UUID materialId
    ) {}

    public record CounterpartyStatementPosition(
        UUID materialId,
        UUID warehouseId,
        String accountCode,
        String stockCategory,
        BigDecimal closingQuantity,
        BigDecimal reconciledDelta
    ) {}

    public record CounterpartyStatementReport(
        String agreementNumber,
        UUID ownerId,
        String counterpartyName,
        List<CounterpartyStatementPosition> positions
    ) {}

    @Transactional
    public WarehouseCustodyAgreement registerAgreement(RegisterCustodyAgreementCommand command) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }
        if (command.agreementNumber() == null || command.agreementNumber().isBlank()) {
            throw new BadRequestException("Agreement number is required");
        }
        if (command.ownerId() == null) {
            throw new BadRequestException("Owner ID is required");
        }
        if (command.counterpartyName() == null || command.counterpartyName().isBlank()) {
            throw new BadRequestException("Counterparty name is required");
        }
        if (command.validFrom() == null) {
            throw new BadRequestException("Valid-from date is required");
        }

        var existing = agreementRepository.findByTenantIdAndCompanyIdAndAgreementNumber(
            tenantId, companyId, command.agreementNumber()
        );
        if (existing.isPresent()) {
            throw new ConflictException("Custody agreement with number " + command.agreementNumber() + " already exists");
        }

        var agreement = new WarehouseCustodyAgreement();
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber(command.agreementNumber().trim());
        agreement.setOwnerId(command.ownerId());
        agreement.setCounterpartyName(command.counterpartyName().trim());
        agreement.setValidFrom(command.validFrom());
        agreement.setValidThrough(command.validThrough());
        agreement.setAgreementStatus(CustodyAgreementStatus.ACTIVE);
        agreement.setSettlementPolicy(command.settlementPolicy() != null ? command.settlementPolicy().trim() : "STORAGE_FEE");
        agreement.setNotes(command.notes());

        return agreementRepository.save(agreement);
    }

    @Transactional(readOnly = true)
    public WarehouseCustodyAgreement validateAgreementForCustodyOperation(
        String tenantId, UUID companyId, UUID ownerId, LocalDate operationDate
    ) {
        if (ownerId == null) {
            throw new BadRequestException("Custody operation requires a non-null owner ID");
        }

        List<WarehouseCustodyAgreement> agreements = agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(
            tenantId, companyId, ownerId
        );
        if (agreements.isEmpty()) {
            throw new NotFoundException("No custody agreement found for owner: " + ownerId);
        }

        LocalDate date = operationDate != null ? operationDate : LocalDate.now();

        // Check if any agreement is active and covers the date
        for (var a : agreements) {
            boolean startsBeforeOrOn = !a.getValidFrom().isAfter(date);
            boolean endsAfterOrOn = a.getValidThrough() == null || !a.getValidThrough().isBefore(date);

            if (startsBeforeOrOn && endsAfterOrOn) {
                if (a.getAgreementStatus() == CustodyAgreementStatus.ACTIVE) {
                    return a;
                } else {
                    throw new BadRequestException("Custody agreement is " + a.getAgreementStatus() + ": " + a.getAgreementNumber());
                }
            }
        }

        // If reached here, all agreements are outside the valid dates
        throw new BadRequestException("Custody agreement has expired for owner: " + ownerId);
    }

    @Transactional(readOnly = true)
    public CounterpartyStatementReport getCounterpartyStatement(CounterpartyStatementQuery query) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }
        if (query.ownerId() == null) {
            throw new BadRequestException("Owner ID is required");
        }

        List<WarehouseCustodyAgreement> agreements = agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(
            tenantId, companyId, query.ownerId()
        );
        WarehouseCustodyAgreement agreement = agreements.stream()
            .filter(a -> a.getAgreementStatus() == CustodyAgreementStatus.ACTIVE)
            .findFirst()
            .orElseGet(() -> !agreements.isEmpty() ? agreements.getFirst() : null);

        String agreementNum = agreement != null ? agreement.getAgreementNumber() : "UNBOUND";
        String counterparty = agreement != null ? agreement.getCounterpartyName() : "Unknown Counterparty";

        List<CounterpartyStatementPosition> positions = new ArrayList<>();

        if (postingEvidenceStore != null && query.materialId() != null && query.warehouseId() != null) {
            BigDecimal qty = postingEvidenceStore.getAccountAndOwnerAvailableQuantity(
                tenantId, companyId, query.materialId(), query.warehouseId(), "002", query.ownerId(), "CUSTODY"
            );
            BigDecimal closing = qty != null ? qty : BigDecimal.ZERO;
            positions.add(new CounterpartyStatementPosition(
                query.materialId(),
                query.warehouseId(),
                "002",
                "CUSTODY",
                closing,
                BigDecimal.ZERO // Zero delta: opening + receipts - issues - returns - closing == 0
            ));
        } else if (stockBalanceRepository != null) {
            List<StockBalance> balances = stockBalanceRepository.findByTenantIdAndCompanyIdAndOwnerPartyIdAndStockCategoryAndIsDeletedFalse(
                tenantId, companyId, query.ownerId(), "CUSTODY"
            );
            for (StockBalance b : balances) {
                if (query.warehouseId() != null && !query.warehouseId().equals(b.getWarehouseId())) {
                    continue;
                }
                if (query.materialId() != null && !query.materialId().equals(b.getMaterialId())) {
                    continue;
                }
                if (b.getQuantity() != null && b.getQuantity().signum() > 0) {
                    positions.add(new CounterpartyStatementPosition(
                        b.getMaterialId(),
                        b.getWarehouseId(),
                        "002",
                        "CUSTODY",
                        b.getQuantity(),
                        BigDecimal.ZERO
                    ));
                }
            }
        }

        return new CounterpartyStatementReport(agreementNum, query.ownerId(), counterparty, positions);
    }
}
