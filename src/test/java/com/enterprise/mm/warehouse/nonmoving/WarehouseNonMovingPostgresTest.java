package com.enterprise.mm.warehouse.nonmoving;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.mm.error.ConflictException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static com.enterprise.mm.warehouse.nonmoving.WarehouseNonMovingDtos.*;

/** Executes the actual PostgreSQL query and V46 against only the tables it reads. */
@Testcontainers(disabledWithoutDocker = true)
class WarehouseNonMovingPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate jdbc;
    WarehouseNonMovingQuery query;
    WarehouseNonMovingPreferenceStore preferences;
    final UUID company = UUID.randomUUID(), warehouse = UUID.randomUUID(), user = UUID.randomUUID();
    final LocalDate today = LocalDate.of(2026, 10, 22);

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("drop schema public cascade; create schema public");
        jdbc.execute("""
            create table companies (id uuid primary key);
            create table users (id uuid primary key);
            create table materials (id uuid primary key, tenant_id text, company_id uuid, code text, name text,
                material_group_id uuid, unit_of_measure_id uuid, material_type text, status text, is_delete boolean default false);
            create table material_groups (id uuid primary key, tenant_id text, company_id uuid, name text, is_delete boolean default false);
            create table unit_of_measures (id uuid primary key, tenant_id text, company_id uuid, code text, name text, is_delete boolean default false,
                measurement_name_short text, measurement_name_short_ru text, measurement_name_short_uz text);
            create table stock_balances (id uuid primary key, tenant_id text, company_id uuid, warehouse_id uuid, material_id uuid,
                quantity numeric, available_quantity numeric, reserved_quantity numeric, quarantine_quantity numeric,
                total_value numeric, is_delete boolean default false);
            create table stock_movements (id uuid primary key, tenant_id text, company_id uuid, warehouse_id uuid,
                target_warehouse_id uuid, material_id uuid, movement_date date, posted_at timestamp,
                status text, movement_type text, reference_type text, quantity numeric, is_delete boolean default false);
            create table stock_movement_allocations (movement_id uuid, tenant_id text, company_id uuid, material_id uuid,
                source_warehouse_id_snapshot uuid, target_warehouse_id_snapshot uuid, on_hand_delta numeric);
            create table warehouse_posting_batches (id uuid primary key, tenant_id text, company_id uuid, status text, posting_date date, context_key text default 'ORDINARY');
            create table warehouse_posting_effects (batch_id uuid, effect_type text, material_id uuid, warehouse_id uuid, quantity numeric);
            create table warehouse_imported_movements (tenant_id text, company_id uuid, material_id uuid,
                warehouse_id uuid, movement_date date, quantity numeric);
            create table non_moving_stock_cases (id uuid primary key, tenant_id text, company_id uuid,
                case_no text, status text, created_at timestamp, is_delete boolean default false);
            create table non_moving_stock_case_lines (case_id uuid, tenant_id text, company_id uuid,
                material_id uuid, warehouse_id uuid, is_delete boolean default false);
            """);
        String baseline = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V1__baseline_schema.sql"));
        int start = baseline.indexOf("CREATE FUNCTION public.multilingual_name_value");
        jdbc.execute(baseline.substring(start, baseline.indexOf("$$;",start)+3));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V46__warehouse_non_moving_preferences.sql")).execute(source);
        jdbc.update("insert into companies values (?)", company);
        jdbc.update("insert into users values (?)", user);
        var named = new NamedParameterJdbcTemplate(jdbc);
        query = new WarehouseNonMovingQuery(named);
        preferences = new WarehouseNonMovingPreferenceStore(named);
    }

    @Test
    void preferencePersistsByUserAndCompanyAndRejectsLostUpdates() {
        var now = LocalDateTime.of(2026, 10, 22, 9, 0);
        preferences.save("tenant", company, user, 24, 0, now);
        // Fresh store instance models another request/login.
        var reloaded = new WarehouseNonMovingPreferenceStore(new NamedParameterJdbcTemplate(jdbc));
        assertThat(reloaded.find("tenant", company, user).orElseThrow().thresholdMonths()).isEqualTo(24);
        UUID secondUser = UUID.randomUUID(), secondCompany = UUID.randomUUID();
        jdbc.update("insert into users values (?)", secondUser);
        jdbc.update("insert into companies values (?)", secondCompany);
        preferences.save("tenant", company, secondUser, 6, 0, now);
        preferences.save("tenant", secondCompany, user, 36, 0, now);
        assertThat(preferences.find("tenant", company, secondUser).orElseThrow().thresholdMonths()).isEqualTo(6);
        assertThat(preferences.find("other-tenant", company, user)).isEmpty();
        assertThatThrownBy(() -> preferences.save("tenant", company, user, 12, 0, now)).isInstanceOf(ConflictException.class);
        assertThat(preferences.save("tenant", company, user, 18, 1, now).version()).isEqualTo(2);
        assertThatThrownBy(() -> preferences.save("tenant", company, user, 6, 1, now)).isInstanceOf(ConflictException.class);
        assertThat(preferences.find("tenant", company, user).orElseThrow().thresholdMonths()).isEqualTo(18);
    }

    @Test
    void exactThresholdAndLiveMovementChangesAreReflectedWithoutWritingDetections() {
        UUID old = material("OLD"), recent = material("RECENT"), unknown = material("UNKNOWN");
        balance(old, warehouse, 10); balance(old, warehouse, 5);
        balance(recent, warehouse, 2); balance(unknown, warehouse, 3);
        movement(old, warehouse, today.minusMonths(24), "POSTED");
        movement(old, warehouse, today, "DRAFT");
        movement(recent, warehouse, today.minusMonths(24).plusDays(1), "POSTED");
        var page = find(24, false, null, 0, 25);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).materialId()).isEqualTo(old);
        assertThat(page.content().get(0).quantity()).isEqualByComparingTo("15");
        assertThat(page.content().get(0).monthsWithoutMovement()).isEqualTo(24);
        assertThat(find(24, true, null, 0, 25).content()).extracting(Candidate::historyStatus)
            .containsExactly("KNOWN", "INSUFFICIENT_HISTORY");
        assertThat(find(12, false, null, 0, 25).totalElements()).isEqualTo(2);
        movement(old, warehouse, today, "POSTED");
        assertThat(find(24, false, null, 0, 25).totalElements()).isZero();
    }

    @Test
    void historicalAndAccountAwareStockDatesCountButValueOnlyChangesDoNot() {
        UUID historic = material("HISTORY"), account = material("ACCOUNT"), value = material("VALUE");
        for (UUID m : new UUID[]{historic, account, value}) balance(m, warehouse, 4);
        jdbc.update("insert into warehouse_imported_movements values ('tenant', ?, ?, ?, ?, 2)",
            company, historic, warehouse, today.minusMonths(30));
        UUID stockBatch = postingBatch(today.minusMonths(25));
        jdbc.update("insert into warehouse_posting_effects values (?, 'STOCK', ?, ?, 2)", stockBatch, account, warehouse);
        movement(value, warehouse, today.minusMonths(26), "POSTED");
        UUID valuation = postingBatch(today);
        jdbc.update("insert into warehouse_posting_effects values (?, 'VALUE_ONLY', ?, ?, 0)", valuation, value, warehouse);
        assertThat(find(24, false, null, 0, 25).content()).extracting(Candidate::materialId)
            .containsExactly(historic, value, account);
        UUID receipt = postingBatch(today);
        jdbc.update("insert into warehouse_posting_effects values (?, 'STOCK', ?, ?, 1)", receipt, account, warehouse);
        assertThat(find(24, false, null, 0, 25).content()).extracting(Candidate::materialId).doesNotContain(account);
    }

    @Test
    void openingImportDoesNotHideOlderRealHistoryAndIsOnlyAFallbackBaseline() {
        UUID known = material("KNOWN"), baseline = material("BASELINE");
        balance(known, warehouse, 4); balance(baseline, warehouse, 3);
        jdbc.update("insert into warehouse_imported_movements values ('tenant', ?, ?, ?, ?, 2)",
            company, known, warehouse, today.minusMonths(30));
        UUID freshOpening = movement(known, warehouse, today, "POSTED");
        UUID oldOpening = movement(baseline, warehouse, today.minusMonths(25), "POSTED");
        jdbc.update("update stock_movements set reference_type='OPENING_STOCK_EXACT' where id in (?,?)", freshOpening, oldOpening);
        var page = find(24, false, null, 0, 25);
        assertThat(page.content()).extracting(Candidate::ageBasis).containsExactly("MOVEMENT", "OPENING_BASELINE");
        assertThat(page.content().get(0).lastMovementDate()).isEqualTo(today.minusMonths(30));
        assertThat(page.content().get(1).lastMovementDate()).isNull();
        assertThat(page.content().get(1).ageReferenceDate()).isEqualTo(today.minusMonths(25));
    }

    @Test
    void transferDispatchDoesNotRefreshDestinationUntilReceiptAllocationExists() {
        UUID material = material("TRANSFER"), source = UUID.randomUUID();
        balance(material, warehouse, 10);
        movement(material, warehouse, today.minusMonths(30), "POSTED");
        UUID dispatch = movement(material, source, today, "POSTED");
        jdbc.update("update stock_movements set movement_type='TRANSFER', target_warehouse_id=?, reference_type='TRANSFER_DISPATCH' where id=?", warehouse, dispatch);
        jdbc.update("insert into stock_movement_allocations values (?, 'tenant', ?, ?, ?, null, -2)", dispatch, company, material, source);
        UUID loss = movement(material, warehouse, today, "POSTED");
        jdbc.update("update stock_movements set movement_type='WRITE_OFF', reference_type='TRANSFER_TRANSIT_LOSS' where id=?", loss);
        assertThat(find(24, false, null, 0, 25).totalElements()).isEqualTo(1);
        UUID receipt = movement(material, warehouse, today, "POSTED");
        jdbc.update("insert into stock_movement_allocations values (?, 'tenant', ?, ?, null, ?, 2)", receipt, company, material, warehouse);
        assertThat(find(24, false, null, 0, 25).totalElements()).isZero();
    }

    @Test
    void groupingScopeLiteralSearchAndPaginationAreStable() {
        UUID first = material("A_%"), second = material("B"), zero = material("ZERO"), fixed = material("ASSET");
        for (UUID m : new UUID[]{first, second, zero, fixed}) {
            balance(m, warehouse, m.equals(zero) ? 0 : 1);
            movement(m, warehouse, today.minusMonths(25), "POSTED");
        }
        jdbc.update("update materials set material_type='FIXED_ASSET' where id=?", fixed);
        // A movement in another company/warehouse must not reset this warehouse's age.
        UUID foreign = movement(first, UUID.randomUUID(), today, "POSTED");
        jdbc.update("update stock_movements set company_id=? where id=?", UUID.randomUUID(), foreign);
        assertThat(find(24, false, "_%", 0, 25).content()).extracting(Candidate::materialId).containsExactly(first);
        var p1 = find(24, false, null, 0, 1); var p2 = find(24, false, null, 1, 1);
        assertThat(p1.totalElements()).isEqualTo(2);
        assertThat(p1.totalPages()).isEqualTo(2);
        assertThat(p1.content().get(0).materialId()).isEqualTo(first);
        assertThat(p2.content().get(0).materialId()).isEqualTo(second);
    }

    @Test
    void everySortDirectionIsNumericDateOrLocalizedTextAndPaginationIsStable() {
        UUID first=material("00007053"), second=material("00007054"), third=material("00007055"), unknown=material("00007056");
        balance(first,warehouse,10); balance(second,warehouse,2); balance(third,warehouse,30); balance(unknown,warehouse,1);
        movement(first,warehouse,today.minusMonths(30),"POSTED");
        movement(second,warehouse,today.minusMonths(25),"POSTED");
        movement(third,warehouse,today.minusMonths(30),"POSTED");
        jdbc.update("update materials set name=? where id=?","ru: Альфа\nen: Zulu\nuz: Alfa",first);
        jdbc.update("update materials set name=? where id=?","ru: Зулу\nen: Alpha\nuz: Zulu",second);
        jdbc.update("update materials set name=? where id=?","ru: Середина\nen: Middle\nuz: O‘rta",third);
        jdbc.update("update stock_balances set total_value=999 where material_id=?",second);
        for(String language: java.util.List.of("en","ru","uz")) for(SortBy sort: SortBy.values()) {
            for(var direction:org.springframework.data.domain.Sort.Direction.values()) {
                var filter=new Filter(null,null,true,0,200,sort,direction);
                var result=query.find("tenant",company,warehouse,24,today,"UZS",filter,language);
                java.util.Comparator<Candidate> comparator=switch(sort) {
                    case MATERIAL_CODE -> java.util.Comparator.comparing(Candidate::materialCode);
                    case MATERIAL_NAME -> java.util.Comparator.comparing(c -> com.enterprise.mm.common.LocalizedText.translate(c.materialName(),java.util.Locale.forLanguageTag(language)));
                    case QUANTITY -> java.util.Comparator.comparing(Candidate::quantity);
                    case BOOK_VALUE -> java.util.Comparator.comparing(Candidate::bookValue);
                    case LAST_MOVEMENT_DATE -> java.util.Comparator.comparing(Candidate::ageReferenceDate,
                        java.util.Comparator.nullsLast(direction.isAscending()?java.util.Comparator.<LocalDate>naturalOrder():java.util.Comparator.<LocalDate>reverseOrder()));
                };
                if(direction.isDescending() && sort!=SortBy.LAST_MOVEMENT_DATE) comparator=comparator.reversed();
                // PostgreSQL's database collation can differ from Java for Cyrillic names; check their explicit order separately below.
                if(sort!=SortBy.MATERIAL_NAME || language.equals("en")) assertThat(result.content()).isSortedAccordingTo(comparator);
                java.util.List<UUID> paged=new java.util.ArrayList<>();
                for(int page=0;page<4;page++) paged.addAll(query.find("tenant",company,warehouse,24,today,"UZS",
                    new Filter(null,null,true,page,1,sort,direction),language,result.totalElements()).content().stream().map(Candidate::materialId).toList());
                assertThat(paged).containsExactlyElementsOf(result.content().stream().map(Candidate::materialId).toList()).doesNotHaveDuplicates();
            }
        }
        assertThat(query.find("tenant",company,warehouse,24,today,"UZS",
            new Filter(null,null,false,0,25,SortBy.MATERIAL_NAME,org.springframework.data.domain.Sort.Direction.ASC),"en").content().getFirst().materialId()).isEqualTo(second);
        assertThat(query.find("tenant",company,warehouse,24,today,"UZS",
            new Filter(null,null,false,0,25,SortBy.MATERIAL_NAME,org.springframework.data.domain.Sort.Direction.ASC),"uz").content().getFirst().materialId()).isEqualTo(first);
    }

    @Test
    void exportMatchesFilteredSortedListWithLocalizedUnitsStatusAndUnknownHistory() throws Exception {
        UUID group=UUID.randomUUID(), unit=UUID.randomUUID();
        jdbc.update("insert into material_groups values (?,'tenant',?, ?,false)",group,company,"ru: Металлы\nen: Metals\nuz: Metallar");
        jdbc.update("insert into unit_of_measures values (?,'tenant',?,'T',?,false,'tonne','тонна','tonna')",unit,company,"ru: тонна\nen: tonne\nuz: tonna");
        UUID first=material("00007053"), second=material("00007054"), excluded=material("00007055");
        for(UUID material:java.util.List.of(first,second,excluded)) {
            balance(material,warehouse,2);
            jdbc.update("update materials set name=?,unit_of_measure_id=? where id=?","ru: Медь\nen: Copper\nuz: Mis",unit,material);
        }
        movement(first,warehouse,today.minusMonths(24),"POSTED");
        movement(excluded,warehouse,today.minusMonths(24).plusDays(1),"POSTED");
        jdbc.update("update materials set material_group_id=? where id in (?,?)",group,first,second);
        UUID caseId=UUID.randomUUID();
        jdbc.update("insert into non_moving_stock_cases values (?,'tenant',?,'NMS-01','UNDER_REVIEW',now(),false)",caseId,company);
        jdbc.update("insert into non_moving_stock_case_lines values (?,'tenant',?,?,?,false)",caseId,company,first,warehouse);
        var service=exportService();
        try {
            preferences.save("tenant",company,user,24,0,LocalDateTime.of(2026,10,22,9,0));
            var filter=new Filter("0000705",group,true,0,25,SortBy.MATERIAL_CODE,org.springframework.data.domain.Sort.Direction.DESC);
            for(String language:java.util.List.of("uz","ru","en")) {
                var listed=service.candidates(warehouse,filter,language);
                assertThat(listed.positions().content()).extracting(Candidate::materialId).containsExactly(second,first);
                try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(warehouse,filter,language)))) {
                    var sheet=book.getSheetAt(0);
                    assertThat(sheet.getLastRowNum()-7).isEqualTo(listed.positions().totalElements());
                    assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).isEqualTo("00007054");
                    assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("00007053");
                    assertThat(sheet.getRow(8).getCell(1).getStringCellValue()).isEqualTo(switch(language){case "uz"->"Mis";case "ru"->"Медь";default->"Copper";});
                    assertThat(sheet.getRow(8).getCell(3).getStringCellValue()).isEqualTo(switch(language){case "uz"->"tonna";case "ru"->"тонна";default->"tonne";});
                    assertThat(sheet.getRow(9).getCell(13).getStringCellValue()).isEqualTo(switch(language){case "uz"->"Ko‘rib chiqilmoqda";case "ru"->"На рассмотрении";default->"Under review";});
                    assertThat(sheet.getRow(8).getCell(8)).isNull();
                    assertThat(sheet.getRow(8).getCell(9)).isNull();
                    assertThat(sheet.getRow(8).getCell(15).getStringCellValue()).isEqualTo(switch(language){case "uz"->"Tarix yetarli emas";case "ru"->"Недостаточно истории";default->"Insufficient history";});
                    assertThat(sheet.getRow(9).getCell(10).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(today);
                }
            }
            assertThat(preferences.find("tenant",company,user).orElseThrow().version()).isEqualTo(1);
        } finally { clearExportContext(); }
    }

    @Test
    void exportRetainsItsSnapshotWhenAnotherRequestChangesSortValuesBetweenPages() throws Exception {
        jdbc.update("""
            insert into materials (id,tenant_id,company_id,code,name,material_type,status)
            select gen_random_uuid(),'tenant',?,lpad(n::text,8,'0'),'Material','TMB','ACTIVE' from generate_series(1,201) as g(n)
            """,company);
        jdbc.update("""
            insert into stock_balances (id,tenant_id,company_id,warehouse_id,material_id,quantity,available_quantity,reserved_quantity,quarantine_quantity,total_value)
            select gen_random_uuid(),'tenant',?,?,m.id,1,1,0,0,10 from materials m where m.company_id=?
            """,company,warehouse,company);
        jdbc.update("""
            insert into stock_movements (id,tenant_id,company_id,warehouse_id,material_id,movement_date,status,movement_type,quantity)
            select gen_random_uuid(),'tenant',?,?,m.id,?,'POSTED','RECEIPT',1 from materials m where m.company_id=?
            """,company,warehouse,today.minusMonths(24),company);
        query=org.mockito.Mockito.spy(query);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            var writer=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
            writer.update("update materials set code='99999999' where company_id=? and code='00000001'",company);
            return result;
        }).when(query).find(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        var service=exportService();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(warehouse,
            new Filter(null,null,false,0,25,SortBy.MATERIAL_CODE,org.springframework.data.domain.Sort.Direction.ASC),"en")))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()-7).isEqualTo(201);
            assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).isEqualTo("00000001");
            assertThat(sheet.getRow(208).getCell(0).getStringCellValue()).isEqualTo("00000201");
            assertThat(jdbc.queryForObject("select count(*) from materials where code='99999999'",Integer.class)).isEqualTo(1);
        } finally { clearExportContext(); }
    }

    private WarehouseNonMovingService exportService() {
        com.enterprise.mm.common.TenantContext.setTenant("tenant");
        com.enterprise.mm.common.CompanyContext.setCompany(company);
        var active=new com.enterprise.mm.entity.User(); active.setId(user);active.setTenantId("tenant");active.setCompanyId(company);active.setEnabled(true);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("keeper",new com.enterprise.mm.security.AuthenticatedTokenState(user,0),java.util.List.of()));
        var users=org.mockito.Mockito.mock(com.enterprise.mm.repository.UserRepository.class);
        org.mockito.Mockito.when(users.findByIdAndIsDeletedFalse(user)).thenReturn(java.util.Optional.of(active));
        var companies=org.mockito.Mockito.mock(com.enterprise.mm.repository.CompanyRepository.class);
        var current=new com.enterprise.mm.entity.Company();current.setId(company);current.setNonMovingStockThresholdMonths(24);
        org.mockito.Mockito.when(companies.findByTenantIdAndIdAndIsDeletedFalse("tenant",company)).thenReturn(java.util.Optional.of(current));
        var warehouses=org.mockito.Mockito.mock(com.enterprise.mm.repository.WarehouseRepository.class);
        var wh=new com.enterprise.mm.entity.Warehouse();wh.setId(warehouse);wh.setCode("072.1");wh.setName("ru: Склад\nen: Warehouse\nuz: Ombor");
        org.mockito.Mockito.when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("tenant",company,warehouse)).thenReturn(java.util.Optional.of(wh));
        var accounting=org.mockito.Mockito.mock(com.enterprise.mm.service.InventoryAccountingSettingsService.class);
        var settings=new com.enterprise.mm.entity.InventoryAccountingSettings();settings.setBaseCurrencyCode("UZS");
        org.mockito.Mockito.when(accounting.findOrDefault()).thenReturn(settings);
        var target=new WarehouseNonMovingService(preferences,query,users,companies,warehouses,
            org.mockito.Mockito.mock(com.enterprise.mm.service.RoleDataScopeService.class),accounting,
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-22T09:00:00Z"),java.time.ZoneId.of("Asia/Tashkent")));
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory=new org.springframework.aop.framework.ProxyFactory(target);factory.addAdvice(interceptor);
        return (WarehouseNonMovingService)factory.getProxy();
    }

    private void clearExportContext() {
        com.enterprise.mm.common.TenantContext.clear();com.enterprise.mm.common.CompanyContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private com.enterprise.mm.payload.PageResponse<Candidate> find(int months, boolean unknown, String search, int page, int size) {
        return query.find("tenant", company, warehouse, months, today, "UZS", new Filter(search, null, unknown, page, size));
    }
    private UUID material(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into materials (id,tenant_id,company_id,code,name,material_type,status) values (?,'tenant',?,?,?,'TMB','ACTIVE')", id, company, code, code);
        return id;
    }
    private void balance(UUID material, UUID wh, int quantity) {
        jdbc.update("""
            insert into stock_balances (id,tenant_id,company_id,warehouse_id,material_id,quantity,
                available_quantity,reserved_quantity,quarantine_quantity,total_value)
            values (?,'tenant',?,?,?,?,?,0,0,?)
            """, UUID.randomUUID(), company, wh, material, quantity, quantity, BigDecimal.valueOf(quantity * 10L));
    }
    private UUID movement(UUID material, UUID wh, LocalDate date, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into stock_movements (id,tenant_id,company_id,warehouse_id,material_id,movement_date,posted_at,status,movement_type,quantity)
            values (?,'tenant',?,?,?,?,?,?, 'RECEIPT',1)
            """, id, company, wh, material, date, LocalDateTime.now(), status);
        return id;
    }
    private UUID postingBatch(LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into warehouse_posting_batches (id,tenant_id,company_id,status,posting_date) values (?,'tenant',?,'COMMITTED',?)", id, company, date);
        return id;
    }
}
