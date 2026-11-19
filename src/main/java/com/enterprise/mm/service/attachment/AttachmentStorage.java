package com.enterprise.mm.service.attachment;

import org.springframework.core.io.InputStreamResource;
import org.springframework.web.multipart.MultipartFile;

public interface AttachmentStorage {
    boolean supports(String provider);

    StoredFile store(MultipartFile file, String storageKey, String contentType);

    StoredFile store(byte[] content, String storageKey, String contentType);

    InputStreamResource read(String storageKey);

    default void delete(String storageKey) {
    }

    record StoredFile(long sizeBytes, String checksumSha256) {
    }
}

