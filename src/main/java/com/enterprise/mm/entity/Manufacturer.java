package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "manufacturers")
public class Manufacturer extends BusinessEntity {
    @Column(length = 120)
    private String country;

    @Column(name = "country_code", length = 3)
    private String countryCode;

    @Column(length = 240)
    private String website;

    @Column(length = 160)
    private String email;

    @Column(length = 80)
    private String phone;
}
