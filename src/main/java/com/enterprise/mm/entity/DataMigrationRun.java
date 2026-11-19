package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "data_migration_runs")
public class DataMigrationRun extends BusinessEntity {
    @Column(name = "migration_type", nullable = false, length = 80)
    private String migrationType;

    @Column(name = "source_system", nullable = false, length = 80)
    private String sourceSystem;

    @Column(name = "file_name", length = 260)
    private String fileName;

    @Column(name = "total_rows")
    private int totalRows;

    @Column(name = "valid_rows")
    private int validRows;

    @Column(name = "warning_rows")
    private int warningRows;

    @Column(name = "error_rows")
    private int errorRows;

    @Column(name = "committed_rows")
    private int committedRows;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
