package com.enterprise.mm.service;

import com.enterprise.mm.payload.UserSyncAckResponse;
import com.enterprise.mm.payload.UserSyncSnapshotRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ErpUserDirectorySyncService {

    private final ObjectMapper objectMapper;
    private final String moduleCode;
    private final String syncToken;
    private final Path syncStore;

    public ErpUserDirectorySyncService(
        ObjectMapper objectMapper,
        @Value("${erp.auth.module-code:MM}") String moduleCode,
        @Value("${ERP_USER_SYNC_TOKEN:}") String syncToken,
        @Value("${erp.auth.sync-store:var/erp-user-sync/latest-users.json}") String syncStore
    ) {
        this.objectMapper = objectMapper;
        this.moduleCode = moduleCode;
        this.syncToken = syncToken;
        this.syncStore = Path.of(syncStore);
    }

    public UserSyncAckResponse syncUsers(UserSyncSnapshotRequest snapshot, HttpServletRequest request) throws IOException {
        assertAuthorized(request);
        validateSnapshot(snapshot);
        storeSnapshot(snapshot);
        return new UserSyncAckResponse(true, moduleCode, Instant.now(), size(snapshot.users()), size(snapshot.roles()));
    }

    public UserSyncAckResponse syncState(HttpServletRequest request) {
        assertAuthorized(request);
        return new UserSyncAckResponse(Files.exists(syncStore), moduleCode, Instant.now(), 0, 0);
    }

    private void validateSnapshot(UserSyncSnapshotRequest snapshot) {
        if (snapshot == null || !StringUtils.hasText(snapshot.moduleCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "moduleCode is required");
        }
        if (!moduleCode.equalsIgnoreCase(snapshot.moduleCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot moduleCode does not match this module");
        }
    }

    private void storeSnapshot(UserSyncSnapshotRequest snapshot) throws IOException {
        Path parent = syncStore.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = syncStore.resolveSibling(syncStore.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), snapshot);
        Files.move(tempFile, syncStore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void assertAuthorized(HttpServletRequest request) {
        if (StringUtils.hasText(syncToken)) {
            String authorization = request.getHeader("Authorization");
            String headerToken = request.getHeader("X-ERP-User-Sync-Token");
            String apiKey = request.getHeader("X-API-Key");
            if (("Bearer " + syncToken).equals(authorization) || syncToken.equals(headerToken) || syncToken.equals(apiKey)) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid ERP user sync token");
        }
        String remoteAddress = request.getRemoteAddr();
        if ("127.0.0.1".equals(remoteAddress) || "0:0:0:0:0:0:0:1".equals(remoteAddress) || "::1".equals(remoteAddress)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ERP user sync token is required");
    }

    private int size(List<?> items) {
        return items == null ? 0 : items.size();
    }
}
