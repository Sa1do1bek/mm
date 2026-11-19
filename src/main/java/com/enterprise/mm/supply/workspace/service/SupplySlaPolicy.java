package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class SupplySlaPolicy {
    public DueDateDecision resolveDueDate(
        LocalDate explicitDueDate,
        SupplyCommitmentRecord record,
        LocalDate asOfDate
    ) {
        SupplySeverity severity = record == null ? null : record.severity();
        LocalDate operationalDate = record == null ? null : record.operationalDate();
        return resolveDueDate(explicitDueDate, operationalDate, severity, asOfDate);
    }

    public DueDateDecision resolveDueDate(
        LocalDate explicitDueDate,
        LocalDate operationalDate,
        SupplySeverity severity,
        LocalDate asOfDate
    ) {
        if (explicitDueDate != null) {
            return new DueDateDecision(explicitDueDate, SupplyDueDateSource.EXPLICIT);
        }
        if (asOfDate == null) {
            throw new IllegalArgumentException("asOfDate is required for a policy due date");
        }
        int horizon = switch (severity == null ? SupplySeverity.WARNING : severity) {
            case BLOCKING -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
        LocalDate policyDate = asOfDate.plusDays(horizon);
        if (operationalDate != null && operationalDate.isBefore(policyDate)) {
            policyDate = operationalDate;
        }
        return new DueDateDecision(policyDate, SupplyDueDateSource.POLICY);
    }

    public long overdueDays(SupplyCommitmentRecord record, LocalDate asOfDate) {
        if (record == null || record.operationalDate() == null || asOfDate == null
            || !record.operationalDate().isBefore(asOfDate)) {
            return 0L;
        }
        return ChronoUnit.DAYS.between(record.operationalDate(), asOfDate);
    }

    public record DueDateDecision(LocalDate dueDate, SupplyDueDateSource source) {
    }
}
