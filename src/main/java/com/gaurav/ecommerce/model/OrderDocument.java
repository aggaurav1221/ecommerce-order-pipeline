package com.gaurav.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * MongoDB document for persisted order events.
 *
 * Indexed for the most common query patterns:
 *   - Dashboard: status + eventTimestamp (latest orders by status)
 *   - Analytics: region + eventTimestamp (regional sales trends)
 *   - Customer:  customerId + eventTimestamp (order history)
 *   - Warehouse: warehouseId + status (fulfilment queries)
 *
 * TTL index on eventTimestamp: raw events purged after 90 days.
 * Aggregated analytics stored separately in time-series collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "orders")
@CompoundIndexes({
    @CompoundIndex(name = "idx_status_ts",
                   def  = "{'status': 1, 'eventTimestamp': -1}"),
    @CompoundIndex(name = "idx_region_ts",
                   def  = "{'region': 1, 'eventTimestamp': -1}"),
    @CompoundIndex(name = "idx_customer_ts",
                   def  = "{'customerId': 1, 'eventTimestamp': -1}"),
    @CompoundIndex(name = "idx_warehouse_status",
                   def  = "{'warehouseId': 1, 'status': 1}")
})
public class OrderDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String orderId;

    @Indexed
    private String customerId;
    private String customerTier;       // PREMIUM | STANDARD | BASIC

    private String status;
    private BigDecimal totalAmount;
    private String currency;

    @Indexed
    private String region;
    private String warehouseId;
    private String paymentMethod;
    private String sourceSystem;

    private List<OrderItemDoc> items;

    // Kafka metadata — useful for debugging and replay
    private Integer kafkaPartition;
    private Long    kafkaOffset;
    private String  kafkaTopic;

    // Processing metadata
    private Integer retryCount;
    private String  processingStatus;  // SUCCESS | FAILED | DLQ
    private String  lastError;

    // Time-series field — primary dimension for all time-based queries
    @Indexed
    private Instant eventTimestamp;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SalesSummary {
        private String     id;
        private long       totalOrders;
        private BigDecimal totalRevenue;
        private BigDecimal avgOrderValue;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemDoc {
        private String     productId;
        private String     productName;
        private String     category;
        private Integer    quantity;
        private BigDecimal unitPrice;
    }
}
