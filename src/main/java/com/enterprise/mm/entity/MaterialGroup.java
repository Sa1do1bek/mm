package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_groups")
public class MaterialGroup extends BusinessEntity {
    @jakarta.persistence.Column(name="parent_id")
    private UUID parentId;
    @jakarta.persistence.Column(name = "additional_category", length = 160)
    private String additionalCategory;
    @jakarta.persistence.Column(name = "lower_category", length = 160)
    private String lowerCategory;
}
