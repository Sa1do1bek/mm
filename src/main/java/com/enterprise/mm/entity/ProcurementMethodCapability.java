package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ProcurementMethodCapabilityState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "procurement_method_capabilities")
public class ProcurementMethodCapability extends BusinessEntity {
    @Column(name = "method_code", nullable = false, length = 60)
    private String methodCode;

    @Column(name = "capability_state", nullable = false, length = 20)
    private String capabilityState = ProcurementMethodCapabilityState.DISABLED.name();

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "min_amount", precision = 19, scale = 4)
    private BigDecimal minAmount;

    @Column(name = "max_amount", precision = 19, scale = 4)
    private BigDecimal maxAmount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_request_types", columnDefinition = "jsonb")
    private Set<String> allowedRequestTypes = new LinkedHashSet<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_category_ids", columnDefinition = "jsonb")
    private Set<String> allowedCategoryIds = new LinkedHashSet<>();

    @Column(name = "justification_required", nullable = false)
    private boolean justificationRequired;

    @Column(name = "supplier_required", nullable = false)
    private boolean supplierRequired;

    @Column(name = "approval_required", nullable = false)
    private boolean approvalRequired = true;

    @Column(name = "configuration_version", nullable = false)
    private Integer configurationVersion = 1;

    @Column(name = "change_reason", columnDefinition = "text")
    private String changeReason;

    @Column(name = "configured_at")
    private LocalDateTime configuredAt;

    @Column(name = "configured_by", length = 120)
    private String configuredBy;

    public ProcurementMethodCapabilityState state() {
        if (capabilityState == null) return ProcurementMethodCapabilityState.DISABLED;
        try {
            return ProcurementMethodCapabilityState.valueOf(capabilityState);
        } catch (IllegalArgumentException ignored) {
            return ProcurementMethodCapabilityState.DISABLED;
        }
    }

    public void setState(ProcurementMethodCapabilityState state) {
        this.capabilityState = state == null ? ProcurementMethodCapabilityState.DISABLED.name() : state.name();
    }

    /** Compatibility aliases used by clients that call this a method configuration. */
    public String getProcurementMethod() { return methodCode; }
    public void setProcurementMethod(String value) { this.methodCode = value; }
    public String getState() { return capabilityState; }
    public void setState(String value) { this.capabilityState = value; }
}
