package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.config.IntegrationProperties;
import java.util.Objects;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IntegrationMessageQueuePublisher {
    private final com.enterprise.mm.security.pilot.PilotCapabilityPolicy pilotCapabilityPolicy;
    private final RabbitTemplate rabbitTemplate;
    private final IntegrationProperties properties;

    public boolean enabled() {
        return properties.getMessageQueue().isEnabled();
    }
    public void publish(IntegrationEvent event, String payload) {
        pilotCapabilityPolicy.requireIntegration(event);
        if (!enabled()) {
            return;
        }
        try {
            Message message = MessageBuilder.withBody(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(event.getId().toString())
                .setHeader("eventType", event.getEventType())
                .setHeader("aggregateType", event.getAggregateType())
                .setHeader("aggregateId", Objects.toString(event.getAggregateId(), ""))
                .setHeader("idempotencyKey", event.getIdempotencyKey() == null ? event.getId().toString() : event.getIdempotencyKey())
                .build();
            rabbitTemplate.send(properties.getMessageQueue().getExchange(), properties.getMessageQueue().getRoutingKey(), message);
        } catch (AmqpException ex) {
            throw ex;
        }
    }
}

