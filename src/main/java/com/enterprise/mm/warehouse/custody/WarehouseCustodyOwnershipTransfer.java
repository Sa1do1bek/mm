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
@Table(name = "warehouse_custody_ownership_transfers")
public class WarehouseCustodyOwnershipTransfer extends BusinessEntity {

    @Column(name = "command_id", nullable = false, length = 160)
    private String commandId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "source_owner_id", nullable = false)
    private UUID sourceOwnerId;

    @Column(name = "source_account_code", nullable = false, length = 40)
    private String sourceAccountCode = "002";

    @Column(name = "target_account_code", nullable = false, length = 40)
    private String targetAccountCode = "1010";

    @Column(name = "quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity;

    @Column(name = "unit_settlement_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitSettlementPrice;

    @Column(name = "total_settlement_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalSettlementValue;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "settlement_reference", length = 120)
    private String settlementReference;
}
