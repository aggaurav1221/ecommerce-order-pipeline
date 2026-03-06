package com.gaurav.ecommerce.consumer;

import com.gaurav.ecommerce.event.producer.OrderPartitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OrderPartitioner.
 * Verifies that PREMIUM customers always land on partition 0
 * and STANDARD customers are distributed across remaining partitions.
 */
@DisplayName("OrderPartitioner Tests")
class OrderPartitionerTest {

    private OrderPartitioner partitioner;
    private Cluster          mockCluster;

    @BeforeEach
    void setUp() {
        partitioner = new OrderPartitioner();

        // Simulate a 4-partition topic
        Node node = new Node(0, "localhost", 9092);
        List<PartitionInfo> partitions = List.of(
            new PartitionInfo("orders.placed", 0, node, new Node[]{node}, new Node[]{node}),
            new PartitionInfo("orders.placed", 1, node, new Node[]{node}, new Node[]{node}),
            new PartitionInfo("orders.placed", 2, node, new Node[]{node}, new Node[]{node}),
            new PartitionInfo("orders.placed", 3, node, new Node[]{node}, new Node[]{node})
        );
        mockCluster = new Cluster("test-cluster", List.of(node), partitions,
                java.util.Collections.emptySet(), java.util.Collections.emptySet());
    }

    @Nested
    @DisplayName("PREMIUM customer routing")
    class PremiumRouting {

        @Test
        @DisplayName("PREMIUM customer should always route to partition 0")
        void premiumCustomer_AlwaysPartition0() {
            int p = partitioner.partition("orders.placed",
                    "PREMIUM:CUST-001", null, null, null, mockCluster);
            assertThat(p).isEqualTo(0);
        }

        @Test
        @DisplayName("Different PREMIUM customers all route to partition 0")
        void multiplePremiumCustomers_AllPartition0() {
            for (int i = 0; i < 20; i++) {
                int p = partitioner.partition("orders.placed",
                        "PREMIUM:CUST-" + i, null, null, null, mockCluster);
                assertThat(p).as("PREMIUM CUST-%d should be on partition 0", i).isEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("STANDARD customer routing")
    class StandardRouting {

        @Test
        @DisplayName("STANDARD customer should NOT route to partition 0")
        void standardCustomer_NotPartition0() {
            // Run many customers — none should land on partition 0
            boolean anyOnZero = false;
            for (int i = 0; i < 100; i++) {
                int p = partitioner.partition("orders.placed",
                        "STANDARD:CUST-" + i, null, null, null, mockCluster);
                if (p == 0) anyOnZero = true;
            }
            assertThat(anyOnZero)
                    .as("STANDARD customers should never be routed to partition 0")
                    .isFalse();
        }

        @Test
        @DisplayName("STANDARD customers distributed across partitions 1 to N-1")
        void standardCustomers_DistributedAcrossPartitions() {
            java.util.Set<Integer> usedPartitions = new java.util.HashSet<>();
            for (int i = 0; i < 50; i++) {
                int p = partitioner.partition("orders.placed",
                        "STANDARD:CUST-" + i, null, null, null, mockCluster);
                usedPartitions.add(p);
            }
            // With 50 customers across 3 partitions (1,2,3), all should be used
            assertThat(usedPartitions).doesNotContain(0);
            assertThat(usedPartitions.size()).isGreaterThan(1);
        }

        @Test
        @DisplayName("Same customer always routes to same partition (consistent hashing)")
        void sameCustomer_ConsistentPartition() {
            String key = "STANDARD:CUST-123";
            int first = partitioner.partition("orders.placed", key, null, null, null, mockCluster);
            for (int i = 0; i < 10; i++) {
                int p = partitioner.partition("orders.placed", key, null, null, null, mockCluster);
                assertThat(p).as("Same customer should always get same partition").isEqualTo(first);
            }
        }
    }

    @Test
    @DisplayName("Null key should return partition 0 gracefully")
    void nullKey_ReturnsPartition0() {
        int p = partitioner.partition("orders.placed", null, null, null, null, mockCluster);
        assertThat(p).isEqualTo(0);
    }
}
