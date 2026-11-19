package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.StockValuationRunScope;
import com.enterprise.mm.enums.StockValuationRunStatus;
import com.enterprise.mm.enums.ValuationMethod;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_valuation_runs")
public class StockValuationRun extends BusinessEntity {
    // Keep existing String callers compatible while defining the codes in one enum.
    public static final String STATUS_DRAFT = StockValuationRunStatus.DRAFT.name();
    public static final String STATUS_QUEUED = StockValuationRunStatus.QUEUED.name();
    public static final String STATUS_RUNNING = StockValuationRunStatus.RUNNING.name();
    public static final String STATUS_COMPLETED = StockValuationRunStatus.COMPLETED.name();
    public static final String STATUS_COMPLETED_WITH_ERRORS = StockValuationRunStatus.COMPLETED_WITH_ERRORS.name();
    public static final String STATUS_FAILED = StockValuationRunStatus.FAILED.name();
    public static final String STATUS_CANCELLED = StockValuationRunStatus.CANCELLED.name();

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 40)
    private StockValuationRunScope scopeType;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "stock_valuation_run_warehouses",
        joinColumns = @JoinColumn(name = "valuation_run_id")
    )
    @Column(name = "warehouse_id", nullable = false)
    private List<UUID> warehouseIds = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_method", length = 50)
    private ValuationMethod valuationMethod;

    @Column(name = "description_uz", columnDefinition = "text")
    private String descriptionUz;

    @Column(name = "description_ru", columnDefinition = "text")
    private String descriptionRu;

    @Column(name = "description_en", columnDefinition = "text")
    private String descriptionEn;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Transient
    private BigDecimal progressPercent;
}
