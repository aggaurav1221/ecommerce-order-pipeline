package com.gaurav.ecommerce.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaurav.ecommerce.dto.OrderEvent;
import com.gaurav.ecommerce.service.OrderPersistenceService;
import com.gaurav.ecommerce.service.SseEventBroadcaster;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Kafka Order Event Consumer with Non-Blocking Retry and Dead Letter Queue.
 *
 * Retry topology (Spring Kafka @RetryableTopic):
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  orders.placed                                                        │
 * │       ↓ (fail)                                                        │
 * │  orders.placed-retry-0   (attempt 2, delay 2s)                       │
 * │       ↓ (fail)                                                        │
 * │  orders.placed-retry-1   (attempt 3, delay 4s — exponential backoff) │
 * │       ↓ (fail)                                                        │
 * │  orders.placed-retry-2   (attempt 4, delay 8s)                       │
 * │       ↓ (still failing)                                               │
 * │  orders.placed-dlt       (Dead Letter Topic — manual inspection)     │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * NON-BLOCKING: Failed messages are moved to retry topics immediately.
 * The main consumer never stalls waiting for retries.
 *
 * DLT consumer: logs and persists failed events with processingStatus=DLQ
 * for ops team visibility and potential manual reprocessing.
 */
@Slf4j
@Component
public class OrderEventConsumer {

    private final OrderPersistenceService persistenceService;
    private final SseEventBroadcaster     sseBroadcaster;
    private final ObjectMapper            objectMapper;
    private final Counter                 processedCounter;
    private final Counter                 dlqCounter;

    public OrderEventConsumer(OrderPersistenceService persistenceService,
                              SseEventBroadcaster sseBroadcaster,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.persistenceService = persistenceService;
        this.sseBroadcaster     = sseBroadcaster;
        this.objectMapper       = objectMapper;
        this.processedCounter   = Counter.builder("kafka.orders.processed")
                .description("Total order events successfully processed")
                .register(meterRegistry);
        this.dlqCounter         = Counter.builder("kafka.orders.dlq")
                .description("Total order events sent to DLQ")
                .register(meterRegistry);
    }

    /**
     * Main consumer — processes orders from orders.placed topic.
     *
     * @RetryableTopic enables non-blocking retry with exponential backoff.
     * On exhaustion → orders.placed-dlt (Dead Letter Topic).
     */
    @RetryableTopic(
        attempts         = "4",                          // 1 original + 3 retries
        backoff          = @Backoff(delay = 2000,
                                    multiplier = 2.0,   // 2s → 4s → 8s
                                    maxDelay = 30000),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix   = "-dlt",
        include          = { Exception.class }
    )
    @KafkaListener(
        topics         = "${kafka.topics.orders-placed:orders.placed}",
        groupId        = "order-processor-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrder(ConsumerRecord<String, String> record,
                             @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                             @Header(KafkaHeaders.OFFSET)             long offset,
                             Acknowledgment acknowledgment) {

        log.info("Consuming order: partition={}, offset={}, key={}",
                partition, offset, record.key());

        try {
            OrderEvent event = objectMapper.readValue(record.value(), OrderEvent.class);

            // 1. Persist to MongoDB with Kafka metadata
            persistenceService.persistOrder(event, partition, offset,
                    record.topic(), "SUCCESS");

            // 2. Broadcast to SSE dashboard subscribers (live updates)
            sseBroadcaster.broadcast(event);

            processedCounter.increment();
            log.info("Order processed: orderId={}, tier={}, status={}, amount={} {}",
                    event.getOrderId(), event.getCustomerTier(),
                    event.getStatus(), event.getTotalAmount(), event.getCurrency());

            // Manual ack — only commit offset after successful processing
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.warn("Failed to process order at partition={}, offset={}: {}",
                    partition, offset, e.getMessage());
            // Re-throw → triggers @RetryableTopic retry mechanism
            throw new RuntimeException("Order processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Dead Letter Topic consumer.
     * Receives messages that failed all retry attempts.
     * Persists with processingStatus=DLQ for ops visibility.
     */
    @KafkaListener(
        topics  = "${kafka.topics.orders-placed:orders.placed}-dlt",
        groupId = "order-dlt-group"
    )
    public void consumeDeadLetter(ConsumerRecord<String, String> record,
                                  @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                  @Header(KafkaHeaders.OFFSET)             long offset) {

        log.error("DLQ message received: partition={}, offset={}, key={}",
                partition, offset, record.key());
        dlqCounter.increment();

        try {
            OrderEvent event = objectMapper.readValue(record.value(), OrderEvent.class);
            event.setRetryCount(4);
            event.setLastError("Exhausted all retry attempts — moved to DLQ");

            persistenceService.persistOrder(event, partition, offset,
                    record.topic(), "DLQ");

            log.error("DLQ order persisted for manual review: orderId={}", event.getOrderId());

        } catch (Exception e) {
            log.error("Failed to persist DLQ message — raw payload logged for ops: {}",
                    record.value().substring(0, Math.min(200, record.value().length())));
        }
    }
}
