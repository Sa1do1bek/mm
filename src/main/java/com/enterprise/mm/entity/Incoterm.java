package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "incoterms")
public class Incoterm extends BusinessEntity {
    @Column(name = "version_year")
    private Integer versionYear = 2020;

    @Column(name = "mode_scope", length = 80)
    private String modeScope = "ANY";

    @Column(name = "risk_transfer_point", length = 500)
    private String riskTransferPoint;

    @Column(name = "seller_cost_responsibility", length = 1000)
    private String sellerCostResponsibility;

    @Column(name = "buyer_cost_responsibility", length = 1000)
    private String buyerCostResponsibility;

    @Column(name = "named_place_required", nullable = false)
    private boolean namedPlaceRequired = true;
}
