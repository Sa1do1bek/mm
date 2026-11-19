package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.DepreciationMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "asset_depreciation_entries")
public class AssetDepreciationEntry extends BusinessEntity {
    @Column(name = "fixed_asset_id", nullable = false)
    private UUID fixedAssetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_asset_id", insertable = false, updatable = false)
    private FixedAsset fixedAsset;

    @Column(name = "period_year", nullable = false)
    private Integer periodYear;

    @Column(name = "period_month", nullable = false)
    private Integer periodMonth;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate;

    @Column(name = "opening_book_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal openingBookValue = BigDecimal.ZERO;

    @Column(name = "depreciation_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal depreciationAmount = BigDecimal.ZERO;

    @Column(name = "closing_book_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal closingBookValue = BigDecimal.ZERO;

    @Column(name = "accumulated_depreciation", precision = 19, scale = 4, nullable = false)
    private BigDecimal accumulatedDepreciation = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_method", length = 40)
    private DepreciationMethod calculationMethod;

    @Column(name = "is_posted", nullable = false)
    private boolean isPosted = false;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 120)
    private String postedBy;

    @Column(name = "run_reference", length = 120)
    private String runReference;
}
