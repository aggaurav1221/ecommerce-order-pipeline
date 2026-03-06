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
 * MongoDB document representing a processed e-commerce order event.
 *
 * Stored in a time-series-optimised collection with compound indexes
 * covering the most common analytics query patterns:
 *  - Orders by status over time
 *  - Orders by customerId (for customer analytics)
 *  - Orders by region + priority (for fulfilment routing)
 *
 * Each document represents one order event — append-only pattern
 * (new event created on each status transition, not an update).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "order_events")
@CompoundIndexes({
    @CompoundIndex(name = "idx_status_created",
                   def = "{'status': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "idx_customer_created",
                   def = "{'customerId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "idx_region_priority_created",
                   def = "{'region': 1, 'priority': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "idx_category_amount",
                   def = "{'category': 1, 'totalAmount': -1}")
})
public class OrderEvent {

    @Id
    private String id;

    @Indexed(unique = true)
    private String orderId;

    @Indexed
    private String customerId;

    private String customerName;
    private String customerEmail;

    // Order details
    private OrderStatus   status;
    private OrderPriority priority;    // STANDARD | EXPRESS | FLASH_SALE
    private String        region;      // NORTH | SOUTH | EAST | WEST | INTERNATIONAL
    private String        category;    // ELECTRONICS | FASHION | GROCERY | PHARMA | HOME

    private List<OrderItem> items;
    private BigDecimal      totalAmount;
    private String          currency;

    // Fulfilment tracking
    private String  warehouseId;
    private String  deliveryPartnerId;
    private Instant estimatedDelivery;
    private Instant actualDelivery;

    // Kafka metadata — useful for debugging and replay
    private Integer kafkaPartition;
    private Long    kafkaOffset;
    private String  kafkaTopic;

    // Processing metadata
    private String  sourceSystem;      // WEB | MOBILE | API | POS
    private Integer retryCount;
    private String  processingError;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    // ── Enums ──────────────────────────────────────────────────────

    public enum OrderStatus {
        PLACED, PAYMENT_CONFIRMED, PROCESSING,
        SHIPPED, OUT_FOR_DELIVERY, DELIVERED,
        CANCELLED, REFUNDED, FAILED
    }

    public enum OrderPriority {
        STANDARD,       // → partition 0-3
        EXPRESS,        // → partition 4-5 (faster consumers)
        FLASH_SALE      // → partition 6-7 (dedicated high-throughput consumers)
    }

    // ── Embedded documents ─────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItem {
        private String     productId;
        private String     productName;
        private String     sku;
        private Integer    quantity;
        private BigDecimal unitPrice;
        private BigDecimal discount;
    }
}
