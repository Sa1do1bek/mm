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
@Table(name = "classifier_types")
public class ClassifierType extends BusinessEntity {
    @Column(length = 120)
    private String module;

    private boolean systemType;
}
