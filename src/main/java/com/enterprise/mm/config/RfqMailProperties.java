package com.enterprise.mm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "mm.rfq-mail")
public class RfqMailProperties {
    private boolean enabled = false;
    private String from;
    private String replyTo;
    private String portalBaseUrl;
    private String magicLinkPepper;
    private String callbackSecret;
    private int maxAutomaticRetries = 5;
    private int batchSize = 20;
    private long pollDelayMs = 30000L;
}
