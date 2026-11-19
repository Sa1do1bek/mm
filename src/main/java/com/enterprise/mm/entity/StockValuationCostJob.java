package com.enterprise.mm.entity;

import com.enterprise.mm.enums.StockValuationCostJobPhase;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import com.enterprise.mm.enums.ValuationMethod;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(name = "stock_valuation_cost_jobs")
public class StockValuationCostJob {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 80)
    private String tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "warehouse_id", updatable = false)
    private UUID warehouseId;

      @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "stock_valuation_cost_job_warehouses",
        joinColumns = @JoinColumn(name = "job_id")
    )
    @Column(name = "warehouse_id", nullable = false)
    private List<UUID> warehouseIds = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_method", length = 50, updatable = false)
    private ValuationMethod valuationMethod;

    @Column(name = "requested_by", nullable = false, updatable = false, length = 120)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private StockValuationCostJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private StockValuationCostJobPhase phase;

    @Column(name = "progress_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal progressPercent = new BigDecimal("0.00");

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "processed_items", nullable = false)
    private int processedItems;

    @Column(name = "successful_items", nullable = false)
    private int successfulItems;

    @Column(name = "failed_items", nullable = false)
    private int failedItems;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static StockValuationCostJob queued(String tenantId, UUID companyId, UUID warehouseId, ValuationMethod valuationMethod, String requestedBy) {
        LocalDateTime now = LocalDateTime.now();
        StockValuationCostJob job = new StockValuationCostJob();
        job.setTenantId(tenantId);
        job.setCompanyId(companyId);
        job.setWarehouseId(warehouseId);
        job.setValuationMethod(valuationMethod);
        job.setRequestedBy(requestedBy == null || requestedBy.isBlank() ? "system" : requestedBy);
        job.setStatus(StockValuationCostJobStatus.QUEUED);
        job.setPhase(StockValuationCostJobPhase.WAITING);
        job.setProgressPercent(new BigDecimal("0.00"));
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    public static StockValuationCostJob queued(String tenantId, UUID companyId, UUID warehouseId, String requestedBy) {
        return queued(tenantId, companyId, warehouseId, (ValuationMethod) null, requestedBy);
    }
}
