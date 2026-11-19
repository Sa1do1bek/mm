package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "asset_events")
public class AssetEvent extends BusinessEntity {
    @Column(name = "fixed_asset_id", nullable = false)
    private UUID fixedAssetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_asset_id", insertable = false, updatable = false)
    private FixedAsset fixedAsset;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "command_id", length = 160)
    private String commandId;

    @Column(name = "idempotency_key", length = 160)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_lifecycle_status", length = 30)
    private FixedAssetLifecycleStatus beforeLifecycleStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_lifecycle_status", length = 30)
    private FixedAssetLifecycleStatus afterLifecycleStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_custody_kind", length = 30)
    private FixedAssetCustodyKind beforeCustodyKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_custody_kind", length = 30)
    private FixedAssetCustodyKind afterCustodyKind;

    @Column(name = "before_custody_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String beforeCustodyJson;

    @Column(name = "after_custody_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String afterCustodyJson;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "actor_username", length = 120)
    private String actorUsername;

    @Column(name = "actor_employee_id")
    private UUID actorEmployeeId;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "approval_instance_id")
    private UUID approvalInstanceId;

    @Column(name = "source_document_type", length = 60)
    private String sourceDocumentType;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "aggregate_version")
    private Long aggregateVersion;
}
