package com.enterprise.mm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Locale;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(
    name = "enum_translations",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_enum_translations_enum_code_locale",
        columnNames = {"enum_name", "code", "locale"}
    )
)
public class EnumTranslation {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "enum_name", nullable = false, length = 120)
    private String enumName;

    @Column(nullable = false, length = 120)
    private String code;

    @Column(nullable = false, length = 8)
    private String locale;

    @Column(nullable = false, length = 240)
    private String label;

    public Locale getLocale() {
        return Locale.forLanguageTag(locale);
    }

    public void setLocale(Locale locale) {
        this.locale = locale == null ? null : locale.toLanguageTag();
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
}
