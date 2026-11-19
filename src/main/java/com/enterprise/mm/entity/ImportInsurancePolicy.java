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
@Table(name = "import_insurance_policies")
public class ImportInsurancePolicy extends BusinessEntity {
    @Column(name = "policy_number", length = 120)
    private String policyNumber;

    @Column(name = "import_delivery_id", nullable = false)
    private UUID importDeliveryId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_delivery_id", insertable = false, updatable = false)
    private ImportDelivery importDelivery;

    @Column(name = "insurer_supplier_id")
    private UUID insurerSupplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "insurer_supplier_id", insertable = false, updatable = false)
    private Supplier insurerSupplier;

    @Column(name = "insurer_name", length = 240)
    private String insurerName;

    @Column(name = "coverage_type", length = 120)
    private String coverageType;

    @Column(name = "insured_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal insuredAmount = BigDecimal.ZERO;

    @Column(name = "premium_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal premiumAmount = BigDecimal.ZERO;

    @Column(name = "deductible_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal deductibleAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "allocation_method", nullable = false, length = 40)
    private String allocationMethod = "VALUE";

    @Column(name = "included_in_inventory_cost", nullable = false)
    private boolean includedInInventoryCost = true;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "policy_attachment_id")
    private UUID policyAttachmentId;

    @Column(name = "landed_cost_component_id")
    private UUID landedCostComponentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "landed_cost_component_id", insertable = false, updatable = false)
    private LandedCostComponent landedCostComponent;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        importDeliveryId = importDelivery == null ? importDeliveryId : importDelivery.getId();
        insurerSupplierId = insurerSupplier == null ? insurerSupplierId : insurerSupplier.getId();
        landedCostComponentId = landedCostComponent == null ? landedCostComponentId : landedCostComponent.getId();
    }
}
