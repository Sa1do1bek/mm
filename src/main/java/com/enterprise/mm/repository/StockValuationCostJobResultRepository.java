package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockValuationCostJobResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockValuationCostJobResultRepository extends JpaRepository<StockValuationCostJobResult, UUID> {
    Page<StockValuationCostJobResult> findByJobIdOrderBySequenceNoAsc(UUID jobId, Pageable pageable);

    List<StockValuationCostJobResult> findByJobIdOrderBySequenceNoAsc(UUID jobId);
}
