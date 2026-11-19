package com.enterprise.mm.entity;

import com.enterprise.mm.common.BaseLongEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CountryCode extends BaseLongEntity {
    private String code;
    private String shortName;
    private String longName;
    private String alpha2;
    private String alpha3;
    @Column(columnDefinition = "boolean default false")
    private boolean deleted = false;
}