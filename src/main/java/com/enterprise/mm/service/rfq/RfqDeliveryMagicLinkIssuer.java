package com.enterprise.mm.service.rfq;

import com.enterprise.mm.entity.RfqInvitationDelivery;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.UUID;

@Component
public interface RfqDeliveryMagicLinkIssuer {
    IssuedLink issueForDelivery(RfqInvitationDelivery delivery);

    record IssuedLink(UUID id, String rawToken, URI url) {
    }
}
