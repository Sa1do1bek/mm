package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ProcurementFrameworkStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "procurement_frameworks")
public class ProcurementFramework extends BusinessEntity {
    @Column(name = "procedure_id") private UUID procedureId;
    @Column(name = "method_decision_id") private UUID methodDecisionId;
    @Column(name = "purchase_request_id") private UUID purchaseRequestId;
    @Column(name = "contract_id") private UUID contractId;
    @Column(name = "award_reference", length = 160) private String awardReference;
    @Column(name = "supplier_id", nullable = false) private UUID supplierId;
    @Column(name = "supplier_ids", columnDefinition = "text") private String supplierIds;
    @Column(name = "supplier_model", nullable = false, length = 30) private String supplierModel = "SINGLE_SUPPLIER";
    @Column(name = "allocation_basis", length = 40) private String allocationBasis;
    @Column(name = "valid_from", nullable = false) private LocalDate validFrom;
    @Column(name = "valid_to") private LocalDate validTo;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Column(name = "ceiling_amount", nullable = false, precision = 19, scale = 4) private BigDecimal ceilingAmount;
    @Column(name = "committed_amount", nullable = false, precision = 19, scale = 4) private BigDecimal committedAmount = BigDecimal.ZERO;
    @Column(name = "consumed_amount", nullable = false, precision = 19, scale = 4) private BigDecimal consumedAmount = BigDecimal.ZERO;
    @Column(name = "call_off_policy", columnDefinition = "text") private String callOffPolicy;
    @Column(name = "approval_reference", length = 160) private String approvalReference;
    @Column(name = "approved_at") private LocalDateTime approvedAt;
    @Column(name = "approved_by", length = 120) private String approvedBy;

    public ProcurementFrameworkStatus frameworkStatus() {
        try { return ProcurementFrameworkStatus.valueOf(getStatus()); }
        catch (Exception ignored) { return ProcurementFrameworkStatus.DRAFT; }
    }

    public void setFrameworkStatus(ProcurementFrameworkStatus value) { setStatus(value == null ? null : value.name()); }
}
