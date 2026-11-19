package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_corrective_actions")
public class SupplierCorrectiveAction extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "source_type", length = 80)
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "issue_title", length = 240)
    private String issueTitle;

    @Column(name = "issue_description", columnDefinition = "text")
    private String issueDescription;

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @Column(name = "corrective_action", columnDefinition = "text")
    private String correctiveAction;

    @Column(name = "preventive_action", columnDefinition = "text")
    private String preventiveAction;

    @Column(name = "owner_employee_id")
    private UUID ownerEmployeeId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_employee_id", insertable = false, updatable = false)
    private Employee ownerEmployee;

    @Column(name = "supplier_responsible_name", length = 240)
    private String supplierResponsibleName;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(nullable = false, length = 40)
    private String severity = "MEDIUM";

    @Column(name = "verification_result", length = 1000)
    private String verificationResult;

    @Column(name = "recurrence_prevented", nullable = false)
    private boolean recurrencePrevented;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
        ownerEmployeeId = ownerEmployee == null ? ownerEmployeeId : ownerEmployee.getId();
    }
}
