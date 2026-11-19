package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.NonMovingStockDecisionLine;
import com.enterprise.mm.entity.NonMovingStockExecution;
import com.enterprise.mm.entity.NonMovingStockExecutionOutput;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.payload.BaseRecordDto;
import com.enterprise.mm.payload.NonMovingStockCaseLineResponse;
import com.enterprise.mm.payload.NonMovingStockCaseResponse;
import com.enterprise.mm.payload.NonMovingStockDecisionResponse;
import com.enterprise.mm.payload.NonMovingStockDecisionLineResponse;
import com.enterprise.mm.payload.NonMovingStockExecutionResponse;
import com.enterprise.mm.payload.NonMovingStockExecutionOutputResponse;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import org.springframework.stereotype.Component;

@Component
public class NonMovingStockCaseMapper {
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final StockBatchRepository stockBatchRepository;
    private final UserRepository userRepository;

    public NonMovingStockCaseMapper(
        MaterialRepository materialRepository,
        WarehouseRepository warehouseRepository,
        StorageLocationRepository storageLocationRepository,
        StockBatchRepository stockBatchRepository,
        UserRepository userRepository
    ) {
        this.materialRepository = materialRepository;
        this.warehouseRepository = warehouseRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.userRepository = userRepository;
    }

    public NonMovingStockCaseResponse toResponse(
        NonMovingStockCase entity,
        List<NonMovingStockCaseLine> lines,
        List<NonMovingStockDecision> decisions,
        List<NonMovingStockExecution> executions
    ) {
        if (entity == null) {
            return null;
        }
        NonMovingStockCaseResponse dto = new NonMovingStockCaseResponse();
        copyBase(entity, dto);
        dto.setCaseNo(entity.getCaseNo());
        dto.setDetectedAt(entity.getDetectedAt());
        dto.setReviewDueDate(entity.getReviewDueDate());
        dto.setTotalQuantity(entity.getTotalQuantity());
        dto.setTotalBookValue(entity.getTotalBookValue());
        dto.setResponsibleUser(entity.getResponsibleUser());
        dto.setResponsibleUserId(entity.getResponsibleUserId());
        dto.setResponsibleUsername(entity.getResponsibleUsername());
        dto.setResponsibleDisplayName(user(entity.getResponsibleUserId(), entity.getTenantId(), entity.getCompanyId())
            .map(item -> display(item.getFullName(), item.getName(), item.getUsername()))
            .orElse(display(entity.getResponsibleUsername(), entity.getResponsibleUser())));
        dto.setAccountingCurrencyCode(entity.getAccountingCurrencyCode());
        dto.setValuationHealth(entity.getValuationHealth());
        dto.setValuationHealthMessage(entity.getValuationHealthMessage());
        dto.setBlockerType(entity.getBlockerType());
        dto.setBlockerDetails(entity.getBlockerDetails());
        dto.setClosedAt(entity.getClosedAt());
        dto.setClosedBy(entity.getClosedBy());
        dto.setClosureReason(entity.getClosureReason());
        dto.setCancelledAt(entity.getCancelledAt());
        dto.setCancelledBy(entity.getCancelledBy());
        dto.setCancellationReason(entity.getCancellationReason());
        dto.setReopenedAt(entity.getReopenedAt());
        dto.setReopenedBy(entity.getReopenedBy());
        dto.setReopenReason(entity.getReopenReason());
        dto.setCommissionId(entity.getCommissionId());
        dto.setCommissionProtocolNo(entity.getCommissionProtocolNo());
        List<NonMovingStockCaseLineResponse> lineResponses = lines.stream().map(this::toLineResponse).toList();
        dto.setLines(lineResponses);
        dto.setLineCount(lineResponses.size());
        lineResponses.stream().findFirst().ifPresent(line -> {
            dto.setPrimaryMaterialId(line.getMaterialId());
            dto.setPrimaryMaterialCode(line.getMaterialCode());
            dto.setPrimaryMaterialName(line.getMaterialName());
            dto.setPrimaryWarehouseId(line.getWarehouseId());
            dto.setPrimaryWarehouseCode(line.getWarehouseCode());
            dto.setPrimaryWarehouseName(line.getWarehouseName());
        });
        dto.setDecisions(decisions.stream().map(this::toDecisionResponse).toList());
        dto.setExecutions(executions.stream().map(this::toExecutionResponse).toList());
        return dto;
    }

    public NonMovingStockCaseLineResponse toLineResponse(NonMovingStockCaseLine entity) {
        NonMovingStockCaseLineResponse dto = new NonMovingStockCaseLineResponse();
        copyBase(entity, dto);
        dto.setCaseId(entity.getCaseId());
        dto.setNonMovingStockId(entity.getNonMovingStockId());
        dto.setMaterialId(entity.getMaterialId());
        material(entity.getMaterialId(), entity.getTenantId(), entity.getCompanyId()).ifPresent(item -> {
            dto.setMaterialCode(item.getCode());
            dto.setMaterialName(item.getName());
        });
        dto.setWarehouseId(entity.getWarehouseId());
        warehouse(entity.getWarehouseId(), entity.getTenantId(), entity.getCompanyId()).ifPresent(item -> {
            dto.setWarehouseCode(item.getCode());
            dto.setWarehouseName(item.getName());
        });
        dto.setStorageLocationId(entity.getStorageLocationId());
        scoped(entity.getStorageLocationId() == null ? java.util.Optional.empty()
            : storageLocationRepository.findByIdAndIsDeletedFalse(entity.getStorageLocationId()), entity.getTenantId(), entity.getCompanyId())
            .ifPresent(item -> {
                dto.setStorageLocationCode(item.getCode());
                dto.setStorageLocationName(item.getName());
            });
        dto.setBatchId(entity.getBatchId());
        scoped(entity.getBatchId() == null ? java.util.Optional.empty()
            : stockBatchRepository.findByIdAndIsDeletedFalse(entity.getBatchId()), entity.getTenantId(), entity.getCompanyId())
            .ifPresent(item -> {
                dto.setBatchCode(item.getCode());
                dto.setBatchName(item.getName());
            });
        dto.setQuantity(entity.getQuantity());
        dto.setAvailableQuantity(entity.getAvailableQuantity());
        dto.setReservedQuantity(entity.getReservedQuantity());
        dto.setBlockedQuantity(entity.getBlockedQuantity());
        dto.setBookValue(entity.getBookValue());
        dto.setUnitCost(entity.getUnitCost());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setValuationHealth(entity.getValuationHealth());
        dto.setValuationHealthMessage(entity.getValuationHealthMessage());
        dto.setValuationDate(entity.getValuationDate());
        dto.setAnalysisRunId(entity.getAnalysisRunId());
        dto.setDetectionRule(entity.getDetectionRule());
        dto.setDetectionRuleVersion(entity.getDetectionRuleVersion());
        dto.setDetectionThreshold(entity.getDetectionThreshold());
        dto.setAnalysisAsOfDate(entity.getAnalysisAsOfDate());
        dto.setExclusionResult(entity.getExclusionResult());
        dto.setActiveMembership(entity.isActiveMembership());
        dto.setMonthsWithoutMovement(entity.getMonthsWithoutMovement());
        dto.setAgingBucketMonths(entity.getAgingBucketMonths());
        dto.setLastMovementAt(entity.getLastMovementAt());
        dto.setReplacementMaterialId(entity.getReplacementMaterialId());
        material(entity.getReplacementMaterialId(), entity.getTenantId(), entity.getCompanyId()).ifPresent(item -> {
            dto.setReplacementMaterialCode(item.getCode());
            dto.setReplacementMaterialName(item.getName());
        });
        dto.setMaterialStatus(entity.getMaterialStatus());
        return dto;
    }

    public NonMovingStockDecisionResponse toDecisionResponse(NonMovingStockDecision entity) {
        return toDecisionResponse(entity, List.of());
    }

    public NonMovingStockDecisionResponse toDecisionResponse(
        NonMovingStockDecision entity,
        List<NonMovingStockDecisionLine> lines
    ) {
        NonMovingStockDecisionResponse dto = new NonMovingStockDecisionResponse();
        copyBase(entity, dto);
        dto.setCaseId(entity.getCaseId());
        dto.setDecisionType(entity.getDecisionType());
        dto.setApprovedQuantity(entity.getApprovedQuantity());
        dto.setTargetWarehouseId(entity.getTargetWarehouseId());
        dto.setSalePrice(entity.getSalePrice());
        dto.setImpairmentReserveAmount(entity.getImpairmentReserveAmount());
        dto.setWriteOffReason(entity.getWriteOffReason());
        dto.setComment(entity.getComment());
        dto.setNextReviewDate(entity.getNextReviewDate());
        dto.setExpectedRecoveryAmount(entity.getExpectedRecoveryAmount());
        dto.setExpectedRecoveryCurrencyCode(entity.getExpectedRecoveryCurrencyCode());
        dto.setRecoveryBasisReference(entity.getRecoveryBasisReference());
        dto.setRecoveryAllocationBasis(entity.getRecoveryAllocationBasis());
        dto.setDecisionVersion(entity.getDecisionVersion());
        dto.setImmutable(entity.isImmutable());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setSubmittedBy(entity.getSubmittedBy());
        dto.setSupersedesDecisionId(entity.getSupersedesDecisionId());
        dto.setLines(lines == null ? List.of() : lines.stream().map(this::toDecisionLineResponse).toList());
        return dto;
    }

    public NonMovingStockDecisionLineResponse toDecisionLineResponse(NonMovingStockDecisionLine entity) {
        NonMovingStockDecisionLineResponse dto = new NonMovingStockDecisionLineResponse();
        copyBase(entity, dto);
        dto.setDecisionId(entity.getDecisionId());
        dto.setCaseLineId(entity.getCaseLineId());
        dto.setDecisionType(entity.getDecisionType());
        dto.setApprovedQuantity(entity.getApprovedQuantity());
        dto.setTargetWarehouseId(entity.getTargetWarehouseId());
        dto.setExpectedRecoveryAmount(entity.getExpectedRecoveryAmount());
        dto.setFinancialAmount(entity.getFinancialAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setExecutionDueDate(entity.getExecutionDueDate());
        dto.setControlledReason(entity.getControlledReason());
        dto.setComment(entity.getComment());
        dto.setLineOrder(entity.getLineOrder());
        return dto;
    }

    public NonMovingStockExecutionResponse toExecutionResponse(NonMovingStockExecution entity) {
        return toExecutionResponse(entity, List.of());
    }

    public NonMovingStockExecutionResponse toExecutionResponse(
        NonMovingStockExecution entity,
        List<NonMovingStockExecutionOutput> outputs
    ) {
        NonMovingStockExecutionResponse dto = new NonMovingStockExecutionResponse();
        copyBase(entity, dto);
        dto.setCaseId(entity.getCaseId());
        dto.setDecisionId(entity.getDecisionId());
        dto.setExecutionStatus(entity.getExecutionStatus());
        if ("PENDING".equals(entity.getExecutionStatus())) {
            dto.setStockMovementDocumentId(entity.getStockMovementId());
        } else {
            dto.setStockMovementId(entity.getStockMovementId());
        }
        dto.setValuationDocumentId(entity.getValuationDocumentId());
        dto.setIntegrationEventId(entity.getIntegrationEventId());
        dto.setIdempotencyKey(entity.getIdempotencyKey());
        dto.setAttemptNo(entity.getAttemptNo());
        dto.setReconciliationStatus(entity.getReconciliationStatus());
        dto.setFailureSummary(entity.getFailureSummary());
        dto.setStartedAt(entity.getStartedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setOutputs(outputs == null ? List.of() : outputs.stream().map(this::toExecutionOutputResponse).toList());
        return dto;
    }

    public NonMovingStockExecutionOutputResponse toExecutionOutputResponse(NonMovingStockExecutionOutput entity) {
        NonMovingStockExecutionOutputResponse dto = new NonMovingStockExecutionOutputResponse();
        copyBase(entity, dto);
        dto.setExecutionId(entity.getExecutionId());
        dto.setDecisionLineId(entity.getDecisionLineId());
        dto.setCaseLineId(entity.getCaseLineId());
        dto.setOutputType(entity.getOutputType());
        dto.setDocumentType(entity.getDocumentType());
        dto.setDocumentId(entity.getDocumentId());
        dto.setDocumentNumber(entity.getDocumentNumber());
        dto.setOutputStatus(entity.getOutputStatus());
        dto.setQuantity(entity.getQuantity());
        dto.setAmount(entity.getAmount());
        dto.setCurrencyCode(entity.getCurrencyCode());
        dto.setErrorCode(entity.getErrorCode());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setCompletedAt(entity.getCompletedAt());
        return dto;
    }

    private void copyBase(com.enterprise.mm.common.BusinessEntity entity, BaseRecordDto dto) {
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
    }

    private java.util.Optional<Material> material(UUID id, String tenantId, UUID companyId) {
        if (id == null) return java.util.Optional.empty();
        return materialRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, id)
            .filter(item -> Objects.equals(companyId, item.getCompanyId()));
    }

    private java.util.Optional<Warehouse> warehouse(UUID id, String tenantId, UUID companyId) {
        if (id == null) return java.util.Optional.empty();
        return warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, id)
            .filter(item -> Objects.equals(companyId, item.getCompanyId()));
    }

    private java.util.Optional<User> user(UUID id, String tenantId, UUID companyId) {
        if (id == null) return java.util.Optional.empty();
        return userRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> Objects.equals(tenantId, item.getTenantId()) && Objects.equals(companyId, item.getCompanyId()));
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> java.util.Optional<T> scoped(
        java.util.Optional<T> entity, String tenantId, UUID companyId
    ) {
        return entity.filter(item -> Objects.equals(tenantId, item.getTenantId()) && Objects.equals(companyId, item.getCompanyId()));
    }

    private String display(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
