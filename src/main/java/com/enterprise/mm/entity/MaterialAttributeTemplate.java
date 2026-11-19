package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_attribute_templates")
public class MaterialAttributeTemplate extends BusinessEntity {
    @Column(name = "material_group_id", nullable = false)
    private UUID materialGroupId;

    @Column(name = "attribute_name", nullable = false, length = 160)
    private String attributeName;

    @Column(name = "required_flag", nullable = false)
    private Boolean required = Boolean.FALSE;

    @Column(name = "data_type", nullable = false, length = 40)
    private String dataType;

    @Column(name = "allowed_values", columnDefinition = "text")
    private String allowedValues;
}
