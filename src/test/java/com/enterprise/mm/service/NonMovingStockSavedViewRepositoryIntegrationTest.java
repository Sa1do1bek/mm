package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewFilter;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewRequest;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NonMovingStockSavedViewRepositoryIntegrationTest {
    @Autowired NonMovingStockSavedViewService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void savedViewsStayPrivateToAuthenticatedOwnerAndCompany() {
        UUID companyA = UUID.randomUUID();
        TenantContext.setTenant("tenant-saved-view-runtime");
        CompanyContext.setCompany(companyA);
        authenticate("manager.a");
        var saved = service.save(new SavedViewRequest(
            "Overdue write-offs", WorkspaceMode.CASES,
            new SavedViewFilter(
                null, List.of("UNDER_REVIEW"), null, null, List.of("WRITE_OFF"), true,
                null, null, null, null, null, List.of(), List.of(), List.of(), List.of(), false
            ),
            true
        ));

        assertThat(service.list(WorkspaceMode.CASES)).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(saved.id());
            assertThat(view.defaultView()).isTrue();
        });

        authenticate("manager.b");
        assertThat(service.list(WorkspaceMode.CASES)).isEmpty();
        assertThatThrownBy(() -> service.delete(saved.id())).isInstanceOf(NotFoundException.class);

        authenticate("manager.a");
        CompanyContext.setCompany(UUID.randomUUID());
        assertThat(service.list(WorkspaceMode.CASES)).isEmpty();
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a")
        );
    }
}
