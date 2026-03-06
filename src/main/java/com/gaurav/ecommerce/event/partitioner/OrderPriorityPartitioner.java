package com.gaurav.ecommerce.event.partitioner;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;

import java.util.List;
import java.util.Map;

/**
 * Custom Kafka Partitioner — routes orders by business priority.
 *
 * Partition strategy (8-partition topic):
 * ┌──────────────┬─────────────┬─────────────────────────────────────────┐
 * │ Priority     │ Partitions  │ Rationale                               │
 * ├──────────────┼─────────────┼─────────────────────────────────────────┤
 * │ FLASH_SALE   │ 6, 7        │ Dedicated — highest throughput consumers │
 * │ EXPRESS      │ 4, 5        │ Fast-lane — SLA < 2 hours               │
 * │ STANDARD     │ 0, 1, 2, 3  │ Bulk — round-robin within range         │
 * └──────────────┴─────────────┴─────────────────────────────────────────┘
 *
 * Within each priority band, orders are further distributed by
 * customerId hash for ordering guarantees per customer.
 *
 * This pattern is directly applicable to:
 * - FHIR critical observation routing (critical → dedicated partition)
 * - Payment processing (VIP customers → priority lane)
 * - Telecom CDR rating (real-time vs batch billing)
 */
@Slf4j
public class OrderPriorityPartitioner implements Partitioner {

    private static final String FLASH_SALE = "FLASH_SALE";
    private static final String EXPRESS    = "EXPRESS";

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {

        List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
        int totalPartitions = partitions.size();

        // Extract priority from message key format: "PRIORITY:customerId"
        String keyStr = key != null ? key.toString() : "";
        String[] parts = keyStr.split(":", 2);
        String priority   = parts.length > 0 ? parts[0] : "STANDARD";
        String customerId = parts.length > 1 ? parts[1] : keyStr;

        int selectedPartition = routeByPriority(priority, customerId, totalPartitions);

        log.debug("Routing order → topic={}, priority={}, customerId={}, partition={}",
                topic, priority, customerId, selectedPartition);

        return selectedPartition;
    }

    private int routeByPriority(String priority, String customerId, int totalPartitions) {
        // Ensure total partitions >= 8; fall back gracefully for smaller setups
        if (totalPartitions < 8) {
            return Math.abs(customerId.hashCode()) % totalPartitions;
        }

        int customerHash = Math.abs(customerId.hashCode());

        return switch (priority) {
            case FLASH_SALE -> 6 + (customerHash % 2);   // partitions 6 or 7
            case EXPRESS    -> 4 + (customerHash % 2);   // partitions 4 or 5
            default         -> customerHash % 4;          // partitions 0-3 (STANDARD)
        };
    }

    @Override
    public void close() { }

    @Override
    public void configure(Map<String, ?> configs) { }
}
