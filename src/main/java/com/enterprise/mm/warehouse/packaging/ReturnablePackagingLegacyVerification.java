package com.enterprise.mm.warehouse.packaging;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.payload.ApiResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only receipt for stock-preserving opening adoption; never executes a migration. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/warehouse/returnable-packaging/legacy-migrations")
@PreAuthorize("hasRole('ADMIN')")
public class ReturnablePackagingLegacyVerification {
    private final NamedParameterJdbcTemplate jdbc;

    @GetMapping @Transactional(readOnly = true)
    public ApiResponse<List<Map<String, Object>>> list() {
        var scope = new MapSqlParameterSource("tenant", TenantContext.currentTenant())
            .addValue("company", CompanyContext.currentCompany());
        return ApiResponse.ok(jdbc.queryForList("""
            select run_code,material_count,lot_count,stock_before::text,stock_after::text,
                material_before_md5,material_after_md5,adopted_value::text,migrated_at,
                stock_before=stock_after as stock_unchanged,
                material_before_md5=material_after_md5 as other_material_fields_unchanged
            from returnable_packaging_legacy_migrations
            where tenant_id=:tenant and company_id=:company order by migrated_at
            """, scope));
    }
}
