package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "service_request_milestones")
public class ServiceRequestMilestone extends BusinessEntity {
    @Column(name = "service_request_id", nullable = false)
    private UUID serviceRequestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_request_id", insertable = false, updatable = false)
    private ServiceRequest serviceRequest;

    @Column(name = "milestone_sequence", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "acceptance_mode", nullable = false, length = 20)
    private String acceptanceMode;

    @Column(name = "ordered_quantity", precision = 22, scale = 7)
    private BigDecimal orderedQuantity;

    @Column(name = "accepted_quantity", precision = 22, scale = 7)
    private BigDecimal acceptedQuantity;

    @Column(name = "rejected_quantity", precision = 22, scale = 7)
    private BigDecimal rejectedQuantity;

    @Column(name = "uom_code", length = 40)
    private String uomCode;

    @Column(name = "ordered_amount", precision = 19, scale = 4)
    private BigDecimal orderedAmount;

    @Column(name = "accepted_amount", precision = 19, scale = 4)
    private BigDecimal acceptedAmount;

    @Column(name = "rejected_amount", precision = 19, scale = 4)
    private BigDecimal rejectedAmount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "acceptance_remarks", columnDefinition = "text")
    private String acceptanceRemarks;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    public BigDecimal getRemainingQuantity() {
        return remaining(orderedQuantity, acceptedQuantity, rejectedQuantity);
    }

    public BigDecimal getRemainingAmount() {
        return remaining(orderedAmount, acceptedAmount, rejectedAmount);
    }

    private BigDecimal remaining(BigDecimal ordered, BigDecimal accepted, BigDecimal rejected) {
        if (ordered == null) return null;
        return ordered
            .subtract(accepted == null ? BigDecimal.ZERO : accepted)
            .subtract(rejected == null ? BigDecimal.ZERO : rejected)
            .max(BigDecimal.ZERO);
    }
}
