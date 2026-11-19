package com.enterprise.mm.service.rfq;

public interface RfqEmailTransport {
    SendResult send(RfqOutboundEmail email);

    record SendResult(String providerMessageId) {
    }
}
