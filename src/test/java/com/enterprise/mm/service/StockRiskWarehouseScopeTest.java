package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.StockRiskSeverity;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.StockRiskAlertResponse;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository.Criteria;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository.PagedRows;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository.Row;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class StockRiskWarehouseScopeTest {
    private final RoleDataScopeService scope = mock(RoleDataScopeService.class);
    private final StockRiskAlertQueryRepository query = mock(StockRiskAlertQueryRepository.class);
    private final UnitOfMeasureRepository units = mock(UnitOfMeasureRepository.class);
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final StockRiskAlertService service = new StockRiskAlertService(scope, query, units, warehouses, mock(com.enterprise.mm.repository.MaterialRepository.class));

    private final UUID companyId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("risk-unit-test");
        CompanyContext.setCompany(companyId);
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        when(scope.availabilityWarehouseId(null)).thenReturn(warehouseId);
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setCompanyId(companyId);
        when(warehouses.findByTenantIdAndIdAndIsDeletedFalse(anyString(), eq(warehouseId)))
                .thenReturn(Optional.of(warehouse));

        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setId(unitId);
        unit.setCode("EACH");
        unit.setMeasurementNameShort("EA");
        when(units.findAllById(any())).thenReturn(List.of(unit));
    }

    @AfterEach
    void clear() {
        CompanyContext.clear();
        TenantContext.clear();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void assignedWarehouseAndCompanyAreAppliedToTheQueryAndResponse() {
        when(query.findPage(any(), any())).thenReturn(new PagedRows(List.of(row(3, 5, 9)), 1));

        Page<StockRiskAlertResponse> page = service.search(
                90, true, null, companyId, null, null, null, PageRequest.of(0, 20));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(query).findPage(criteria.capture(), any());
        Criteria c = criteria.getValue();
        assertThat(c.tenantId()).isEqualTo("risk-unit-test");
        assertThat(c.companyId()).isEqualTo(companyId);
        assertThat(c.warehouseId()).isEqualTo(warehouseId);
        assertThat(c.periodDays()).isEqualTo(90);
        assertThat(c.includeHealthy()).isTrue();
        assertThat(c.movementTypes()).containsExactly("ISSUE");
        assertThat(c.movementStatuses()).containsExactly("POSTED");
        assertThat(c.highRiskDays()).isEqualTo(7);
        assertThat(c.mediumRiskDays()).isEqualTo(30);

        assertThat(page.getTotalElements()).isEqualTo(1);
        StockRiskAlertResponse response = page.getContent().get(0);
        assertThat(response.warehouseId()).isEqualTo(warehouseId);
        assertThat(response.companyId()).isEqualTo(companyId);
        assertThat(response.severity()).isEqualTo(StockRiskSeverity.HIGH);
        assertThat(response.risk()).isTrue();
        assertThat(response.unitOfMeasureCode()).isEqualTo("EACH");
        assertThat(response.unitOfMeasureShortName()).isEqualTo("EA");
        assertThat(response.message())
                .isEqualTo("Material may fall below minimum stock in 5 days and may be out of stock in 9 days.");
    }

    @Test
    void unitsAreLoadedOnlyForTheRowsOfTheCurrentPage() {
        when(query.findPage(any(), any())).thenReturn(new PagedRows(List.of(row(0, null, null), row(0, null, null)), 500));

        service.search(90, true, null, companyId, null, null, null, PageRequest.of(1, 2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UUID>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(units).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactly(unitId);
    }

    @Test
    void severityRanksAreMappedToSeveritiesAndMessages() {
        when(query.findPage(any(), any())).thenReturn(new PagedRows(
                List.of(row(4, null, null), row(2, 20, 20), row(1, null, null), row(0, null, null)), 4));

        List<StockRiskAlertResponse> content = service
                .search(90, true, null, companyId, null, null, null, PageRequest.of(0, 20)).getContent();

        assertThat(content).extracting(StockRiskAlertResponse::severity).containsExactly(
                StockRiskSeverity.CRITICAL, StockRiskSeverity.MEDIUM, StockRiskSeverity.LOW, StockRiskSeverity.OK);
        assertThat(content).extracting(StockRiskAlertResponse::risk).containsExactly(true, true, true, false);
        assertThat(content.get(0).message()).isEqualTo("Available stock is at or below minimum stock.");
        assertThat(content.get(2).message()).isEqualTo("Available stock is at or below reorder point.");
        assertThat(content.get(3).message()).isEqualTo("No consumption was detected during the selected period.");
    }

    @Test
    void healthyRowWithConsumptionReportsHealthyMessage() {
        Row healthy = new Row(UUID.randomUUID(), "MAT", "Material", unitId,
                BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(90), BigDecimal.ONE, 1000, 1000, 0);
        when(query.findPage(any(), any())).thenReturn(new PagedRows(List.of(healthy), 1));

        StockRiskAlertResponse response = service
                .search(90, true, null, companyId, null, null, null, PageRequest.of(0, 20)).getContent().get(0);

        assertThat(response.message()).isEqualTo("Stock level is healthy.");
    }

    @Test
    void pageSizeIsCappedAndUnpagedFallsBackToDefault() {
        when(query.findPage(any(), any())).thenReturn(new PagedRows(List.of(), 0));

        service.search(90, true, null, companyId, null, null, null, PageRequest.of(2, 1000));
        service.search(90, true, null, companyId, null, null, null, Pageable.unpaged());

        ArgumentCaptor<Pageable> pageables = ArgumentCaptor.forClass(Pageable.class);
        verify(query, org.mockito.Mockito.times(2)).findPage(any(), pageables.capture());
        assertThat(pageables.getAllValues().get(0).getPageSize()).isEqualTo(200);
        assertThat(pageables.getAllValues().get(0).getPageNumber()).isEqualTo(2);
        assertThat(pageables.getAllValues().get(1).getPageSize()).isEqualTo(50);
        assertThat(pageables.getAllValues().get(1).getPageNumber()).isZero();
    }

    @Test
    void severityFilterPreservesExactOriginalMatching() {
        when(query.findPage(any(), any())).thenReturn(new PagedRows(List.of(), 0));

        service.search(90, false, null, companyId, null, null, List.of("CRITICAL", "HIGH", "high", "BOGUS"), PageRequest.of(0, 20));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(query).findPage(criteria.capture(), any());
        assertThat(criteria.getValue().severityRanks()).isEqualTo(Set.of(4, 3));
        assertThat(criteria.getValue().includeHealthy()).isFalse();
    }

    @Test
    void searchTextIsEscapedSoWildcardsAreMatchedLiterally() {
        when(query.findPage(any(), any())).thenReturn(new PagedRows(List.of(), 0));

        service.search(90, true, null, companyId, null, "  50%_A\\  ", null, PageRequest.of(0, 20));
        service.search(90, true, null, companyId, null, "   ", null, PageRequest.of(0, 20));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(query, org.mockito.Mockito.times(2)).findPage(criteria.capture(), any());
        assertThat(criteria.getAllValues().get(0).searchPattern()).isEqualTo("%  50\\%\\_a\\\\  %");
        assertThat(criteria.getAllValues().get(1).searchPattern()).isNull();
    }

    @Test
    void invalidInputIsRejectedBeforeTheDatabaseIsTouched() {
        Pageable page = PageRequest.of(0, 20);
        assertThatThrownBy(() -> service.search(0, true, null, companyId, null, null, null, page))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(366, true, null, companyId, null, null, null, page))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(90, true, null, companyId, null, null, null,
                PageRequest.of(0, 20, Sort.by("passwordHash; drop table materials"))))
                .isInstanceOf(BadRequestException.class);
        verify(query, never()).findPage(any(), any());
    }

    @Test
    void emptyResultDoesNotLoadUnits() {
        when(query.findPage(any(), any())).thenReturn(new PagedRows(List.of(), 0));

        Page<StockRiskAlertResponse> page = service.search(90, true, null, companyId, null, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        verify(units, never()).findAllById(any());
    }

    private Row row(int severityRank, Integer daysUntilMin, Integer daysUntilOut) {
        return new Row(UUID.randomUUID(), "MAT-" + severityRank, "Material " + severityRank, unitId,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, daysUntilMin, daysUntilOut, severityRank);
    }
}
