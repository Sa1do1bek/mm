package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Secret-bearing input deliberately avoids record/Lombok-generated toString.
 */
public final class ChangePasswordRequest {
    @NotNull
    @Size(min = 1, max = 256)
    private final String oldPassword;

    @NotNull
    @Size(min = 1, max = 256)
    private final String newPassword;

    @NotNull
    @Size(min = 1, max = 256)
    private final String confirmNewPassword;

    @JsonCreator
    public ChangePasswordRequest(
        @JsonProperty("oldPassword") String oldPassword,
        @JsonProperty("newPassword") String newPassword,
        @JsonProperty("confirmNewPassword") String confirmNewPassword
    ) {
        this.oldPassword = oldPassword;
        this.newPassword = newPassword;
        this.confirmNewPassword = confirmNewPassword;
    }

    public String getOldPassword() {
        return oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public String getConfirmNewPassword() {
        return confirmNewPassword;
    }

    @Override
    public String toString() {
        return "ChangePasswordRequest[REDACTED]";
    }
}
