package com.gaurav.ecommerce.event.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaurav.ecommerce.dto.OrderEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka Order Event Producer.
 *
 * Key design decisions:
 * 1. Message key = "{customerTier}:{customerId}" — drives OrderPartitioner routing
 * 2. Async send with callback — never blocks the calling thread
 * 3. Idempotent producer (enable.idempotence=true) — safe to retry on failure
 * 4. Prometheus counters for published / failed events (visible in Grafana)
 */
@Slf4j
@Component
public class OrderEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper                  objectMapper;
    private final String                        ordersTopic;
    private final Counter                       publishedCounter;
    private final Counter                       failedCounter;

    public OrderEventProducer(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              @Value("${kafka.topics.orders-placed:orders.placed}") String ordersTopic,
                              MeterRegistry meterRegistry) {
        this.kafkaTemplate    = kafkaTemplate;
        this.objectMapper     = objectMapper;
        this.ordersTopic      = ordersTopic;
        this.publishedCounter = Counter.builder("kafka.orders.published")
                .description("Total order events published to Kafka")
                .register(meterRegistry);
        this.failedCounter    = Counter.builder("kafka.orders.failed")
                .description("Total order events that failed to publish")
                .register(meterRegistry);
    }

    /**
     * Publish an order event to Kafka.
     * Key = "{customerTier}:{customerId}" — routes PREMIUM to partition 0.
     */
    public CompletableFuture<SendResult<String, String>> publishOrder(OrderEvent event) {
        String key = buildPartitionKey(event);

        try {
            String payload = objectMapper.writeValueAsString(event);

            return kafkaTemplate.send(ordersTopic, key, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            failedCounter.increment();
                            log.error("Failed to publish order: orderId={}, key={}, error={}",
                                    event.getOrderId(), key, ex.getMessage());
                        } else {
                            publishedCounter.increment();
                            log.info("Order published: orderId={}, tier={}, partition={}, offset={}",
                                    event.getOrderId(),
                                    event.getCustomerTier(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });

        } catch (JsonProcessingException e) {
            failedCounter.increment();
            log.error("Failed to serialize order event: orderId={}", event.getOrderId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Partition key: "PREMIUM:CUST-001" or "STANDARD:CUST-999"
     * OrderPartitioner uses the prefix to route to the correct partition.
     */
    private String buildPartitionKey(OrderEvent event) {
        return event.getCustomerTier() + ":" + event.getCustomerId();
    }
}
