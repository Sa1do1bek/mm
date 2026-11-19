package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "service_executions")
public class ServiceExecution extends BusinessEntity {
    @Column(name = "service_request_id", nullable = false)
    private UUID serviceRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_request_id", insertable = false, updatable = false)
    private ServiceRequest serviceRequest;

    @Column(name = "service_order_id")
    private UUID serviceOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_order_id", insertable = false, updatable = false)
    private ServiceOrder serviceOrder;

    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
    private LocalDate actualStartDate;
    private LocalDate actualEndDate;

    @Column(precision = 7, scale = 2)
    private BigDecimal progressPercent = BigDecimal.ZERO;

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsible_user_id", insertable = false, updatable = false)
    private User responsibleUser;

    @Column(columnDefinition = "text")
    private String remarks;

    @Column(columnDefinition = "text")
    private String executionReport;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        serviceRequestId = serviceRequest == null ? serviceRequestId : serviceRequest.getId();
        serviceOrderId = serviceOrder == null ? serviceOrderId : serviceOrder.getId();
        responsibleUserId = responsibleUser == null ? responsibleUserId : responsibleUser.getId();
    }
}
