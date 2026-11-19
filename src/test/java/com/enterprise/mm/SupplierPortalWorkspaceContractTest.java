package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplierPortalWorkspaceContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void supplierPortalTeamEndpointsScopeFromCurrentPortalSession() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/SupplierPortalExternalController.java"));
        String service = Files.readString(ROOT.resolve("service/SupplierPortalExternalService.java"));
        String publicDtos = Files.readString(ROOT.resolve("payload/SupplierPortalPublicDtos.java"));

        assertThat(controller).contains("@GetMapping(\"/team\")");
        assertThat(controller).contains("@PostMapping(\"/team\")");
        assertThat(controller).doesNotContain("/team/{supplierId}");
        assertThat(publicDtos).contains("SupplierPortalTeamInviteRequest");
        assertThat(publicDtos).doesNotContain("SupplierPortalTeamInviteRequest(\n        UUID supplierId");

        assertThat(service).contains("findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(");
        assertThat(service).contains("account.getSupplierId()");
        assertThat(service).contains("member.setSupplierId(account.getSupplierId())");
        assertThat(service).contains("Only supplier admins can manage portal team members");
        assertThat(service).contains("Supplier portal login email already belongs to another supplier");
    }
}
