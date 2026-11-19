package com.enterprise.mm.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.integrations.message-queue", name = "enabled", havingValue = "true")
public class IntegrationQueueConfig {
    public static final String QUEUE_NAME = "mm.integration.events";

    @Bean
    DirectExchange integrationExchange(IntegrationProperties properties) {
        return new DirectExchange(properties.getMessageQueue().getExchange(), true, false);
    }

    @Bean
    Queue integrationQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    Binding integrationBinding(Queue integrationQueue, DirectExchange integrationExchange, IntegrationProperties properties) {
        return BindingBuilder.bind(integrationQueue)
            .to(integrationExchange)
            .with(properties.getMessageQueue().getRoutingKey());
    }
}
