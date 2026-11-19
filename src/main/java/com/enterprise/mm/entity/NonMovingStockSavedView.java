package com.enterprise.mm.entity;

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
@Table(name = "non_moving_stock_saved_views")
public class NonMovingStockSavedView extends BusinessEntity {
    @Column(name = "owner_username", nullable = false, length = 120)
    private String ownerUsername;

    @Column(name = "workspace_mode", nullable = false, length = 20)
    private String workspaceMode;

    @Column(name = "search_text", length = 240)
    private String searchText;

    @Column(name = "case_statuses", length = 800)
    private String caseStatuses;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;

    @Column(name = "decision_types", length = 800)
    private String decisionTypes;

    @Column(name = "overdue_only", nullable = false)
    private boolean overdueOnly;

    @Column(name = "detection_date_from")
    private LocalDate dateFrom;

    @Column(name = "detection_date_to")
    private LocalDate dateTo;

    @Column(name = "material_group_id")
    private UUID materialGroupId;

    @Column(name = "min_book_value", precision = 19, scale = 4)
    private BigDecimal minBookValue;

    @Column(name = "max_book_value", precision = 19, scale = 4)
    private BigDecimal maxBookValue;

    @Column(name = "execution_statuses", length = 800)
    private String executionStatuses;

    @Column(name = "blocker_types", length = 800)
    private String blockerTypes;

    @Column(name = "integration_statuses", length = 800)
    private String integrationStatuses;

    @Column(name = "exclusion_results", length = 800)
    private String exclusionResults;

    @Column(name = "active_case_only", nullable = false)
    private boolean activeCaseOnly;

    @Column(name = "default_view", nullable = false)
    private boolean defaultView;
}
