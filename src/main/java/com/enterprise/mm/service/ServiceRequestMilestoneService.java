package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceRequestMilestone;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.IntakeMilestone;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceRequestMilestoneRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {"listForRequest"})
public class ServiceRequestMilestoneService {
    private static final Set<String> ACCEPTANCE_READY_STATUSES = Set.of(
        ServiceRequestStatus.WAITING_ACCEPTANCE.name(),
        ServiceRequestStatus.PARTIALLY_ACCEPTED.name()
    );
    private static final Set<String> FINISHED_EXECUTION_STATUSES = Set.of(
        ServiceExecutionStatus.ACCEPTED.name(),
        ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name(),
        ServiceExecutionStatus.CANCELLED.name()
    );

    private final ServiceRequestMilestoneRepository milestoneRepository;
    private final ServiceRequestRepository requestRepository;
    private final ServiceExecutionRepository executionRepository;
    private final AuditService auditService;
    private final ServiceReviewHistoryService historyService;

    public record Acceptance(
        BigDecimal acceptedVolume,
        BigDecimal rejectedVolume,
        boolean finalAcceptance,
        String remarks
    ) {
    }

    @Transactional
    public List<ServiceRequestMilestone> createForRequest(UUID requestId, List<IntakeMilestone> definitions) {
        if (definitions == null || definitions.isEmpty()) return List.of();
        ServiceRequest request = findRequest(requestId);
        String mode = normalizeMode(request.getAcceptanceMode());
        BigDecimal requestBasis = requestBasis(request, mode);
        BigDecimal allocated = BigDecimal.ZERO;
        Set<String> names = new HashSet<>();

        for (IntakeMilestone definition : definitions) {
            validateDefinition(request, definition, mode, names);
            allocated = allocated.add("QUANTITY".equals(mode) ? definition.quantity() : definition.amount());
        }
        if (allocated.compareTo(requestBasis) != 0) {
            throw new BusinessException(
                "Milestone allocation must equal ordered " + mode.toLowerCase(Locale.ROOT)
            );
        }

        List<ServiceRequestMilestone> milestones = java.util.stream.IntStream.range(0, definitions.size())
            .mapToObj(index -> milestone(request, definitions.get(index), mode, index + 1))
            .toList();
        List<ServiceRequestMilestone> saved = milestoneRepository.saveAll(milestones);
        auditService.logInCurrentTransaction(
            "ServiceRequest", requestId, "CREATE_MILESTONES", "count=" + saved.size()
        , null, null);
        return List.copyOf(saved);
    }

    @Transactional(readOnly = true)
    public List<ServiceRequestMilestone> listForRequest(UUID requestId) {
        return List.copyOf(milestoneRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderBySequenceNumberAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), requestId
            ));
    }

    @Transactional
    public ServiceRequestMilestone accept(
        UUID requestId,
        UUID milestoneId,
        Long expectedVersion,
        Acceptance acceptance
    ) {
        ServiceRequest request = findRequest(requestId);
        if (!ACCEPTANCE_READY_STATUSES.contains(request.getStatus())) {
            throw new BusinessException("Service request is not ready for milestone acceptance");
        }
        ServiceRequestMilestone milestone = milestoneRepository.findScoped(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), requestId, milestoneId
            )
            .orElseThrow(() -> new NotFoundException("Service request milestone not found: " + milestoneId));
        requireVersion(milestone.getVersion(), expectedVersion);
        requireFinishedExecutions(requestId);

        BigDecimal accepted = nvl(acceptance == null ? null : acceptance.acceptedVolume());
        BigDecimal rejected = nvl(acceptance == null ? null : acceptance.rejectedVolume());
        if (accepted.signum() < 0 || rejected.signum() < 0 || accepted.add(rejected).signum() <= 0) {
            throw new BusinessException("Accepted or rejected milestone volume must be positive");
        }
        String remarks = trim(acceptance == null ? null : acceptance.remarks());
        if (rejected.signum() > 0 && remarks == null) {
            throw new BusinessException("Remarks are required when milestone volume is rejected");
        }

        String mode = normalizeMode(milestone.getAcceptanceMode());
        if ("QUANTITY".equals(mode)) {
            applyQuantity(milestone, accepted, rejected);
        } else {
            applyAmount(milestone, accepted, rejected);
        }
        BigDecimal remaining = "QUANTITY".equals(mode)
            ? milestone.getRemainingQuantity()
            : milestone.getRemainingAmount();
        if (acceptance != null && acceptance.finalAcceptance() && nvl(remaining).signum() > 0) {
            throw new BusinessException("Final acceptance must cover the full remaining milestone basis");
        }

        milestone.setAcceptanceRemarks(remarks);
        milestone.setStatus(milestoneStatus(milestone, mode));
        milestone.setAcceptedAt("ACCEPTED".equals(milestone.getStatus()) ? LocalDateTime.now() : null);
        ServiceRequestMilestone saved = milestoneRepository.saveAndFlush(milestone);
        recomputeRequest(request, listForRequest(requestId), mode, remarks);
        historyService.record(requestId, null, "MILESTONE_ACCEPTANCE", saved.getStatus(), remarks);
        auditService.logInCurrentTransaction(
            "ServiceRequestMilestone", saved.getId(), "ACCEPT",
            "accepted=" + accepted + ", rejected=" + rejected + ", final="
                + (acceptance != null && acceptance.finalAcceptance())
        , null, null);
        return saved;
    }

    private ServiceRequest findRequest(UUID requestId) {
        return requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), requestId)
            .filter(request -> CompanyContext.currentCompany().equals(request.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ServiceRequest not found: " + requestId));
    }

    private void validateDefinition(
        ServiceRequest request,
        IntakeMilestone definition,
        String mode,
        Set<String> names
    ) {
        if (definition == null || trim(definition.name()) == null) {
            throw new BusinessException("Every service milestone requires a name");
        }
        if (!names.add(definition.name().trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException("Service milestone names must be unique within a request");
        }
        if (definition.dueDate() != null
            && ((request.getPlannedStartDate() != null && definition.dueDate().isBefore(request.getPlannedStartDate()))
                || (request.getPlannedEndDate() != null && definition.dueDate().isAfter(request.getPlannedEndDate())))) {
            throw new BusinessException("Service milestone due dates must be within the request planned period");
        }
        if ("QUANTITY".equals(mode)) {
            if (definition.quantity() == null || definition.quantity().signum() <= 0 || definition.amount() != null) {
                throw new BusinessException("QUANTITY milestones require a positive quantity allocation and no amount allocation");
            }
        } else if (definition.amount() == null || definition.amount().signum() <= 0 || definition.quantity() != null) {
            throw new BusinessException("AMOUNT milestones require a positive amount allocation and no quantity allocation");
        }
    }

    private ServiceRequestMilestone milestone(
        ServiceRequest request,
        IntakeMilestone definition,
        String mode,
        int sequence
    ) {
        ServiceRequestMilestone milestone = new ServiceRequestMilestone();
        milestone.setTenantId(request.getTenantId());
        milestone.setCompanyId(request.getCompanyId());
        milestone.setServiceRequestId(request.getId());
        milestone.setSequenceNumber(sequence);
        milestone.setCode(milestoneCode(request.getCode(), sequence));
        milestone.setName(definition.name().trim());
        milestone.setStatus("PENDING");
        milestone.setDueDate(definition.dueDate());
        milestone.setAcceptanceMode(mode);
        if ("QUANTITY".equals(mode)) {
            milestone.setOrderedQuantity(definition.quantity());
            milestone.setAcceptedQuantity(BigDecimal.ZERO);
            milestone.setRejectedQuantity(BigDecimal.ZERO);
            milestone.setUomCode(request.getUomCode());
        } else {
            milestone.setOrderedAmount(definition.amount());
            milestone.setAcceptedAmount(BigDecimal.ZERO);
            milestone.setRejectedAmount(BigDecimal.ZERO);
            milestone.setCurrencyCode(request.getCurrencyCode());
        }
        return milestone;
    }

    private void requireFinishedExecutions(UUID requestId) {
        List<ServiceExecution> executions = executionRepository
            .findByTenantIdAndServiceRequestIdAndIsDeletedFalse(TenantContext.currentTenant(), requestId);
        if (executions.isEmpty()) return;
        boolean atLeastOneAccepted = executions.stream().anyMatch(item -> Set.of(
            ServiceExecutionStatus.ACCEPTED.name(), ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name()
        ).contains(item.getStatus()));
        boolean everyExecutionFinished = executions.stream()
            .allMatch(item -> FINISHED_EXECUTION_STATUSES.contains(item.getStatus()));
        if (!atLeastOneAccepted || !everyExecutionFinished) {
            throw new BusinessException(
                "Service request milestone acceptance requires all active executions to be accepted or cancelled"
            );
        }
    }

    private void applyQuantity(ServiceRequestMilestone milestone, BigDecimal accepted, BigDecimal rejected) {
        BigDecimal ordered = requiredPositive(milestone.getOrderedQuantity(), "ordered milestone quantity");
        BigDecimal currentRejected = nvl(milestone.getRejectedQuantity());
        BigDecimal correctedRejection = accepted.min(currentRejected);
        BigDecimal nextAccepted = nvl(milestone.getAcceptedQuantity()).add(accepted);
        BigDecimal nextRejected = currentRejected.subtract(correctedRejection).add(rejected);
        rejectOverAcceptance(nextAccepted, nextRejected, ordered, "quantity");
        milestone.setAcceptedQuantity(nextAccepted);
        milestone.setRejectedQuantity(nextRejected);
    }

    private void applyAmount(ServiceRequestMilestone milestone, BigDecimal accepted, BigDecimal rejected) {
        BigDecimal ordered = requiredPositive(milestone.getOrderedAmount(), "ordered milestone amount");
        BigDecimal currentRejected = nvl(milestone.getRejectedAmount());
        BigDecimal correctedRejection = accepted.min(currentRejected);
        BigDecimal nextAccepted = nvl(milestone.getAcceptedAmount()).add(accepted);
        BigDecimal nextRejected = currentRejected.subtract(correctedRejection).add(rejected);
        rejectOverAcceptance(nextAccepted, nextRejected, ordered, "amount");
        milestone.setAcceptedAmount(nextAccepted);
        milestone.setRejectedAmount(nextRejected);
    }

    private void recomputeRequest(
        ServiceRequest request,
        List<ServiceRequestMilestone> milestones,
        String mode,
        String remarks
    ) {
        BigDecimal accepted = milestones.stream()
            .map(item -> "QUANTITY".equals(mode) ? item.getAcceptedQuantity() : item.getAcceptedAmount())
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rejected = milestones.stream()
            .map(item -> "QUANTITY".equals(mode) ? item.getRejectedQuantity() : item.getRejectedAmount())
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if ("QUANTITY".equals(mode)) {
            request.setAcceptedQuantity(accepted);
            request.setRejectedQuantity(rejected);
        } else {
            request.setAcceptedAmount(accepted);
            request.setRejectedAmount(rejected);
            request.setActualAmount(accepted);
        }
        request.setAcceptedVolume(accepted);
        request.setRejectedVolume(rejected);
        request.setAcceptanceRemarks(remarks);
        boolean fullyAccepted = !milestones.isEmpty()
            && milestones.stream().allMatch(item -> "ACCEPTED".equals(item.getStatus()));
        request.setStatus(fullyAccepted
            ? ServiceRequestStatus.ACCEPTED.name()
            : ServiceRequestStatus.PARTIALLY_ACCEPTED.name());
        requestRepository.saveAndFlush(request);
    }

    private String milestoneStatus(ServiceRequestMilestone milestone, String mode) {
        BigDecimal ordered = "QUANTITY".equals(mode) ? milestone.getOrderedQuantity() : milestone.getOrderedAmount();
        BigDecimal accepted = "QUANTITY".equals(mode) ? milestone.getAcceptedQuantity() : milestone.getAcceptedAmount();
        BigDecimal rejected = "QUANTITY".equals(mode) ? milestone.getRejectedQuantity() : milestone.getRejectedAmount();
        if (nvl(accepted).compareTo(ordered) == 0 && nvl(rejected).signum() == 0) return "ACCEPTED";
        if (nvl(accepted).add(nvl(rejected)).compareTo(ordered) == 0 && nvl(rejected).signum() > 0) {
            return "REWORK_REQUIRED";
        }
        return "PARTIALLY_ACCEPTED";
    }

    private BigDecimal requestBasis(ServiceRequest request, String mode) {
        return "QUANTITY".equals(mode)
            ? requiredPositive(request.getOrderedQuantity(), "ordered quantity")
            : requiredPositive(request.getOrderedAmount(), "ordered amount");
    }

    private BigDecimal requiredPositive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) throw new BusinessException(label + " must be positive");
        return value;
    }

    private void rejectOverAcceptance(
        BigDecimal accepted,
        BigDecimal rejected,
        BigDecimal ordered,
        String basis
    ) {
        if (accepted.add(rejected).compareTo(ordered) > 0) {
            throw new BusinessException("Milestone accepted and rejected " + basis + " cannot exceed ordered " + basis);
        }
    }

    private void requireVersion(Long actual, Long expected) {
        if (expected == null || !Objects.equals(actual, expected)) {
            throw new ConflictException("Service request milestone was changed by another user; refresh and retry");
        }
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AMOUNT", "QUANTITY").contains(normalized)) {
            throw new BusinessException("Service request acceptanceMode must be AMOUNT or QUANTITY");
        }
        return normalized;
    }

    private String milestoneCode(String requestCode, int sequence) {
        String prefix = trim(requestCode) == null ? "SR" : requestCode.trim();
        String suffix = "-M" + String.format(Locale.ROOT, "%02d", sequence);
        return (prefix.length() + suffix.length() <= 80
            ? prefix
            : prefix.substring(0, 80 - suffix.length())) + suffix;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
