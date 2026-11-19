package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.config.AttachmentProperties;
import com.lowagie.text.pdf.PdfReader;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ResourceFileSafetyService {
    private final boolean scanEnabled;
    private final String scanHost;
    private final int scanPort;
    private final AttachmentProperties attachments;

    public ResourceFileSafetyService(@Value("${app.resources.malware-scan-enabled:false}") boolean scanEnabled,
                                     @Value("${app.resources.clamav-host:}") String scanHost,
                                     @Value("${app.resources.clamav-port:3310}") int scanPort,
                                     AttachmentProperties attachments) {
        this.scanEnabled = scanEnabled;
        this.scanHost = scanHost;
        this.scanPort = scanPort;
        this.attachments = attachments;
    }

    public void requireSafe(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Resource file is required");
        if (file.getSize() > Math.min(attachments.getMaxSizeBytes(), 20L * 1024 * 1024))
            throw new BadRequestException("Resource file exceeds size limit (20 MiB maximum)");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw new BadRequestException("Cannot read resource file"); }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        boolean valid = switch (type) {
            case "application/pdf" -> name.endsWith(".pdf") && starts(bytes, "%PDF-");
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                name.endsWith(".docx") && docx(bytes);
            case "text/plain" -> (name.endsWith(".txt") || name.endsWith(".md")) && plainText(bytes);
            case "text/markdown" -> name.endsWith(".md") && plainText(bytes);
            default -> false;
        };
        if (!valid) throw new BadRequestException("Resource file type or content is invalid");
        if ("application/pdf".equals(type)) requirePdfLimit(bytes);
        if (type.startsWith("text/")) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.codePointCount(0, text.length()) > 60_000)
                throw new BadRequestException("Resource text exceeds AI limit (60,000 characters)");
        }
        if (scanEnabled) scan(bytes);
    }

    private void requirePdfLimit(byte[] bytes) {
        try (PdfReader pdf = new PdfReader(bytes)) {
            if (pdf.isEncrypted()) throw new BadRequestException("Encrypted resource PDFs are unsupported");
            if (pdf.getNumberOfPages() > 30) throw new BadRequestException("Resource PDF exceeds AI limit (30 pages)");
        } catch (IOException ex) {
            throw new BadRequestException("Resource PDF cannot be read");
        }
    }

    private boolean starts(byte[] bytes, String prefix) {
        byte[] magic = prefix.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i]) return false;
        return true;
    }

    private boolean plainText(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException ex) { return false; }
    }

    private boolean docx(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') return false;
        boolean contentTypes = false, document = false;
        int entries = 0;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10_000 || entry.getName().contains("..") || entry.getName().startsWith("/")) return false;
                if (entry.getName().equals("[Content_Types].xml")) contentTypes = true;
                if (entry.getName().equals("word/document.xml")) document = true;
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) return false;
                int read;
                while ((read = zip.read(buffer)) != -1) if ((expanded += read) > 100_000_000) return false;
            }
        } catch (IOException ex) { return false; }
        return contentTypes && document;
    }

    private void scan(byte[] bytes) {
        if (scanHost == null || scanHost.isBlank()) throw new ScanUnavailableException("Resource malware scanner is not configured");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(scanHost, scanPort), 5000);
            socket.setSoTimeout(30000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < bytes.length; offset += 8192) {
                int length = Math.min(8192, bytes.length - offset);
                out.writeInt(length);
                out.write(bytes, offset, length);
            }
            out.writeInt(0);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] reply = new byte[4096];
            int count = 0, next;
            while (count < reply.length && (next = in.read()) >= 0 && next != 0 && next != '\n')
                reply[count++] = (byte) next;
            String result = new String(reply, 0, count, StandardCharsets.UTF_8);
            if (result.endsWith(" FOUND")) throw new BadRequestException("Resource file failed malware scan");
            if (!result.endsWith(": OK")) throw new ScanUnavailableException("Resource malware scan did not complete");
        } catch (IOException ex) {
            throw new ScanUnavailableException("Resource malware scanner is unavailable", ex);
        }
    }

    public static class ScanUnavailableException extends RuntimeException {
        public ScanUnavailableException(String message) { super(message); }
        public ScanUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
