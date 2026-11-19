package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "service_request_remarks")
public class ServiceRequestRemark extends BusinessEntity {
    @Column(name = "service_request_id", nullable = false)
    private UUID serviceRequestId;

    @Column(nullable = false, length = 30)
    private String severity;

    @Column(nullable = false, columnDefinition = "text")
    private String remarkDescription;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "assigned_to")
    private UUID assignedTo;
}
