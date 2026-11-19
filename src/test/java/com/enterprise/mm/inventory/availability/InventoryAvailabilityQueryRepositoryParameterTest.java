package com.enterprise.mm.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.inventory.availability.repository.InventoryAvailabilityQueryRepository;
import java.sql.Types;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@ExtendWith(MockitoExtension.class)
class InventoryAvailabilityQueryRepositoryParameterTest {
    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bindsAnEmptyWarehouseFilterAsAPostgresUuid() {
        TenantContext.setTenant("tenant-overview-parameter");
        CompanyContext.setCompany(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        when(jdbcTemplate.queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(null);
        InventoryAvailabilityQueryRepository repository =
            new InventoryAvailabilityQueryRepository(jdbcTemplate);

        repository.overview(new InventoryAvailabilityFilter(null, null, null, null,
                null, null, null, null, null));

        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).queryForObject(anyString(), parameters.capture(), any(RowMapper.class));
        assertThat(parameters.getValue().getValue("warehouseId")).isNull();
        assertThat(parameters.getValue().getSqlType("warehouseId")).isEqualTo(Types.OTHER);
        assertThat(parameters.getValue().getTypeName("warehouseId")).isEqualTo("uuid");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bindsMaterialDateAndWarehouseIdsParametersAndBuildsWhereClause() {
        TenantContext.setTenant("tenant-overview-parameter");
        CompanyContext.setCompany(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        when(jdbcTemplate.queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(null);
        InventoryAvailabilityQueryRepository repository =
            new InventoryAvailabilityQueryRepository(jdbcTemplate);

        UUID materialId = UUID.randomUUID();
        UUID wh1 = UUID.randomUUID();
        UUID wh2 = UUID.randomUUID();
        java.time.LocalDate asOfDate = java.time.LocalDate.of(2026, 10, 20);
        java.time.LocalDate from = java.time.LocalDate.of(2026, 10, 1);
        java.time.LocalDate to = java.time.LocalDate.of(2026, 10, 31);

        InventoryAvailabilityFilter filter = new InventoryAvailabilityFilter(
            null, null, null, null, null, null, null, null, null,
            "MAT-100", "Steel", materialId, java.util.List.of(wh1, wh2), asOfDate, from, to
        );

        repository.overview(filter);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), paramCaptor.capture(), any(RowMapper.class));

        SqlParameterSource params = paramCaptor.getValue();
        assertThat(params.getValue("materialCode")).isEqualTo("%mat-100%");
        assertThat(params.getValue("materialName")).isEqualTo("%steel%");
        assertThat(params.getValue("materialId")).isEqualTo(materialId);
        assertThat(params.getValue("hasWarehouseIds")).isEqualTo(true);
        assertThat(params.getValue("warehouseIds")).isEqualTo(java.util.List.of(wh1, wh2));
        assertThat(params.getValue("asOfDateTime")).isNotNull();
        assertThat(params.getValue("dateFrom")).isNotNull();
        assertThat(params.getValue("dateTo")).isNotNull();

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("lower(material_code) like :materialCode");
        assertThat(sql).contains("lower(material_name) like :materialName");
        assertThat(sql).contains("material_id = cast(:materialId as uuid)");
        assertThat(sql).contains("balance_at <= :asOfDateTime");
        assertThat(sql).contains("balance_at >= :dateFrom");
        assertThat(sql).contains("balance_at <= :dateTo");
        assertThat(sql).contains(":hasWarehouseIds = false or balance.warehouse_id in (:warehouseIds)");
    }
}
