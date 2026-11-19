package com.enterprise.mm.warehouse.custody;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "warehouse_custody_agreements")
public class WarehouseCustodyAgreement extends BusinessEntity {

    @Column(name = "agreement_number", nullable = false, length = 120)
    private String agreementNumber;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "counterparty_name", nullable = false)
    private String counterpartyName;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_through")
    private LocalDate validThrough;

    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_status", nullable = false, length = 40)
    private CustodyAgreementStatus agreementStatus = CustodyAgreementStatus.ACTIVE;

    @Column(name = "settlement_policy", nullable = false, length = 60)
    private String settlementPolicy = "STORAGE_FEE";

    @Column(name = "notes", columnDefinition = "text")
    private String notes;
}
