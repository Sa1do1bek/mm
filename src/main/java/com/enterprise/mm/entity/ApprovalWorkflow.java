package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "approval_workflows")
public class ApprovalWorkflow extends BusinessEntity {
    @Column(name = "module_key", length = 80)
    private String moduleKey;
    @Column(name = "request_type", length = 20)
    private String requestType;
    @Column(length = 80)
    private String entityType;
    @Column(name = "entity_id")
    private UUID entityId;
    private Integer currentStep = 1;
}
