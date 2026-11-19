package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "manual_unmatched_issues")
public class ManualUnmatchedIssue extends BusinessEntity {
    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "issued_to", nullable = false, length = 240)
    private String issuedTo;

    @Column(name = "reference", length = 120)
    private String reference;

    /** Access context only; this is not asserted to be the physical source. */
    @Column(name = "entry_context_warehouse_id", nullable = false)
    private UUID entryContextWarehouseId;

    @Column(name = "source_warehouse_id")
    private UUID sourceWarehouseId;
}
