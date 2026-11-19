package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierPortalAccountResponse extends BaseRecordDto {
    @NotNull
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    @Email
    @Size(max = 240)
    private String loginEmail;
    @Size(max = 240)
    private String contactPerson;
    @Size(max = 50)
    private String portalRole;
    @Size(max = 80)
    private String phone;
    @JsonIgnore
    @Size(max = 64)
    private String inviteTokenHash;
    @Deprecated(forRemoval = false)
    @Size(max = 120)
    private String inviteToken;
    private LocalDateTime inviteTokenExpiresAt;
    private LocalDateTime invitedAt;
    private LocalDateTime activatedAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime lastSubmissionAt;
    @JsonIgnore
    @Size(max = 240)
    private String allowedIpRange;
    @JsonIgnore
    private boolean mfaRequired;
    private LocalDateTime termsAcceptedAt;
}
