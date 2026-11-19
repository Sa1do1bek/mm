package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementCampaignStatus;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementAwardFinalizationOrchestrator {
    private final ProcurementAwardOutcomeRepository outcomeRepository;
    private final ProcurementProcedureRepository procedureRepository;
    private final ProcurementCampaignRepository campaignRepository;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final ProcurementAwardOutcomeLineService outcomeLineService;

    public record AwardFinalizationCommand(
        UUID procurementProcedureId,
        ProcurementAwardSourceType sourceType,
        UUID sourceId,
        UUID awardedSupplierId,
        UUID purchaseOrderId,
        UUID contractId,
        BigDecimal awardAmount,
        String currencyCode,
        String awardReference,
        ProcurementProcedureStatus procedureStatus,
        String eventType,
        String auditAction
    ) {
    }

    @Transactional
    public ProcurementAwardOutcome finalizeAward(AwardFinalizationCommand command) {
        requireCommand(command);
        ProcurementProcedure procedure = procedureRepository.lockScopedById(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            command.procurementProcedureId()
        ).orElseThrow(() -> new NotFoundException(
            "ProcurementProcedure not found: " + command.procurementProcedureId()));
        assertCompatible("awardedSupplierId", procedure.getAwardedSupplierId(), command.awardedSupplierId());
        assertCompatible("purchaseOrderId", procedure.getPurchaseOrderId(), command.purchaseOrderId());
        assertCompatible("contractId", procedure.getContractId(), command.contractId());

        String idempotencyKey = idempotencyKey(command);
        ProcurementAwardOutcome existing = outcomeRepository
            .findByTenantIdAndCompanyIdAndProcurementProcedureIdAndSourceTypeAndIdempotencyKeyAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                procedure.getId(),
                command.sourceType(),
                idempotencyKey
            ).orElse(null);
        if (existing != null) {
            applyProcedureOutcome(procedure, existing, command.procedureStatus());
            outcomeLineService.recordLines(existing);
            return existing;
        }

        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setCode("PAO-" + shortId());
        outcome.setName(BusinessNameGenerator.workflowDocumentName("ProcurementAwardOutcome", procedure.getName()));
        outcome.setStatus("FINALIZED");
        outcome.setProcurementProcedureId(procedure.getId());
        outcome.setSourceType(command.sourceType());
        outcome.setSourceId(command.sourceId());
        outcome.setAwardedSupplierId(command.awardedSupplierId());
        outcome.setPurchaseOrderId(command.purchaseOrderId());
        outcome.setContractId(command.contractId());
        outcome.setAwardAmount(amount(command.awardAmount()));
        outcome.setCurrencyCode(currency(command.currencyCode()));
        outcome.setAwardReference(trim(command.awardReference()));
        outcome.setIdempotencyKey(idempotencyKey);
        outcome.setFinalizedAt(LocalDateTime.now());
        ProcurementAwardOutcome saved = outcomeRepository.save(outcome);

        applyProcedureOutcome(procedure, saved, command.procedureStatus());
        outcomeLineService.recordLines(saved);
        emit(command, saved);
        audit(command, saved);
        return saved;
    }

    private void applyProcedureOutcome(
        ProcurementProcedure procedure,
        ProcurementAwardOutcome outcome,
        ProcurementProcedureStatus targetStatus
    ) {
        assertCompatible("awardedSupplierId", procedure.getAwardedSupplierId(), outcome.getAwardedSupplierId());
        assertCompatible("purchaseOrderId", procedure.getPurchaseOrderId(), outcome.getPurchaseOrderId());
        assertCompatible("contractId", procedure.getContractId(), outcome.getContractId());
        if (outcome.getAwardedSupplierId() != null) {
            procedure.setAwardedSupplierId(outcome.getAwardedSupplierId());
        }
        if (outcome.getPurchaseOrderId() != null) {
            procedure.setPurchaseOrderId(outcome.getPurchaseOrderId());
        }
        if (outcome.getContractId() != null) {
            procedure.setContractId(outcome.getContractId());
        }
        if (outcome.getAwardAmount() != null) {
            procedure.setFinalizedAmount(outcome.getAwardAmount());
        }
        if (outcome.getCurrencyCode() != null) {
            procedure.setFinalizedCurrencyCode(outcome.getCurrencyCode());
        }
        if (outcome.getAwardReference() != null) {
            procedure.setAwardReference(outcome.getAwardReference());
        }
        if (targetStatus != null) {
            procedure.setStatus(targetStatus.name());
            if (targetStatus == ProcurementProcedureStatus.COMPLETED && procedure.getCompletedAt() == null) {
                procedure.setCompletedAt(LocalDateTime.now());
            }
        }
        procedureRepository.save(procedure);
        if (targetStatus == ProcurementProcedureStatus.COMPLETED) {
            completeCampaign(procedure);
        }
    }

    private void completeCampaign(ProcurementProcedure procedure) {
        if (procedure.getCampaignId() == null) {
            return;
        }
        ProcurementCampaign campaign = campaignRepository.findByIdAndIsDeletedFalse(procedure.getCampaignId())
            .filter(this::scoped)
            .orElse(null);
        if (campaign == null) {
            return;
        }
        campaign.setStatus(ProcurementCampaignStatus.COMPLETED.name());
        if (campaign.getCompletedAt() == null) {
            campaign.setCompletedAt(LocalDateTime.now());
        }
        campaignRepository.save(campaign);
    }

    private void emit(AwardFinalizationCommand command, ProcurementAwardOutcome outcome) {
        String eventType = trim(command.eventType());
        if (eventType == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("procedureId", outcome.getProcurementProcedureId());
        putIfPresent(payload, "outcomeId", outcome.getId());
        putIfPresent(payload, "sourceType", outcome.getSourceType().name());
        putIfPresent(payload, "sourceId", outcome.getSourceId());
        putIfPresent(payload, "supplierId", outcome.getAwardedSupplierId());
        putIfPresent(payload, "purchaseOrderId", outcome.getPurchaseOrderId());
        putIfPresent(payload, "contractId", outcome.getContractId());
        integrationEventService.emitIdempotentInCurrentTransaction(
            eventType,
            "ProcurementProcedure",
            outcome.getProcurementProcedureId(),
            payload,
            "AWARD_OUTCOME:" + outcome.getId() + ":" + eventType
        );
    }

    private void audit(AwardFinalizationCommand command, ProcurementAwardOutcome outcome) {
        String action = trim(command.auditAction());
        if (action != null) {
            auditService.log("ProcurementProcedure", outcome.getProcurementProcedureId(), action,
                outcome.getAwardReference(), null, outcome);
        }
    }

    private void requireCommand(AwardFinalizationCommand command) {
        if (command == null) {
            throw new BusinessException("Award finalization command is required");
        }
        if (command.procurementProcedureId() == null) {
            throw new BusinessException("procurementProcedureId is required for award finalization");
        }
        if (command.sourceType() == null) {
            throw new BusinessException("sourceType is required for award finalization");
        }
        requireCommercialDocumentPolicy(command);
    }

    private void requireCommercialDocumentPolicy(AwardFinalizationCommand command) {
        switch (command.sourceType()) {
            case RFQ_CONTRACT, FRAMEWORK_AWARD -> requireContract(command);
            case FRAMEWORK_CALLOFF -> {
                requireContract(command);
                requirePurchaseOrder(command);
            }
            case RFQ_QUOTATION, DIRECT_AWARD, MANAGED_AUCTION -> requirePurchaseOrder(command);
            default -> throw new BusinessException("Unsupported procurement award source type");
        }
    }

    private void requireContract(AwardFinalizationCommand command) {
        if (command.contractId() == null) {
            throw new BusinessException(
                "contractId is required for " + command.sourceType() + " award finalization"
            );
        }
    }

    private void requirePurchaseOrder(AwardFinalizationCommand command) {
        if (command.purchaseOrderId() == null) {
            throw new BusinessException(
                "purchaseOrderId is required for " + command.sourceType() + " award finalization"
            );
        }
    }

    private void assertCompatible(String field, UUID existing, UUID incoming) {
        if (existing != null && incoming != null && !existing.equals(incoming)) {
            throw new BusinessException("Procurement procedure already has a different " + field);
        }
    }

    private String idempotencyKey(AwardFinalizationCommand command) {
        if (command.sourceId() != null) {
            return command.sourceId().toString();
        }
        if (command.purchaseOrderId() != null) {
            return "PO:" + command.purchaseOrderId();
        }
        if (command.contractId() != null) {
            return "CONTRACT:" + command.contractId();
        }
        return "PROCEDURE";
    }

    private String currency(String value) {
        String normalized = value == null || value.isBlank()
            ? "UZS"
            : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new BusinessException("Currency code must contain three letters");
        }
        return normalized;
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
