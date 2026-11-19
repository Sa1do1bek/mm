package com.enterprise.mm.service.rfq;

import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.payload.RfqMailCallbackDtos.RfqMailEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RfqMailCallbackVerifier {
    private final RfqMailProperties properties;
    private final ObjectMapper objectMapper;



    public void requireValid(String suppliedSignature, RfqMailEvent event) {
        String secret = properties.getCallbackSecret();
        if (secret == null || secret.isBlank()) {
            throw new AccessDeniedException("RFQ mail callback secret is not configured");
        }
        String expected = signature(secret, event);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] suppliedBytes = suppliedSignature == null
            ? new byte[0]
            : suppliedSignature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expectedBytes, suppliedBytes)) {
            throw new AccessDeniedException("Invalid RFQ mail callback signature");
        }
    }

    private String signature(String secret, RfqMailEvent event) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(objectMapper.writeValueAsBytes(event)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot verify RFQ mail callback signature", exception);
        }
    }
}
