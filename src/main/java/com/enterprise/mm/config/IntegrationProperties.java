package com.enterprise.mm.config;

import com.enterprise.mm.enums.ExternalIntegrationSystem;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.integrations")
public class IntegrationProperties {
    private String webhookUrl = "";
    private String settingsSecret = "change-this-integration-settings-secret";
    private MessageQueue messageQueue = new MessageQueue();
    private Map<String, SystemEndpoint> systems = new LinkedHashMap<>();

    public SystemEndpoint endpoint(ExternalIntegrationSystem system) {
        String key = system.name().toLowerCase(Locale.ROOT).replace('_', '-');
        SystemEndpoint endpoint = systems.get(key);
        if (endpoint == null) {
            endpoint = systems.get(system.name());
        }
        if (endpoint == null) {
            endpoint = new SystemEndpoint();
        }
        return endpoint;
    }

    @Getter
    @Setter
    public static class SystemEndpoint {
        private boolean enabled = true;
        private boolean mockMode = true;
        private String baseUrl = "";
        private String authType = "NONE";
        private String username = "";
        private String password = "";
        private String bearerToken = "";
        private String apiKeyHeader = "Authorization";
        private String apiKey = "";
        private String clientId = "";
        private String clientSecret = "";
        private String authPath = "";
        private String healthPath = "/health";
        private String eventsPath = "/events";

    }

    @Getter
    @Setter
    public static class MessageQueue {
        private boolean enabled = false;
        private String exchange = "mm.integration.events";
        private String routingKey = "mm.integration.event";
    }
}
