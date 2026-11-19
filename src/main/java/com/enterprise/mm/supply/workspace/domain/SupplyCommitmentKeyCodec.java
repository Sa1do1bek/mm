package com.enterprise.mm.supply.workspace.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class SupplyCommitmentKeyCodec {
    public String encode(SupplyCommitmentKey key) {
        if (key == null) {
            throw new IllegalArgumentException("Supply commitment key is required");
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.canonical().getBytes(StandardCharsets.UTF_8));
    }

    public SupplyCommitmentKey decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.indexOf('=') >= 0
            || !encoded.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid encoded supply commitment key");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String canonical = strictUtf8(bytes);
            SupplyCommitmentKey key = SupplyCommitmentKey.parse(canonical);
            if (!encode(key).equals(encoded)) {
                throw new IllegalArgumentException("Non-canonical encoded supply commitment key");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 supply commitment key", exception);
        }
    }

    private String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }
}
