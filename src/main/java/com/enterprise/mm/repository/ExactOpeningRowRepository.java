package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ExactOpeningRow;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExactOpeningRowRepository extends JpaRepository<ExactOpeningRow, UUID> {
    boolean existsByImportIdAndSourceRowId(UUID importId, String sourceRowId);
}
