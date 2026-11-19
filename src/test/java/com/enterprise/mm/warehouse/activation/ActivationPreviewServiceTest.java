package com.enterprise.mm.warehouse.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class ActivationPreviewServiceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ActivationPreviewService service = new ActivationPreviewService(jdbc);

    private final UUID companyId = UUID.randomUUID();
    private final String tenantId = "test-tenant";

    @BeforeEach
    void setupScope() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void emptyResultReturnedWhenNoCompletedImportsExist() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
            .thenReturn(0L);

        var result = service.preview(new ActivationPreviewQuery(null, null, null, 0, 50));

        assertThat(result.totalCount()).isZero();
        assertThat(result.eligibleCount()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void evaluatesAndAggregatesAllSixClassificationStatesCorrectly() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
            .thenReturn(1L);

        UUID mat1 = UUID.randomUUID();
        UUID wh1 = UUID.randomUUID();

        // Prepare 6 candidates representing all classification states
        var eligibleCandidate = new ActivationPreviewCandidate(
            "src-1", "1010", "Materials", mat1, "M01", "Steel Pipe",
            wh1, "W01", "Central Warehouse", "OWNED", "PCS",
            new BigDecimal("10.0000000"), new BigDecimal("1000.0000"),
            new BigDecimal("4.0000000"), new BigDecimal("400.0000"),
            new BigDecimal("6.0000000"), new BigDecimal("600.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "hash-1", null
        );

        var alreadyRepresentedCandidate = new ActivationPreviewCandidate(
            "src-2", "1010", "Materials", mat1, "M01", "Steel Pipe",
            wh1, "W01", "Central Warehouse", "OWNED", "PCS",
            new BigDecimal("5.0000000"), new BigDecimal("500.0000"),
            new BigDecimal("5.0000000"), new BigDecimal("500.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            SourcePositionClassification.ALREADY_REPRESENTED, "hash-2", "Already covered by native operational balance"
        );

        var valueOnlyCandidate = new ActivationPreviewCandidate(
            "src-3", "1010", "Materials", mat1, "M01", "Steel Pipe",
            wh1, "W01", "Central Warehouse", "OWNED", "PCS",
            BigDecimal.ZERO, new BigDecimal("250.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, new BigDecimal("250.0000"),
            SourcePositionClassification.VALUE_ONLY, "hash-3", "Value-only accounting position without physical stock"
        );

        var negativeCandidate = new ActivationPreviewCandidate(
            "src-4", "1010", "Materials", mat1, "M01", "Steel Pipe",
            wh1, "W01", "Central Warehouse", "OWNED", "PCS",
            new BigDecimal("-2.0000000"), new BigDecimal("-200.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            SourcePositionClassification.NEGATIVE_DISPUTED, "hash-4", "Negative balance in source snapshot"
        );

        var historicalCandidate = new ActivationPreviewCandidate(
            "src-5", "1010", "Materials", mat1, "M01", "Steel Pipe",
            wh1, "W01", "Central Warehouse", "OWNED", "PCS",
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            SourcePositionClassification.HISTORICAL_ONLY, "hash-5", "Zero balance historical record"
        );

        var unresolvedCandidate = new ActivationPreviewCandidate(
            "src-6", "1010", "Materials", null, "M99", "Unknown Material",
            wh1, "W01", "Central Warehouse", "OWNED", "PCS",
            new BigDecimal("1.0000000"), new BigDecimal("100.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            SourcePositionClassification.UNRESOLVED, "hash-6", "Unresolved material or warehouse master binding"
        );

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(List.of(
                eligibleCandidate,
                alreadyRepresentedCandidate,
                valueOnlyCandidate,
                negativeCandidate,
                historicalCandidate,
                unresolvedCandidate
            ));

        var result = service.preview(new ActivationPreviewQuery(null, null, null, 0, 50));

        assertThat(result.totalCount()).isEqualTo(6);
        assertThat(result.eligibleCount()).isEqualTo(1);
        assertThat(result.alreadyRepresentedCount()).isEqualTo(1);
        assertThat(result.valueOnlyCount()).isEqualTo(1);
        assertThat(result.negativeDisputedCount()).isEqualTo(1);
        assertThat(result.historicalOnlyCount()).isEqualTo(1);
        assertThat(result.unresolvedCount()).isEqualTo(1);

        assertThat(result.totalDeltaQuantity()).isEqualByComparingTo("6.0000000");
        assertThat(result.totalDeltaAmount()).isEqualByComparingTo("850.0000"); // 600 + 250
        assertThat(result.items()).hasSize(6);
    }

    @Test
    void filteringByClassificationReturnsOnlyMatchingItems() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(Class.class)))
            .thenReturn(1L);

        var eligible = new ActivationPreviewCandidate(
            "src-1", "1010", "Materials", UUID.randomUUID(), "M01", "Item 1",
            UUID.randomUUID(), "W01", "Warehouse 1", "OWNED", "PCS",
            new BigDecimal("10.0000000"), new BigDecimal("1000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("10.0000000"), new BigDecimal("1000.0000"),
            SourcePositionClassification.ELIGIBLE_DELTA, "hash-1", null
        );

        var valueOnly = new ActivationPreviewCandidate(
            "src-2", "1010", "Materials", UUID.randomUUID(), "M02", "Item 2",
            UUID.randomUUID(), "W01", "Warehouse 1", "OWNED", "PCS",
            BigDecimal.ZERO, new BigDecimal("500.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, new BigDecimal("500.0000"),
            SourcePositionClassification.VALUE_ONLY, "hash-2", "Value-only"
        );

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
            .thenReturn(List.of(eligible, valueOnly));

        var query = new ActivationPreviewQuery(null, null, SourcePositionClassification.ELIGIBLE_DELTA, 0, 10);
        var result = service.preview(query);

        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).classification()).isEqualTo(SourcePositionClassification.ELIGIBLE_DELTA);
    }
}
