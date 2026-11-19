package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.payload.SupplierEligibilityDtos.SupplierEligibilityReason;
import com.enterprise.mm.payload.SupplierEligibilityDtos.UnifiedSupplierEligibility;
import com.enterprise.mm.repository.SupplierRepository;
import java.time.LocalDate;
import java.util.ArrayList;
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
public class SupplierEligibilityService {
    public static final String STATUS_ELIGIBLE = "ELIGIBLE";
    public static final String STATUS_CONDITIONAL = "CONDITIONAL";
    public static final String STATUS_NOT_ELIGIBLE = "NOT_ELIGIBLE";

    private static final Set<String> ACCREDITATION_REQUIRED_OPERATIONS = Set.of(
        "PO_DISPATCH",
        "RFQ_INVITATION",
        "RFQ_PROPOSAL_SUBMISSION",
        "RFQ_AWARD",
        "DIRECT_AWARD",
        "FRAMEWORK_AWARD",
        "CONTRACT_AWARD",
        "SERVICE_ORDER_AWARD"
    );

    private final SupplierRepository supplierRepository;
    private final SupplierAccreditationService accreditationService;

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public UnifiedSupplierEligibility evaluateNewBusiness(
        UUID supplierId,
        AccreditationDemand demand,
        String operation
    ) {
        return evaluateNewBusiness(supplierId, demand, operation, LocalDate.now());
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public UnifiedSupplierEligibility evaluateNewBusiness(
        UUID supplierId,
        AccreditationDemand demand,
        String operation,
        LocalDate asOfDate
    ) {
        Supplier supplier = requireSupplier(supplierId);
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        String normalizedOperation = normalizeOperation(operation);
        List<SupplierEligibilityReason> reasons = new ArrayList<>();
        List<SupplierEligibilityReason> warnings = new ArrayList<>();

        if (hardBlocked(supplier)) {
            reasons.add(new SupplierEligibilityReason(hardBlockCode(supplier), hardBlockMessage(supplier, normalizedOperation)));
            return result(supplierId, date, normalizedOperation, false, false, true, reasons, warnings);
        }
        if (SupplierStatus.ARCHIVED.name().equals(supplier.getStatus())) {
            reasons.add(new SupplierEligibilityReason("SUPPLIER_ARCHIVED", "Archived supplier cannot receive new business"));
            return result(supplierId, date, normalizedOperation, false, false, false, reasons, warnings);
        }
        if (!SupplierStatus.ACTIVE.name().equals(supplier.getStatus())
            && !SupplierStatus.LIMITED.name().equals(supplier.getStatus())) {
            reasons.add(new SupplierEligibilityReason(
                "SUPPLIER_NOT_ACTIVE",
                "Supplier must be active before receiving new business"
            ));
        }
        if (SupplierStatus.LIMITED.name().equals(supplier.getStatus())) {
            warnings.add(new SupplierEligibilityReason(
                "SUPPLIER_LIMITED_COMPATIBILITY",
                "Supplier has legacy LIMITED status; eligibility is evaluated by this transaction context"
            ));
        }

        addPerformanceSignals(supplier, reasons, warnings, true);

        if (accreditationRequired(normalizedOperation, demand)) {
            String accreditationBlock = accreditationService.supplierEligibilityBlockedReasonForDemand(
                supplierId,
                demand,
                operationLabel(normalizedOperation),
                date
            );
            if (accreditationBlock != null) {
                reasons.add(new SupplierEligibilityReason(
                    accreditationReasonCode(accreditationBlock),
                    accreditationBlock
                ));
            }
        } else if (hasDemand(demand)) {
            warnings.add(new SupplierEligibilityReason(
                "ACCREDITATION_NOT_REQUIRED_FOR_CONTEXT",
                "Accreditation is not required for this procurement context"
            ));
        }

        return result(supplierId, date, normalizedOperation, reasons.isEmpty(), true, false, reasons, warnings);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public UnifiedSupplierEligibility evaluateExistingObligation(UUID supplierId, String operation) {
        return evaluateExistingObligation(supplierId, operation, LocalDate.now());
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public UnifiedSupplierEligibility evaluateExistingObligation(
        UUID supplierId,
        String operation,
        LocalDate asOfDate
    ) {
        Supplier supplier = requireSupplier(supplierId);
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        String normalizedOperation = normalizeOperation(operation);
        List<SupplierEligibilityReason> reasons = new ArrayList<>();
        List<SupplierEligibilityReason> warnings = new ArrayList<>();
        if (hardBlocked(supplier)) {
            reasons.add(new SupplierEligibilityReason(hardBlockCode(supplier), hardBlockMessage(supplier, normalizedOperation)));
            return result(supplierId, date, normalizedOperation, false, false, true, reasons, warnings);
        }
        if (SupplierStatus.ARCHIVED.name().equals(supplier.getStatus())) {
            reasons.add(new SupplierEligibilityReason(
                "SUPPLIER_ARCHIVED",
                "Archived supplier cannot continue supplier obligations"
            ));
            return result(supplierId, date, normalizedOperation, false, false, false, reasons, warnings);
        }
        if (!isOperationalLifecycle(supplier.getStatus())) {
            warnings.add(new SupplierEligibilityReason(
                "SUPPLIER_NOT_ACTIVE_EXISTING_OBLIGATION",
                "Supplier is not active, but existing obligations can continue without a hard block"
            ));
        }
        addPerformanceSignals(supplier, reasons, warnings, false);
        return result(supplierId, date, normalizedOperation, false, true, false, reasons, warnings);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public void requireNewBusinessAllowed(UUID supplierId, AccreditationDemand demand, String operation) {
        UnifiedSupplierEligibility eligibility = evaluateNewBusiness(supplierId, demand, operation);
        if (!eligibility.newBusinessAllowed()) {
            throw new BusinessException(eligibility.summaryReason() == null
                ? "Supplier is not eligible for " + normalizeOperation(operation)
                : eligibility.summaryReason());
        }
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public void requireExistingObligationAllowed(UUID supplierId, String operation) {
        UnifiedSupplierEligibility eligibility = evaluateExistingObligation(supplierId, operation);
        if (!eligibility.existingObligationsAllowed()) {
            throw new BusinessException(eligibility.summaryReason() == null
                ? "Supplier cannot continue " + normalizeOperation(operation)
                : eligibility.summaryReason());
        }
    }

    public boolean accreditationRequired(String operation, AccreditationDemand demand) {
        return hasDemand(demand) && ACCREDITATION_REQUIRED_OPERATIONS.contains(normalizeOperation(operation));
    }

    private boolean isOperationalLifecycle(String status) {
        return SupplierStatus.ACTIVE.name().equals(status)
            || SupplierStatus.LIMITED.name().equals(status);
    }

    private UnifiedSupplierEligibility result(
        UUID supplierId,
        LocalDate asOfDate,
        String operation,
        boolean newBusinessAllowed,
        boolean existingObligationsAllowed,
        boolean hardBlocked,
        List<SupplierEligibilityReason> reasons,
        List<SupplierEligibilityReason> warnings
    ) {
        String status = !reasons.isEmpty()
            ? STATUS_NOT_ELIGIBLE
            : warnings.isEmpty() ? STATUS_ELIGIBLE : STATUS_CONDITIONAL;
        return new UnifiedSupplierEligibility(
            supplierId,
            asOfDate,
            operation,
            status,
            newBusinessAllowed,
            existingObligationsAllowed,
            hardBlocked,
            reasons,
            warnings
        );
    }

    private Supplier requireSupplier(UUID supplierId) {
        if (supplierId == null) {
            throw new BusinessException("supplierId is required");
        }
        return supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId)
            .filter(supplier -> Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier not found: " + supplierId));
    }

    private boolean hardBlocked(Supplier supplier) {
        return SupplierStatus.BLOCKED.name().equals(supplier.getStatus());
    }

    private String hardBlockCode(Supplier supplier) {
        String reason = supplier.getBlockedReason() == null ? "" : supplier.getBlockedReason().toUpperCase(Locale.ROOT);
        if (reason.contains("COMPLIANCE") || reason.contains("KYC") || reason.contains("SANCTION")) {
            return "SUPPLIER_COMPLIANCE_HARD_BLOCK";
        }
        return "SUPPLIER_HARD_BLOCKED";
    }

    private String hardBlockMessage(Supplier supplier, String operation) {
        String reason = supplier.getBlockedReason() == null || supplier.getBlockedReason().isBlank()
            ? "Supplier is hard blocked"
            : supplier.getBlockedReason();
        return "Supplier is blocked for " + operation + ": " + reason;
    }

    private void addPerformanceSignals(
        Supplier supplier,
        List<SupplierEligibilityReason> reasons,
        List<SupplierEligibilityReason> warnings,
        boolean restrictNewBusiness
    ) {
        String category = supplier.getCategory() == null
            ? ""
            : supplier.getCategory().trim().toUpperCase(Locale.ROOT);
        if ("D".equals(category) && restrictNewBusiness) {
            reasons.add(new SupplierEligibilityReason(
                "SUPPLIER_PERFORMANCE_RESTRICTS_NEW_BUSINESS",
                "Supplier performance category restricts new business"
            ));
        } else if ("D".equals(category)) {
            warnings.add(new SupplierEligibilityReason(
                "SUPPLIER_PERFORMANCE_REVIEW_REQUIRED",
                "Supplier performance category requires buyer review"
            ));
        } else if ("C".equals(category)) {
            warnings.add(new SupplierEligibilityReason(
                "SUPPLIER_PERFORMANCE_REVIEW_REQUIRED",
                "Supplier performance category requires buyer review"
            ));
        }
    }

    private String accreditationReasonCode(String message) {
        String normalized = message == null ? "" : message.toUpperCase(Locale.ROOT);
        if (normalized.contains("EXPIRED")) {
            return "SUPPLIER_ACCREDITATION_EXPIRED";
        }
        if (normalized.contains("MATERIAL GROUP")) {
            return "SUPPLIER_MATERIAL_GROUP_NOT_COVERED";
        }
        if (normalized.contains("SERVICE")) {
            return "SUPPLIER_SERVICE_ACCREDITATION_REQUIRED";
        }
        if (normalized.contains("WORKS") || normalized.contains("WORK ")) {
            return "SUPPLIER_WORKS_ACCREDITATION_REQUIRED";
        }
        if (normalized.contains("COMPLIANCE") || normalized.contains("BLOCKED")) {
            return "SUPPLIER_COMPLIANCE_HARD_BLOCK";
        }
        return "SUPPLIER_ACCREDITATION_REQUIRED";
    }

    private boolean hasDemand(AccreditationDemand demand) {
        return demand != null
            && (demand.materialRequired() || demand.serviceRequired() || demand.worksRequired());
    }

    private String normalizeOperation(String value) {
        return value == null || value.isBlank()
            ? "SUPPLIER_OPERATION"
            : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String operationLabel(String operation) {
        String normalized = normalizeOperation(operation);
        return switch (normalized) {
            case "RFQ_INVITATION" -> "RFQ invitation";
            case "RFQ_PROPOSAL_SUBMISSION" -> "RFQ proposal submission";
            case "RFQ_AWARD" -> "RFQ award";
            case "PO_DISPATCH" -> "purchase order dispatch";
            case "DIRECT_AWARD" -> "direct award";
            case "FRAMEWORK_AWARD" -> "framework award";
            case "CONTRACT_AWARD" -> "contract award";
            case "SERVICE_ORDER_AWARD" -> "service order award";
            default -> normalized;
        };
    }
}
