package com.enterprise.mm.entity;

import com.enterprise.mm.payload.WarehouseLayoutDtos.Layout;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "warehouse_layouts") @Getter @Setter
public class WarehouseLayout {
    @Id @Column(name = "warehouse_id") private UUID warehouseId;
    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(name = "company_id", nullable = false) private UUID companyId;
    @Version private Long version;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Layout layout;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 120) private String updatedBy;
}
