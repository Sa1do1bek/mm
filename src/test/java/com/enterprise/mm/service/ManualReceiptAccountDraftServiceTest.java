package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ManualReceiptAccountDraftServiceTest {
    private final UUID companyId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void savesImmutableReceiptContextAndPreventsReuse() throws Exception {
        TenantContext.setTenant("draft-test");
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "operator", new AuthenticatedTokenState(UUID.randomUUID(), 0), java.util.List.of()));
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:receipt-draft-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table warehouses (id uuid primary key)");
        jdbc.execute("create table materials (id uuid primary key)");
        jdbc.update("insert into warehouses(id) values (?)", warehouseId);
        jdbc.update("insert into materials(id) values (?)", materialId);
        // H2 2.4 retains a closed session for IN-list CHECK expressions across JdbcTemplate connections.
        String schema = Files.readString(Path.of("src/main/resources/db/migration/V80__manual_receipt_account_drafts.sql"))
            .replace(" CHECK (status IN ('DRAFT','POSTED'))", "");
        jdbc.execute(schema);
        var scope = mock(RoleDataScopeService.class);
        var catalog = mock(MaterialCatalogGuard.class);
        var warehouses = mock(WarehouseRepository.class);
        var warehouse = new Warehouse();
        warehouse.setCompanyId(companyId);
        warehouse.setStatus("ACTIVE");
        when(warehouses.findByTenantIdAndIdAndIsDeletedFalse("draft-test", warehouseId))
            .thenReturn(Optional.of(warehouse));
        var service = new ManualReceiptAccountDraftService(jdbc, scope, catalog, warehouses);
        LocalDate date = LocalDate.now().minusDays(1);
        var draft = service.create(new ManualReceiptAccountDraftService.Create(warehouseId, materialId,
            date, " Repair ", new BigDecimal("2.00"), new BigDecimal("50.00")));

        assertThat(service.get(draft.id()).purpose()).isEqualTo("Repair");
        assertThat(service.lockForPosting(draft.id(), warehouseId, materialId, date, "Repair",
            new BigDecimal("2"), new BigDecimal("50"))).isEqualTo(draft);
        assertThatThrownBy(() -> service.lockForPosting(draft.id(), warehouseId, materialId, date, "Other",
            new BigDecimal("2"), new BigDecimal("50"))).isInstanceOf(ConflictException.class);
        UUID movementId = UUID.randomUUID();
        service.markPosted(draft.id(), movementId);
        assertThat(jdbc.queryForObject("select posted_movement_id from manual_receipt_account_drafts where id=?",
            UUID.class, draft.id())).isEqualTo(movementId);
        assertThatThrownBy(() -> service.get(draft.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.markPosted(draft.id(), UUID.randomUUID()))
            .isInstanceOf(ConflictException.class);
    }
}
