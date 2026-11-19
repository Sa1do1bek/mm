package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.CompanyScope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class AttachmentCurrentCompanyScopeIntegrationTest {
    @Autowired AttachmentService attachmentService;

    @AfterEach
    void tearDown() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void globalAdminListAndDownloadBecomeCurrentCompanyScopedOnlyWhenExplicitlyRequested() throws Exception {
        UUID currentCompany = UUID.randomUUID();
        UUID otherCompany = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        TenantContext.setTenant("tenant-attachment-admin-scope");
        authenticateGlobalAdmin();

        CompanyContext.setCompany(currentCompany);
        var currentAttachment = upload(materialId, "current.pdf", "current company");
        CompanyContext.setCompany(otherCompany);
        var otherAttachment = upload(materialId, "other.pdf", "other company");
        CompanyContext.setCompany(currentCompany);

        assertThat(attachmentService.list("MATERIAL", materialId))
            .extracting("id")
            .containsExactlyInAnyOrder(currentAttachment.getId(), otherAttachment.getId());
        assertThat(read(attachmentService.download(otherAttachment.getId())))
            .isEqualTo("other company");

        AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);

        assertThat(attachmentService.list("MATERIAL", materialId))
            .extracting("id")
            .containsExactly(currentAttachment.getId());
        assertThat(read(attachmentService.download(currentAttachment.getId())))
            .isEqualTo("current company");
        assertThatThrownBy(() -> attachmentService.download(otherAttachment.getId()))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Attachment not found");
    }

    private com.enterprise.mm.payload.AttachmentDto upload(
        UUID materialId,
        String fileName,
        String content
    ) {
        return attachmentService.upload(
            "MATERIAL",
            materialId,
            "DOCUMENT",
            new MockMultipartFile(
                "file",
                fileName,
                "application/pdf",
                content.getBytes(StandardCharsets.UTF_8)
            )
        );
    }

    private String read(AttachmentService.AttachmentDownload download) throws Exception {
        return new String(download.resource().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void authenticateGlobalAdmin() {
        var authentication = new UsernamePasswordAuthenticationToken(
            "global-admin",
            null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
