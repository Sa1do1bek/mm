package com.enterprise.mm.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="exact_opening_rows", uniqueConstraints=@UniqueConstraint(name="uk_exact_opening_source_row", columnNames={"import_id","source_row_id"}))
@Getter @Setter
public class ExactOpeningRow {
    @Id private UUID id;
    @Column(name="import_id", nullable=false) private UUID importId;
    @Column(name="source_row_id", nullable=false, length=220) private String sourceRowId;
    @Column(name="account_source_id", nullable=false, length=32) private String accountSourceId;
    @Column(name="account_code", nullable=false, length=4) private String accountCode;
    @Column(name="material_source_id", nullable=false, length=32) private String materialSourceId;
    @Column(name="warehouse_source_id", nullable=false, length=32) private String warehouseSourceId;
    @Column(name="movement_id", nullable=false, unique=true) private UUID movementId;
    @Column(nullable=false, precision=22, scale=7) private BigDecimal quantity;
    @Column(name="total_value", nullable=false, precision=19, scale=4) private BigDecimal totalValue;
}
