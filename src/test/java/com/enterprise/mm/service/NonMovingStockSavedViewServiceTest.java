package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.NonMovingStockSavedView;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewFilter;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewRequest;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceMode;
import com.enterprise.mm.repository.NonMovingStockSavedViewRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class NonMovingStockSavedViewServiceTest {
    @Mock private NonMovingStockSavedViewRepository repository;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-nms-view");
        CompanyContext.setCompany(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("case.manager", "n/a")
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void savesNormalizedFiltersInsideCurrentUserAndCompanyScope() {
        when(repository.findByTenantIdAndCompanyIdAndOwnerUsernameAndWorkspaceModeAndIsDeletedFalseOrderByNameAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "case.manager", WorkspaceMode.CASES.name()
        )).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(invocation -> {
            NonMovingStockSavedView entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setVersion(0L);
            return entity;
        });

        var result = service().save(new SavedViewRequest(
            "  My overdue cases  ", WorkspaceMode.CASES,
            new SavedViewFilter("  bearing  ", List.of(" under_review "), UUID.randomUUID(),
                UUID.randomUUID(), List.of(" write_off "), true,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 15), UUID.randomUUID(),
                new BigDecimal("100"), new BigDecimal("900"), List.of(" failed "),
                List.of(" unfinished_output "), List.of(" retry "), List.of(" included "), true), false
        ));

        assertThat(result.name()).isEqualTo("My overdue cases");
        assertThat(result.filters().search()).isEqualTo("bearing");
        assertThat(result.filters().statuses()).containsExactly("UNDER_REVIEW");
        assertThat(result.filters().decisionTypes()).containsExactly("WRITE_OFF");
        assertThat(result.filters().executionStatuses()).containsExactly("FAILED");
        assertThat(result.filters().blockerTypes()).containsExactly("UNFINISHED_OUTPUT");
        assertThat(result.filters().integrationStatuses()).containsExactly("RETRY");
        assertThat(result.filters().exclusionResults()).containsExactly("INCLUDED");
        assertThat(result.filters().activeCaseOnly()).isTrue();
        assertThat(result.filters().minBookValue()).isEqualByComparingTo("100");
        verify(repository).save(any(NonMovingStockSavedView.class));
    }

    @Test
    void refusesToDeleteAViewOutsideTheCurrentOwnerScope() {
        UUID viewId = UUID.randomUUID();
        when(repository.findByIdAndTenantIdAndCompanyIdAndOwnerUsernameAndIsDeletedFalse(
            viewId, TenantContext.currentTenant(), CompanyContext.currentCompany(), "case.manager"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(viewId))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Saved view not found");
        verify(repository, never()).save(any());
    }

    private NonMovingStockSavedViewService service() {
        return new NonMovingStockSavedViewService(repository);
    }
}
