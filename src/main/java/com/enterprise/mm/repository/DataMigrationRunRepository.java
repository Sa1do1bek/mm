package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.DataMigrationRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataMigrationRunRepository extends JpaRepository<DataMigrationRun, UUID> {
    Optional<DataMigrationRun> findAnyById(UUID id);
    Optional<DataMigrationRun> findByIdAndIsDeletedFalse(UUID id);
}

