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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tender_commission_members")
public class TenderCommissionMember extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;

    @Column(name = "employee_id")
    private UUID employeeId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @Column(name = "member_name", nullable = false, length = 240)
    private String memberName;

    @Column(name = "member_role", nullable = false, length = 60)
    private String memberRole = "MEMBER";

    @Column(name = "voting_weight", nullable = false, precision = 7, scale = 2)
    private BigDecimal votingWeight = BigDecimal.ONE;

    @Column(name = "conflict_declaration_signed", nullable = false)
    private boolean conflictDeclarationSigned;

    @Column(name = "declared_at")
    private LocalDateTime declaredAt;

    @Column(name = "active_flag", nullable = false)
    private boolean active = true;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        rfqId = rfq == null ? rfqId : rfq.getId();
        employeeId = employee == null ? employeeId : employee.getId();
    }
}
