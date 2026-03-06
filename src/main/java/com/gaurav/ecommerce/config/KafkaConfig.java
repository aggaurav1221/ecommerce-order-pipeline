package com.gaurav.ecommerce.config;

import com.gaurav.ecommerce.event.producer.OrderPartitioner;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration:
 *
 * Producer:
 *   - Custom OrderPartitioner (PREMIUM → partition 0)
 *   - Idempotent (enable.idempotence=true, acks=all)
 *   - LZ4 compression for throughput
 *
 * Consumer:
 *   - Manual acknowledgment (MANUAL_IMMEDIATE)
 *   - 3 concurrent threads per listener
 *   - Integrates with @RetryableTopic for non-blocking retry
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ── Producer ──────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,       bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,    StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,  StringSerializer.class);
        // Custom partitioner — routes PREMIUM customers to partition 0
        config.put(ProducerConfig.PARTITIONER_CLASS_CONFIG,       OrderPartitioner.class);
        // Idempotent producer — safe retries, no duplicate messages
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,      true);
        config.put(ProducerConfig.ACKS_CONFIG,                    "all");
        config.put(ProducerConfig.RETRIES_CONFIG,                 3);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        // Throughput tuning
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,        "lz4");
        config.put(ProducerConfig.BATCH_SIZE_CONFIG,              16384);
        config.put(ProducerConfig.LINGER_MS_CONFIG,               5);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Consumer ──────────────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG,           "order-processor-group");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Manual offset commit — only ack after successful processing
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Fetch tuning
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,   50);
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,    1024);
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,  500);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        // 3 concurrent consumer threads per listener
        factory.setConcurrency(3);
        // Manual acknowledgment mode
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
