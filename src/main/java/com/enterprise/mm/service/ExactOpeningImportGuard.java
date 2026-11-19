package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class ExactOpeningImportGuard {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertOperationsAllowed(UUID companyId) {
        if (OpeningImportContext.allows(companyId)) return;
        // Mutations that do not use InventoryScopeLockService still participate in its company lock.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                try (var statement = connection.prepareStatement("select pg_advisory_xact_lock_shared(?)")) {
                    statement.setLong(1, InventoryScopeLockService.companyLockKey(companyId)); statement.execute();
                }
            } else {
                try (var statement = connection.prepareStatement("select id from companies where id=? for update")) {
                    statement.setObject(1, companyId); statement.execute();
                }
            }
            return null;
        });
        Integer count = jdbc.queryForObject("select count(*) from exact_opening_imports where tenant_id=? and company_id=? and status='IMPORTING'",
            Integer.class, TenantContext.currentTenant(), companyId);
        if (count != null && count > 0)
            throw new BusinessException("Company stock operations are blocked until its opening import is reconciled", "OPENING_IMPORT_IN_PROGRESS");
    }
}
