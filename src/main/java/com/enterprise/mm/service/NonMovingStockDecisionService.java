package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.NonMovingStockDecisionLine;
import com.enterprise.mm.enums.NonMovingStockDecisionType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.NonMovingStockDecisionCreateRequest;
import com.enterprise.mm.payload.NonMovingStockDecisionLineRequest;
import com.enterprise.mm.repository.NonMovingStockDecisionLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NonMovingStockDecisionService {
    private static final Set<NonMovingStockDecisionType> EXECUTABLE_LINE_TYPES = Set.of(
        NonMovingStockDecisionType.TRANSFER,
        NonMovingStockDecisionType.WRITE_OFF,
        NonMovingStockDecisionType.SCRAP,
        NonMovingStockDecisionType.IMPAIRMENT_RESERVE
    );

    private final NonMovingStockDecisionRepository decisionRepository;
    private final NonMovingStockDecisionLineRepository decisionLineRepository;


    public void validateAllocations(
        List<NonMovingStockCaseLine> caseLines,
        List<NonMovingStockDecisionLineRequest> requests
    ) {
        validateAllocations(caseLines, requests, true);
    }

    private void validateAllocations(
        List<NonMovingStockCaseLine> caseLines,
        List<NonMovingStockDecisionLineRequest> requests,
        boolean explicitLinePackage
    ) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException("At least one decision line is required");
        }
        Map<UUID, NonMovingStockCaseLine> caseLineById = new HashMap<>();
        for (NonMovingStockCaseLine line : caseLines == null ? List.<NonMovingStockCaseLine>of() : caseLines) {
            caseLineById.put(line.getId(), line);
        }
        Map<UUID, BigDecimal> allocatedByLine = new LinkedHashMap<>();
        Map<UUID, BigDecimal> impairmentByLine = new LinkedHashMap<>();
        Map<UUID, BigDecimal> impairmentQuantityByLine = new LinkedHashMap<>();
        for (NonMovingStockDecisionLineRequest request : requests) {
            if (request == null || request.getCaseLineId() == null) {
                throw new BusinessException("Decision case line is required");
            }
            NonMovingStockCaseLine caseLine = caseLineById.get(request.getCaseLineId());
            if (caseLine == null) {
                throw new BusinessException("Decision line does not belong to the case: " + request.getCaseLineId());
            }
            BigDecimal quantity = nvl(request.getApprovedQuantity());
            if (quantity.signum() <= 0) {
                throw new BusinessException("Approved decision quantity must be greater than zero");
            }
            NonMovingStockDecisionType type = decisionType(request.getDecisionType());
            if (explicitLinePackage && !EXECUTABLE_LINE_TYPES.contains(type)) {
                throw new BusinessException("Decision type is not executable for a decision line: " + type.name());
            }
            validateTypeRules(type, caseLine, request);
            allocatedByLine.merge(caseLine.getId(), quantity, BigDecimal::add);
            if (type == NonMovingStockDecisionType.IMPAIRMENT_RESERVE) {
                impairmentByLine.merge(caseLine.getId(), nvl(request.getFinancialAmount()), BigDecimal::add);
                impairmentQuantityByLine.merge(caseLine.getId(), quantity, BigDecimal::add);
            }
        }
        caseLineById.forEach((caseLineId, caseLine) -> {
            BigDecimal available = nvl(caseLine.getAvailableQuantity());
            BigDecimal eligible = explicitLinePackage || available.signum() > 0
                ? available
                : nvl(caseLine.getQuantity());
            BigDecimal allocated = allocatedByLine.getOrDefault(caseLineId, BigDecimal.ZERO);
            if (allocated.compareTo(eligible) > 0) {
                throw new BusinessException(
                    "Approved quantity exceeds eligible quantity for case line " + caseLineId
                );
            }
            if (explicitLinePackage && eligible.signum() > 0 && allocated.compareTo(eligible) != 0) {
                throw new BusinessException(
                    "Eligible quantity must be fully allocated for case line " + caseLineId
                );
            }
            if (impairmentByLine.getOrDefault(caseLineId, BigDecimal.ZERO)
                .compareTo(nvl(caseLine.getBookValue())) > 0) {
                throw new BusinessException(
                    "aggregate impairment amount exceeds case-line book value for case line " + caseLineId
                );
            }
            BigDecimal impairmentQuantity = impairmentQuantityByLine.getOrDefault(caseLineId, BigDecimal.ZERO);
            BigDecimal carryingQuantity = nvl(caseLine.getQuantity()).signum() > 0
                ? nvl(caseLine.getQuantity())
                : eligible;
            if (impairmentQuantity.signum() > 0 && carryingQuantity.signum() > 0) {
                BigDecimal proportionalCarryingValue = nvl(caseLine.getBookValue())
                    .multiply(impairmentQuantity)
                    .divide(carryingQuantity, 8, RoundingMode.HALF_UP);
                if (impairmentByLine.getOrDefault(caseLineId, BigDecimal.ZERO)
                    .compareTo(proportionalCarryingValue) > 0) {
                    throw new BusinessException(
                        "aggregate impairment amount exceeds proportional carrying value for case line " + caseLineId
                    );
                }
            }
        });
    }

    public void requireEditable(NonMovingStockDecision decision) {
        if (decision == null) {
            throw new BusinessException("Decision is required");
        }
        if (decision.isImmutable() || List.of("APPROVAL_PENDING", "APPROVED", "DECISION_APPROVED").contains(decision.getStatus())) {
            throw new BusinessException("Submitted non-moving stock decision is immutable; create a new version");
        }
    }

    @Transactional
    public List<NonMovingStockDecisionLine> persistLines(
        NonMovingStockDecision decision,
        List<NonMovingStockCaseLine> caseLines,
        NonMovingStockDecisionCreateRequest request
    ) {
        List<NonMovingStockDecisionLineRequest> requestedLines = request == null ? List.of() : request.getLines();
        boolean explicitLinePackage = requestedLines != null && !requestedLines.isEmpty();
        if (!explicitLinePackage) {
            requestedLines = legacyAllocations(decision, caseLines, request);
        }
        validateAllocations(caseLines, requestedLines, explicitLinePackage);
        List<NonMovingStockDecisionLine> entities = new ArrayList<>();
        for (int index = 0; index < requestedLines.size(); index++) {
            NonMovingStockDecisionLineRequest lineRequest = requestedLines.get(index);
            NonMovingStockDecisionLine line = new NonMovingStockDecisionLine();
            line.setCompanyId(decision.getCompanyId());
            line.setDecisionId(decision.getId());
            line.setCaseLineId(lineRequest.getCaseLineId());
            line.setDecisionType(decisionType(lineRequest.getDecisionType()).name());
            line.setApprovedQuantity(lineRequest.getApprovedQuantity());
            line.setTargetWarehouseId(lineRequest.getTargetWarehouseId());
            line.setExpectedRecoveryAmount(lineRequest.getExpectedRecoveryAmount());
            line.setFinancialAmount(lineRequest.getFinancialAmount());
            line.setCurrencyCode(normalizeCurrency(lineRequest.getCurrencyCode()));
            line.setExecutionDueDate(lineRequest.getExecutionDueDate());
            line.setControlledReason(trim(lineRequest.getControlledReason()));
            line.setComment(trim(lineRequest.getComment()));
            line.setLineOrder(index);
            line.setCode("NMS-DL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            line.setName(line.getDecisionType() + " decision line");
            line.setStatus(decision.getStatus());
            entities.add(line);
        }
        return decisionLineRepository.saveAll(entities);
    }

    @Transactional
    public NonMovingStockDecision markSubmitted(NonMovingStockDecision decision) {
        decision.setImmutable(true);
        decision.setSubmittedAt(LocalDateTime.now());
        decision.setSubmittedBy(currentUsername());
        return decisionRepository.save(decision);
    }

    @Transactional(readOnly = true)
    public List<NonMovingStockDecisionLine> lines(UUID decisionId) {
        return decisionLineRepository.findByTenantIdAndDecisionIdAndIsDeletedFalseOrderByLineOrderAsc(
            TenantContext.currentTenant(),
            decisionId
        );
    }

    private List<NonMovingStockDecisionLineRequest> legacyAllocations(
        NonMovingStockDecision decision,
        List<NonMovingStockCaseLine> caseLines,
        NonMovingStockDecisionCreateRequest request
    ) {
        BigDecimal remaining = nvl(decision.getApprovedQuantity());
        List<NonMovingStockDecisionLineRequest> result = new ArrayList<>();
        for (NonMovingStockCaseLine caseLine : caseLines) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal eligible = nvl(caseLine.getAvailableQuantity()).signum() > 0
                ? nvl(caseLine.getAvailableQuantity())
                : nvl(caseLine.getQuantity());
            BigDecimal allocated = eligible.min(remaining);
            if (allocated.signum() <= 0) {
                continue;
            }
            NonMovingStockDecisionLineRequest line = new NonMovingStockDecisionLineRequest();
            line.setCaseLineId(caseLine.getId());
            line.setDecisionType(decision.getDecisionType());
            line.setApprovedQuantity(allocated);
            line.setTargetWarehouseId(decision.getTargetWarehouseId());
            line.setExpectedRecoveryAmount(decision.getExpectedRecoveryAmount());
            line.setFinancialAmount(decision.getImpairmentReserveAmount());
            line.setCurrencyCode(decision.getExpectedRecoveryCurrencyCode());
            line.setExecutionDueDate(decision.getNextReviewDate());
            line.setControlledReason(decision.getWriteOffReason());
            line.setComment(decision.getComment());
            result.add(line);
            remaining = remaining.subtract(allocated);
        }
        if (remaining.signum() > 0) {
            throw new BusinessException("Approved quantity exceeds eligible case-line quantity");
        }
        return result;
    }

    private void validateTypeRules(
        NonMovingStockDecisionType type,
        NonMovingStockCaseLine caseLine,
        NonMovingStockDecisionLineRequest request
    ) {
        if (type == NonMovingStockDecisionType.TRANSFER) {
            if (request.getTargetWarehouseId() == null) {
                throw new BusinessException("Target warehouse is required for transfer decision line");
            }
            if (request.getTargetWarehouseId().equals(caseLine.getWarehouseId())) {
                throw new BusinessException("Transfer target warehouse must be different from source warehouse");
            }
        }
        if ((type == NonMovingStockDecisionType.WRITE_OFF || type == NonMovingStockDecisionType.SCRAP)
            && (request.getControlledReason() == null || request.getControlledReason().isBlank())) {
            throw new BusinessException("Controlled reason is required for " + type.name());
        }
        if (request.getFinancialAmount() != null && request.getFinancialAmount().signum() < 0) {
            throw new BusinessException("Decision financial amount cannot be negative");
        }
        if (type == NonMovingStockDecisionType.IMPAIRMENT_RESERVE
            && nvl(request.getFinancialAmount()).signum() <= 0) {
            throw new BusinessException("Positive impairment amount is required for every impairment decision line");
        }
        if (type == NonMovingStockDecisionType.IMPAIRMENT_RESERVE
            && request.getFinancialAmount() != null
            && request.getFinancialAmount().compareTo(nvl(caseLine.getBookValue())) > 0) {
            throw new BusinessException("Impairment amount cannot exceed case-line book value");
        }
    }

    private NonMovingStockDecisionType decisionType(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Decision type is required for every line");
        }
        try {
            return NonMovingStockDecisionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Unsupported decision type: " + value);
        }
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizeCurrency(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
