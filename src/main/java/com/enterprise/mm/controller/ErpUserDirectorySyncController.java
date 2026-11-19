package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.UserSyncAckResponse;
import com.enterprise.mm.payload.UserSyncSnapshotRequest;
import com.enterprise.mm.service.ErpUserDirectorySyncService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ErpUserDirectorySyncController {

    private final ErpUserDirectorySyncService service;

    @PostMapping("/api/auth/erp-users/sync")
    public ApiResponse<UserSyncAckResponse> syncUsers(@RequestBody UserSyncSnapshotRequest snapshot, HttpServletRequest request) throws IOException {
        return ApiResponse.ok(service.syncUsers(snapshot, request));
    }

    @GetMapping("/api/auth/erp-users/sync")
    public ApiResponse<UserSyncAckResponse> syncState(HttpServletRequest request) {
        return ApiResponse.ok(service.syncState(request));
    }
}
