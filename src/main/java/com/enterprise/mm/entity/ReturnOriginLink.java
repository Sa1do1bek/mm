package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "return_origin_links")
public class ReturnOriginLink extends BusinessEntity {

    @Column(name = "return_document_id", nullable = false)
    private UUID returnDocumentId;

    @Column(name = "return_line_id", nullable = false)
    private UUID returnLineId;

    @Column(name = "origin_type", nullable = false, length = 40)
    private String originType;

    @Column(name = "origin_reference", nullable = false, length = 120)
    private String originReference;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "returned_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    @Column(name = "returned_unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal returnedUnitCost = BigDecimal.ZERO;

    @Column(name = "returned_total_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal returnedTotalValue = BigDecimal.ZERO;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;
}
