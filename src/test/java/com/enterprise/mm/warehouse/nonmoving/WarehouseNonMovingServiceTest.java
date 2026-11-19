package com.enterprise.mm.warehouse.nonmoving;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import com.enterprise.mm.service.RoleDataScopeService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.enterprise.mm.warehouse.nonmoving.WarehouseNonMovingDtos.*;

@ExtendWith(MockitoExtension.class)
class WarehouseNonMovingServiceTest {
    @Mock WarehouseNonMovingPreferenceStore preferences;
    @Mock WarehouseNonMovingQuery query;
    @Mock UserRepository users;
    @Mock CompanyRepository companies;
    @Mock WarehouseRepository warehouses;
    @Mock RoleDataScopeService access;
    @Mock InventoryAccountingSettingsService accounting;
    final UUID companyId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final UUID warehouseId = UUID.randomUUID();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-22T20:30:00Z"), ZoneId.of("Asia/Tashkent"));
    WarehouseNonMovingService service;
    Company company;
    User user;

    @BeforeEach
    void setup() {
        TenantContext.setTenant("personal-non-moving");
        CompanyContext.setCompany(companyId);
        company = new Company(); company.setId(companyId); company.setNonMovingStockThresholdMonths(12);
        user = new User(); user.setId(userId); user.setTenantId(TenantContext.currentTenant()); user.setCompanyId(companyId);
        user.setEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "keeper", new AuthenticatedTokenState(userId, 0), List.of()));
        service = new WarehouseNonMovingService(preferences, query, users, companies, warehouses, access, accounting, clock);
    }

    @AfterEach
    void clear() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }

    void identity() { when(users.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user)); }
    void company() {
        when(companies.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId)).thenReturn(Optional.of(company));
    }

    @Test
    void defaultReadDoesNotPersistAnything() {
        identity(); company();
        assertThat(service.preference()).isEqualTo(new Preference(12, "COMPANY", 0, null));
        verify(preferences).find(TenantContext.currentTenant(), companyId, userId);
        verify(preferences, never()).save(any(), any(), any(), anyInt(), anyLong(), any());
    }

    @Test
    void missingCompanyThresholdUsesTwelveMonths() {
        identity(); company(); company.setNonMovingStockThresholdMonths(null);
        assertThat(service.preference()).isEqualTo(new Preference(12, "DEFAULT", 0, null));
    }

    @Test
    void personalThresholdWinsWithoutChangingCompanyPolicy() {
        identity(); company();
        var saved = new Preference(24, "PERSONAL", 2, LocalDateTime.now(clock));
        when(preferences.find(TenantContext.currentTenant(), companyId, userId)).thenReturn(Optional.of(saved));
        assertThat(service.preference()).isEqualTo(saved);
        assertThat(company.getNonMovingStockThresholdMonths()).isEqualTo(12);
        verify(companies, never()).save(any());
    }

    @Test
    void saveUsesAuthenticatedUserAndCompanyAndVersion() {
        identity(); company();
        var saved = new Preference(24, "PERSONAL", 1, LocalDateTime.now(clock));
        when(preferences.save(TenantContext.currentTenant(), companyId, userId, 24, 0, LocalDateTime.now(clock))).thenReturn(saved);
        assertThat(service.savePreference(new PreferenceRequest(24, 0L))).isEqualTo(saved);
        verify(preferences).save(TenantContext.currentTenant(), companyId, userId, 24, 0, LocalDateTime.now(clock));
        verifyNoInteractions(query);
    }

    @Test
    void jwtIdentityCannotFallBackToAnIdenticallyNamedUserInAnotherScope() {
        identity(); user.setCompanyId(UUID.randomUUID());
        assertThatThrownBy(service::preference).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(preferences, companies);
        verify(users, never()).findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(any(), any(), any());
    }

    @Test
    void otherTenantAndDisabledUsersAreDenied() {
        identity(); user.setTenantId("other");
        assertThatThrownBy(service::preference).isInstanceOf(AccessDeniedException.class);
        user.setTenantId(TenantContext.currentTenant()); user.setEnabled(false);
        assertThatThrownBy(service::preference).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anonymousCannotReadOrSavePreferences() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(service::preference).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.savePreference(new PreferenceRequest(24, 0L))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(preferences);
    }

    @Test
    void invalidSettingsFailBeforePersistence() {
        for (var request : List.of(new PreferenceRequest(0, 0L), new PreferenceRequest(1201, 0L),
            new PreferenceRequest(null, 0L), new PreferenceRequest(24, null), new PreferenceRequest(24, -1L))) {
            assertThatThrownBy(() -> service.savePreference(request)).isInstanceOf(BadRequestException.class);
        }
        verifyNoInteractions(users, preferences);
    }

    @Test
    void assignedWarehouseIsCheckedBeforeReadingCandidateData() {
        when(access.availabilityWarehouseId(warehouseId)).thenThrow(new AccessDeniedException("assignment"));
        assertThatThrownBy(() -> service.candidates(warehouseId, new Filter(null, null, false, 0, 25)))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(query, preferences);
    }

    @Test
    void warehouseInAnotherCompanyIsNotVisibleEvenForBroadReadRoles() {
        assertThatThrownBy(() -> service.candidates(warehouseId, new Filter(null, null, false, 0, 25)))
            .isInstanceOf(NotFoundException.class);
        verify(warehouses).findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId, warehouseId);
        verifyNoInteractions(query, preferences);
    }

    @Test
    void candidatesUseSavedThresholdAndBusinessDateWithReadOnlyPagination() {
        identity(); company();
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId, warehouseId))
            .thenReturn(Optional.of(new Warehouse()));
        when(preferences.find(TenantContext.currentTenant(), companyId, userId))
            .thenReturn(Optional.of(new Preference(24, "PERSONAL", 1, LocalDateTime.now(clock))));
        var settings = new InventoryAccountingSettings(); settings.setBaseCurrencyCode("UZS");
        when(accounting.findOrDefault()).thenReturn(settings);
        var filter = new Filter("bearing", null, false, 1, 25);
        var page = new PageResponse<Candidate>(List.of(), 1, 25, 0, 0);
        when(query.find(TenantContext.currentTenant(), companyId, warehouseId, 24,
            LocalDate.of(2026, 10, 23), "UZS", filter, "uz")).thenReturn(page);
        var result = service.candidates(warehouseId, filter, "uz");
        assertThat(result.effectiveThresholdMonths()).isEqualTo(24);
        assertThat(result.asOfDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(result.positions()).isSameAs(page);
        verify(preferences, never()).save(any(), any(), any(), anyInt(), anyLong(), any());
    }

    @Test
    void invalidPagingNeverQueriesStock() {
        for (var filter : List.of(new Filter(null, null, false, -1, 25), new Filter(null, null, false, 0, 201),
            new Filter(null, null, false, 0, 0), new Filter("a".repeat(241), null, false, 0, 25))) {
            assertThatThrownBy(() -> service.candidates(warehouseId, filter)).isInstanceOf(BadRequestException.class);
        }
        verifyNoInteractions(query, warehouses);
    }

    @Test
    void calendarMonthAnniversariesHandleLeapYearsAndMonthEnds() {
        assertThat(WarehouseNonMovingQuery.elapsedMonths(LocalDate.of(2024, 2, 29), LocalDate.of(2026, 2, 28))).isEqualTo(24);
        assertThat(WarehouseNonMovingQuery.elapsedMonths(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28))).isEqualTo(1);
        assertThat(WarehouseNonMovingQuery.elapsedMonths(LocalDate.of(2024, 10, 23), LocalDate.of(2026, 10, 22))).isEqualTo(23);
    }
    private void candidateAccess() {
        identity(); company();
        Warehouse warehouse = new Warehouse(); warehouse.setId(warehouseId); warehouse.setCode("072.1");
        warehouse.setName("ru: Склад\nen: Warehouse\nuz: Ombor");
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId, warehouseId))
            .thenReturn(Optional.of(warehouse));
        var settings = new InventoryAccountingSettings(); settings.setBaseCurrencyCode("UZS");
        when(accounting.findOrDefault()).thenReturn(settings);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"uz,Material kodi,Mis,Ish ochilmagan", "ru,Код материала,Медь,Дело не открыто", "en,Material code,Copper,No case opened"})
    void exportIsLocalizedTypedAndIncludesEveryPage(String language, String header, String material, String status) throws Exception {
        candidateAccess();
        var filter = new Filter("copper", UUID.randomUUID(), true, 9, 25, SortBy.MATERIAL_CODE, org.springframework.data.domain.Sort.Direction.DESC);
        Candidate candidate = exportCandidate();
        when(query.find(anyString(), eq(companyId), eq(warehouseId), eq(12), eq(LocalDate.of(2026,10,23)), eq("UZS"),
            argThat(f -> f.page()==0 && f.size()==200 && f.sortBy()==SortBy.MATERIAL_CODE && f.direction().isDescending()
                && f.search().equals("copper") && f.includeUnknownHistory() && f.materialGroupId().equals(filter.materialGroupId())), eq(language)))
            .thenReturn(new PageResponse<>(Collections.nCopies(200,candidate),0,200,201,2));
        when(query.find(anyString(), eq(companyId), eq(warehouseId), eq(12), eq(LocalDate.of(2026,10,23)), eq("UZS"),
            argThat(f -> f.page()==1 && f.size()==200 && f.sortBy()==filter.sortBy() && f.direction()==filter.direction()
                && Objects.equals(f.search(),filter.search()) && Objects.equals(f.materialGroupId(),filter.materialGroupId()) && f.includeUnknownHistory()), eq(language), eq(201L)))
            .thenReturn(new PageResponse<>(List.of(candidate),1,200,201,2));
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(warehouseId,filter,language)))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo(header);
            var row = sheet.getRow(8);
            assertThat(row.getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("00007053");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo(material);
            assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(0.0767);
            assertThat(row.getCell(8).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2020,12,31));
            assertThat(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(row.getCell(8))).isTrue();
            assertThat(row.getCell(12).getStringCellValue()).isEqualTo("UZS");
            assertThat(row.getCell(13).getStringCellValue()).isEqualTo(status);
            assertThat(row.getCell(14).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(row.getCell(14).getStringCellValue()).isEqualTo("=HYPERLINK(\"bad\")");
            assertThat(sheet.getLastRowNum()).isEqualTo(208);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).contains("12", "2026-10-23");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).contains("Asia/Tashkent");
        }
        verify(preferences,times(1)).find(TenantContext.currentTenant(),companyId,userId);
        verify(preferences,never()).save(any(),any(),any(),anyInt(),anyLong(),any());
    }

    @Test void exportLimitRejectsWithoutTruncatingAndEmptyResultHasHeaders() throws Exception {
        candidateAccess();
        var filter = new Filter(null,null,false,0,25);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"exportMaxRows",200);
        when(query.find(anyString(),any(),any(),anyInt(),any(),anyString(),any(),anyString()))
            .thenReturn(new PageResponse<>(List.of(),0,200,201,2));
        assertThatThrownBy(() -> service.export(warehouseId,filter,"uz")).isInstanceOf(BadRequestException.class).hasMessageContaining("200");
        when(query.find(anyString(),any(),any(),anyInt(),any(),anyString(),any(),anyString()))
            .thenReturn(new PageResponse<>(List.of(),0,200,0,0));
        try(var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(warehouseId,filter,"uz")))) {
            assertThat(book.getSheetAt(0).getRow(8).getCell(0).getStringCellValue()).isEqualTo("Mos harakatsiz qoldiqlar topilmadi");
        }
        verify(query,never()).find(anyString(),any(),any(),anyInt(),any(),anyString(),any(),anyString(),anyLong());
    }

    @Test void exportCannotBypassWarehouseScope() {
        when(access.availabilityWarehouseId(warehouseId)).thenThrow(new AccessDeniedException("assignment"));
        assertThatThrownBy(() -> service.export(warehouseId,new Filter(null,null,false,0,25),"uz"))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(query,preferences);
    }

    @Test void exportRejectsInvalidLanguageBeforeReadingData() {
        assertThatThrownBy(() -> service.export(warehouseId,new Filter(null,null,false,0,25),"de"))
            .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(query,preferences,warehouses);
    }

    @Test void existingControllerBindsExportFiltersSortAndLanguageAndIgnoresPagination() throws Exception {
        var mockService = mock(WarehouseNonMovingService.class);
        when(mockService.export(any(),any(),anyString())).thenReturn(new byte[]{1,2,3});
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new WarehouseNonMovingController(mockService))
            .setLocaleResolver(new com.enterprise.mm.config.LocaleConfig().localeResolver())
            .setControllerAdvice(new com.enterprise.mm.error.GlobalExceptionHandler()).build();
        UUID group = UUID.randomUUID();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/warehouses/"+warehouseId+"/non-moving-candidates/export")
                .param("search","bearing").param("materialGroupId",group.toString()).param("includeUnknownHistory","true")
                .param("sortBy","BOOK_VALUE").param("direction","DESC").param("language","uz-UZ")
                .param("page","7").param("size","1").header("Accept-Language","ru"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Language","uz"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        verify(mockService).export(eq(warehouseId),eq(new Filter("bearing",group,true,0,200,SortBy.BOOK_VALUE,org.springframework.data.domain.Sort.Direction.DESC)),eq("uz"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/warehouses/"+warehouseId+"/non-moving-candidates/export").header("Accept-Language","ru-RU,ru;q=0.9"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Language","ru"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/warehouses/"+warehouseId+"/non-moving-candidates/export"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Language","uz"));
        for(String[] invalid : List.of(new String[]{"sortBy","BOGUS"},new String[]{"direction","SIDEWAYS"},new String[]{"language","de"},new String[]{"materialGroupId","bad-uuid"})) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/warehouses/"+warehouseId+"/non-moving-candidates/export").param(invalid[0],invalid[1]))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
    }

    private Candidate exportCandidate() {
        return new Candidate(UUID.randomUUID(),"00007053","ru: Медь\nen: Copper\nuz: Mis",null,
            "{ru=Металлы, en=Metals, uz=Metallar}",warehouseId,"T","ru: тонна\nen: tonne\nuz: tonna",
            new java.math.BigDecimal("0.0767"),new java.math.BigDecimal("0.0767"),java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,
            new java.math.BigDecimal("3835.31"),"UZS",LocalDate.of(2020,12,31),LocalDate.of(2020,12,31),"MOVEMENT",69,
            LocalDate.of(2022,12,31),"KNOWN",null,"=HYPERLINK(\"bad\")",null);
    }

}
