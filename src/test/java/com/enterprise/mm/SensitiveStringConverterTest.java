package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.security.SensitiveStringConverter;
import org.junit.jupiter.api.Test;

class SensitiveStringConverterTest {
    private final SensitiveStringConverter converter = new SensitiveStringConverter();

    @Test
    void encryptedValueRoundTrips() {
        String stored = converter.convertToDatabaseColumn("860012345");

        assertThat(stored).startsWith("sec:v1:");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("860012345");
    }

    @Test
    void unreadableEncryptedValueIsMasked() {
        assertThat(converter.convertToEntityAttribute("sec:v1:not-valid-base64")).isNull();
    }
}
