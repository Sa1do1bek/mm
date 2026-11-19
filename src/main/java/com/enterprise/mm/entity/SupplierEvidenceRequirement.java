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
@Table(name = "supplier_evidence_requirements")
public class SupplierEvidenceRequirement extends BusinessEntity {
    @Column(name = "template_version_id", nullable = false)
    private UUID templateVersionId;

    @Column(name = "requirement_code", nullable = false, length = 80)
    private String requirementCode;

    @Column(name = "document_type", nullable = false, length = 80)
    private String documentType;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    @Column(name = "reusable", nullable = false)
    private boolean reusable;

    @Column(name = "validity_required", nullable = false)
    private boolean validityRequired;

    @Column(name = "owner_role", nullable = false, length = 80)
    private String ownerRole;

    @Column(name = "sla_days", nullable = false)
    private int slaDays;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
