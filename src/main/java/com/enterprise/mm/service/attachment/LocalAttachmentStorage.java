package com.enterprise.mm.service.attachment;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.config.AttachmentProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class LocalAttachmentStorage implements AttachmentStorage {
    private final AttachmentProperties properties;

    
    @Override
    public boolean supports(String provider) {
        return provider == null || "LOCAL".equals(provider.toUpperCase(Locale.ROOT));
    }

    @Override
    public StoredFile store(MultipartFile file, String storageKey, String contentType) {
        try {
            return store(file.getInputStream(), storageKey);
        } catch (Exception ex) {
            throw new BusinessException("Cannot store attachment file: " + ex.getMessage());
        }
    }

    @Override
    public StoredFile store(byte[] content, String storageKey, String contentType) {
        try {
            return store(new ByteArrayInputStream(content == null ? new byte[0] : content), storageKey);
        } catch (Exception ex) {
            throw new BusinessException("Cannot store attachment file: " + ex.getMessage());
        }
    }

    private StoredFile store(InputStream source, String storageKey) throws Exception {
        Path path = resolveStoragePath(storageKey);
        Files.createDirectories(path.getParent());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream input = new DigestInputStream(source, digest);
             OutputStream output = Files.newOutputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                size += read;
            }
        }
        return new StoredFile(size, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public InputStreamResource read(String storageKey) {
        try {
            Path path = resolveStoragePath(storageKey);
            if (!Files.exists(path)) {
                throw new NotFoundException("Attachment file not found");
            }
            return new InputStreamResource(Files.newInputStream(path));
        } catch (NotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot read attachment file");
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolveStoragePath(storageKey));
        } catch (Exception ex) {
            throw new BusinessException("Cannot delete attachment file");
        }
    }

    private Path resolveStoragePath(String storageKey) {
        Path root = storageRoot();
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new BadRequestException("Invalid storage key");
        }
        return resolved;
    }

    private Path storageRoot() {
        Path configured = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        if (isWritableDirectory(configured)) {
            return configured;
        }
        Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "mm-attachments").toAbsolutePath().normalize();
        if (isWritableDirectory(fallback)) {
            return fallback;
        }
        throw new BusinessException("Attachment storage root is not writable: " + configured);
    }

    private boolean isWritableDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return Files.isDirectory(path) && Files.isWritable(path);
        } catch (Exception ex) {
            return false;
        }
    }
}

