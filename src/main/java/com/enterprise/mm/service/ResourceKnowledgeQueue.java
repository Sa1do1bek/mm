package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResourceKnowledgeQueue {
    private final JdbcTemplate db;

    /** Called inside the same transaction that publishes, replaces or archives a document. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void visibility(UUID resourceId, long revision) {
        db.update("""
            INSERT INTO resource_knowledge_events(id,tenant_id,company_id,resource_id,version_id,kind,
                index_generation,knowledge_revision,visibility_state)
            SELECT gen_random_uuid(),v.tenant_id,v.company_id,r.id,v.id,'VISIBILITY',v.index_generation,?,
                CASE WHEN r.status <> 'PUBLISHED' OR v.status='BLOCKED' THEN 'BLOCKED'
                     WHEN v.id=r.current_version_id THEN 'CURRENT' ELSE 'HISTORICAL' END
            FROM resources r JOIN resource_versions v ON v.resource_id=r.id
            WHERE r.id=? AND r.tenant_id=? AND r.company_id=?
            """, revision, resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany());
        db.update("""
            UPDATE resource_knowledge_events SET status='SUPERSEDED',lease_token=NULL,lease_until=NULL
            WHERE resource_id=? AND tenant_id=? AND company_id=? AND kind='VISIBILITY'
              AND knowledge_revision < ? AND status NOT IN ('DONE','SUPERSEDED')
            """, resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany(), revision);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void index(UUID versionId, long revision) {
        db.update("""
            INSERT INTO resource_knowledge_events(id,tenant_id,company_id,resource_id,version_id,kind,
                index_generation,knowledge_revision,visibility_state)
            SELECT ?,v.tenant_id,v.company_id,v.resource_id,v.id,'INDEX',v.index_generation,?,
                CASE WHEN v.id=r.current_version_id THEN 'CURRENT' ELSE 'HISTORICAL' END
            FROM resource_versions v JOIN resources r ON r.id=v.resource_id
            WHERE v.id=? AND v.tenant_id=? AND v.company_id=? AND r.status='PUBLISHED'
              AND v.status IN ('PUBLISHED','HISTORICAL')
            """, UUID.randomUUID(), revision, versionId, TenantContext.currentTenant(), CompanyContext.currentCompany());
    }
}
