package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResourceVersionAccessService {
    private final JdbcTemplate db;

    public void requireVisible(UUID versionId) {
        Integer count = db.queryForObject("""
            SELECT count(*) FROM resource_versions v JOIN resources r ON r.id = v.resource_id
            WHERE v.id = ? AND v.tenant_id = ? AND v.company_id = ?
              AND r.tenant_id = ? AND r.company_id = ?
              AND v.status IN ('PUBLISHED', 'HISTORICAL')
              AND r.status IN ('PUBLISHED', 'ARCHIVED')
            """, Integer.class, versionId, TenantContext.currentTenant(), CompanyContext.currentCompany(),
            TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (count == null || count == 0) throw new NotFoundException("Resource version not found");
    }
}
