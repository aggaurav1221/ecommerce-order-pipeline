package com.gaurav.ecommerce.event.producer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;

import java.util.List;
import java.util.Map;

/**
 * Custom Kafka Partitioner — Priority Lane for PREMIUM Customers.
 *
 * Strategy:
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  Partition 0  →  PREMIUM customers (dedicated lane)     │
 *   │  Partitions 1-N → STANDARD/BASIC (hash of customerId)  │
 *   └─────────────────────────────────────────────────────────┘
 *
 * Why this matters in production:
 * - PREMIUM customers represent 20% of users but 60%+ of revenue
 * - Dedicated partition = no head-of-line blocking from bulk orders
 * - Consumer group for partition 0 can be scaled independently
 * - This is the same pattern used in telecom (VIP CDR processing)
 *   and fintech (high-value transaction priority lanes)
 *
 * The message key format: "{customerTier}:{customerId}"
 * e.g. "PREMIUM:CUST-001" or "STANDARD:CUST-999"
 */
@Slf4j
public class OrderPartitioner implements Partitioner {

    private static final String PREMIUM_TIER  = "PREMIUM";
    private static final int    PREMIUM_PARTITION = 0;

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {

        List<PartitionInfo> partitions     = cluster.partitionsForTopic(topic);
        int                 totalPartitions = partitions.size();

        if (key == null || totalPartitions <= 1) {
            return 0;
        }

        String keyStr = key.toString();

        // Key format: "PREMIUM:CUST-001"
        if (keyStr.startsWith(PREMIUM_TIER + ":")) {
            log.debug("PREMIUM customer routed to partition {}: key={}", PREMIUM_PARTITION, keyStr);
            return PREMIUM_PARTITION;
        }

        // Standard/Basic: consistent hash across remaining partitions (1 to N-1)
        String customerId   = keyStr.contains(":") ? keyStr.split(":")[1] : keyStr;
        int    hashCode     = Math.abs(customerId.hashCode());
        int    partition    = (hashCode % (totalPartitions - 1)) + 1;

        log.debug("STANDARD customer routed to partition {}: key={}", partition, keyStr);
        return partition;
    }

    @Override public void close() {}

    @Override public void configure(Map<String, ?> configs) {}
}
