package com.gaurav.ecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * E-Commerce Order Event Pipeline
 *
 * Demonstrates a production-grade Kafka event streaming architecture:
 *
 * 1. ORDER PRODUCER  — Simulates real orders; custom partitioner routes
 *                      PREMIUM customers to dedicated Kafka partition
 * 2. ORDER CONSUMER  — Processes events with retry (3 attempts, exponential
 *                      backoff) and Dead Letter Queue for failed messages
 * 3. MONGODB STORAGE — Time-series collection with TTL index for analytics
 * 4. REST API        — Query orders by status, customer, date range, region
 * 5. SSE DASHBOARD   — Live event stream pushed to browser via Server-Sent Events
 *
 * @author Gaurav Aggarwal | github.com/aggaurav1221
 */
@SpringBootApplication
@EnableKafka
@EnableAsync
@EnableScheduling
@EnableMongoAuditing
public class EcommerceOrderPipelineApplication {
    public static void main(String[] args) {
        SpringApplication.run(EcommerceOrderPipelineApplication.class, args);
    }
}
