package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "manual_unmatched_issue_lines")
public class ManualUnmatchedIssueLine extends BusinessEntity {
    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "material_code_snapshot", nullable = false, length = 80)
    private String materialCodeSnapshot;

    @Column(name = "material_name_snapshot", nullable = false, columnDefinition = "text")
    private String materialNameSnapshot;

    @Column(name = "unit_code_snapshot", nullable = false, length = 80)
    private String unitCodeSnapshot;

    @Column(name = "quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity;
}
