package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_master_requests")
public class MaterialMasterRequest extends BusinessEntity {
    @Column(name = "request_type", nullable = false, length = 40)
    private String requestType;

    @Column(name = "material_id")
    private UUID materialId;

    @Column(name = "target_material_id")
    private UUID targetMaterialId;

    @Column(name = "proposed_name", length = 240)
    private String proposedName;

    @Column(name = "material_group_id")
    private UUID materialGroupId;

    @Column(name = "uom_id")
    private UUID uomId;

    @Column(name = "mxik_code", length = 80)
    private String mxikCode;

    @Column(name = "tnved_code", length = 80)
    private String tnvedCode;

    @Column(name = "requested_by", length = 120)
    private String requestedBy;

    @Column(name = "last_action_comment", columnDefinition = "text")
    private String lastActionComment;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
}
