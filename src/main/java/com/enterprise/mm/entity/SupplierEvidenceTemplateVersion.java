package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_evidence_template_versions")
public class SupplierEvidenceTemplateVersion extends BusinessEntity {
    @Column(name = "accreditation_type", nullable = false, length = 80)
    private String accreditationType;

    @Column(name = "template_version", nullable = false)
    private Integer templateVersion;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;
}
