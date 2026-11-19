package com.enterprise.mm.warehouse.installation;

import com.enterprise.mm.warehouse.installation.InstallationCommands.InstallationPositionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "warehouse_installation_positions")
@Getter
@Setter
public class WarehouseInstallationPosition {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "company_id", nullable = false, length = 50)
    private String companyId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "account_code", nullable = false, length = 20)
    private String accountCode = "07";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private InstallationPositionStatus status;

    @Column(name = "dispatched_quantity", nullable = false, precision = 19, scale = 7)
    private BigDecimal dispatchedQuantity = BigDecimal.ZERO;

    @Column(name = "accepted_quantity", nullable = false, precision = 19, scale = 7)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;

    @Column(name = "capitalized_quantity", nullable = false, precision = 19, scale = 7)
    private BigDecimal capitalizedQuantity = BigDecimal.ZERO;

    @Column(name = "returned_quantity", nullable = false, precision = 19, scale = 7)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    @Column(name = "source_reference", length = 120)
    private String sourceReference;

    @Column(name = "dispatched_date")
    private LocalDate dispatchedDate;

    @Column(name = "accepted_date")
    private LocalDate acceptedDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public WarehouseInstallationPosition() {
    }

    public BigDecimal getRemainingQuantity() {
        return dispatchedQuantity.subtract(capitalizedQuantity).subtract(returnedQuantity);
    }
}
