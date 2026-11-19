package com.enterprise.mm.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.service.FinishedGoodsOpeningMigrationService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FinishedGoodsOpeningControllerTest {
    private final FinishedGoodsOpeningMigrationService service = mock(FinishedGoodsOpeningMigrationService.class);
    private final FinishedGoodsOpeningController controller = new FinishedGoodsOpeningController(service);
    private final UUID warehouse = UUID.randomUUID();

    @AfterEach
    void clear() { CompanyContext.clear(); }

    @Test
    void usesOnlyTheAuthorizedCompanyAndSnapshot() {
        CompanyContext.setCompany(UUID.fromString("753e618e-8d47-4efd-a41e-df8311b83d2f"));
        controller.preview(warehouse);
        verify(service).preview(LocalDate.of(2026, 10, 8), warehouse);
    }

    @Test
    void rejectsAnotherCompany() {
        CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> controller.preview(warehouse)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void appliesOnlyTheAuthorizedSnapshot() {
        CompanyContext.setCompany(UUID.fromString("753e618e-8d47-4efd-a41e-df8311b83d2f"));
        var request = new FinishedGoodsOpeningController.ApplyRequest(warehouse, "0".repeat(64), "key", true);
        controller.apply(request);
        verify(service).apply(LocalDate.of(2026, 10, 8), warehouse, "0".repeat(64), "key");
    }

    @Test
    void cannotPostWithoutExplicitlyAcceptingUnavailableLaterMovements() {
        CompanyContext.setCompany(UUID.fromString("753e618e-8d47-4efd-a41e-df8311b83d2f"));
        var request = new FinishedGoodsOpeningController.ApplyRequest(warehouse, "0".repeat(64), "key", false);
        assertThatThrownBy(() -> controller.apply(request))
            .isInstanceOf(ConflictException.class).hasMessageContaining("acceptance");
    }
}
