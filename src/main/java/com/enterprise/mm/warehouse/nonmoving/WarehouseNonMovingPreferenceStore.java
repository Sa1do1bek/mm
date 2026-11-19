package com.enterprise.mm.warehouse.nonmoving;

import com.enterprise.mm.error.ConflictException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WarehouseNonMovingPreferenceStore {
    private final NamedParameterJdbcTemplate jdbc;

    public Optional<WarehouseNonMovingDtos.Preference> find(String tenant, UUID company, UUID user) {
        return jdbc.query("""
            select threshold_months, version, updated_at from warehouse_non_moving_preferences
            where tenant_id=:tenant and company_id=:company and user_id=:user
            """, scope(tenant, company, user), (rs, n) -> new WarehouseNonMovingDtos.Preference(
                rs.getInt("threshold_months"), "PERSONAL", rs.getLong("version"),
                rs.getTimestamp("updated_at").toLocalDateTime())).stream().findFirst();
    }

    /** Atomic compare-and-set also covers two requests racing to create the first preference. */
    public WarehouseNonMovingDtos.Preference save(String tenant, UUID company, UUID user, int months,
                                                  long expectedVersion, LocalDateTime now) {
        var params = scope(tenant, company, user).addValue("id", UUID.randomUUID())
            .addValue("months", months).addValue("expected", expectedVersion).addValue("now", now);
        String sql = expectedVersion == 0 ? """
            insert into warehouse_non_moving_preferences
                (id, tenant_id, company_id, user_id, threshold_months, version, created_at, updated_at)
            values (:id, :tenant, :company, :user, :months, 1, :now, :now)
            on conflict (tenant_id, company_id, user_id) do nothing
            returning threshold_months, version, updated_at
            """ : """
            update warehouse_non_moving_preferences set threshold_months=:months,
                version=version+1, updated_at=:now
            where tenant_id=:tenant and company_id=:company and user_id=:user and version=:expected
            returning threshold_months, version, updated_at
            """;
        return jdbc.query(sql, params, (rs, n) -> new WarehouseNonMovingDtos.Preference(
            rs.getInt("threshold_months"), "PERSONAL", rs.getLong("version"),
            rs.getTimestamp("updated_at").toLocalDateTime())).stream().findFirst()
            .orElseThrow(() -> new ConflictException("Personal non-moving threshold changed; reload the preference before saving"));
    }

    private MapSqlParameterSource scope(String tenant, UUID company, UUID user) {
        return new MapSqlParameterSource("tenant", tenant).addValue("company", company).addValue("user", user);
    }
}
