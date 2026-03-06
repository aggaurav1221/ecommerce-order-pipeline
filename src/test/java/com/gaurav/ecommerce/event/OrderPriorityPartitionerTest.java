package com.gaurav.ecommerce.event;

import com.gaurav.ecommerce.event.partitioner.OrderPriorityPartitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OrderPriorityPartitioner.
 * Verifies that orders are routed to the correct partition band.
 *
 * Partition layout (8 partitions):
 *   FLASH_SALE → 6 or 7
 *   EXPRESS    → 4 or 5
 *   STANDARD   → 0, 1, 2, or 3
 */
@DisplayName("OrderPriorityPartitioner Tests")
class OrderPriorityPartitionerTest {

    private OrderPriorityPartitioner partitioner;
    private Cluster cluster;
    private static final String TOPIC = "ecommerce.orders";

    @BeforeEach
    void setUp() {
        partitioner = new OrderPriorityPartitioner();
        partitioner.configure(Map.of());

        // Build a mock 8-partition cluster
        Node node = new Node(0, "localhost", 9092);
        List<PartitionInfo> partitions = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            partitions.add(new PartitionInfo(TOPIC, i, node, new Node[]{node}, new Node[]{node}));
        }
        cluster = new Cluster("clusterId", List.of(node), partitions,
                java.util.Collections.emptySet(), java.util.Collections.emptySet());
    }

    @Nested
    @DisplayName("FLASH_SALE priority")
    class FlashSaleTests {

        @Test
        @DisplayName("Should route FLASH_SALE orders to partition 6 or 7")
        void flashSale_RoutesToHighPartitions() {
            for (int i = 0; i < 20; i++) {
                String key = "FLASH_SALE:CUST-" + String.format("%03d", i);
                int partition = partitioner.partition(TOPIC, key, null, null, null, cluster);
                assertThat(partition).isBetween(6, 7)
                        .withFailMessage("FLASH_SALE should go to partition 6 or 7, got %d for key %s", partition, key);
            }
        }
    }

    @Nested
    @DisplayName("EXPRESS priority")
    class ExpressTests {

        @Test
        @DisplayName("Should route EXPRESS orders to partition 4 or 5")
        void express_RoutesToMidHighPartitions() {
            for (int i = 0; i < 20; i++) {
                String key = "EXPRESS:CUST-" + String.format("%03d", i);
                int partition = partitioner.partition(TOPIC, key, null, null, null, cluster);
                assertThat(partition).isBetween(4, 5)
                        .withFailMessage("EXPRESS should go to partition 4 or 5, got %d", partition);
            }
        }
    }

    @Nested
    @DisplayName("STANDARD priority")
    class StandardTests {

        @Test
        @DisplayName("Should route STANDARD orders to partitions 0-3")
        void standard_RoutesToLowPartitions() {
            for (int i = 0; i < 20; i++) {
                String key = "STANDARD:CUST-" + String.format("%03d", i);
                int partition = partitioner.partition(TOPIC, key, null, null, null, cluster);
                assertThat(partition).isBetween(0, 3)
                        .withFailMessage("STANDARD should go to partition 0-3, got %d", partition);
            }
        }

        @Test
        @DisplayName("Unknown priority should default to STANDARD band (0-3)")
        void unknownPriority_DefaultsToStandard() {
            String key = "UNKNOWN:CUST-001";
            int partition = partitioner.partition(TOPIC, key, null, null, null, cluster);
            assertThat(partition).isBetween(0, 3);
        }
    }

    @Nested
    @DisplayName("Customer ordering guarantee")
    class OrderingTests {

        @Test
        @DisplayName("Same customer+priority should always map to same partition (ordering guarantee)")
        void sameCustomer_AlwaysSamePartition() {
            String key = "EXPRESS:CUST-042";
            int first = partitioner.partition(TOPIC, key, null, null, null, cluster);
            for (int i = 0; i < 10; i++) {
                int next = partitioner.partition(TOPIC, key, null, null, null, cluster);
                assertThat(next).isEqualTo(first)
                        .withFailMessage("Same customer must always go to same partition for ordering");
            }
        }

        @Test
        @DisplayName("Different priorities for same customer route to different partition bands")
        void sameCustomer_DifferentPriority_DifferentBand() {
            String customerId = "CUST-001";
            int standardPartition  = partitioner.partition(TOPIC, "STANDARD:"  + customerId, null, null, null, cluster);
            int expressPartition   = partitioner.partition(TOPIC, "EXPRESS:"   + customerId, null, null, null, cluster);
            int flashSalePartition = partitioner.partition(TOPIC, "FLASH_SALE:" + customerId, null, null, null, cluster);

            assertThat(standardPartition).isBetween(0, 3);
            assertThat(expressPartition).isBetween(4, 5);
            assertThat(flashSalePartition).isBetween(6, 7);
        }
    }

    @Nested
    @DisplayName("Graceful fallback")
    class FallbackTests {

        @Test
        @DisplayName("Should handle null key gracefully without NPE")
        void nullKey_HandledGracefully() {
            int partition = partitioner.partition(TOPIC, null, null, null, null, cluster);
            assertThat(partition).isBetween(0, 7);
        }

        @Test
        @DisplayName("Should fall back gracefully when cluster has fewer than 8 partitions")
        void smallCluster_FallsBackGracefully() {
            Node node = new Node(0, "localhost", 9092);
            List<PartitionInfo> smallPartitions = List.of(
                    new PartitionInfo(TOPIC, 0, node, new Node[]{}, new Node[]{}),
                    new PartitionInfo(TOPIC, 1, node, new Node[]{}, new Node[]{}),
                    new PartitionInfo(TOPIC, 2, node, new Node[]{}, new Node[]{})
            );
            Cluster smallCluster = new Cluster("c", List.of(node), smallPartitions,
                    java.util.Collections.emptySet(), java.util.Collections.emptySet());

            int partition = partitioner.partition(TOPIC, "FLASH_SALE:CUST-001", null, null, null, smallCluster);
            assertThat(partition).isBetween(0, 2);
        }
    }
}
