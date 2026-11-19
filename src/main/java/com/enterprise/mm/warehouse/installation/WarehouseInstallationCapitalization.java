package com.enterprise.mm.warehouse.installation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "warehouse_installation_capitalizations")
@Getter
@Setter
public class WarehouseInstallationCapitalization {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "company_id", nullable = false, length = 50)
    private String companyId;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "capitalization_command_id", nullable = false, length = 120)
    private String capitalizationCommandId;

    @Column(name = "fixed_asset_id", nullable = false)
    private UUID fixedAssetId;

    @Column(name = "capitalized_quantity", nullable = false, precision = 19, scale = 7)
    private BigDecimal capitalizedQuantity;

    @Column(name = "capitalized_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal capitalizedAmount;

    @Column(name = "capitalization_date", nullable = false)
    private LocalDate capitalizationDate;

    @Column(name = "source_reference", length = 120)
    private String sourceReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public WarehouseInstallationCapitalization() {
    }
}
