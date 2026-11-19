package com.enterprise.mm.supplier.governance.readiness;

import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierActivationRecord;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.SupplierActivationCommand;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.IntegrationEventService;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessAction;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.OverallStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierActivationService {
    private final SupplierRepository supplierRepository;
    private final SupplierActivationRecordRepository activationRepository;
    private final SupplierReadinessService readinessService;
    private final IdempotencyService idempotencyService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy supplierGovernanceActionPolicy;

    @Transactional
    public SupplierReadinessSummary activate(
        UUID supplierId,
        SupplierActivationCommand command,
        String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency-Key is required");
        }
        return idempotencyService.execute(
            "SUPPLIER_ACTIVATE",
            supplierId,
            idempotencyKey,
            command,
            SupplierReadinessSummary.class,
            () -> activateOnce(supplierId, command)
        );
    }

    private SupplierReadinessSummary activateOnce(UUID supplierId, SupplierActivationCommand command) {
        supplierGovernanceActionPolicy.requireSupplierActionAllowed("ACTIVATE_SUPPLIER");
        Supplier supplier = supplierRepository.findByIdAndIsDeletedFalse(supplierId)
            .orElseThrow(() -> new NotFoundException("Supplier not found: " + supplierId));
        if (!Objects.equals(command.expectedVersion(), supplier.getVersion())) {
            throw new ConflictException("Supplier version changed; refresh readiness and retry");
        }

        SupplierReadinessSummary readiness = readinessService.get(supplierId);
        if (!readiness.activationEligible()) {
            if (!readiness.blockers().isEmpty()) {
                throw new SupplierReadinessBlockedException(readiness.blockers().get(0));
            }
            throw new BusinessException("Supplier is not eligible for activation");
        }

        LocalDateTime activatedAt = LocalDateTime.now();
        SupplierActivationRecord record = new SupplierActivationRecord();
        record.setSupplierId(supplierId);
        record.setActivatedBy(currentUsername());
        record.setActivatedAt(activatedAt);
        record.setActivationComment(command.comment().trim());
        record.setReadinessSnapshotJson(serialize(readiness));
        record.setPolicyVersion(readiness.policyVersion());
        record.setCode("ACT-" + supplierId.toString().substring(0, 8));
        record.setName("Supplier activation");
        record.setStatus("RECORDED");
        activationRepository.save(record);

        supplier.setStatus(SupplierStatus.ACTIVE.name());
        supplier.setBlockedReason(null);
        supplierRepository.save(supplier);

        integrationEventService.emitInCurrentTransaction(
            "SUPPLIER_ACTIVATED",
            "Supplier",
            supplierId,
            Map.of(
                "supplierId", supplierId.toString(),
                "supplierCode", supplier.getCode(),
                "supplierName", supplier.getName(),
                "policyVersion", readiness.policyVersion(),
                "activatedAt", activatedAt.toString(),
                "activationRecordId", record.getId(),
                "overallStatus", readiness.overallStatus().name(),
                "readinessScore", readiness.readinessPercent(),
                "blockersCount", readiness.blockers() != null ? readiness.blockers().size() : 0
            )
        );
        auditService.logInCurrentTransaction(
            "Supplier",
            supplierId,
            "ACTIVATE",
            command.comment(),
            readiness,
            Map.of("status", SupplierStatus.ACTIVE.name(), "activationRecordId", String.valueOf(record.getId()))
        );
        return activated(readiness, activatedAt);
    }

    private SupplierReadinessSummary activated(SupplierReadinessSummary readiness, LocalDateTime activatedAt) {
        var stages = readiness.stages().stream().map(stage ->
            stage.key() == SupplierReadinessEnums.StageKey.ACTIVATION
                ? new SupplierReadinessDtos.ReadinessStage(stage.key(), SupplierReadinessEnums.StageState.COMPLETED,
                    stage.totalItems(), stage.totalItems(), List.of())
                : stage
        ).toList();
        int totalItems = stages.stream().mapToInt(SupplierReadinessDtos.ReadinessStage::totalItems).sum();
        int completedItems = stages.stream().mapToInt(SupplierReadinessDtos.ReadinessStage::completedItems).sum();
        return new SupplierReadinessSummary(
            OverallStatus.ACTIVE,
            totalItems == 0 ? 0 : Math.min(100, Math.round(completedItems * 100f / totalItems)),
            false,
            stages,
            readiness.scopes(),
            readiness.blockers(),
            new ReadinessAction("NONE", false, "No readiness action required", null, null, null),
            activatedAt,
            readiness.policyVersion()
        );
    }

    private String serialize(SupplierReadinessSummary readiness) {
        try {
            return objectMapper.writeValueAsString(readiness);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("Cannot serialize supplier readiness snapshot");
        }
    }

    private String currentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
            ? "system"
            : authentication.getName();
    }
}
