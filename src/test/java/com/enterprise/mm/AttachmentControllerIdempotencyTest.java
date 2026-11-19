package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.controller.AttachmentController;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.service.AttachmentService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.multipart.MultipartFile;

class AttachmentControllerIdempotencyTest {
    @Test
    void uploadForwardsTheOptionalIdempotencyHeaderToTheService() throws Exception {
        AttachmentService service = mock(AttachmentService.class);
        AttachmentController controller = new AttachmentController(service);
        UUID entityId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
            "file", "evidence.pdf", "application/pdf", "evidence".getBytes(StandardCharsets.UTF_8)
        );
        AttachmentDto stored = new AttachmentDto();
        stored.setId(UUID.randomUUID());
        when(service.upload("MATERIAL", entityId, "DOCUMENT", file, "upload-key")).thenReturn(stored);

        var response = controller.upload("MATERIAL", entityId, "DOCUMENT", file, "upload-key");

        assertThat(response.data()).isSameAs(stored);
        verify(service).upload("MATERIAL", entityId, "DOCUMENT", file, "upload-key");

        var upload = AttachmentController.class.getDeclaredMethod(
            "upload", String.class, UUID.class, String.class, MultipartFile.class, String.class
        );
        RequestHeader header = upload.getParameters()[4].getAnnotation(RequestHeader.class);
        assertThat(header).isNotNull();
        assertThat(header.value()).isEqualTo("Idempotency-Key");
        assertThat(header.required()).isFalse();
    }
}
