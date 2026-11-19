package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class SupplyCommitmentQueryRepositoryTimeZoneTest {

    @Test
    @SuppressWarnings("unchecked")
    void findScopedDerivesBusinessDateFromConfiguredWorkspaceTimeZone() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(List.of());
        SupplyCommitmentQueryRepository repository =
            new SupplyCommitmentQueryRepository(jdbcTemplate, "Pacific/Honolulu");
        UUID companyId = UUID.randomUUID();

        repository.findScoped(
            new SupplyCommitmentKey(SupplyCommitmentKind.PO, UUID.randomUUID()),
            new SupplyAccessScope("tenant", Set.of(companyId), Set.of(), false, false),
            Instant.parse("2026-07-15T01:00:00Z"));

        ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(anyString(), parameters.capture(), any(RowMapper.class));
        assertThat(parameters.getValue().getValue("asOfDate"))
            .isEqualTo(LocalDate.of(2026, 7, 14));
    }
}
