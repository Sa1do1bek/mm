package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalRequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "approval_flows")
public class ApprovalFlow extends BusinessEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, length = 40)
    private ApprovalFlowType flowType = ApprovalFlowType.SEQUENTIAL;

    @Column(name = "module_key", nullable = false, length = 80)
    private String moduleKey;

    @Column(name = "department_id")
    private UUID departmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", length = 20)
    private ApprovalRequestType requestType;

    @Column(nullable = false)
    private boolean active = true;
}
