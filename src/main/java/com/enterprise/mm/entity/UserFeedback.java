package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_feedback")
public class UserFeedback extends BusinessEntity {
    @Column(name = "comment_text", columnDefinition = "text")
    private String commentText;

    @Column(name = "screenshot_data_url", nullable = false, columnDefinition = "text")
    private String screenshotDataUrl;

    @Column(length = 1024)
    private String route;

    @Column(name = "client_created_at")
    private OffsetDateTime clientCreatedAt;

    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    @Column(name = "viewport_width")
    private Integer viewportWidth;

    @Column(name = "viewport_height")
    private Integer viewportHeight;

    @Column(name = "device_pixel_ratio", precision = 8, scale = 3)
    private BigDecimal devicePixelRatio;
}
