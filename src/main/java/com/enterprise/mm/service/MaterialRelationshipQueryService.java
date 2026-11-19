package com.enterprise.mm.service;

import com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.RelationshipRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey;
import com.enterprise.mm.repository.MaterialRelationshipQueryRepository;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialRelationshipQueryService {
    private final MaterialRelationshipQueryRepository repository;

    public Page<RelationshipRow> page(
        MaterialScope scope, RelationshipFilter filter, Set<SectionKey> readableSections, Pageable pageable
    ) {
        Pageable bounded = bounded(pageable);
        Set<SectionKey> sections = readableSections == null ? Set.of() : Set.copyOf(readableSections);
        boolean documents = sections.contains(SectionKey.DOCUMENTS);
        boolean audit = sections.contains(SectionKey.AUDIT);
        boolean integrations = sections.contains(SectionKey.INTEGRATIONS);
        if (!documents && !audit && !integrations) return new PageImpl<>(List.of(), bounded, 0);
        return repository.page(scope.tenantId(), scope.companyId(), scope.materialId(), documents, audit,
                integrations, filter, bounded)
            .map(record -> new RelationshipRow(
                record.id(), record.type(), record.businessCode(), record.displayName(), record.status(),
                record.occurredAt(), record.sourceType(), record.sourceId(),
                LinkTarget.of(record.moduleKey(), record.sourceId(), Map.of())));
    }

    public Optional<RelationshipRow> latestIntegrationRisk(MaterialScope scope) {
        return repository.latestIntegrationRisk(scope.tenantId(), scope.companyId(), scope.materialId())
            .map(this::map);
    }

    private RelationshipRow map(MaterialRelationshipQueryRepository.RelationshipRecord record) {
        return new RelationshipRow(
            record.id(), record.type(), record.businessCode(), record.displayName(), record.status(),
            record.occurredAt(), record.sourceType(), record.sourceId(),
            LinkTarget.of(record.moduleKey(), record.sourceId(), Map.of()));
    }

    private Pageable bounded(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) return PageRequest.of(0, 25);
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), Math.min(pageable.getPageSize(), 100));
    }
}
