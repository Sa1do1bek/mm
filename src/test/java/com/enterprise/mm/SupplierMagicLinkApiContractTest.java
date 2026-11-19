package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplierMagicLinkApiContractTest {

    @Test
    void publicApiExposesExchangeAndNeutralRenewalEndpoints() throws Exception {
        String controller = source("controller/SupplierPortalExternalController.java");
        String dto = source("payload/SupplierMagicLinkDtos.java");

        assertThat(controller).contains("@PostMapping(\"/magic-links/exchange\")");
        assertThat(controller).contains("@PostMapping(\"/magic-links/request\")");
        assertThat(controller).contains("magicLinkService.exchange(request.token())");
        assertThat(controller).contains("servletRequest.getRemoteAddr()");
        assertThat(dto).contains("record MagicLinkExchangeResponse(");
        assertThat(dto).contains("String returnPath");
        assertThat(dto).doesNotContain("tokenHash");
    }

    @Test
    void legacyLoginIsOnlyAOneTimeMagicLinkAdapter() throws Exception {
        String externalService = source("service/SupplierPortalExternalService.java");
        String accountService = source("service/SupplierPortalAccountService.java");
        String accountRepository = source("repository/SupplierPortalAccountRepository.java");
        String response = source("payload/SupplierPortalAccountResponse.java");

        assertThat(externalService).contains("magicLinkService.exchange(request.inviteToken())");
        assertThat(externalService).doesNotContain("consumeInvitationForSession(");
        assertThat(accountRepository).doesNotContain("consumeInvitationForSession(");
        assertThat(accountRepository).doesNotContain("consumeInvitationForActivation(");
        assertThat(accountService).doesNotContain("rawInviteToken");
        assertThat(response).contains("@Deprecated(forRemoval = false)");
    }

    private String source(String relativePath) throws Exception {
        return Files.readString(Path.of("src/main/java/com/enterprise/mm").resolve(relativePath));
    }
}
