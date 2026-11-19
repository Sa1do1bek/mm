package com.enterprise.mm.quality.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.WorkspaceAction;
import com.enterprise.mm.quality.workspace.service.IncomingQualityActionPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class IncomingQualityActionPolicyTest {
    private static final List<String> MUTATION_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST",
        "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR",
        "SUPPLIER_MANAGER"
    );

    private IncomingQualityActionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new IncomingQualityActionPolicy();
        authenticate("MM_MANAGER");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesOnlyTheWorkflowActionsOwnedByEachInspectionStatus() {
        Map<QualityInspectionStatus, Set<String>> expected = Map.of(
            QualityInspectionStatus.DRAFT, Set.of("QUARANTINE", "CANCEL"),
            QualityInspectionStatus.IN_QUARANTINE, Set.of("SAMPLING", "CANCEL"),
            QualityInspectionStatus.SAMPLING, Set.of("SEND_TO_LAB", "CANCEL"),
            QualityInspectionStatus.LAB_TESTING, Set.of("CANCEL"),
            QualityInspectionStatus.PASSED, Set.of("RELEASE", "REJECT"),
            QualityInspectionStatus.FAILED, Set.of("RELEASE", "REJECT"),
            QualityInspectionStatus.RELEASED, Set.of(),
            QualityInspectionStatus.REJECTED, Set.of(),
            QualityInspectionStatus.CANCELLED, Set.of()
        );

        for (QualityInspectionStatus status : QualityInspectionStatus.values()) {
            assertThat(policy.actions(inspection(status, BigDecimal.TEN)))
                .as("actions for %s", status)
                .extracting(WorkspaceAction::code)
                .containsExactlyInAnyOrderElementsOf(expected.get(status));
        }
    }

    @Test
    void decisionActionsRequirePositiveHeldQuantity() {
        for (QualityInspectionStatus status : List.of(
            QualityInspectionStatus.PASSED,
            QualityInspectionStatus.FAILED
        )) {
            List<WorkspaceAction> withHeldStock = policy.actions(inspection(status, BigDecimal.TEN));
            assertThat(action(withHeldStock, "RELEASE").enabled()).isTrue();
            assertThat(action(withHeldStock, "REJECT").enabled()).isTrue();

            List<WorkspaceAction> withoutHeldStock = policy.actions(inspection(status, BigDecimal.ZERO));
            assertThat(action(withoutHeldStock, "RELEASE").enabled()).isFalse();
            assertThat(action(withoutHeldStock, "RELEASE").disabledReason())
                .isEqualTo("No quarantined quantity remains");
            assertThat(action(withoutHeldStock, "REJECT").enabled()).isFalse();
        }
    }

    @Test
    void readOnlyAuthorityReceivesWorkflowContextWithoutEnabledMutations() {
        authenticate("VIEWER");

        List<WorkspaceAction> actions = policy.actions(
            inspection(QualityInspectionStatus.FAILED, BigDecimal.TEN));

        assertThat(actions).isNotEmpty().allSatisfy(action -> {
            assertThat(action.enabled()).isFalse();
            assertThat(action.disabledReason()).isEqualTo("Current role cannot execute this action");
            assertThat(action.requiredRoles()).containsExactlyElementsOf(List.of("ADMIN", "MM_MANAGER"));
        });
    }

    @Test
    void terminalStatusesExposeNoStockChangingOrRepeatedCancelAction() {
        for (QualityInspectionStatus status : List.of(
            QualityInspectionStatus.RELEASED,
            QualityInspectionStatus.REJECTED,
            QualityInspectionStatus.CANCELLED
        )) {
            assertThat(policy.actions(inspection(status, BigDecimal.TEN)))
                .extracting(WorkspaceAction::code)
                .doesNotContain("QUARANTINE", "RELEASE", "REJECT", "CANCEL");
            assertThatThrownBy(() -> policy.requireStateAllowed(inspection(status, BigDecimal.TEN), "CANCEL"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not allowed");
        }
    }

    @Test
    void mutationGuardUsesTheSameStatusAndHeldQuantityRulesAsWorkspaceActions() {
        assertThatThrownBy(() -> policy.requireStateAllowed(
            inspection(QualityInspectionStatus.SAMPLING, BigDecimal.TEN), "QUARANTINE"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("QUARANTINE");

        assertThatThrownBy(() -> policy.requireStateAllowed(
            inspection(QualityInspectionStatus.PASSED, BigDecimal.ZERO), "RELEASE"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("quarantined quantity");

        policy.requireStateAllowed(
            inspection(QualityInspectionStatus.FAILED, BigDecimal.ONE), "REJECT");
    }

    private WorkspaceAction action(List<WorkspaceAction> actions, String code) {
        return actions.stream().filter(action -> code.equals(action.code())).findFirst().orElseThrow();
    }

    private QualityInspection inspection(QualityInspectionStatus status, BigDecimal heldQuantity) {
        QualityInspection inspection = new QualityInspection();
        inspection.setStatus(status.name());
        inspection.setQuarantinedQuantity(heldQuantity);
        return inspection;
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "quality-user",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }
}
