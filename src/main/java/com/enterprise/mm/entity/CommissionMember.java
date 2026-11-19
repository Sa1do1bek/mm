package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.CommissionMemberRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "commission_members")
public class CommissionMember extends BusinessEntity {
    @Column(name = "commission_id", nullable = false)
    private UUID commissionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commission_id", insertable = false, updatable = false)
    private Commission commission;

    @Column(name = "user_id")
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "employee_id")
    private UUID employeeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false, length = 60)
    private CommissionMemberRole memberRole = CommissionMemberRole.MEMBER;

    @Column(name = "member_name", nullable = false, length = 240)
    private String memberName;

    @Column(name = "active_flag", nullable = false)
    private boolean active = true;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        commissionId = commission == null ? commissionId : commission.getId();
        userId = user == null ? userId : user.getId();
        employeeId = employee == null ? employeeId : employee.getId();
    }
}
