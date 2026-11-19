package com.enterprise.mm.quality.workspace.service;

import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.WorkspaceAction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class IncomingQualityActionPolicy {
    public static final String QUARANTINE = "QUARANTINE";
    public static final String SAMPLING = "SAMPLING";
    public static final String SEND_TO_LAB = "SEND_TO_LAB";
    public static final String RELEASE = "RELEASE";
    public static final String REJECT = "REJECT";
    public static final String CANCEL = "CANCEL";

    public static final List<String> MUTATION_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST",
        "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR",
        "SUPPLIER_MANAGER"
    );

    public List<WorkspaceAction> actions(QualityInspection inspection) {
        if (inspection == null) {
            return List.of();
        }
        QualityInspectionStatus status = status(inspection);
        List<String> codes = actionCodes(status);
        boolean roleAllowed = hasMutationRole();
        List<WorkspaceAction> result = new ArrayList<>(codes.size());
        for (String code : codes) {
            boolean qualityAllowed = !(decision(code) || CANCEL.equals(code)) || hasQualityRole();
            String disabledReason = disabledReason(inspection, code, roleAllowed && qualityAllowed);
            result.add(new WorkspaceAction(
                code,
                primary(status, code),
                disabledReason == null,
                disabledReason,
                decision(code) || CANCEL.equals(code) ? List.of("ADMIN", "MM_MANAGER") : MUTATION_ROLES,
                requiredFields(code),
                confirmationImpact(code),
                irreversible(code)
            ));
        }
        return List.copyOf(result);
    }

    public void requireStateAllowed(QualityInspection inspection, String actionCode) {
        if (inspection == null) {
            throw new BusinessException("Quality inspection is required");
        }
        String code = normalize(actionCode);
        QualityInspectionStatus status = status(inspection);
        if (!actionCodes(status).contains(code)) {
            throw new BusinessException(
                "Action " + code + " is not allowed for inspection status " + status.name());
        }
        if (decision(code) && heldQuantity(inspection).signum() <= 0) {
            throw new BusinessException(
                "Action " + code + " requires positive quarantined quantity");
        }
    }

    private List<String> actionCodes(QualityInspectionStatus status) {
        return switch (status) {
            case DRAFT -> List.of(QUARANTINE, CANCEL);
            case IN_QUARANTINE -> List.of(SAMPLING, CANCEL);
            case SAMPLING -> List.of(SEND_TO_LAB, CANCEL);
            case LAB_TESTING -> List.of(CANCEL);
            case PASSED, FAILED -> List.of(RELEASE, REJECT);
            case RELEASED, REJECTED, CANCELLED -> List.of();
        };
    }

    private String disabledReason(
        QualityInspection inspection,
        String actionCode,
        boolean roleAllowed
    ) {
        if (decision(actionCode) && heldQuantity(inspection).signum() <= 0) {
            return "No quarantined quantity remains";
        }
        return roleAllowed ? null : "Current role cannot execute this action";
    }

    private boolean primary(QualityInspectionStatus status, String actionCode) {
        return switch (status) {
            case DRAFT -> QUARANTINE.equals(actionCode);
            case IN_QUARANTINE -> SAMPLING.equals(actionCode);
            case SAMPLING -> SEND_TO_LAB.equals(actionCode);
            case PASSED -> RELEASE.equals(actionCode);
            case FAILED -> REJECT.equals(actionCode);
            default -> false;
        };
    }

    private List<String> requiredFields(String actionCode) {
        return switch (actionCode) {
            case RELEASE, REJECT -> List.of(
                "acceptedQuantity", "rejectedQuantity", "conclusion");
            default -> List.of();
        };
    }

    private String confirmationImpact(String actionCode) {
        return switch (actionCode) {
            case QUARANTINE -> "Moves received stock from available to quarantine";
            case SAMPLING -> "Starts the inspection sampling lifecycle";
            case SEND_TO_LAB -> "Submits the sampled inspection to laboratory testing";
            case RELEASE -> "Moves accepted quantity from quarantine to available stock";
            case REJECT -> "Finalizes rejected quantity and supplier quality impact";
            case CANCEL -> "Releases held stock and cancels the inspection";
            default -> null;
        };
    }

    private boolean irreversible(String actionCode) {
        return RELEASE.equals(actionCode)
            || REJECT.equals(actionCode)
            || CANCEL.equals(actionCode);
    }

    private boolean decision(String actionCode) {
        return RELEASE.equals(actionCode) || REJECT.equals(actionCode);
    }

    private BigDecimal heldQuantity(QualityInspection inspection) {
        return inspection.getQuarantinedQuantity() == null
            ? BigDecimal.ZERO
            : inspection.getQuarantinedQuantity();
    }

    private QualityInspectionStatus status(QualityInspection inspection) {
        try {
            return QualityInspectionStatus.valueOf(normalize(inspection.getStatus()));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                "Unsupported quality inspection status: " + inspection.getStatus());
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Quality inspection action/status is required");
        }
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private boolean hasQualityRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && authentication.getAuthorities().stream()
            .anyMatch(a -> List.of("ROLE_ADMIN", "ROLE_MM_MANAGER").contains(a.getAuthority()));
    }

    private boolean hasMutationRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return MUTATION_ROLES.stream().anyMatch(roles::contains);
    }
}
