package com.enterprise.mm.payload;

public record UserSyncRoleRequest(String name, String description, String moduleCode, boolean systemRole) {
}
