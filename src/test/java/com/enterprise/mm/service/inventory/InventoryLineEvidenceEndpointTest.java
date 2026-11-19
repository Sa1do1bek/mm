package com.enterprise.mm.service.inventory;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.controller.InventoryItemController;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.InventoryItemService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InventoryLineEvidenceEndpointTest {
    @Test void uploadBindsTheLineAndIdempotencyKeyToObservationEvidence() throws Exception {
        var attachments = mock(AttachmentService.class);
        var controller = new InventoryItemController(mock(InventoryItemService.class), mock(InventoryReviewService.class), attachments);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var lineId = UUID.randomUUID();
        var file = new MockMultipartFile("file", "shelf.png", "image/png", new byte[] {1, 2, 3});
        when(attachments.upload("INVENTORY_LINE_COUNT_EVIDENCE", lineId, "EVIDENCE", file, "count-photo-1")).thenReturn(new AttachmentDto());
        mvc.perform(multipart("/api/inventory-items/{id}/count-evidence", lineId).file(file).header("Idempotency-Key", "count-photo-1")).andExpect(status().isCreated());
        verify(attachments).upload("INVENTORY_LINE_COUNT_EVIDENCE", lineId, "EVIDENCE", file, "count-photo-1");
        mvc.perform(multipart("/api/inventory-items/{id}/count-evidence", lineId).file(file)).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(attachments);
    }
}
