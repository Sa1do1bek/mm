package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "warehouses")
public class Warehouse extends BusinessEntity {
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="source_data",columnDefinition="jsonb")
    private java.util.Map<String,Object> sourceData;
    @Column(name="parent_id") private java.util.UUID parentId;
    @Column(name="source_group",nullable=false) private boolean sourceGroup;
    @Column(name="source_has_balance",nullable=false) private boolean sourceHasBalance;

    @Column(length = 500)
    private String address;

    @OneToMany(mappedBy = "warehouse", fetch = FetchType.LAZY)
    private List<StorageLocation> storageLocations = new ArrayList<>();

    @OneToMany(mappedBy = "warehouse", fetch = FetchType.LAZY)
    private List<StockMovement> stockMovements = new ArrayList<>();

    @OneToMany(mappedBy = "warehouse", fetch = FetchType.LAZY)
    private List<StockBatch> stockBatches = new ArrayList<>();
}
