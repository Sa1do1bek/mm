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
class InventoryAvailabilityPostgresSqlCastTest {
    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void explicitlyCastsNullableWarehouseParametersInPostgresSql() {
        TenantContext.setTenant("tenant-overview-sql");
        CompanyContext.setCompany(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        when(jdbcTemplate.queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(null);
        InventoryAvailabilityQueryRepository repository =
            new InventoryAvailabilityQueryRepository(jdbcTemplate);

        repository.overview(new InventoryAvailabilityFilter(null, null, null, null,
                null, null, null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue())
            .contains("cast(:warehouseId as uuid) is null")
            .doesNotContain(":warehouseId is null");
    }
}
