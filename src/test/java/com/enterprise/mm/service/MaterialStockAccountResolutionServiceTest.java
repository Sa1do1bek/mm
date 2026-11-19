package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.ChartOfAccountRepository;
import com.enterprise.mm.repository.MaterialRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MaterialStockAccountResolutionServiceTest {
    private final MaterialStockAccountResolutionService service = new MaterialStockAccountResolutionService(
        mock(NamedParameterJdbcTemplate.class), mock(MaterialRepository.class),
        mock(StockAccountResolver.class), mock(ChartOfAccountRepository.class),
        mock(RoleDataScopeService.class));

    @Test
    void multipleImportedAccountsRequireAReceiptSpecificCandidate() {
        var first = new MaterialStockAccountResolutionService.Account(UUID.randomUUID(), "1010", "First");
        var second = new MaterialStockAccountResolutionService.Account(UUID.randomUUID(), "1030", "Second");
        var resolution = new MaterialStockAccountResolutionService.Resolution(UUID.randomUUID(), 1L, true,
            "CHOICE_REQUIRED", first, null, null, List.of(first, second), null, "fingerprint", List.of());

        assertThatThrownBy(() -> service.requireReceiptAccount(resolution, null))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.requireReceiptAccount(resolution, UUID.randomUUID()))
            .isInstanceOf(BadRequestException.class);
        assertThat(service.requireReceiptAccount(resolution, second.id())).isEqualTo(second);
    }

    @Test
    void automaticAccountCannotBeOverriddenByReceiptInput() {
        var account = new MaterialStockAccountResolutionService.Account(UUID.randomUUID(), "1010", "Stock");
        var resolution = new MaterialStockAccountResolutionService.Resolution(UUID.randomUUID(), 1L, true,
            "AUTO_1C", null, account, "ONE_C_BALANCE", List.of(account), null, "fingerprint", List.of());

        assertThat(service.requireReceiptAccount(resolution, null)).isEqualTo(account);
        assertThatThrownBy(() -> service.requireReceiptAccount(resolution, UUID.randomUUID()))
            .isInstanceOf(BadRequestException.class);
    }
}
