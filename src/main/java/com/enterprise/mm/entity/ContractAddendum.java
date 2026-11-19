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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "contract_addendums")
public class ContractAddendum extends BusinessEntity {
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;
    @Column(length = 120)
    private String addendumNumber;
    private LocalDate addendumDate;
    @Column(length = 40)
    private String addendumType = "PROLONGATION";
    private LocalDate effectiveDate;
    private LocalDate newEndDate;
    @Column(precision = 19, scale = 4)
    private BigDecimal limitDelta = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal newLimitAmount;
    @Column(columnDefinition = "text")
    private String reason;
    private LocalDateTime postedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        contractId = contract == null ? contractId : contract.getId();
    }
}
