package com.enterprise.mm.warehouse.custody;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "warehouse_custody_activations")
public class WarehouseCustodyActivation extends BusinessEntity {

    @Column(name = "source_reference_id", nullable = false, length = 160)
    private String sourceReferenceId;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "account_code", nullable = false, length = 40)
    private String accountCode = "002";

    @Column(name = "quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity;

    @Column(name = "reference_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal referenceAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "activation_date", nullable = false)
    private LocalDate activationDate;

    @Column(name = "receipt_json", columnDefinition = "text")
    private String receiptJson;
}
