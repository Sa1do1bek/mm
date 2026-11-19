package com.enterprise.mm.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.attachments")
public class AttachmentProperties {
    private String storageProvider = "LOCAL";
    private String storageRoot = "./data/attachments";
    private String s3Endpoint;
    private String s3Region = "us-east-1";
    private String s3Bucket;
    private String s3AccessKey;
    private String s3SecretKey;
    private boolean s3PathStyle = true;
    private int s3RequestTimeoutSeconds = 30;
    private long maxSizeBytes = 20L * 1024L * 1024L;
    private List<String> allowedContentTypes = List.of(
        "image/jpeg",
        "image/png",
        "image/webp",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "text/markdown",
        "application/json"
    );
}
