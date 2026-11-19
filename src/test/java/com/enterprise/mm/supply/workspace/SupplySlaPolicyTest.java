package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import com.enterprise.mm.supply.workspace.service.SupplySlaPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplySlaPolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 15);
    private final SupplySlaPolicy policy = new SupplySlaPolicy();

    @Test
    void explicitDueDateWinsAndCarriesExplicitSource() {
        LocalDate requested = AS_OF.plusDays(4);

        var decision = policy.resolveDueDate(requested, record(AS_OF.plusDays(10), SupplySeverity.WARNING), AS_OF);

        assertThat(decision.dueDate()).isEqualTo(requested);
        assertThat(decision.source()).isEqualTo(SupplyDueDateSource.EXPLICIT);
    }

    @Test
    void policyDueDateUsesSeverityHorizonWithoutPassingOperationalCommitmentDate() {
        var warning = policy.resolveDueDate(null, record(AS_OF.plusDays(10), SupplySeverity.WARNING), AS_OF);
        var blocking = policy.resolveDueDate(null, record(AS_OF.plusDays(10), SupplySeverity.BLOCKING), AS_OF);
        var earlierCommitment = policy.resolveDueDate(null, record(AS_OF.minusDays(2), SupplySeverity.INFO), AS_OF);

        assertThat(warning.dueDate()).isEqualTo(AS_OF.plusDays(1));
        assertThat(blocking.dueDate()).isEqualTo(AS_OF);
        assertThat(earlierCommitment.dueDate()).isEqualTo(AS_OF.minusDays(2));
        assertThat(warning.source()).isEqualTo(SupplyDueDateSource.POLICY);
        assertThat(blocking.source()).isEqualTo(SupplyDueDateSource.POLICY);
    }

    private SupplyCommitmentRecord record(LocalDate operationalDate, SupplySeverity severity) {
        UUID id = UUID.randomUUID();
        return new SupplyCommitmentRecord(
            new SupplyCommitmentKey(SupplyCommitmentKind.PO, id), UUID.randomUUID(), id,
            "PO", "PO", UUID.randomUUID(), "SUP", "Supplier", null,
            operationalDate, "SENT", "USD", BigDecimal.TEN, BigDecimal.ONE, "EA",
            SupplyDataQuality.AUTHORITATIVE, severity,
            operationalDate.isBefore(AS_OF), false, false, false, false, true,
            null, null, null, null, null);
    }
}
