package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplyCommitmentKeyCodecTest {
    private static final UUID ID = UUID.fromString("2ad50d84-6cdd-4ec2-b2db-a440c45441a4");

    private final SupplyCommitmentKeyCodec codec = new SupplyCommitmentKeyCodec();

    @Test
    void roundTripsStrictCanonicalKeysWithUnpaddedBase64Url() {
        SupplyCommitmentKey key = new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, ID);

        String encoded = codec.encode(key);

        assertThat(key.canonical()).isEqualTo("SCHEDULE:" + ID);
        assertThat(encoded).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        assertThat(codec.decode(encoded)).isEqualTo(key);
        assertThat(codec.decode(codec.encode(new SupplyCommitmentKey(SupplyCommitmentKind.PO, ID))))
            .isEqualTo(new SupplyCommitmentKey(SupplyCommitmentKind.PO, ID));
    }

    @Test
    void rejectsPaddedMalformedAndNonCanonicalAliases() {
        String canonical = codec.encode(new SupplyCommitmentKey(SupplyCommitmentKind.SCHEDULE, ID));
        String wrongKind = encodeRaw("SHIPMENT:" + ID);
        String lowerKind = encodeRaw("schedule:" + ID);
        String upperUuid = encodeRaw("SCHEDULE:" + ID.toString().toUpperCase());
        String nonUuid = encodeRaw("PO:not-a-uuid");
        String malformedUtf8 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[] {(byte) 0xC3, 0x28});

        assertThatThrownBy(() -> codec.decode(canonical + "="))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("***"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(wrongKind))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(lowerKind))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(upperUuid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(nonUuid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(malformedUtf8))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private String encodeRaw(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
