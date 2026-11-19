package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "notifications")
public class Notification extends BusinessEntity {
    @Column(length = 120)
    private String recipientUsername;
    @Column(length = 300)
    private String title;
    @Column(columnDefinition = "text")
    private String message;
    @Column(name = "title_key", length = 200)
    private String titleKey;
    @Column(name = "message_key", length = 200)
    private String messageKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_args", columnDefinition = "jsonb")
    private String templateArgsJson;
    @Column(name = "rendered_language", length = 5)
    private String renderedLanguage;
    @Column(name = "entity_type", length = 80)
    private String entityType;
    @Column(name = "entity_id")
    private UUID entityId;
    @Column(name = "read_flag", nullable = false)
    private boolean read;
}
