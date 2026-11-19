package com.enterprise.mm.service;

import com.enterprise.mm.config.AttachmentProperties;
import com.enterprise.mm.error.BadRequestException;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceFileSafetyServiceTest {
    private final AttachmentProperties properties = new AttachmentProperties();

    @Test
    void scannerMustBeConfiguredBeforePublication() {
        ResourceFileSafetyService service = new ResourceFileSafetyService(true, "", 3310, properties);
        assertThrows(ResourceFileSafetyService.ScanUnavailableException.class,
            () -> service.requireSafe(pdf()));
    }

    @Test
    void scannerCanBeDeferredWhileFileChecksRemainActive() {
        ResourceFileSafetyService service = new ResourceFileSafetyService(false, "", 3310, properties);
        assertDoesNotThrow(() -> service.requireSafe(pdf()));
    }

    @Test
    void signatureMismatchIsRejectedBeforeScanning() {
        ResourceFileSafetyService service = new ResourceFileSafetyService(false, "", 3310, properties);
        MockMultipartFile file = new MockMultipartFile("file", "order.pdf", "application/pdf", "not a PDF".getBytes(StandardCharsets.UTF_8));
        assertThrows(BadRequestException.class, () -> service.requireSafe(file));
    }

    @Test
    void cleanScannerReplyAllowsPublication() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> reply = CompletableFuture.runAsync(() -> respond(server, "stream: OK\0"));
            ResourceFileSafetyService service = new ResourceFileSafetyService(true, "127.0.0.1", server.getLocalPort(), properties);
            assertDoesNotThrow(() -> service.requireSafe(pdf()));
            reply.join();
        }
    }

    @Test
    void infectedScannerReplyRejectsPublication() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> reply = CompletableFuture.runAsync(() -> respond(server, "stream: Test.Virus FOUND\0"));
            ResourceFileSafetyService service = new ResourceFileSafetyService(true, "127.0.0.1", server.getLocalPort(), properties);
            assertThrows(BadRequestException.class, () -> service.requireSafe(pdf()));
            reply.join();
        }
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "order.pdf", "application/pdf", onePagePdf());
    }

    private byte[] onePagePdf() {
        String header = "%PDF-1.4\n";
        String catalog = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        String pages = "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n";
        String page = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n";
        int catalogOffset = header.length();
        int pagesOffset = catalogOffset + catalog.length();
        int pageOffset = pagesOffset + pages.length();
        int xrefOffset = pageOffset + page.length();
        String trailer = String.format("xref%n0 4%n0000000000 65535 f%n%010d 00000 n%n%010d 00000 n%n"
                + "%010d 00000 n%ntrailer%n<< /Size 4 /Root 1 0 R >>%nstartxref%n%d%n%%EOF%n",
            catalogOffset, pagesOffset, pageOffset, xrefOffset);
        return (header + catalog + pages + page + trailer).getBytes(StandardCharsets.US_ASCII);
    }

    private void respond(ServerSocket server, String response) {
        try (var socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            for (int i = 0; i < 10; i++) in.readByte();
            while (true) {
                int length = in.readInt();
                if (length == 0) break;
                in.readNBytes(length);
            }
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
}
