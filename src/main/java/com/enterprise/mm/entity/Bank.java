package com.enterprise.mm.entity;

import com.enterprise.mm.util.BusinessNameGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Справочник банков (глобальные reference-данные, без tenant/audit).
 * Заполняется миграцией V2__bank_reference.sql.
 */
@Getter
@Setter
@Entity
@Table(name = "banks")
public class Bank {
    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(length = 10)
    private String mfo;

    @Column(length = 20)
    private String swift;

    @Column(length = 40)
    private String bankType;

    @Column(length = 120)
    private String city;

    @Column(nullable = false)
    private Integer sortOrder;

    @PrePersist
    @PreUpdate
    void normalizeName() {
        name = BusinessNameGenerator.normalize(name, "Bank");
    }
}
