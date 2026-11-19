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
public class SupplierPortalAccountCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @JsonIgnore
    @Size(max = 50)
    private String status;
    private String description;
    @NotNull
    private UUID supplierId;
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
    @JsonIgnore
    @Size(max = 120)
    private String inviteToken;
    @JsonIgnore
    private LocalDateTime invitedAt;
    @JsonIgnore
    private LocalDateTime activatedAt;
    @JsonIgnore
    private LocalDateTime lastLoginAt;
    @JsonIgnore
    private LocalDateTime lastSubmissionAt;
    @JsonIgnore
    @Size(max = 240)
    private String allowedIpRange;
    @JsonIgnore
    private boolean mfaRequired;
    @JsonIgnore
    private LocalDateTime termsAcceptedAt;
}
