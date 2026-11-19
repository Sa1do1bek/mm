package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetOpeningImportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_opening_imports")
public class AssetOpeningImport extends BusinessEntity {
    @Column(name = "source_file_id")
    private UUID sourceFileId;

    @Column(name = "validated_at")
    private LocalDateTime validatedAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 120)
    private String postedBy;

    public AssetOpeningImportStatus getOpeningImportStatus() {
        return getStatus() == null ? null : AssetOpeningImportStatus.valueOf(getStatus());
    }

    public void setOpeningImportStatus(AssetOpeningImportStatus status) {
        setStatus(status == null ? null : status.name());
    }
}
