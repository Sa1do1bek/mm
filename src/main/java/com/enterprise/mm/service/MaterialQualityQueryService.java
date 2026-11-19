package com.enterprise.mm.service;

import com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.QualityCompatibilityRow;
import com.enterprise.mm.repository.MaterialQualityCompatibilityQueryRepository;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialQualityQueryService {
    private final MaterialQualityCompatibilityQueryRepository repository;

    public Page<QualityCompatibilityRow> page(
        MaterialScope scope, QualityCompatibilityFilter filter, Pageable pageable
    ) {
        return repository.page(scope.tenantId(), scope.companyId(), scope.materialId(), filter, bounded(pageable))
            .map(row -> new QualityCompatibilityRow(
                row.id(), row.kind(), row.status(), row.displayName(), row.detail(), row.effectiveFrom(),
                row.effectiveTo(), LinkTarget.of(row.moduleKey(), row.recordId(), Map.of())));
    }

    public boolean openIssueExists(MaterialScope scope) {
        return repository.openIssueExists(scope.tenantId(), scope.companyId(), scope.materialId());
    }

    private Pageable bounded(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) return PageRequest.of(0, 25);
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), Math.min(pageable.getPageSize(), 100));
    }
}
