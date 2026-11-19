package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_valuation_method_history")
public class MaterialValuationMethodHistory extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "old_valuation_method", length = 40)
    private String oldValuationMethod;

    @Column(name = "new_valuation_method", nullable = false, length = 40)
    private String newValuationMethod;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @Column(name = "change_reason", columnDefinition = "text")
    private String changeReason;
}
