package com.enterprise.mm.entity;

import com.enterprise.mm.common.BaseLongEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class MXIK extends BaseLongEntity {

    public MXIK() {
    }

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "name_ru", columnDefinition = "text")
    private String nameRu;

    @Column(unique = true, nullable = false, length = 20)
    private String kod;

    @Column(nullable = false)
    private String type;

    @Column(name = "group_name", columnDefinition = "text")
    private String groupName;
    @Column(name = "group_name_ru", columnDefinition = "text")
    private String groupNameRu;
    @Column(name = "group_name_cyril", columnDefinition = "text")
    private String groupNameCyril;

    @Column(name = "class_name", columnDefinition = "text")
    private String className;
    @Column(name = "class_name_ru", columnDefinition = "text")
    private String classNameRu;
    @Column(name = "class_name_cyril", columnDefinition = "text")
    private String classNameCyril;

    @Column(name = "position_name", columnDefinition = "text")
    private String positionName;
    @Column(name = "position_name_ru", columnDefinition = "text")
    private String positionNameRu;
    @Column(name = "position_name_cyril", columnDefinition = "text")
    private String positionNameCyril;

    @Column(name = "sub_position_name", columnDefinition = "text")
    private String subPositionName;
    @Column(name = "sub_position_name_ru", columnDefinition = "text")
    private String subPositionNameRu;
    @Column(name = "sub_position_name_cyril", columnDefinition = "text")
    private String subPositionNameCyril;

    @Column(name = "brand_name", columnDefinition = "text")
    private String brandName;
    @Column(name = "brand_name_ru", columnDefinition = "text")
    private String brandNameRu;
    @Column(name = "brand_name_cyril", columnDefinition = "text")
    private String brandNameCyril;

    @Column(name = "attribute_name", columnDefinition = "text")
    private String attributeName;
    @Column(name = "attribute_name_ru", columnDefinition = "text")
    private String attributeNameRu;
    @Column(name = "attribute_name_cyril", columnDefinition = "text")
    private String attributeNameCyril;
    private String barcode;

}
